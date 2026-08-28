package dev.konacode.policy;

import dev.konacode.tools.Action;

import java.util.Objects;

/**
 * Asks a judge about every question another policy writes.
 *
 * <p>It uses {@link EffectPolicy}, and it repeats nothing that policy does. A call that policy
 * allows runs with no judgement. It decides nothing itself: it takes the answer of the judge, and
 * for a refusal only it writes the frame around the reason.
 */
public final class JudgePolicy implements ToolPolicy {

    static final int REASON_CAP = 200;

    private static final String ENDS = ".!?…";

    private final ToolPolicy effect;
    private final Judge judge;

    public JudgePolicy(ToolPolicy effect, Judge judge) {
        this.effect = Objects.requireNonNull(effect, "effect");
        this.judge = Objects.requireNonNull(judge, "judge");
    }

    @Override
    public Decision check(Action action, String userText) {
        Decision inner = effect.check(action, userText);
        if (!(inner instanceof Decision.Ask ask)) {
            return inner;
        }
        Decision answer = judge.judge(ask, userText);
        return answer instanceof Decision.Deny(String reason) ? Decision.deny(frame(ask, reason)) : answer;
    }

    @Override
    public String label() {
        return "judge";
    }

    @Override
    public boolean asks() {
        return true;
    }

    // The main model reads this text, so the frame names one call and denies the rule.
    private static String frame(Decision.Ask ask, String reason) {
        return "konacode refused this call: " + ask.toolName() + " on " + ask.toolOperand() + ". The judge said: " + cut(reason) + " This answers one call and sets no rule.";
    }

    private static String cut(String reason) {
        String clause = reason.strip();
        if (clause.length() > REASON_CAP) {
            clause = clause.substring(0, REASON_CAP) + "…";
        }
        return clause.isEmpty() || ENDS.indexOf(clause.charAt(clause.length() - 1)) >= 0 ? clause : clause + ".";
    }
}
