package dev.konacode.policy;

/**
 * Answers whether one call may run.
 *
 * <p>An interface, because an implementation needs a model, and {@code agent} depends on
 * {@code policy}. A concrete class here would reverse that.
 */
public interface Judge {

    /** The sentence a question shows when the judge did not answer. konacode writes it. */
    String NO_ANSWER = "The judge did not answer, so konacode asks.";

    /**
     * @param ask the question the policy wrote
     * @param userText the message the user typed to start this turn
     * @return {@code Decision.allow()}, the {@code ask} it received, a {@code Decision.deny}, or
     *     {@code ask.withNote(NO_ANSWER)} when it could not decide
     */
    Decision judge(Decision.Ask ask, String userText);
}
