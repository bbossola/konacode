package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import dev.konacode.cli.markdown.Markdown;
import dev.konacode.policy.Decision;
import dev.konacode.trace.Level;
import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import org.jline.keymap.KeyMap;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
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
 * therefore gives this class a mocked reader and terminal, and a spinner that records. It also
 * keeps the {@link Cancellation}, so {@link #ask} can stop the turn when the user presses ESC.
 */
final class RichUi implements Ui {

    private final LineReader reader;
    private final Terminal terminal;
    private final PrintStream out;
    private final Spinner spinner;
    private final EscapeWatcher watcher;
    private final Cancellation cancellation;
    private Level live = Level.OFF;

    RichUi(LineReader reader, Terminal terminal, PrintStream out, Spinner spinner,
           EscapeWatcher watcher, Cancellation cancellation) {
        this.reader = reader;
        this.terminal = terminal;
        this.out = out;
        this.spinner = spinner;
        this.watcher = watcher;
        this.cancellation = cancellation;
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
                new EscapeWatcher(terminal, cancellation), cancellation);
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
    public boolean canAsk() {
        return true;
    }

    /**
     * Asks the user, and reads one key.
     *
     * <p>{@link EscapeWatcher} reads the terminal during a turn, so it must stop before the key is
     * read and start again after it. Without that it consumes the answer. The watcher is stopped
     * while the key is read, so it cannot see ESC there; the question reports the stop itself.
     */
    @Override
    public Answer ask(String toolName, Decision.Ask ask) {
        spinner.stop();
        watcher.stop();
        try {
            show(toolName, ask);
            return answer(read(), ask.alwaysFolder() != null);
        } finally {
            watcher.start();
        }
    }

    private void show(String toolName, Decision.Ask ask) {
        String verb = ask.action().split(" ", 2)[0];
        out.println();
        out.println(toolName + " wants to " + ask.action() + ".");
        out.println();
        out.println("  " + ask.subject());
        out.println();
        out.println("  y  " + verb + " it once");
        out.println("  n  refuse");
        if (ask.alwaysFolder() != null) {
            out.println("  a  always, for " + toolName + " in " + ask.alwaysFolder());
        }
        out.flush();
    }

    private int read() {
        Attributes saved = terminal.enterRawMode();
        try {
            return terminal.reader().read();
        } catch (IOException e) {
            return -1;
        } finally {
            terminal.setAttributes(saved);
        }
    }

    private Answer answer(int key, boolean folderOffered) {
        if (key == -1) {
            out.println();
            out.println("Could not read the answer. konacode refuses.");
            out.flush();
            return Answer.NO;
        }
        if (key == EscapeWatcher.ESCAPE) {
            cancellation.request();
            return Answer.NO;
        }
        if (key == 'y' || key == 'Y') {
            return Answer.YES;
        }
        if (folderOffered && (key == 'a' || key == 'A')) {
            return Answer.ALWAYS;
        }
        return Answer.NO;
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
            spinner.stop();
            out.println(Ansi.style(
                    "tool: " + called.name() + "(" + called.argumentsJson() + ")", Ansi.GREEN));
            return;
        }
        live.keep(event).ifPresent(kept -> {
            spinner.stop();
            out.println(Ansi.style("trace: " + TraceLine.of(kept), Ansi.MAGENTA));
        });
        if (event instanceof ToolFinished) {
            spinner.start();
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
