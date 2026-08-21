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
import dev.konacode.policy.Decision;
import dev.konacode.policy.ToolPolicy;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.ToolResult;

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
    private final int maxIterations;

    public Agent(LlmClient client,
                 ToolRegistry registry,
                 ToolPolicy policy,
                 Conversation conversation,
                 ToolCallListener listener,
                 int maxIterations) {
        this.client = Objects.requireNonNull(client, "client");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.listener = Objects.requireNonNull(listener, "listener");
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be at least 1.");
        }
        this.maxIterations = maxIterations;
    }

    /** Eight is enough for read-read-edit and too few for anything that plans. */
    public static int configuredMaxIterations() {
        return Integer.getInteger("konacode.maxIterations", DEFAULT_MAX_ITERATIONS);
    }

    public String respond(String userText) {
        conversation.add(new UserMessage(userText));
        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                AssistantMessage reply =
                        client.chat(conversation.messages(), ToolSpecs.from(registry));

                // Before running anything: providers reject a tool result whose originating
                // assistant message is absent from the history.
                conversation.add(reply);

                if (!reply.hasToolCalls()) {
                    return reply.text();
                }

                for (ToolCall call : reply.toolCalls()) {
                    ToolResult result = perform(call);
                    conversation.add(new ToolMessage(call.id(), result.render()));
                }
            }
            return "<error> Exceeded maximum tool iterations.";
        } catch (LlmException e) {
            return "<error> " + e.getMessage();
        }
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

        if (policy.check(tool, args) instanceof Decision.Deny(String reason)) {
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
