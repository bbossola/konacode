package dev.konacode.cli;

import dev.konacode.agent.ToolCallListener;
import dev.konacode.tools.ToolResult;

import java.io.PrintStream;

/**
 * Prints the {@code tool:} lines. Every one of them is the model deciding, on its own, that it
 * needs more context before it can answer.
 */
public final class ConsoleToolCallListener implements ToolCallListener {

    private final PrintStream out;

    public ConsoleToolCallListener(PrintStream out) {
        this.out = out;
    }

    @Override
    public void onToolCall(String name, String argumentsJson) {
        out.println("tool: " + name + "(" + argumentsJson + ")");
    }

    @Override
    public void onToolResult(String name, ToolResult result) {
        // The result goes to the model, not the human; the call line is enough here.
    }
}
