package dev.konacode.cli;

import dev.konacode.cli.markdown.Markdown;
import dev.konacode.tools.ToolResult;
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

    RichUi(LineReader reader, Terminal terminal, PrintStream out, Spinner spinner) {
        this.reader = reader;
        this.terminal = terminal;
        this.out = out;
        this.spinner = spinner;
    }

    static RichUi open() throws IOException {
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

        return new RichUi(reader, terminal, System.out, new Spinner(System.out, "thinking"));
    }

    @Override
    public void welcome() {
        out.println();
        out.println(Ansi.style("konacode", Ansi.BOLD, Ansi.CYAN)
                + Ansi.style("  ctrl-d quits, alt-enter adds a line, /help lists the commands",
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
        out.println(Markdown.render(text, terminal.getWidth()));
        out.println();
    }

    @Override
    public void showError(String message) {
        spinner.stop();
        out.println(Ansi.style(message, Ansi.RED));
    }

    @Override
    public void thinking() {
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
    public void close() {
        spinner.stop();
        try {
            reader.getHistory().save();
            terminal.close();
        } catch (IOException e) {
            out.println(Ansi.style("Could not close the terminal: " + e.getMessage(), Ansi.RED));
        }
    }
}
