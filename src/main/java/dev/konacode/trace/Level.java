package dev.konacode.trace;

import dev.konacode.trace.TraceEvent.FromAgent;
import dev.konacode.trace.TraceEvent.IterationStarted;
import dev.konacode.trace.TraceEvent.Judged;
import dev.konacode.trace.TraceEvent.ReplyReceived;
import dev.konacode.trace.TraceEvent.RequestSent;
import dev.konacode.trace.TraceEvent.RetryRequested;
import dev.konacode.trace.TraceEvent.TokensUsed;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import dev.konacode.trace.TraceEvent.TurnEnded;
import dev.konacode.trace.TraceEvent.TurnStarted;

import java.util.Locale;
import java.util.Optional;

/**
 * How much of the stream a sink keeps.
 *
 * <p>The rule lives here and not in the sinks, because each sink has its own level. The screen can
 * show {@code FULL} while the file records {@code BASIC}, so one filter in front of both cannot
 * work.
 */
public enum Level {

    OFF, BASIC, FULL;

    private static final int CAP = 2048;

    /**
     * The event this level keeps. Empty means the sink writes nothing.
     *
     * <p>A {@code BASIC} answer is a new event with the payloads already cut, so a sink never cuts
     * a string itself.
     */
    public Optional<TraceEvent> keep(TraceEvent event) {
        if (event instanceof FromAgent named) {
            return keep(named.event()).map(kept -> new FromAgent(named.agent(), kept));
        }
        return switch (this) {
            case OFF -> Optional.empty();
            case FULL -> Optional.of(event);
            case BASIC -> Optional.of(cut(event));
        };
    }

    public static Optional<Level> parse(String name) {
        for (Level level : values()) {
            if (level.name().equalsIgnoreCase(name.trim())) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }

    /** The name a command shows. */
    public String label() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * The level of the file, for the whole session.
     *
     * <p>A wrong value is an error and not a silent fall back, for the reason
     * {@code konacode.maxIterations} gives: a typo that quietly does nothing goes unnoticed.
     */
    public static Level configured() {
        String configured = System.getProperty("konacode.trace", "off");
        return parse(configured).orElseThrow(() -> new IllegalArgumentException(
                "konacode.trace must be off, basic or full, but was: " + configured));
    }

    private static TraceEvent cut(TraceEvent event) {
        return switch (event) {
            case TurnStarted e -> new TurnStarted(e.turn(), cap(e.userText()));
            case ToolCalled e -> new ToolCalled(e.turn(), e.name(), cap(e.argumentsJson()));
            case ToolFinished e ->
                    new ToolFinished(e.turn(), e.name(), e.ok(), cap(e.output()), e.millis());
            case RequestSent e ->
                    new RequestSent(e.url(), e.model(), e.messageCount(), e.toolCount(), "");
            case ReplyReceived e -> new ReplyReceived(e.status(), e.millis(), "");
            case RetryRequested e -> new RetryRequested(cap(e.reason()));
            case Judged e -> new Judged(e.toolName(), e.verdict(), e.millis(), cap(e.toolOperand()));
            // keep unwraps a FromAgent before it calls cut, so this arm is here for the switch only.
            case FromAgent e -> throw new IllegalStateException("cut got a FromAgent from " + e.agent());
            case IterationStarted e -> e;
            case TurnEnded e -> e;
            case TokensUsed e -> e;
        };
    }

    private static String cap(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= CAP ? text : text.substring(0, CAP) + "…";
    }
}
