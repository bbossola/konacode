package dev.konacode.cli;

import dev.konacode.agent.ToolApproval;
import dev.konacode.policy.Decision;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import org.jline.reader.EndOfFileException;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
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

    private RichUi ui() {
        when(terminal.getWidth()).thenReturn(40);
        when(reader.getHistory()).thenReturn(history);
        return new RichUi(reader, terminal, out, spinner, new RecordingEscapeWatcher(terminal));
    }

    private String written() {
        return Ansi.strip(captured.toString(StandardCharsets.UTF_8));
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

        new RichUi(reader, terminal, out, spinner, new RecordingEscapeWatcher(terminal))
                .showAnswer("alpha beta gamma delta epsilon zeta");

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
                spinner, watcher);

        ui.thinking();

        assertEquals(List.of("start"), spinner.calls);
        assertEquals(List.of("start"), watcher.calls);
    }

    @Test
    void showAnswerStopsTheSpinnerAndTheWatcher() {
        RecordingSpinner spinner = new RecordingSpinner();
        RecordingEscapeWatcher watcher = new RecordingEscapeWatcher(terminal);
        RichUi ui = new RichUi(reader, terminal, new PrintStream(new ByteArrayOutputStream()),
                spinner, watcher);

        ui.showAnswer("done");

        assertEquals(List.of("stop"), spinner.calls);
        assertEquals(List.of("stop"), watcher.calls);
    }

    @Test
    void aToolCallStopsTheSpinnerAndLeavesTheWatcherRunning() {
        RecordingSpinner spinner = new RecordingSpinner();
        RecordingEscapeWatcher watcher = new RecordingEscapeWatcher(terminal);
        RichUi ui = new RichUi(reader, terminal, new PrintStream(new ByteArrayOutputStream()),
                spinner, watcher);

        ui.emit(new ToolCalled(1, "read_file", "{}"));

        assertEquals(List.of("stop"), spinner.calls);
        assertEquals(List.of(), watcher.calls);
    }

    // Task 5 flips this to a real question. Writing the test now is deliberate: it must fail on
    // the day this refuses less, not the day someone remembers to check.
    @Test
    void itCannotAskSoItRefuses() {
        assertEquals(ToolApproval.Answer.NO,
                ui().ask("edit_file", new Decision.Ask("write outside this project",
                        "/etc/hosts", Path.of("/etc"))));
    }
}
