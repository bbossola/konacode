package dev.konacode.cli;

import dev.konacode.agent.ToolCallListener;
import dev.konacode.trace.Trace;

import java.util.Optional;

/**
 * Everything konacode shows the user, and the one thing it reads from them.
 *
 * <p>This extends {@link ToolCallListener} because showing a tool call is a user interface
 * concern. One object then owns the screen, and the agent loop still never touches
 * {@code System.out}.
 */
public interface Ui extends ToolCallListener, Trace, AutoCloseable {

    void welcome();

    /** The next line the user typed. Empty when the session ends. */
    Optional<String> readLine();

    void showAnswer(String text);

    void showError(String message);

    /** The agent started work. An implementation may show progress. */
    void thinking();

    @Override
    default void close() {
    }
}
