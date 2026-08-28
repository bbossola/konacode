package dev.konacode.policy;

import dev.konacode.tools.Action;

/**
 * The default policy: allows everything.
 *
 * <p>The seam exists so restrictions can be added without touching the agent loop; the default
 * behavior imposes none. See FOLLOWUP.md for the confinement policy this will be replaced by.
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
