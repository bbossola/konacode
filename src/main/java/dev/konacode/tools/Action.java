package dev.konacode.tools;

import java.util.Objects;
import java.util.Optional;

/**
 * What one call to a tool does, as the tool states it. The tool decides nothing; a
 * {@code ToolPolicy} reads this and decides.
 *
 * @param toolName the name of the tool that states the action
 * @param effect what the call does
 * @param toolOperand what the call acts on, in words, for the screen. A path for a file tool, and
 *     the command line for a tool that runs a command.
 * @param standingPermission what a standing "always" would cover. Empty means konacode offers no
 *     "always" for this call, because no standing permission can describe it honestly.
 */
public record Action(String toolName, Effect effect, String toolOperand, Optional<Permission> standingPermission) {

    public Action {
        Objects.requireNonNull(toolName, "toolName");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(toolOperand, "toolOperand");
        Objects.requireNonNull(standingPermission, "standingPermission");
    }

    /** A call the user may approve once only. */
    public static Action once(String toolName, Effect effect, String toolOperand) {
        return new Action(toolName, effect, toolOperand, Optional.empty());
    }

    /** A call the user may approve once, or approve as a standing permission. */
    public static Action of(String toolName, Effect effect, String toolOperand, Permission permission) {
        return new Action(toolName, effect, toolOperand, Optional.of(permission));
    }
}
