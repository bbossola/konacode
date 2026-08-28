package dev.konacode.policy;

import dev.konacode.tools.Action;
import dev.konacode.trace.Trace;
import dev.konacode.trace.TraceEvent.Judged;

import java.util.Objects;
import java.util.Optional;

/**
 * Asks a judge about every question another policy writes.
 *
 * <p>It uses {@link EffectPolicy}, and it repeats nothing that policy does. A call that policy
 * allows runs with no judgement. It decides nothing itself: it takes the answer of the judge, and
 * for a refusal only it writes the frame around the reason.
 *
 * <p>It reports every judgement to the trace. A call the judge allows runs with no question, so
 * without the report a user cannot tell it from a call inside the project.
 */
public final class JudgePolicy implements ToolPolicy {

    static final int REASON_CAP = 200;

    private static final String ENDS = ".!?…";

    private final ToolPolicy effect;
    private final Judge judge;
    private final Trace trace;

    public JudgePolicy(ToolPolicy effect, Judge judge, Trace trace) {
        this.effect = Objects.requireNonNull(effect, "effect");
        this.judge = Objects.requireNonNull(judge, "judge");
        this.trace = Objects.requireNonNull(trace, "trace");
    }

    @Override
    public Decision check(Action action, String userText) {
        Decision inner = effect.check(action, userText);
        if (!(inner instanceof Decision.Ask ask)) {
            return inner;
        }
        long started = System.nanoTime();
        Decision answer = judge.judge(ask, userText);
        // The judgement is the cost of this policy, so the line that reports it names the time.
        trace.emit(new Judged(ask.toolName(), verdict(answer), (System.nanoTime() - started) / 1_000_000, ask.toolOperand()));
        return answer instanceof Decision.Deny(String reason) ? Decision.deny(frame(ask, reason)) : answer;
    }

    @Override
    public String label() {
        return "judge";
    }

    /** It names the two kinds of call it judges. A call inside this project reaches no judge. */
    @Override
    public Optional<String> refusal() {
        return Optional.of("refuses every call outside this project, and every command, that the judge does not allow");
    }

    // One word, because the line that shows it puts no delimiter between the fields.
    private static String verdict(Decision answer) {
        // A switch over the sealed Decision, so a new case there is a compile error here too.
        return switch (answer) {
            case Decision.Allow ignored -> "allow";
            case Decision.Deny ignored -> "deny";
            case Decision.Ask a -> Judge.NO_ANSWER.equals(a.note()) ? "no-answer" : "ask";
        };
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
