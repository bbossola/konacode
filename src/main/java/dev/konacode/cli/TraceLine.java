package dev.konacode.cli;

import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.FromAgent;
import dev.konacode.trace.TraceEvent.IterationStarted;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.RetryRequested;
import dev.konacode.trace.TraceEvent.TokensUsed;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import dev.konacode.trace.TraceEvent.TurnEnded;
import dev.konacode.trace.TraceEvent.TurnStarted;

/**
 * One event as one line of text. Both interfaces share the words.
 *
 * <p>{@link Ansi#oneLine} guards every payload the model or the provider chose, and no word
 * konacode writes around it. This is the one method both interfaces call, so the guard sits here
 * and not at two call sites.
 */
final class TraceLine {

    private TraceLine() {
    }

    static String of(TraceEvent event) {
        return switch (event) {
            case TurnStarted e -> "turn " + e.turn() + " started";
            case IterationStarted e ->
                    "turn " + e.turn() + " iteration " + e.iteration() + " of " + e.maxIterations();
            case ToolCalled e -> "tool " + Ansi.oneLine(e.name()) + " "
                    + Ansi.oneLine(e.argumentsJson());
            case ToolFinished e -> "tool " + Ansi.oneLine(e.name())
                    + (e.ok() ? " ok" : " error") + " in " + e.millis() + "ms";
            case TurnEnded e -> "turn " + e.turn() + " " + e.outcome() + " after "
                    + e.iterations() + " iterations, " + e.millis() + "ms";
            case RequestSent e -> "request " + e.model() + ", " + e.messageCount()
                    + " messages, " + e.toolCount() + " tools" + body(e.bodyJson());
            case ReplyReceived e -> "reply " + e.status() + " in " + e.millis() + "ms"
                    + body(e.bodyJson());
            case TokensUsed e -> "tokens " + e.prompt() + " + " + e.completion() + " = " + e.total();
            case RetryRequested e -> "retry: " + e.reason();
            case FromAgent e -> e.agent() + "> " + of(e.event());
        };
    }

    /**
     * The event a {@code FromAgent} holds, or the event itself.
     *
     * <p>An interface that matches one kind of event must reach through the name first. A match on
     * the wrapper alone stopped printing the tool line, and the suite could not see it, because
     * every test emitted a bare event.
     */
    static TraceEvent inside(TraceEvent event) {
        return event instanceof FromAgent named ? inside(named.event()) : event;
    }

    /** The names around an event, ready to print before a line konacode writes. */
    static String names(TraceEvent event) {
        return event instanceof FromAgent named ? named.agent() + "> " + names(named.event()) : "";
    }

    /** The newline is the one konacode writes. The body is the text a provider sent. */
    private static String body(String json) {
        return json.isEmpty() ? "" : "\n" + Ansi.oneLine(json);
    }
}
