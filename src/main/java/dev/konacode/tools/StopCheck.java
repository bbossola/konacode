package dev.konacode.tools;

/**
 * Asks whether the user stopped the turn.
 *
 * <p>A tool that works in many steps reads this between the steps, and returns a
 * {@link ToolResult.Err} that says what it changed before it stopped. The interface lives here
 * and not in {@code dev.konacode.agent} because {@code agent} already depends on {@code tools},
 * so the reverse import would close a cycle.
 */
@FunctionalInterface
public interface StopCheck {

    boolean stopped();

    /** For a tool built without one, and for every test that does not test stopping. */
    StopCheck NEVER = () -> false;
}
