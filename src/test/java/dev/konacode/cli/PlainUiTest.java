package dev.konacode.cli;

import dev.konacode.agent.ToolApproval;
import dev.konacode.policy.Decision;
import dev.konacode.tools.Permission;
import dev.konacode.trace.Level;
import dev.konacode.trace.TraceEvent.FromAgent;
import dev.konacode.trace.TraceEvent.Judged;
import dev.konacode.trace.TraceEvent.Outcome;
import dev.konacode.trace.TraceEvent.ToolCalled;
import dev.konacode.trace.TraceEvent.ToolFinished;
import dev.konacode.trace.TraceEvent.TurnEnded;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlainUiTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

    private PlainUi ui(String input) {
        return new PlainUi(new BufferedReader(new StringReader(input)), out);
    }

    private String written() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void readsALineAndPrintsThePrompt() {
        assertEquals(Optional.of("hello"), ui("hello\n").readLine());
        assertTrue(written().contains("You"), written());
    }

    @Test
    void returnsEmptyAtTheEndOfInput() {
        assertEquals(Optional.empty(), ui("").readLine());
    }

    @Test
    void printsTheBanner() {
        ui("").welcome();

        assertTrue(written().contains("Chat with konacode"), written());
    }

    @Test
    void printsTheAnswerAfterTheName() {
        ui("").showAnswer("two files here");

        assertTrue(Ansi.strip(written()).contains("konacode: two files here"), written());
    }

    @Test
    void printsOneLineForEachToolCall() {
        ui("").emit(new ToolCalled(1, "read_file", "{\"path\":\"pom.xml\"}"));

        assertEquals("tool: read_file({\"path\":\"pom.xml\"})" + System.lineSeparator(), written());
    }

    @Test
    void printsTheToolLineOfANamedCall() {
        ui("").emit(new FromAgent("judge", new ToolCalled(1, "read_file", "{\"path\":\"pom.xml\"}")));

        assertEquals("tool: judge> read_file({\"path\":\"pom.xml\"})" + System.lineSeparator(), written());
    }

    @Test
    void printsNothingForAToolResult() {
        ui("").emit(new ToolFinished(1, "read_file", true, "content", 5));

        assertEquals("", written());
    }

    @Test
    void printsNothingWhenTheAgentStartsWork() {
        ui("").thinking();

        assertEquals("", written());
    }

    @Test
    void doesNotRenderMarkdown() {
        ui("").showAnswer("# not a heading");

        assertTrue(Ansi.strip(written()).contains("# not a heading"), written());
    }

    @Test
    void showsNoTraceEventWhenTheScreenIsOff() {
        ui("").emit(new TurnEnded(1, Outcome.ANSWERED, 2, 30));

        assertEquals("", written());
    }

    @Test
    void showsATraceEventWhenTheScreenIsOn() {
        PlainUi ui = ui("");
        ui.liveTrace(Level.BASIC);

        ui.emit(new TurnEnded(1, Outcome.ANSWERED, 2, 30));

        assertTrue(written().contains("ANSWERED"), written());
    }

    @Test
    void alwaysShowsTheToolCall() {
        ui("").emit(new ToolCalled(1, "read_file", "{}"));

        assertTrue(written().contains("tool: read_file({})"), written());
    }

    @Test
    void alwaysShowsWhatTheJudgeAnswered() {
        ui("").emit(new Judged("run_command", "mvn -q test", "allow"));

        assertEquals("judged: allow run_command mvn -q test" + System.lineSeparator(), written());
    }

    @Test
    void showsAJudgementOnce() {
        PlainUi ui = ui("");
        ui.liveTrace(Level.FULL);

        ui.emit(new Judged("run_command", "mvn -q test", "allow"));

        assertEquals(1, written().lines().count(), written());
    }

    @Test
    void namesTheAgentBesideAJudgement() {
        ui("").emit(new FromAgent("kona", new Judged("run_command", "mvn -q test", "allow")));

        assertEquals("judged: kona> allow run_command mvn -q test" + System.lineSeparator(), written());
    }

    @Test
    void itCannotAskSoItRefuses() {
        assertEquals(ToolApproval.Answer.NO,
                ui("").ask(new Decision.Ask("edit_file", "write outside this project",
                        "/etc/hosts",
                        Optional.of(new Permission.InFolder("edit_file", Path.of("/etc"))), "")));
    }

    @Test
    void itCannotAsk() {
        assertFalse(ui("").canAsk());
    }

    @Test
    void aToolCallLineHoldsOneLine() {
        // The model wrote the arguments. A newline here draws a line konacode did not write.
        ui("").emit(new ToolCalled(1, "run_command",
                "{\"command\":\"echo safe\nrm -rf /\"}"));

        assertEquals(1, written().lines().count(), written());
        assertFalse(written().contains("\u001B"), written());
    }
}
