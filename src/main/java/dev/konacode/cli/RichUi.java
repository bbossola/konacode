package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import dev.konacode.cli.markdown.Markdown;
import dev.konacode.tools.ToolResult;
import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The interface for a real terminal. JLine gives the line editing, the history and the input on
 * more than one line.
 *
 * <p>The constructor takes every collaborator, and {@link #open()} builds the real ones. A test
 * therefore gives this class a mocked reader and terminal, and a spinner that records.
 */
final class RichUi implements Ui {

    private final LineReader reader;
    private final Terminal terminal;
    private final PrintStream out;
    private final Spinner spinner;
    private final EscapeWatcher watcher;

    RichUi(LineReader reader, Terminal terminal, PrintStream out, Spinner spinner,
           EscapeWatcher watcher) {
        this.reader = reader;
        this.terminal = terminal;
        this.out = out;
        this.spinner = spinner;
        this.watcher = watcher;
    }

    static RichUi open(Cancellation cancellation) throws IOException {
        Terminal terminal = TerminalBuilder.builder().system(true).build();

        Path history = Path.of(System.getProperty("user.home"), ".konacode", "chat_history");
        Files.createDirectories(history.getParent());

        LineReader reader = LineReaderBuilder.builder()
                .terminal(terminal)
                .variable(LineReader.HISTORY_FILE, history)
                .build();

        reader.getKeyMaps()
                .get(LineReader.MAIN)
                .bind(new Reference(LineReader.SELF_INSERT_UNMETA), KeyMap.alt("\r"));

        return new RichUi(reader, terminal, System.out, new Spinner(System.out, "thinking"),
                new EscapeWatcher(terminal, cancellation));
    }

    @Override
    public void welcome() {
        out.println(Ansi.style(Banner.forWidth(terminal.getWidth()), Ansi.CYAN));
        out.println(Ansi.style("esc stops · ctrl-d quits · alt-enter adds a line · /help",
                Ansi.DIM));
        out.println();
    }

    @Override
    public Optional<String> readLine() {
        try {
            return Optional.ofNullable(reader.readLine(Ansi.blue("> ")));
        } catch (UserInterruptException e) {
            return Optional.of("");
        } catch (EndOfFileException e) {
            return Optional.empty();
        }
    }

    @Override
    public void showAnswer(String text) {
        spinner.stop();
        watcher.stop();
        out.println(Markdown.render(text, terminal.getWidth()));
        out.println();
    }

    @Override
    public void showError(String message) {
        spinner.stop();
        watcher.stop();
        out.println(Ansi.style(message, Ansi.RED));
    }

    @Override
    public void thinking() {
        watcher.start();
        spinner.start();
    }

    @Override
    public void onToolCall(String name, String argumentsJson) {
        spinner.stop();
        out.println(Ansi.style("tool: " + name + "(" + argumentsJson + ")", Ansi.GREEN));
    }

    @Override
    public void onToolResult(String name, ToolResult result) {
        spinner.start();
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

    @Override
    public void close() {
        spinner.stop();
        watcher.stop();
        try {
            reader.getHistory().save();
            terminal.close();
        } catch (IOException e) {
            out.println(Ansi.style("Could not close the terminal: " + e.getMessage(), Ansi.RED));
        }
    }
}
