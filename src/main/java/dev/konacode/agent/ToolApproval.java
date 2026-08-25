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
        /** Runs this call, and every later call the same tool makes directly in the same folder. */
        ALWAYS
    }

    /**
     * @param toolName the tool the model wants to call
     * @param ask what the policy needs decided. A caller that draws the question should offer
     *     {@code ALWAYS} only when {@code ask.alwaysFolder()} is not null. An {@code ALWAYS} with
     *     a null folder is legal, and it approves this call only.
     */
    Answer ask(String toolName, Decision.Ask ask);

    /** True when this interface can put a question to a user. */
    boolean canAsk();
}
