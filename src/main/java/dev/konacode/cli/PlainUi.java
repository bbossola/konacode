package dev.konacode.cli;

import dev.konacode.tools.ToolResult;
import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;

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

    @Override
    public void onToolCall(String name, String argumentsJson) {
        out.println("tool: " + name + "(" + argumentsJson + ")");
    }

    @Override
    public void onToolResult(String name, ToolResult result) {
    }

    @Override
    public void emit(TraceEvent event) {
        switch (event) {
            case ToolCalled called -> onToolCall(called.name(), called.argumentsJson());
            case ToolFinished finished -> onToolResult(finished.name(), finished.ok()
                    ? ToolResult.ok(finished.output())
                    : ToolResult.err(finished.output()));
            default -> {
            }
        }
    }
}
