package dev.konacode.cli;

import dev.konacode.tools.ToolResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

final class RecordingUi implements Ui {

    private final Deque<String> lines = new ArrayDeque<>();
    final List<String> answers = new ArrayList<>();
    final List<String> errors = new ArrayList<>();
    final List<String> events = new ArrayList<>();

    RecordingUi(String... input) {
        Collections.addAll(lines, input);
    }

    @Override
    public void welcome() {
        events.add("welcome");
    }

    @Override
    public Optional<String> readLine() {
        return Optional.ofNullable(lines.poll());
    }

    @Override
    public void showAnswer(String text) {
        answers.add(text);
        events.add("answer");
    }

    @Override
    public void showError(String message) {
        errors.add(message);
        events.add("error");
    }

    @Override
    public void thinking() {
        events.add("thinking");
    }

    @Override
    public void onToolCall(String name, String argumentsJson) {
        events.add("tool:" + name);
    }

    @Override
    public void onToolResult(String name, ToolResult result) {
    }
}
