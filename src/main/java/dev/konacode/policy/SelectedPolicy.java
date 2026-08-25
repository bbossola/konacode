package dev.konacode.policy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.konacode.tools.Tool;

import java.util.Objects;

/**
 * The policy in use now. {@code /policy} changes it while a session runs.
 *
 * <p>{@code Agent} holds one policy in a final field and never learns that the choice can change.
 * {@code Repl} runs the command and the turn on one thread today, so a plain field would be
 * correct now. The field is volatile so a later loop that runs a turn on its own thread stays
 * correct too.
 */
public final class SelectedPolicy implements ToolPolicy {

    private volatile ToolPolicy current;

    public SelectedPolicy(ToolPolicy initial) {
        this.current = Objects.requireNonNull(initial, "initial");
    }

    @Override
    public Decision check(Tool tool, JsonNode args) {
        return current.check(tool, args);
    }

    public void select(ToolPolicy policy) {
        this.current = Objects.requireNonNull(policy, "policy");
    }

    public ToolPolicy selected() {
        return current;
    }
}
