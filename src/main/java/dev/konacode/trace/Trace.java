package dev.konacode.trace;

import java.util.List;

/**
 * Where a trace event goes.
 *
 * <p>One method, so a sink is a lambda. A sink must never throw into the caller: the loop reports
 * what it did, and reporting must not be able to end a turn.
 */
public interface Trace extends AutoCloseable {

    /** Discards every event. */
    Trace NONE = event -> {
    };

    void emit(TraceEvent event);

    /**
     * One stream to several sinks.
     *
     * <p>The result does not close its sinks. The caller that opened a sink closes it.
     */
    static Trace fanOut(Trace... sinks) {
        List<Trace> all = List.of(sinks);
        return event -> {
            for (Trace sink : all) {
                sink.emit(event);
            }
        };
    }

    @Override
    default void close() {
    }
}
