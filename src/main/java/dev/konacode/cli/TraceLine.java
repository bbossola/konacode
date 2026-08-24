package dev.konacode.cli;

import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.IterationStarted;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.RetryRequested;
import dev.konacode.trace.TraceEvent.TokensUsed;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import dev.konacode.trace.TraceEvent.TurnEnded;
import dev.konacode.trace.TraceEvent.TurnStarted;

/** One event as one line of text. Both interfaces share the words. */
final class TraceLine {

    private TraceLine() {
    }

    static String of(TraceEvent event) {
        return switch (event) {
            case TurnStarted e -> "turn " + e.turn() + " started";
            case IterationStarted e ->
                    "turn " + e.turn() + " iteration " + e.iteration() + " of " + e.maxIterations();
            case ToolCalled e -> "tool " + e.name() + " " + e.argumentsJson();
            case ToolFinished e ->
                    "tool " + e.name() + (e.ok() ? " ok" : " error") + " in " + e.millis() + "ms";
            case TurnEnded e -> "turn " + e.turn() + " " + e.outcome() + " after "
                    + e.iterations() + " iterations, " + e.millis() + "ms";
            case RequestSent e -> "request " + e.model() + ", " + e.messageCount()
                    + " messages, " + e.toolCount() + " tools" + body(e.bodyJson());
            case ReplyReceived e -> "reply " + e.status() + " in " + e.millis() + "ms"
                    + body(e.bodyJson());
            case TokensUsed e -> "tokens " + e.prompt() + " + " + e.completion() + " = " + e.total();
            case RetryRequested e -> "retry: " + e.reason();
        };
    }

    private static String body(String json) {
        return json.isEmpty() ? "" : "\n" + json;
    }
}
