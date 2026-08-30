package dev.konacode.policy;

import dev.konacode.tools.Action;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

/** A policy the test drives, and that records the action and the user's text of every call. */
public final class FakePolicy implements ToolPolicy {

    private final BiFunction<Action, String, Decision> answer;
    private final List<Action> actions = new ArrayList<>();
    private final List<String> userTexts = new ArrayList<>();

    public FakePolicy(BiFunction<Action, String, Decision> answer) {
        this.answer = answer;
    }

    @Override
    public Decision check(Action action, String userText) {
        actions.add(action);
        userTexts.add(userText);
        return answer.apply(action, userText);
    }

    @Override
    public String label() {
        return "fake";
    }

    @Override
    public Optional<String> refusal() {
        return Optional.of("refuses every call");
    }

    public List<Action> actions() {
        return actions;
    }

    public List<String> userTexts() {
        return userTexts;
    }
}
