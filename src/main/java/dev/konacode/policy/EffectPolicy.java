package dev.konacode.policy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.konacode.tools.Action;
import dev.konacode.tools.Tool;

/**
 * Allows a call inside the launch directory, and asks about every other one.
 *
 * <p>The tool states what the call does and what it acts on. This class decides what to do about
 * the answer, and it writes the words. "Outside this project" is not a fact about the call: it is
 * this policy that names its own boundary. A policy with another boundary writes another sentence,
 * so a tool must never write it.
 *
 * <p>This class holds no state. It reads the tool on every call.
 */
public final class EffectPolicy implements ToolPolicy {

    @Override
    public Decision check(Tool tool, JsonNode args) {
        Action action = tool.computeAction(args);
        return switch (action.effect()) {
            case READS_INSIDE, WRITES_INSIDE -> Decision.allow();
            case READS_OUTSIDE -> ask("read outside this project", action);
            case WRITES_OUTSIDE -> ask("write outside this project", action);
            case RUNS -> ask("run a command", action);
        };
    }

    private static Decision ask(String intent, Action action) {
        return Decision.ask(action.toolName(), intent, action.toolOperand(), action.standingPermission());
    }
}
