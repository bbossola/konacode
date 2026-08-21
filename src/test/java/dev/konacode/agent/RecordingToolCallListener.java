package dev.konacode.agent;

import dev.konacode.tools.ToolResult;

import java.util.ArrayList;
import java.util.List;

/** Captures what the loop reported, so tests can assert on it without capturing stdout. */
final class RecordingToolCallListener implements ToolCallListener {

    private final List<String> calls = new ArrayList<>();
    private final List<ToolResult> results = new ArrayList<>();

    List<String> calls() {
        return calls;
    }

    List<ToolResult> results() {
        return results;
    }

    @Override
    public void onToolCall(String name, String argumentsJson) {
        calls.add(name + "(" + argumentsJson + ")");
    }

    @Override
    public void onToolResult(String name, ToolResult result) {
        results.add(result);
    }
}
