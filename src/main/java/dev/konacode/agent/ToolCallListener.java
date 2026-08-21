package dev.konacode.agent;

import dev.konacode.tools.ToolResult;

/**
 * How the loop reports activity without owning {@code System.out}. The CLI prints; tests record.
 */
public interface ToolCallListener {

    ToolCallListener SILENT = new ToolCallListener() {
        @Override
        public void onToolCall(String name, String argumentsJson) {
        }

        @Override
        public void onToolResult(String name, ToolResult result) {
        }
    };

    void onToolCall(String name, String argumentsJson);

    void onToolResult(String name, ToolResult result);
}
