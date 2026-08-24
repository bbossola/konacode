package dev.konacode.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.konacode.llm.LlmClient;
import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.ToolMessage;
import dev.konacode.llm.Message.UserMessage;
import dev.konacode.llm.ToolCall;
import dev.konacode.llm.ToolSpec;
import dev.konacode.policy.Decision;
import dev.konacode.policy.ToolPolicy;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.ToolResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The loop. Send the conversation and the tool descriptions; print text, or run tools and go
 * round again.
 *
 * <p>{@link #respond} never throws. A REPL that dies on a transient 500 is worse than one that
 * reports it, so every failure comes back as a string.
 */
public final class Agent {

    public static final int DEFAULT_MAX_ITERATIONS = 8;

    private final ObjectMapper mapper = new ObjectMapper();

    private final LlmClient client;
    private final ToolRegistry registry;
    private final ToolPolicy policy;
    private final Conversation conversation;
    private final ToolCallListener listener;
    private final Cancellation cancellation;
    private final int maxIterations;

    public Agent(LlmClient client,
                 ToolRegistry registry,
                 ToolPolicy policy,
                 Conversation conversation,
                 ToolCallListener listener,
                 Cancellation cancellation,
                 int maxIterations) {
        this.client = Objects.requireNonNull(client, "client");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1.");
        }
        this.maxIterations = maxIterations;
    }

    /**
     * Eight is enough for read-read-edit and too few for anything that plans.
     *
     * <p>A malformed value is an error rather than a silent fall back to the default: this is set
     * once in a shell script or a unit file, and a typo that quietly does nothing would go
     * unnoticed indefinitely.
     */
    public static int configuredMaxIterations() {
        String configured = System.getProperty("konacode.maxIterations");
        if (configured == null) {
            return DEFAULT_MAX_ITERATIONS;
        }
        try {
            return Integer.parseInt(configured.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "konacode.maxIterations must be a whole number, but was: " + configured);
        }
    }

    public String respond(String userText) {
        cancellation.clear();
        conversation.add(new UserMessage(userText));
        List<ToolSpec> tools = ToolSpecs.from(registry);
        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                AssistantMessage reply = client.chat(conversation.messages(), tools);

                // Before running anything: providers reject a tool result whose originating
                // assistant message is absent from the history.
                conversation.add(reply);

                if (!reply.hasToolCalls()) {
                    return reply.text();
                }

                List<ToolCall> calls = reply.toolCalls();
                for (int index = 0; index < calls.size(); index++) {
                    if (cancellation.stopped()) {
                        return stopped(calls.subList(index, calls.size()));
                    }
                    ToolCall call = calls.get(index);
                    ToolResult result = perform(call);
                    conversation.add(new ToolMessage(call.id(), result.render()));
                }

                if (cancellation.stopped()) {
                    return stopped(List.of());
                }
            }
            return fail("<error> Exceeded maximum tool iterations.");
        } catch (LlmException e) {
            if (cancellation.stopped()) {
                return stopped(List.of());
            }
            return fail("<error> " + e.getMessage());
        }
    }

    /**
     * Records a failure as the assistant's turn before returning it.
     *
     * <p>Without this, a failed turn leaves the conversation ending on an unanswered user
     * message — two failures in a row would produce two consecutive user turns, which providers
     * that enforce strict alternation reject. It also gives the model a record of why a turn
     * ended, which it otherwise has no way to see.
     */
    private String fail(String message) {
        conversation.add(new AssistantMessage(message, List.of()));
        return message;
    }

    /**
     * Closes a stopped turn.
     *
     * <p>The history keeps the whole turn, so the model can read what it did and reverse it when
     * the user asks. Every tool call that never ran is answered here, because a provider rejects
     * a conversation where a call has no result.
     */
    private String stopped(List<ToolCall> unanswered) {
        for (ToolCall call : unanswered) {
            conversation.add(new ToolMessage(call.id(),
                    ToolResult.err("Stopped by the user before this tool ran.").render()));
        }
        conversation.add(new AssistantMessage("Stopped by the user.", List.of()));
        return "Stopped.";
    }

    private ToolResult perform(ToolCall call) {
        listener.onToolCall(call.name(), call.argumentsJson());
        ToolResult result = run(call);
        listener.onToolResult(call.name(), result);
        return result;
    }

    private ToolResult run(ToolCall call) {
        Optional<Tool> found = registry.lookup(call.name());
        if (found.isEmpty()) {
            return ToolResult.err("Unknown tool: " + call.name());
        }
        Tool tool = found.get();

        JsonNode args;
        try {
            args = parseArguments(call.argumentsJson());
        } catch (JsonProcessingException e) {
            return ToolResult.err(
                    "Could not parse arguments for " + call.name() + ": " + e.getOriginalMessage());
        }

        Decision decision;
        try {
            decision = policy.check(tool, args);
        } catch (RuntimeException e) {
            // A misbehaving policy must not kill the session, for the same reason a
            // misbehaving tool must not. Denying is the safe reading of a broken policy.
            return ToolResult.err("Policy check for " + call.name() + " failed: " + e);
        }
        if (decision instanceof Decision.Deny(String reason)) {
            return ToolResult.err(reason);
        }

        try {
            return tool.execute(args);
        } catch (RuntimeException e) {
            // A misbehaving tool must not kill the session.
            return ToolResult.err("Tool " + call.name() + " failed: " + e);
        }
    }

    private JsonNode parseArguments(String argumentsJson) throws JsonProcessingException {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(argumentsJson);
    }
}
