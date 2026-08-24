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

    enum Answer { YES, NO, ALWAYS }

    /** {@code ALWAYS} is offered only when {@code ask.alwaysFolder()} is not null. */
    Answer ask(String toolName, Decision.Ask ask);
}
