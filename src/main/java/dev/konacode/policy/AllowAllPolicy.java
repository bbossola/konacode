package dev.konacode.policy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.konacode.tools.Tool;

/**
 * The default policy: allows everything.
 *
 * <p>The seam exists so restrictions can be added without touching the agent loop; the default
 * behavior imposes none. See FOLLOWUP.md for the confinement policy this will be replaced by.
 */
public final class AllowAllPolicy implements ToolPolicy {

    @Override
    public Decision check(Tool tool, JsonNode args) {
        return Decision.allow();
    }
}
