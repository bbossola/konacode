package dev.konacode.tools;

import java.util.Objects;
import java.util.Optional;

/**
 * What one call to a tool does, as the tool states it. The tool decides nothing; a
 * {@code ToolPolicy} reads this and decides.
 *
 * @param effect what the call does
 * @param operand what the call acts on, in words, for the screen. A path for a file tool, and the
 *     command line for a tool that runs a command.
 * @param permission what a standing "always" would cover. Empty means konacode offers no
 *     "always" for this call, because no standing permission can describe it honestly.
 */
public record Action(Effect effect, String operand, Optional<Permission> permission) {

    public Action {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(operand, "operand");
        Objects.requireNonNull(permission, "permission");
    }

    /** A call the user may approve once only. */
    public static Action once(Effect effect, String operand) {
        return new Action(effect, operand, Optional.empty());
    }

    /** A call the user may approve once, or approve as a standing permission. */
    public static Action of(Effect effect, String operand, Permission permission) {
        return new Action(effect, operand, Optional.of(permission));
    }
}
