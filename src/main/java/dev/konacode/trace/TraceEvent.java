package dev.konacode.trace;

/**
 * One thing that happened during a turn.
 *
 * <p>Every case carries strings, numbers and booleans only. No case carries a {@code Message}, a
 * {@code ToolResult} or a {@code JsonNode}. That is what keeps this package free of every other
 * konacode package, and it is what lets the agent loop and the provider both emit into it.
 *
 * <p>Sealed, so a new case is a compile error at every sink.
 */
public sealed interface TraceEvent {

    /** How a turn finished. The fact the screen hides today. */
    enum Outcome { ANSWERED, STOPPED, EXHAUSTED, FAILED }

    record TurnStarted(int turn, String userText) implements TraceEvent {}

    record IterationStarted(int turn, int iteration, int maxIterations) implements TraceEvent {}

    record ToolCalled(int turn, String name, String argumentsJson) implements TraceEvent {}

    record ToolFinished(int turn, String name, boolean ok, String output, long millis)
            implements TraceEvent {}

    record TurnEnded(int turn, Outcome outcome, int iterations, long millis)
            implements TraceEvent {}

    record RequestSent(String url, String model, int messageCount, int toolCount, String bodyJson)
            implements TraceEvent {}

    record ReplyReceived(int status, long millis, String bodyJson) implements TraceEvent {}

    record TokensUsed(int prompt, int completion, int total) implements TraceEvent {}

    record RetryRequested(String reason) implements TraceEvent {}
}
