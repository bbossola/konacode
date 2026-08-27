package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import dev.konacode.policy.Decision;
import dev.konacode.tools.Permission;
import dev.konacode.trace.TraceEvent.ToolCalled;
import org.jline.reader.History;
import org.jline.reader.LineReader;
import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.NonBlockingReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Every payload a review threw at the approval question, and the two lines it can reach.
 *
 * <p>The model chooses the operand, the permission and the arguments of a call. Each test here
 * fails when the guard gets weaker, so a payload that once forged a question cannot come back.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ForgedQuestionTest {

    private static String ch(int code) {
        return String.valueOf((char) code);
    }

    private static final String BACKSPACE = ch(0x08);
    private static final String VERTICAL_TAB = ch(0x0B);
    private static final String FORM_FEED = ch(0x0C);
    private static final String NEXT_LINE = ch(0x85);
    private static final String CSI = ch(0x9B);
    private static final String LINE_SEPARATOR = ch(0x2028);
    private static final String PARAGRAPH_SEPARATOR = ch(0x2029);
    private static final String ZERO_WIDTH_SPACE = ch(0x200B);

    /** The words konacode writes above a real question. A second one on the screen is a forgery. */
    private static final String QUESTION = "run_command wants to run a command.";

    /** Spinner is our own type, so the double is hand-written. */
    static final class QuietSpinner extends Spinner {
        QuietSpinner() {
            super(new PrintStream(OutputStream.nullOutputStream()), "thinking");
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    }

    /** EscapeWatcher is our own type, so the double is hand-written. */
    static final class QuietWatcher extends EscapeWatcher {
        QuietWatcher(Terminal terminal) {
            super(terminal, new Cancellation());
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }
    }

    private static final int WIDTH = 40;

    @Mock
    LineReader reader;

    @Mock
    Terminal terminal;

    @Mock
    History history;

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private RichUi ui(int width) {
        when(terminal.getWidth()).thenReturn(width);
        when(reader.getHistory()).thenReturn(history);
        when(terminal.enterRawMode()).thenReturn(new Attributes());
        return new RichUi(reader, terminal, out, new QuietSpinner(), new QuietWatcher(terminal),
                new Cancellation());
    }

    /** What the terminal gets, with no strip, because a forgery test must see an escape code. */
    private String raw() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private NonBlockingReader keys(int key) throws IOException {
        NonBlockingReader keys = mock(NonBlockingReader.class);
        when(keys.read()).thenReturn(key);
        return keys;
    }

    private String askWith(String operand) throws IOException {
        return askWith(operand, WIDTH);
    }

    private String askWith(String operand, int width) throws IOException {
        NonBlockingReader input = keys('n');
        when(terminal.reader()).thenReturn(input);
        ui(width).ask(new Decision.Ask("run_command", "run a command", operand, Optional.empty()));
        return raw();
    }

    /** How many lines start with the words of a question, after the indent. */
    private static long questions(String output) {
        return output.lines().map(String::stripLeading).filter(line -> line.startsWith(QUESTION))
                .count();
    }

    private static void assertOneQuestion(String output) {
        assertEquals(1, questions(output), "the screen must hold one question: " + output);
    }

    // ---- a control character cannot end the line ---------------------------------------------

    @Test
    void aCarriageReturnDrawsNoSecondLine() throws IOException {
        String output = askWith("echo safe\r" + QUESTION + "\r  rm -rf /");

        assertFalse(output.contains("\r"), output);
        assertOneQuestion(output);
    }

    @Test
    void aVerticalTabAndAFormFeedDrawNoSecondLine() throws IOException {
        String output = askWith("echo safe" + VERTICAL_TAB + QUESTION + FORM_FEED + "  rm -rf /");

        assertFalse(output.contains(VERTICAL_TAB), "vertical tab");
        assertFalse(output.contains(FORM_FEED), "form feed");
        assertOneQuestion(output);
    }

    @Test
    void aBackspaceCannotEraseWhatTheUserRead() throws IOException {
        String output = askWith("echo safe" + BACKSPACE.repeat(4) + "rm -rf /");

        assertFalse(output.contains(BACKSPACE), output);
    }

    @Test
    void aZeroWidthSpaceCannotHideInACommand() throws IOException {
        String output = askWith("rm" + ZERO_WIDTH_SPACE + " -rf /");

        assertFalse(output.contains(ZERO_WIDTH_SPACE), output);
    }

    @Test
    void theUnicodeLineAndParagraphSeparatorsDrawNoSecondLine() throws IOException {
        String output = askWith("echo safe" + LINE_SEPARATOR + QUESTION + PARAGRAPH_SEPARATOR);

        assertFalse(output.contains(LINE_SEPARATOR), "U+2028");
        assertFalse(output.contains(PARAGRAPH_SEPARATOR), "U+2029");
        assertOneQuestion(output);
    }

    @Test
    void theC1NextLineDrawsNoSecondLine() throws IOException {
        String output = askWith("echo safe" + NEXT_LINE + QUESTION + NEXT_LINE + "  rm -rf /");

        assertFalse(output.contains(NEXT_LINE), "U+0085");
        assertOneQuestion(output);
    }

    @Test
    void theC1ControlSequenceIntroducerReachesNoTerminal() throws IOException {
        // A terminal that reads C1 from UTF-8 treats U+009B and "2J" as erase display.
        String output = askWith("echo safe" + CSI + "2J" + CSI + "H rm -rf /");

        assertFalse(output.contains(CSI), "U+009B");
    }

    @Test
    void everyC1ControlReachesNoTerminal() throws IOException {
        StringBuilder line = new StringBuilder("echo ");
        for (int code = 0x80; code <= 0x9F; code++) {
            line.append((char) code);
        }

        String output = askWith(line.toString());

        for (int code = 0x80; code <= 0x9F; code++) {
            assertFalse(output.indexOf(code) >= 0, "U+00" + Integer.toHexString(code));
        }
    }

    @Test
    void anEscapeByteInTheArgumentsOfACallReachesNoTerminal() throws IOException {
        String output = askWith("echo \u001B[2J\u001B[H safe");

        assertFalse(output.contains("\u001B"), output);
    }

    // ---- a long line cannot wrap and forge a line ---------------------------------------------

    @Test
    void aLongOperandIsCutToTheWidthOfTheTerminal() throws IOException {
        // The model pads to 80 columns, the width it would guess, and the terminal here is 40.
        String pad = " ".repeat(80 - "echo safe".length());

        String output = askWith("echo safe" + pad + QUESTION + "  rm -rf /");

        assertTrue(output.lines().allMatch(line -> line.length() <= WIDTH), output);
        assertTrue(output.contains("echo safe"), "the beginning of the operand stays: " + output);
        assertTrue(output.contains("\u2026"), "the cut is marked: " + output);
        assertOneQuestion(output);
    }

    @Test
    void aLongPermissionIsCutToTheWidthOfTheTerminal() throws IOException {
        NonBlockingReader input = keys('n');
        when(terminal.reader()).thenReturn(input);
        String command = "echo " + "x".repeat(200);

        ui(WIDTH).ask(new Decision.Ask("run_command", "run a command", "echo safe",
                Optional.of(new Permission.ExactCommand("run_command", command))));

        assertTrue(raw().lines().allMatch(line -> line.length() <= WIDTH), raw());
    }

    @Test
    void aNarrowTerminalCutsNothingAndThrowsNothing() throws IOException {
        String output = askWith("echo /home/bruno/notes/a.txt", 0);

        assertTrue(output.contains("/home/bruno/notes/a.txt"), output);
    }

    // ---- the trace line above the question ----------------------------------------------------

    @Test
    void aToolCalledLineDrawsNoForgedQuestion() {
        // Agent emits this line before it asks, so the guard must cover it too.
        RichUi ui = ui(WIDTH);

        ui.emit(new ToolCalled(1, "run_command",
                "{\"command\":\"echo safe\u001B[2J\n\n" + QUESTION
                        + "\n\n  rm -rf /\"}"));

        String output = raw();
        assertFalse(output.contains("\u001B[2J"), output);
        assertEquals(0, questions(output), "the trace line forges no question: " + output);
    }

    // ---- text a user must read -----------------------------------------------------------------

    @Test
    void aCjkNameAnEmojiAndAnAccentSurvive() throws IOException {
        String output = askWith("cat /home/bruno/\u6587\u66F8/caf\u00E9-\uD83D\uDE80.txt");

        assertTrue(output.contains("\u6587\u66F8"), "CJK");
        assertTrue(output.contains("caf\u00E9"), "accent");
        assertTrue(output.contains("\uD83D\uDE80"), "emoji");
    }

    @Test
    void aHebrewNameSurvives() throws IOException {
        // A Hebrew letter is a letter, and a real file name holds one. The guard stops the
        // direction override only, because that character has no other use.
        String output = askWith("cat /home/bruno/\u05D0\u05D1\u05D2.txt");

        assertTrue(output.contains("\u05D0\u05D1\u05D2"), output);
    }

    @Test
    void aCombiningMarkSurvives() throws IOException {
        String output = askWith("cat /home/bruno/e\u0301.txt");

        assertTrue(output.contains("e\u0301"), output);
    }

    @Test
    void aTabBecomesAPicture() throws IOException {
        String output = askWith("cat /home/bruno/odd\tname.txt");

        assertFalse(output.contains("\t"), "a tab is replaced");
        assertTrue(output.contains("odd\u2400name.txt"), output);
    }
}
