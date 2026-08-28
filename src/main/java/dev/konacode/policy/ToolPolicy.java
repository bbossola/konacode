package dev.konacode.policy;

import dev.konacode.tools.Action;

/** Consulted before every tool execution. */
public interface ToolPolicy {

    /**
     * @param action what the tool says the call does
     * @param userText the message the user typed to start this turn
     */
    Decision check(Action action, String userText);
}
