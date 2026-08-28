package dev.konacode.policy;

import dev.konacode.tools.Permission;

import java.util.Objects;
import java.util.Optional;

/**
 * Whether a tool call may proceed.
 *
 * <p>Sealed deliberately: a new case is a compile error at every handling site.
 */
public sealed interface Decision {

    record Allow() implements Decision {}

    record Deny(String reason) implements Decision {}

    /**
     * A question, written and not yet put. The policy needs the user to decide.
     *
     * @param toolName the tool that wants to act. The question begins with it, and it is present
     *     even when the permission is empty.
     * @param toolIntent what the tool wants to do, for example "write outside this project". The
     *     first word is an imperative verb, because the question builds a line from it.
     * @param toolOperand what the call acts on, in words
     * @param standingPermission what an "always" answer covers, or empty when konacode offers no
     *     "always". The question then shows yes and no only.
     * @param note one sentence that says why konacode asks, or empty. The question shows it below
     *     the operand.
     */
    record Ask(String toolName, String toolIntent, String toolOperand, Optional<Permission> standingPermission, String note) implements Decision {

        public Ask {
            Objects.requireNonNull(toolName, "toolName");
            Objects.requireNonNull(toolIntent, "toolIntent");
            Objects.requireNonNull(toolOperand, "toolOperand");
            Objects.requireNonNull(standingPermission, "standingPermission");
            Objects.requireNonNull(note, "note");
        }

        /** The same question, with one sentence added below the operand. */
        public Ask withNote(String note) {
            return new Ask(toolName, toolIntent, toolOperand, standingPermission, note);
        }
    }

    static Decision allow() {
        return new Allow();
    }

    static Decision deny(String reason) {
        return new Deny(reason);
    }

    static Decision ask(String toolName, String toolIntent, String toolOperand, Optional<Permission> standingPermission) {
        return new Ask(toolName, toolIntent, toolOperand, standingPermission, "");
    }
}
