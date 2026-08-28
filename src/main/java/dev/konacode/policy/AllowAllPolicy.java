package dev.konacode.policy;

import dev.konacode.tools.Action;

/**
 * Allows every call, and asks nothing.
 *
 * <p>It is no longer a default. {@code JudgePolicy} starts both interfaces, and {@code /policy
 * allow-all} selects this one for a user who wants no judge and no question.
 */
public final class AllowAllPolicy implements ToolPolicy {

    @Override
    public Decision check(Action action, String userText) {
        return Decision.allow();
    }

    @Override
    public String label() {
        return "allow-all";
    }

    @Override
    public boolean asks() {
        return false;
    }
}
