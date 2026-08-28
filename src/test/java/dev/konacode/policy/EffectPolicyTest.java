package dev.konacode.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.tools.Action;
import dev.konacode.tools.DeleteFile;
import dev.konacode.tools.Effect;
import dev.konacode.tools.EditFile;
import dev.konacode.tools.ListFiles;
import dev.konacode.tools.Permission;
import dev.konacode.tools.ReadFile;
import dev.konacode.tools.Schemas;
import dev.konacode.tools.StopCheck;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolResult;
import dev.konacode.tools.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectPolicyTest {

    @TempDir
    Path root;

    /** A second temporary folder. Never use {@code root.getParent()}: that is shared. */
    @TempDir
    Path outside;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A stub that answers RUNS, so this test needs no real tool. */
    private static final class Running implements Tool {
        @Override
        public String name() {
            return "run_command";
        }

        @Override
        public String description() {
            return "Runs a command.";
        }

        @Override
        public com.fasterxml.jackson.databind.node.ObjectNode inputSchema() {
            return Schemas.object().build();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            return ToolResult.ok("");
        }

        @Override
        public boolean stopsOnInterrupt() {
            return false;
        }

        @Override
        public Action computeAction(JsonNode args) {
            return Action.once(name(), Effect.RUNS, "run_command");
        }
    }

    private static ObjectNode path(String value) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("path", value);
        return args;
    }

    private Workspace workspace() {
        return new Workspace(root);
    }

    private EffectPolicy policy() {
        return new EffectPolicy();
    }

    /** The loop computes the action and gives the policy the user's text, so the test does too. */
    private Decision check(Tool tool, JsonNode args) {
        return policy().check(tool.computeAction(args), "do the work");
    }

    @Test
    void aReadInsideIsAllowed() {
        assertInstanceOf(Decision.Allow.class, check(new ReadFile(workspace(), StopCheck.NEVER), path("notes.txt")));
    }

    @Test
    void aWriteInsideIsAllowed() {
        assertInstanceOf(Decision.Allow.class, check(new EditFile(workspace(), StopCheck.NEVER), path("src/Main.java")));
    }

    @Test
    void aListWithNoPathIsAllowed() {
        assertInstanceOf(Decision.Allow.class, check(new ListFiles(workspace(), StopCheck.NEVER), MAPPER.createObjectNode()));
    }

    @Test
    void aReadOutsideAsksAndNamesTheToolThePathAndThePermission() throws IOException {
        Path file = outside.toRealPath().resolve("secret.txt");

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class, check(new ReadFile(workspace(), StopCheck.NEVER), path(file.toString())));

        assertEquals("read_file", ask.toolName());
        assertEquals("read outside this project", ask.toolIntent());
        assertEquals(file.toString(), ask.toolOperand());
        assertEquals(new Permission.InFolder("read_file", outside.toRealPath()),
                ask.standingPermission().orElseThrow());
    }

    @Test
    void aWriteOutsideAsks() throws IOException {
        Path file = outside.toRealPath().resolve("notes.txt");

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class, check(new EditFile(workspace(), StopCheck.NEVER), path(file.toString())));

        assertEquals("write outside this project", ask.toolIntent());
        assertEquals(new Permission.InFolder("edit_file", outside.toRealPath()),
                ask.standingPermission().orElseThrow());
    }

    @Test
    void aDeleteOutsideAsks() {
        Decision.Ask ask = assertInstanceOf(Decision.Ask.class, check(new DeleteFile(workspace()), path(outside.resolve("old.txt").toString())));

        assertEquals("write outside this project", ask.toolIntent());
    }

    @Test
    void aCommandAsksAndOffersNoPermission() {
        Decision.Ask ask = assertInstanceOf(Decision.Ask.class, check(new Running(), MAPPER.createObjectNode()));

        assertEquals("run_command", ask.toolName());
        assertEquals("run a command", ask.toolIntent());
        assertEquals("run_command", ask.toolOperand());
        assertTrue(ask.standingPermission().isEmpty());
    }

    @Test
    void aCallWithNoUsablePathOffersNoPermission() {
        Decision.Ask ask = assertInstanceOf(Decision.Ask.class, check(new EditFile(workspace(), StopCheck.NEVER), MAPPER.createObjectNode()));

        assertEquals("edit_file", ask.toolOperand());
        assertTrue(ask.standingPermission().isEmpty());
    }

    @Test
    void aPathThatIsNotTextIsNotAPath() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("path", 123);

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class, check(new EditFile(workspace(), StopCheck.NEVER), args));

        assertEquals("edit_file", ask.toolOperand());
        assertTrue(ask.standingPermission().isEmpty());
    }

    @Test
    void thePolicyHoldsNoStateAndAnswersTheSameTwice() throws IOException {
        Path file = outside.toRealPath().resolve("secret.txt");
        EffectPolicy policy = policy();
        Action action = new ReadFile(workspace(), StopCheck.NEVER).computeAction(path(file.toString()));

        assertEquals(policy.check(action, "read the secret"), policy.check(action, "read the secret"));
    }

    @Test
    void thePolicyCopiesTheOperandAndThePermissionOfTheAction() throws IOException {
        Path file = outside.toRealPath().resolve("secret.txt");
        ReadFile tool = new ReadFile(workspace(), StopCheck.NEVER);
        Action action = tool.computeAction(path(file.toString()));

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class, check(tool, path(file.toString())));

        assertEquals(action.toolOperand(), ask.toolOperand());
        assertEquals(action.standingPermission(), ask.standingPermission());
    }

    @Test
    void listingAFolderRemembersThatFolderAndNotItsParent() throws IOException {
        Path folder = Files.createDirectories(outside.resolve("logs"));

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class, check(new ListFiles(workspace(), StopCheck.NEVER), path(folder.toString())));

        assertEquals(new Permission.InFolder("list_files", folder.toRealPath()),
                ask.standingPermission().orElseThrow(),
                "always must cover the folder the user listed");
    }

    @Test
    void aLinkInsideTheProjectDoesNotOfferTheProject() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "x");
        Path link = Files.createSymbolicLink(root.resolve("a.txt"), secret);

        try {
            Decision.Ask ask = assertInstanceOf(Decision.Ask.class, check(new ReadFile(workspace(), StopCheck.NEVER), path(link.toString())));

            assertEquals(secret.toRealPath().toString(), ask.toolOperand());
            assertEquals(new Permission.InFolder("read_file", outside.toRealPath()),
                    ask.standingPermission().orElseThrow());
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aBrokenLinkOffersNoPermission() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("dangling"), outside.resolve("gone"));

        try {
            Decision.Ask ask = assertInstanceOf(Decision.Ask.class, check(new ReadFile(workspace(), StopCheck.NEVER), path(link.toString())));

            assertTrue(ask.standingPermission().isEmpty(), "a call that reaches nothing offers no always");
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aWriteThroughALinkAsksAboutTheEntry() throws IOException {
        Path inside = Files.writeString(root.resolve("real.txt"), "x");
        Path link = Files.createSymbolicLink(outside.resolve("link.txt"), inside);

        try {
            Decision.Ask ask = assertInstanceOf(Decision.Ask.class, check(new EditFile(workspace(), StopCheck.NEVER), path(link.toString())));

            assertEquals(new Permission.InFolder("edit_file", outside.toRealPath()),
                    ask.standingPermission().orElseThrow(),
                    "a write replaces the entry, so the entry's folder is what is approved");
        } finally {
            Files.delete(link);
        }
    }

    /**
     * Pins the verb {@link dev.konacode.cli.RichUi} actually reads out of {@code toolIntent}:
     * {@code toolIntent.split(" ", 2)[0]}. Reads a real {@link Decision.Ask} from the policy for
     * each of the three intents it can produce, rather than asserting a literal against itself.
     */
    @Test
    void everyIntentBeginsWithAnImperativeVerb() {
        Path file = outside.resolve("secret.txt");

        Decision.Ask read = assertInstanceOf(Decision.Ask.class, check(new ReadFile(workspace(), StopCheck.NEVER), path(file.toString())));
        Decision.Ask write = assertInstanceOf(Decision.Ask.class, check(new EditFile(workspace(), StopCheck.NEVER), path(file.toString())));
        Decision.Ask run = assertInstanceOf(Decision.Ask.class, check(new Running(), MAPPER.createObjectNode()));

        assertEquals("read", verbOf(read));
        assertEquals("write", verbOf(write));
        assertEquals("run", verbOf(run));
    }

    @Test
    void aQuestionRefusesANullPermission() {
        assertThrows(NullPointerException.class,
                () -> new Decision.Ask("read_file", "read outside this project", "/etc", null, ""));
    }

    @Test
    void itNamesItselfAndAsks() {
        ToolPolicy policy = new EffectPolicy();

        assertEquals("effect", policy.label());
        assertTrue(policy.asks());
    }

    private static String verbOf(Decision.Ask ask) {
        return ask.toolIntent().split(" ", 2)[0];
    }
}
