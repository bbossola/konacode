package dev.konacode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            assertTrue(tool().computeAction(command(line)).permission().isEmpty(),
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
    void aBlankCommandOffersNoPermission() {
        assertTrue(tool().computeAction(command("   ")).permission().isEmpty());
    }

    @Test
    void theUserCanStopTheCommand() {
        assertTrue(tool().stopsOnInterrupt());
    }

    @Test
    void theDescriptionTellsTheModelThatANonZeroExitIsNormal() {
        assertTrue(tool().description().contains("exit code"),
                "the description is prompt text, and it must name the exit code");
    }
}
