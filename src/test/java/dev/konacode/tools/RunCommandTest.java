package dev.konacode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
class RunCommandTest {

    @TempDir
    Path root;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Answers "stopped" from the first question. */
    private static final StopCheck STOPPED = () -> true;

    /** execute() restores the interrupt flag. This keeps it out of the next test in this thread. */
    @AfterEach
    void clearTheInterruptFlag() {
        Thread.interrupted();
    }

    private static ObjectNode command(String line) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("command", line);
        return args;
    }

    private RunCommand tool() {
        return new RunCommand(new Workspace(root), StopCheck.NEVER, Duration.ofSeconds(10));
    }

    @Test
    void theToolIsNamedRunCommand() {
        assertEquals("run_command", tool().name());
    }

    @Test
    void theSchemaRequiresACommand() {
        assertEquals("[\"command\"]", tool().inputSchema().get("required").toString());
    }

    @Test
    void aCommandAlwaysRuns() {
        assertEquals(Effect.RUNS, tool().computeAction(command("ls")).effect());
        assertEquals(Effect.RUNS, tool().computeAction(command("rm *.log")).effect());
        assertEquals(Effect.RUNS, tool().computeAction(MAPPER.createObjectNode()).effect());
    }

    @Test
    void theOperandIsTheCommandLine() {
        assertEquals("mvn -q test", tool().computeAction(command("mvn -q test")).operand());
    }

    @Test
    void aPlainLineOffersTheExactLine() {
        Action action = tool().computeAction(command("mvn -q test"));

        assertEquals(new Permission.ExactCommand("run_command", "mvn -q test"),
                action.permission().orElseThrow());
    }

    @Test
    void aLineThatJoinsCommandsStillOffersTheExactLine() {
        Action action = tool().computeAction(command("git add -A && git status | head -5; true"));

        assertTrue(action.permission().isPresent(),
                "a pipe, && and ; mean the same thing on the next day");
    }

    @Test
    void aLineThatExpandsOffersNoPermission() {
        for (String line : new String[] {
                "echo $HOME", "echo `date`", "rm *.log", "ls file?.txt",
                "ls file[12].txt", "ls ~/notes"}) {
            Action action = tool().computeAction(command(line));

            assertEquals(Effect.RUNS, action.effect(), line);
            assertTrue(action.permission().isEmpty(),
                    "this line means something else on another day: " + line);
        }
    }

    @Test
    void aMissingCommandOffersNoPermissionAndNamesTheTool() {
        Action action = tool().computeAction(MAPPER.createObjectNode());

        assertEquals(Effect.RUNS, action.effect());
        assertEquals("run_command", action.operand());
        assertTrue(action.permission().isEmpty());
    }

    @Test
    void aBlankCommandOffersNoPermissionAndNamesTheTool() {
        Action action = tool().computeAction(command("   "));

        assertEquals(Effect.RUNS, action.effect());
        assertEquals("run_command", action.operand());
        assertTrue(action.permission().isEmpty());
    }

    @Test
    void aCommandThatIsNotTextOffersNoPermissionAndNamesTheTool() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("command", 123);

        Action action = tool().computeAction(args);

        assertEquals(Effect.RUNS, action.effect());
        assertEquals("run_command", action.operand());
        assertTrue(action.permission().isEmpty());
    }

    @Test
    void theUserCanStopTheCommand() {
        assertTrue(tool().stopsOnInterrupt());
    }

    @Test
    void theDescriptionTellsTheModelThatANonZeroExitIsNormal() {
        String description = tool().description();

        assertTrue(description.contains("exit code"), description);
        assertTrue(description.contains("normal output"), description);
    }

    @Test
    void theDescriptionWarnsAboutABackgroundJob() {
        assertTrue(tool().description().contains("background"), tool().description());
    }

    @Test
    void theDescriptionSendsFileWorkToTheFileTools() {
        String description = tool().description();

        assertTrue(description.contains("read_file"), description);
        assertTrue(description.contains("delete_file"), description);
    }

    private String run(String line) {
        ToolResult result = tool().execute(command(line));
        return assertInstanceOf(ToolResult.Ok.class, result).text();
    }

    @Test
    void aCommandGivesBackWhatItPrinted() {
        assertTrue(run("echo hello").startsWith("hello"), "the output must come first");
    }

    @Test
    void aCommandThatSucceedsReportsExitZero() {
        assertTrue(run("echo hello").endsWith("<exit 0>"), run("echo hello"));
    }

    @Test
    void aCommandThatFailsIsNotAToolFailure() {
        assertTrue(run("exit 3").endsWith("<exit 3>"), "konacode ran it, so the result is Ok");
    }

    @Test
    void standardErrorComesBackWithStandardOutput() {
        assertTrue(run("echo bad >&2").contains("bad"));
    }

    @Test
    void theCommandRunsInTheProjectDirectory() throws IOException {
        assertTrue(run("pwd").startsWith(root.toRealPath().toString()), run("pwd"));
    }

    @Test
    void theCommandGetsNoStandardInput() {
        assertTrue(run("cat; echo done").contains("done"),
                "cat must reach the end of input at once and not hold the turn");
    }

    @Test
    void longOutputKeepsTheFirstPartAndTheLastPart() {
        String text = run("seq 1 200000");

        assertTrue(text.startsWith("1\n"), "the first line must survive");
        assertTrue(text.contains("<removed "), "the cap must say what it removed");
        assertTrue(text.contains("200000"), "the last line must survive");
    }

    @Test
    void anOrphanThatHoldsTheOutputOpenIsReported() {
        // The last sleep lets the drain thread block on the pipe before the shell exits. A read
        // that blocks already stays blocked when the shell exits, so the test always sees the
        // orphan. Without it, the result depends on a race and the test is not reliable.
        String text = run("sleep 5 & echo hi; sleep 0.3");

        assertTrue(text.contains("hi"), text);
        assertTrue(text.contains("<output may be incomplete"), text);
    }

    @Test
    void theExitLineIsLastEvenWhenTheOutputIsShort() {
        // The trailing sleep makes the orphan reliable, for the reason
        // anOrphanThatHoldsTheOutputOpenIsReported gives above.
        String text = run("sleep 5 & echo hi; sleep 0.3");

        assertTrue(text.contains("<output may be incomplete"), text);
        assertTrue(text.endsWith("<exit 0>"), text);
    }

    @Test
    void aMissingCommandIsAToolFailure() {
        ToolResult result = tool().execute(MAPPER.createObjectNode());

        assertInstanceOf(ToolResult.Err.class, result);
    }

    @Test
    void aCommandThatPassesTheTimeoutIsStopped() {
        RunCommand tool = new RunCommand(new Workspace(root), StopCheck.NEVER,
                Duration.ofMillis(200));

        ToolResult result = tool.execute(command("sleep 30"));

        ToolResult.Err err = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(err.message().contains("sleep 30"), err.message());
        assertTrue(err.message().contains("did not finish"), err.message());
    }

    @Test
    void theUserStopsACommandThatRuns() {
        RunCommand tool = new RunCommand(new Workspace(root), STOPPED, Duration.ofSeconds(30));

        ToolResult result = tool.execute(command("sleep 30"));

        ToolResult.Err err = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(err.message().contains("Stopped by the user"), err.message());
        assertTrue(err.message().contains("sleep 30"), err.message());
    }

    @Test
    void aStopEndsTheCommandQuickly() {
        RunCommand tool = new RunCommand(new Workspace(root), STOPPED, Duration.ofSeconds(30));
        long started = System.nanoTime();

        tool.execute(command("sleep 30"));

        long millis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(millis < 1_000, "the command must end at once, and it took " + millis + " ms");
    }

    @Test
    void theDefaultTimeoutIsTenMinutes() {
        assertEquals(Duration.ofSeconds(600), RunCommand.DEFAULT_TIMEOUT);
    }

    @Test
    void aConfiguredTimeoutIsUsed() {
        System.setProperty("konacode.command.timeoutSeconds", "5");
        try {
            assertEquals(Duration.ofSeconds(5), RunCommand.configuredTimeout());
        } finally {
            System.clearProperty("konacode.command.timeoutSeconds");
        }
    }

    @Test
    void aWrongTimeoutFailsLoudly() {
        System.setProperty("konacode.command.timeoutSeconds", "soon");
        try {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    RunCommand::configuredTimeout);
            assertTrue(thrown.getMessage().contains("konacode.command.timeoutSeconds"));
        } finally {
            System.clearProperty("konacode.command.timeoutSeconds");
        }
    }

    @Test
    void aTimeoutBelowOneSecondFailsLoudly() {
        System.setProperty("konacode.command.timeoutSeconds", "0");
        try {
            assertThrows(IllegalArgumentException.class, RunCommand::configuredTimeout);
        } finally {
            System.clearProperty("konacode.command.timeoutSeconds");
        }
    }

    @Test
    void anInterruptFromTheUserReadsAsAStop() {
        RunCommand tool = new RunCommand(new Workspace(root), STOPPED, Duration.ofSeconds(30));
        Thread.currentThread().interrupt();

        ToolResult result = tool.execute(command("sleep 30"));

        assertTrue(assertInstanceOf(ToolResult.Err.class, result).message()
                .contains("Stopped by the user"), "ESC interrupts, and the user must read a stop");
    }

    @Test
    void anInterruptWithNoUserStopReadsAsAnInterrupt() {
        RunCommand tool = new RunCommand(new Workspace(root), StopCheck.NEVER,
                Duration.ofSeconds(30));
        Thread.currentThread().interrupt();

        ToolResult result = tool.execute(command("sleep 30"));

        assertTrue(assertInstanceOf(ToolResult.Err.class, result).message()
                .contains("Interrupted while this command ran"), "no user stop, so no stop message");
    }

    @Test
    void aStoppedCommandGivesBackWhatItPrinted() {
        // The trailing sleep lets the drain thread read "working" before the first stop question,
        // 50 ms after the start. Without it the test depends on a race.
        RunCommand tool = new RunCommand(new Workspace(root), STOPPED, Duration.ofSeconds(30));

        ToolResult result = tool.execute(command("echo working; sleep 30"));

        String message = assertInstanceOf(ToolResult.Err.class, result).message();
        assertTrue(message.contains("working"), message);
        assertTrue(message.contains("<output before konacode stopped the command>"), message);
    }

    @Test
    void aCommandStoppedBeforeItPrintsGivesNoEmptyMarker() {
        RunCommand tool = new RunCommand(new Workspace(root), STOPPED, Duration.ofSeconds(30));

        ToolResult result = tool.execute(command("sleep 30"));

        assertFalse(assertInstanceOf(ToolResult.Err.class, result).message()
                .contains("<output before"), "no output means no marker");
    }

    @Test
    void aTimedOutCommandLeavesNoChildBehind() throws Exception {
        Path marker = root.resolve("timeout-marker.txt");
        RunCommand tool = new RunCommand(new Workspace(root), StopCheck.NEVER,
                Duration.ofMillis(200));

        tool.execute(command("sh -c 'sleep 0.3; echo late > " + marker + "'"));
        Thread.sleep(1000);

        assertFalse(Files.exists(marker), "the child of the shell must be destroyed too");
    }

    @Test
    void aStoppedCommandLeavesNoChildBehind() throws Exception {
        Path marker = root.resolve("marker.txt");
        RunCommand tool = new RunCommand(new Workspace(root), STOPPED, Duration.ofSeconds(30));

        tool.execute(command("sh -c 'sleep 0.3; echo late > " + marker + "'"));
        Thread.sleep(1000);

        assertFalse(Files.exists(marker), "the child of the shell must be destroyed too");
    }
}
