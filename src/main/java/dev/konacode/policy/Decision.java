package dev.konacode.policy;

/**
 * Whether a tool call may proceed.
 *
 * <p>Sealed deliberately: when an {@code Ask} case is added for interactive approval, every
 * handling site becomes a compile error until it is dealt with.
 */
public sealed interface Decision {

    record Allow() implements Decision {}

    record Deny(String reason) implements Decision {}

    static Decision allow() {
        return new Allow();
    }

    static Decision deny(String reason) {
        return new Deny(reason);
    }
}
