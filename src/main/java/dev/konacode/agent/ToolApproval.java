package dev.konacode.agent;

import dev.konacode.policy.Decision;

/**
 * How the loop asks the user to approve one tool call.
 *
 * <p>The loop asks, and not the policy, because {@link Cancellation} lives here and only the loop
 * knows where an interrupt is safe. A policy that blocked inside {@code check} would put the wait
 * where nobody designed for it.
 */
public interface ToolApproval {

    enum Answer {
        /** Runs this one call. */
        YES,
        /** Refuses this one call. */
        NO,
        /** Runs this call, and every later call the permission covers. */
        ALWAYS
    }

    /**
     * @param ask what the policy needs decided. A caller that draws the question offers
     *     {@code ALWAYS} only when {@code ask.permission()} is present. An {@code ALWAYS} with an
     *     empty permission is legal, and it approves this call only.
     */
    Answer ask(Decision.Ask ask);

    /** True when this interface can put a question to a user. */
    boolean canAsk();
}
