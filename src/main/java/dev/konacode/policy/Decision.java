package dev.konacode.policy;

import java.nio.file.Path;

/**
 * Whether a tool call may proceed.
 *
 * <p>Sealed deliberately: a new case is a compile error at every handling site.
 */
public sealed interface Decision {

    record Allow() implements Decision {}

    record Deny(String reason) implements Decision {}

    /**
     * The policy needs the user to decide.
     *
     * @param action what the tool wants to do, for example "write outside this project". The
     *     first word is an imperative verb, because the question builds a line from it.
     * @param subject the absolute path the question is about
     * @param alwaysFolder the folder that an "always" answer covers, or {@code null} when konacode
     *     offers no "always". The question then shows yes and no only.
     */
    record Ask(String action, String subject, Path alwaysFolder) implements Decision {}

    static Decision allow() {
        return new Allow();
    }

    static Decision deny(String reason) {
        return new Deny(reason);
    }

    static Decision ask(String action, String subject, Path alwaysFolder) {
        return new Ask(action, subject, alwaysFolder);
    }
}
