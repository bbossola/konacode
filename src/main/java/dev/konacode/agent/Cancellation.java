package dev.konacode.agent;

import dev.konacode.tools.StopCheck;

/**
 * The user's request to stop one turn.
 *
 * <p>{@code request} is public and {@code arm} is not. The user interface may ask for a stop.
 * Only the loop may decide where an interrupt is safe.
 */
public final class Cancellation implements StopCheck {

    private volatile boolean requested;

    public void request() {
        requested = true;
    }

    @Override
    public boolean stopped() {
        return requested;
    }

    public void clear() {
        requested = false;
    }
}
