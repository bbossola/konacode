package dev.konacode.cli;

import dev.konacode.policy.Decision;
import dev.konacode.trace.Level;
import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.ToolCalled;

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
    Level live = Level.OFF;
    boolean canAsk = true;
    Answer nextAsk = Answer.NO;
    int askCount;

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
    public Answer ask(Decision.Ask ask) {
        askCount++;
        return nextAsk;
    }

    @Override
    public boolean canAsk() {
        return canAsk;
    }

    @Override
    public void liveTrace(Level level) {
        this.live = level;
    }

    @Override
    public Level liveTrace() {
        return live;
    }

    @Override
    public void emit(TraceEvent event) {
        if (event instanceof ToolCalled called) {
            events.add("tool:" + called.name());
        }
    }
}
