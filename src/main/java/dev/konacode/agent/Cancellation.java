package dev.konacode.agent;

import dev.konacode.tools.StopCheck;

/**
 * The user's request to stop one turn.
 *
 * <p>{@code request} is public and {@code arm} is not. The user interface may ask for a stop.
 * Only the loop may decide where an interrupt is safe.
 *
 * <p>The three methods that touch {@code armed} share one lock. Without it, an interrupt sent as
 * the loop disarms arrives after the clear, and the status stays set. A file operation ignores a
 * set status, but the next blocking HTTP send throws at once, so the following turn would fail
 * for no reason.
 */
public final class Cancellation implements StopCheck {

    private final Object lock = new Object();
    private volatile boolean requested;
    private Thread armed;

    public void request() {
        requested = true;
        synchronized (lock) {
            if (armed != null) {
                armed.interrupt();
            }
        }
    }

    @Override
    public boolean stopped() {
        return requested;
    }

    public void clear() {
        requested = false;
    }

    void arm() {
        synchronized (lock) {
            armed = Thread.currentThread();
        }
    }

    void disarm() {
        synchronized (lock) {
            armed = null;
            Thread.interrupted();
        }
    }
}
