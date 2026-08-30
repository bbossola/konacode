package dev.konacode.trace;

import java.util.Objects;

/**
 * Names the agent that made each event, and passes the event on.
 *
 * <p>The name travels in the data, and not in a thread local. A turn that runs on another thread,
 * or a provider that emits from a callback, would take the wrong name from a thread local, and
 * nothing would fail.
 */
public final class NamedTrace implements Trace {

    private final String agent;
    private final Trace target;

    public NamedTrace(String agent, Trace target) {
        this.agent = Objects.requireNonNull(agent, "agent");
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public void emit(TraceEvent event) {
        target.emit(new TraceEvent.FromAgent(agent, event));
    }
}
