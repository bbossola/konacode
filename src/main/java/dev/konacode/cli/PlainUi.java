package dev.konacode.cli;

import dev.konacode.policy.Decision;
import dev.konacode.trace.Level;
import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.ToolCalled;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The interface for a pipe, and for a terminal that JLine cannot open.
 *
 * <p>It prints exactly what konacode printed before there were two interfaces. Every piped test
 * depends on that.
 */
final class PlainUi implements Ui {

    private final BufferedReader in;
    private final PrintStream out;
    private Level live = Level.OFF;

    PlainUi(BufferedReader in, PrintStream out) {
        this.in = in;
        this.out = out;
    }

    static PlainUi open() {
        return new PlainUi(
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)),
                System.out);
    }

    @Override
    public void welcome() {
        out.println("Chat with konacode (use 'ctrl-c' to quit)");
        out.println();
    }

    @Override
    public Optional<String> readLine() {
        out.print(Ansi.blue("You") + ": ");
        out.flush();
        try {
            return Optional.ofNullable(in.readLine());
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    @Override
    public void showAnswer(String text) {
        out.println(Ansi.green("konacode") + ": " + text);
    }

    @Override
    public void showError(String message) {
        out.println(Ansi.style(message, Ansi.RED));
    }

    @Override
    public void thinking() {
    }

    /** This interface cannot ask a question, so konacode refuses rather than guess. */
    @Override
    public Answer ask(Decision.Ask ask) {
        return Answer.NO;
    }

    @Override
    public boolean canAsk() {
        return false;
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
            out.println("tool: " + called.name() + "(" + called.argumentsJson() + ")");
            return;
        }
        live.keep(event).ifPresent(kept -> out.println("trace: " + TraceLine.of(kept)));
    }
}
