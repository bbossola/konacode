package dev.konacode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandTest {

    @TempDir
    Path root;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
        assertTrue(run("echo hello").endsWith("\nexit 0"), run("echo hello"));
    }

    @Test
    void aCommandThatFailsIsNotAToolFailure() {
        assertTrue(run("exit 3").endsWith("\nexit 3"), "konacode ran it, so the result is Ok");
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
    void aMissingCommandIsAToolFailure() {
        ToolResult result = tool().execute(MAPPER.createObjectNode());

        assertInstanceOf(ToolResult.Err.class, result);
    }
}
