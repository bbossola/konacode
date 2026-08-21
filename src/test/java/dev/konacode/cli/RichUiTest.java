package dev.konacode.cli;

import dev.konacode.tools.ToolResult;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
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
        return new RichUi(reader, terminal, out, spinner);
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

        new RichUi(reader, terminal, out, spinner)
                .showAnswer("alpha beta gamma delta epsilon zeta");

        assertTrue(written().contains("alpha beta gamma\ndelta epsilon zeta"), written());
    }

    @Test
    void rendersMarkdown() {
        ui().showAnswer("a **bold** word");

        assertTrue(captured.toString(StandardCharsets.UTF_8).contains(Ansi.BOLD));
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
        ui.onToolCall("read_file", "{}");
        ui.onToolResult("read_file", ToolResult.ok("content"));

        assertEquals(List.of("start", "stop", "start"), spinner.calls);
        assertTrue(written().contains("tool: read_file({})"), written());
    }

    @Test
    void savesTheHistoryAndClosesTheTerminalAtTheEnd() throws Exception {
        ui().close();

        verify(history).save();
        verify(terminal).close();
    }
}
