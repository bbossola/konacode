package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import dev.konacode.agent.ToolApproval;
import dev.konacode.policy.Decision;
import dev.konacode.tools.Permission;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RichUiTest {

    /** Spinner is our own type, so the double is hand-written. */
    static final class RecordingSpinner extends Spinner {
        final List<String> calls = new ArrayList<>();

        RecordingSpinner() {
            super(new PrintStream(OutputStream.nullOutputStream()), "thinking");
        }

        @Override
        public void start() {
            calls.add("start");
        }

        @Override
        public void stop() {
            calls.add("stop");
        }
    }

    /** EscapeWatcher is our own type, so the double is hand-written. */
    static final class RecordingEscapeWatcher extends EscapeWatcher {
        final List<String> calls = new ArrayList<>();

        RecordingEscapeWatcher(Terminal terminal) {
            super(terminal, new dev.konacode.agent.Cancellation());
        }

        @Override
        public void start() {
            calls.add("start");
        }

        @Override
        public void stop() {
            calls.add("stop");
        }
    }

    @Mock
    LineReader reader;

    @Mock
    Terminal terminal;

    @Mock
    History history;

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
    private final RecordingSpinner spinner = new RecordingSpinner();
    private final Cancellation cancellation = new Cancellation();

    private RichUi ui() {
        when(terminal.getWidth()).thenReturn(40);
        when(reader.getHistory()).thenReturn(history);
        return new RichUi(reader, terminal, out, spinner, new RecordingEscapeWatcher(terminal),
                cancellation);
    }

    private String written() {
        return Ansi.strip(captured.toString(StandardCharsets.UTF_8));
    }

    /** What the terminal gets, with no strip, because a forgery test must see an escape code. */
    private String raw() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    /**
     * How many lines start with the text, after the indent.
     *
     * <p>A forged question is a line of its own. A guarded one sits inside another line, so
     * "contains" counts both and "starts with" counts only the line konacode wrote.
     */
    private static long countLinesThatStartWith(String output, String text) {
        return output.lines().map(String::stripLeading).filter(line -> line.startsWith(text))
                .count();
    }

    @Test
    void returnsTheLineTheUserTyped() {
        when(reader.readLine(anyString())).thenReturn("what files are here?");

        assertEquals(Optional.of("what files are here?"), ui().readLine());
    }

    @Test
    void endsTheSessionWhenTheUserPressesCtrlD() {
        when(reader.readLine(anyString())).thenThrow(new EndOfFileException());

        assertEquals(Optional.empty(), ui().readLine());
    }

    @Test
    void givesAnEmptyLineWhenTheUserPressesCtrlC() {
        when(reader.readLine(anyString())).thenThrow(new UserInterruptException(""));

        assertEquals(Optional.of(""), ui().readLine());
    }

    @Test
    void rendersTheAnswerToTheWidthTheTerminalReports() {
        when(terminal.getWidth()).thenReturn(20);
        when(reader.getHistory()).thenReturn(history);

        new RichUi(reader, terminal, out, spinner, new RecordingEscapeWatcher(terminal),
                cancellation).showAnswer("alpha beta gamma delta epsilon zeta");

        assertTrue(written().contains("alpha beta gamma\ndelta epsilon zeta"), written());
    }

    @Test
    void promptsWithAGreaterThanSign() {
        when(reader.readLine(anyString())).thenReturn("hello");

        ui().readLine();

        verify(reader).readLine(contains(">"));
    }

    @Test
    void showsTheAnswerWithNoName() {
        ui().showAnswer("Hello.");

        assertFalse(written().contains("konacode"), written());
        assertTrue(written().contains("Hello."), written());
    }

    @Test
    void rendersMarkdown() {
        ui().showAnswer("a **bold** word");

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains(Ansi.BOLD));
    }

    @Test
    void showsAToolCallInGreen() {
        ui().emit(new ToolCalled(1, "read_file", "{}"));

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains(Ansi.GREEN),
                captured.toString(StandardCharsets.UTF_8));
    }

    @Test
    void startsTheSpinnerWhenTheAgentBeginsWork() {
        ui().thinking();

        assertEquals(List.of("start"), spinner.calls);
    }

    @Test
    void stopsTheSpinnerBeforeItPrintsTheAnswer() {
        RichUi ui = ui();
        ui.thinking();
        ui.showAnswer("done");

        assertEquals(List.of("start", "stop"), spinner.calls);
    }

    @Test
    void stopsTheSpinnerForAToolCallAndStartsItAfterTheResult() {
        RichUi ui = ui();
        ui.thinking();
        ui.emit(new ToolCalled(1, "read_file", "{}"));
        ui.emit(new ToolFinished(1, "read_file", true, "content", 5));

        assertEquals(List.of("start", "stop", "start"), spinner.calls);
        assertTrue(written().contains("tool: read_file({})"), written());
    }

    @Test
    void savesTheHistoryAndClosesTheTerminalAtTheEnd() throws Exception {
        ui().close();

        verify(history).save();
        verify(terminal).close();
    }

    @Test
    void thinkingStartsTheSpinnerAndTheWatcher() {
        RecordingSpinner spinner = new RecordingSpinner();
        RecordingEscapeWatcher watcher = new RecordingEscapeWatcher(terminal);
        RichUi ui = new RichUi(reader, terminal, new PrintStream(new ByteArrayOutputStream()),
                spinner, watcher, cancellation);

        ui.thinking();

        assertEquals(List.of("start"), spinner.calls);
        assertEquals(List.of("start"), watcher.calls);
    }

    @Test
    void showAnswerStopsTheSpinnerAndTheWatcher() {
        RecordingSpinner spinner = new RecordingSpinner();
        RecordingEscapeWatcher watcher = new RecordingEscapeWatcher(terminal);
        RichUi ui = new RichUi(reader, terminal, new PrintStream(new ByteArrayOutputStream()),
                spinner, watcher, cancellation);

        ui.showAnswer("done");

        assertEquals(List.of("stop"), spinner.calls);
        assertEquals(List.of("stop"), watcher.calls);
    }

    @Test
    void aToolCallStopsTheSpinnerAndLeavesTheWatcherRunning() {
        RecordingSpinner spinner = new RecordingSpinner();
        RecordingEscapeWatcher watcher = new RecordingEscapeWatcher(terminal);
        RichUi ui = new RichUi(reader, terminal, new PrintStream(new ByteArrayOutputStream()),
                spinner, watcher, cancellation);

        ui.emit(new ToolCalled(1, "read_file", "{}"));

        assertEquals(List.of("stop"), spinner.calls);
        assertEquals(List.of(), watcher.calls);
    }

    private static Decision.Ask askAbout(String file) {
        return new Decision.Ask("edit_file", "write outside this project", file,
                Optional.of(new Permission.InFolder("edit_file", Path.of(file).getParent())));
    }

    private NonBlockingReader keys(int key) throws IOException {
        NonBlockingReader keys = mock(NonBlockingReader.class);
        when(keys.read()).thenReturn(key);
        return keys;
    }

    @Test
    void canAskIsTrue() {
        assertTrue(ui().canAsk());
    }

    @Test
    void yMeansYes() throws IOException {
        NonBlockingReader input = keys('y');
        when(terminal.reader()).thenReturn(input);

        assertEquals(ToolApproval.Answer.YES, ui().ask(askAbout("/notes/a.txt")));
    }

    @Test
    void aMeansAlways() throws IOException {
        NonBlockingReader input = keys('a');
        when(terminal.reader()).thenReturn(input);

        assertEquals(ToolApproval.Answer.ALWAYS, ui().ask(askAbout("/notes/a.txt")));
    }

    @Test
    void theUppercaseVariantsAlsoWork() throws IOException {
        NonBlockingReader upperY = keys('Y');
        when(terminal.reader()).thenReturn(upperY);
        assertEquals(ToolApproval.Answer.YES, ui().ask(askAbout("/notes/a.txt")));

        NonBlockingReader upperA = keys('A');
        when(terminal.reader()).thenReturn(upperA);
        assertEquals(ToolApproval.Answer.ALWAYS, ui().ask(askAbout("/notes/a.txt")));
    }

    @Test
    void anyOtherKeyMeansNo() throws IOException {
        NonBlockingReader input = keys('q');
        when(terminal.reader()).thenReturn(input);

        assertEquals(ToolApproval.Answer.NO, ui().ask(askAbout("/notes/a.txt")));
    }

    @Test
    void escapeMeansNoAndStopsTheTurn() throws IOException {
        NonBlockingReader input = keys(EscapeWatcher.ESCAPE);
        when(terminal.reader()).thenReturn(input);

        assertEquals(ToolApproval.Answer.NO, ui().ask(askAbout("/notes/a.txt")));
        assertTrue(cancellation.stopped(), "esc must stop the turn");
    }

    @Test
    void aIsRefusedWhenNoPermissionIsOffered() throws IOException {
        NonBlockingReader input = keys('a');
        when(terminal.reader()).thenReturn(input);

        assertEquals(ToolApproval.Answer.NO,
                ui().ask(new Decision.Ask("run_command", "run a command", "run_command",
                        Optional.empty())));
    }

    @Test
    void theQuestionNamesTheToolThePathAndThePermission() throws IOException {
        NonBlockingReader input = keys('n');
        when(terminal.reader()).thenReturn(input);

        ui().ask(askAbout("/notes/a.txt"));

        String shown = written();
        assertTrue(shown.contains("edit_file wants to write outside this project."), shown);
        assertTrue(shown.contains("/notes/a.txt"), shown);
        assertTrue(shown.contains("always, for edit_file in /notes"), shown);
    }

    @Test
    void noAlwaysLineWithoutAPermission() throws IOException {
        NonBlockingReader input = keys('n');
        when(terminal.reader()).thenReturn(input);

        ui().ask(new Decision.Ask("run_command", "run a command", "run_command",
                Optional.empty()));

        assertFalse(written().contains("always"), written());
    }

    @Test
    void theWatcherStopsForTheAnswerAndStartsAgain() throws IOException {
        NonBlockingReader input = keys('y');
        when(terminal.reader()).thenReturn(input);
        when(terminal.getWidth()).thenReturn(40);
        when(reader.getHistory()).thenReturn(history);
        RecordingEscapeWatcher watcher = new RecordingEscapeWatcher(terminal);
        RichUi ui = new RichUi(reader, terminal, out, spinner, watcher, cancellation);

        ui.ask(askAbout("/notes/a.txt"));

        assertEquals(List.of("stop", "start"), watcher.calls);
    }

    @Test
    void aMissingAnswerRefuses() throws IOException {
        NonBlockingReader input = keys(-1);
        when(terminal.reader()).thenReturn(input);

        assertEquals(ToolApproval.Answer.NO, ui().ask(askAbout("/notes/a.txt")));
        assertTrue(written().contains("Could not read the answer. konacode refuses."), written());
    }

    @Test
    void theFirstWordOfTheIntentBecomesTheVerb() throws IOException {
        NonBlockingReader input = keys('n');
        when(terminal.reader()).thenReturn(input);

        ui().ask(new Decision.Ask("read_file", "read outside this project", "/etc/hosts",
                Optional.of(new Permission.InFolder("read_file", Path.of("/etc")))));

        assertTrue(written().contains("y  read it once"), written());
    }

    @Test
    void aNewlineInTheOperandCannotDrawASecondQuestion() throws IOException {
        // The model chooses this string. Without a guard it paints a question the user did not ask.
        NonBlockingReader input = keys('n');
        when(terminal.reader()).thenReturn(input);
        Decision.Ask ask = new Decision.Ask("run_command", "run a command",
                "echo safe\n\nrun_command wants to run a command.\n\n  rm -rf /\n",
                Optional.empty());

        ui().ask(ask);

        assertEquals(1, countLinesThatStartWith(raw(), "run_command wants to run a command."),
                "the screen must hold one question");
    }

    @Test
    void anEscapeCodeInTheOperandReachesNoTerminal() throws IOException {
        NonBlockingReader input = keys('n');
        when(terminal.reader()).thenReturn(input);
        Decision.Ask ask = new Decision.Ask("run_command", "run a command",
                "echo \u001B[2J\u001B[H safe", Optional.empty());

        ui().ask(ask);

        assertFalse(raw().contains("\u001B"), raw());
    }

    @Test
    void aLoneEscapeByteReachesNoTerminal() throws IOException {
        // Ansi.strip removes a whole colour code only, so the replace must cover a bare byte.
        NonBlockingReader input = keys('n');
        when(terminal.reader()).thenReturn(input);
        Decision.Ask ask = new Decision.Ask("run_command", "run a command", "echo \u001B safe",
                Optional.empty());

        ui().ask(ask);

        assertFalse(raw().contains("\u001B"), raw());
    }

    @Test
    void aDirectionOverrideReachesNoTerminal() throws IOException {
        // The override is written as an escape. A literal one here would reverse this test too.
        NonBlockingReader input = keys('n');
        when(terminal.reader()).thenReturn(input);
        Decision.Ask ask = new Decision.Ask("run_command", "run a command",
                "echo \u202Egnahc\u202C safe", Optional.empty());

        ui().ask(ask);

        assertFalse(raw().contains("\u202E"), raw());
    }

    @Test
    void anAccentedFileNameSurvives() throws IOException {
        NonBlockingReader input = keys('n');
        when(terminal.reader()).thenReturn(input);
        Decision.Ask ask = new Decision.Ask("read_file", "read outside this project",
                "/home/bruno/n\u00F3tes/caf\u00E9.txt", Optional.empty());

        ui().ask(ask);

        assertTrue(raw().contains("/home/bruno/n\u00F3tes/caf\u00E9.txt"), raw());
    }

    @Test
    void aNewlineInAPermissionCannotDrawASecondQuestion() throws IOException {
        NonBlockingReader input = keys('n');
        when(terminal.reader()).thenReturn(input);
        Decision.Ask ask = new Decision.Ask("run_command", "run a command", "echo safe",
                Optional.of(new Permission.ExactCommand("run_command",
                        "echo safe\n  y  run it once")));

        ui().ask(ask);

        assertEquals(1, countLinesThatStartWith(raw(), "y  run it once"),
                "the screen must offer one yes");
    }

    @Test
    void theTerminalEntersRawModeAndIsRestored() throws IOException {
        Attributes saved = new Attributes();
        when(terminal.enterRawMode()).thenReturn(saved);
        NonBlockingReader input = keys('y');
        when(terminal.reader()).thenReturn(input);

        ui().ask(askAbout("/notes/a.txt"));

        InOrder order = inOrder(terminal);
        order.verify(terminal).enterRawMode();
        order.verify(terminal).reader();
        order.verify(terminal).setAttributes(saved);
    }
}
