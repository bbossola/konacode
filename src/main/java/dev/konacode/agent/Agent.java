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
import dev.konacode.trace.Trace;
import dev.konacode.trace.TraceEvent.IterationStarted;
import dev.konacode.trace.TraceEvent.Outcome;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import dev.konacode.trace.TraceEvent.TurnEnded;
import dev.konacode.trace.TraceEvent.TurnStarted;

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
    private final Approvals approvals;
    private final Conversation conversation;
    private final Trace trace;
    private final Cancellation cancellation;
    private final int maxIterations;
    private int turn;

    public Agent(LlmClient client,
                 ToolRegistry registry,
                 ToolPolicy policy,
                 Approvals approvals,
                 Conversation conversation,
                 Trace trace,
                 Cancellation cancellation,
                 int maxIterations) {
        this.client = Objects.requireNonNull(client, "client");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.conversation = Objects.requireNonNull(conversation, "conversation");
        this.trace = Objects.requireNonNull(trace, "trace");
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
        turn++;
        long started = System.nanoTime();
        trace.emit(new TurnStarted(turn, userText));
        conversation.add(new UserMessage(userText));
        List<ToolSpec> tools = ToolSpecs.from(registry);
        int iterations = 0;
        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                iterations = iteration + 1;
                trace.emit(new IterationStarted(turn, iterations, maxIterations));
                AssistantMessage reply = chat(tools);

                // Before running anything: providers reject a tool result whose originating
                // assistant message is absent from the history.
                conversation.add(reply);

                if (!reply.hasToolCalls()) {
                    return end(Outcome.ANSWERED, iterations, started, reply.text());
                }

                List<ToolCall> calls = reply.toolCalls();
                for (int index = 0; index < calls.size(); index++) {
                    if (cancellation.stopped()) {
                        return end(Outcome.STOPPED, iterations, started,
                                closeStoppedTurn(calls.subList(index, calls.size())));
                    }
                    ToolCall call = calls.get(index);
                    ToolResult result = perform(call);
                    conversation.add(new ToolMessage(call.id(), result.render()));
                }

                if (cancellation.stopped()) {
                    return end(Outcome.STOPPED, iterations, started, closeStoppedTurn(List.of()));
                }
            }
            return end(Outcome.EXHAUSTED, iterations, started,
                    fail("<error> Exceeded maximum tool iterations."));
        } catch (LlmException e) {
            if (cancellation.stopped()) {
                return end(Outcome.STOPPED, iterations, started, closeStoppedTurn(List.of()));
            }
            return end(Outcome.FAILED, iterations, started, fail("<error> " + e.getMessage()));
        }
    }

    /** Reports how the turn finished, and gives back the answer unchanged. */
    private String end(Outcome outcome, int iterations, long started, String answer) {
        trace.emit(new TurnEnded(turn, outcome, iterations, millisSince(started)));
        return answer;
    }

    private static long millisSince(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
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
     * The history keeps the whole turn, so the model can read what it did and reverse it when the
     * user asks. Every tool call that never ran is answered here, because a provider rejects a
     * conversation where a call has no result.
     */
    private String closeStoppedTurn(List<ToolCall> unanswered) {
        for (ToolCall call : unanswered) {
            conversation.add(new ToolMessage(call.id(),
                    ToolResult.err("Stopped by the user before this tool ran.").render()));
        }
        conversation.add(new AssistantMessage("Stopped by the user.", List.of()));
        return "Stopped.";
    }

    /**
     * Arms the interrupt for the length of the provider call and no longer.
     *
     * <p>The finally makes the window exactly this call, whether it returns or throws. A tool
     * that runs afterwards is never interrupted by accident.
     */
    private AssistantMessage chat(List<ToolSpec> tools) {
        cancellation.arm();
        try {
            return client.chat(conversation.messages(), tools);
        } finally {
            cancellation.disarm();
        }
    }

    private ToolResult perform(ToolCall call) {
        trace.emit(new ToolCalled(turn, call.name(), call.argumentsJson()));
        long started = System.nanoTime();
        ToolResult result = run(call);
        long millis = millisSince(started);
        // One switch derives both facts. A third result would then be a compile error here, and
        // never a report that quietly says the tool failed.
        trace.emit(switch (result) {
            case ToolResult.Ok ok ->
                    new ToolFinished(turn, call.name(), true, ok.text(), millis);
            case ToolResult.Err err ->
                    new ToolFinished(turn, call.name(), false, err.message(), millis);
        });
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
        switch (decision) {
            case Decision.Allow ignored -> { }
            case Decision.Deny(String reason) -> {
                return ToolResult.err(reason);
            }
            case Decision.Ask ask -> {
                if (!approvals.approve(call.name(), ask)) {
                    return ToolResult.err("konacode did not get approval for " + call.name()
                            + " to " + ask.action() + ".");
                }
            }
        }

        try {
            return executeUnderCancellation(tool, args);
        } catch (RuntimeException e) {
            // A misbehaving tool must not kill the session.
            return ToolResult.err("Tool " + call.name() + " failed: " + e);
        }
    }

    /**
     * Arms the interrupt only for a tool that says an interrupt is safe for it.
     *
     * <p>A tool that says nothing is never interrupted. Arming every tool would rest the safety
     * of the loop on every tool author writing correct cleanup, for ever.
     */
    private ToolResult executeUnderCancellation(Tool tool, JsonNode args) {
        if (!tool.stopsOnInterrupt()) {
            return tool.execute(args);
        }
        cancellation.arm();
        try {
            return tool.execute(args);
        } finally {
            cancellation.disarm();
        }
    }

    private JsonNode parseArguments(String argumentsJson) throws JsonProcessingException {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(argumentsJson);
    }
}
