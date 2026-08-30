package dev.konacode.policy;

import dev.konacode.tools.Action;

import java.util.Optional;

/** Consulted before every tool execution. */
public interface ToolPolicy {

    /**
     * @param action what the tool says the call does
     * @param userText the message the user typed to start this turn
     */
    Decision check(Action action, String userText);

    /** The word the user types after {@code /policy}. */
    String label();

    /**
     * What this policy refuses when nothing can answer its question, or empty when it asks nothing.
     *
     * <p>One method and not two, because a boolean beside the words must be kept in step with them
     * for ever. The policy owns the words, so {@code Commands} reads a policy with no
     * {@code instanceof}, and a policy added later cannot take the sentence of another one.
     */
    Optional<String> refusal();
}
