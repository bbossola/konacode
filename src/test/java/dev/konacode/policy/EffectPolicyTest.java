package dev.konacode.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.tools.DeleteFile;
import dev.konacode.tools.Effect;
import dev.konacode.tools.EditFile;
import dev.konacode.tools.ListFiles;
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
import static org.junit.jupiter.api.Assertions.assertNull;

class EffectPolicyTest {

    @TempDir
    Path root;

    /** A second temporary folder. Never use {@code root.getParent()}: that is shared. */
    @TempDir
    Path outside;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The only tool that answers RUNS. No real tool does until run_command exists. */
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
        public Effect effect(JsonNode args) {
            return Effect.RUNS;
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
        return new EffectPolicy(workspace());
    }

    @Test
    void aReadInsideIsAllowed() {
        assertInstanceOf(Decision.Allow.class,
                policy().check(new ReadFile(workspace(), StopCheck.NEVER), path("notes.txt")));
    }

    @Test
    void aWriteInsideIsAllowed() {
        assertInstanceOf(Decision.Allow.class,
                policy().check(new EditFile(workspace(), StopCheck.NEVER), path("src/Main.java")));
    }

    @Test
    void aListWithNoPathIsAllowed() {
        assertInstanceOf(Decision.Allow.class,
                policy().check(new ListFiles(workspace(), StopCheck.NEVER),
                        MAPPER.createObjectNode()));
    }

    @Test
    void aReadOutsideAsksAndNamesThePathAndTheFolder() throws IOException {
        Path file = outside.toRealPath().resolve("secret.txt");

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new ReadFile(workspace(), StopCheck.NEVER),
                        path(file.toString())));

        assertEquals("read outside this project", ask.action());
        assertEquals(file.toString(), ask.subject());
        assertEquals(outside.toRealPath(), ask.alwaysFolder());
    }

    @Test
    void aWriteOutsideAsks() throws IOException {
        Path file = outside.toRealPath().resolve("notes.txt");

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new EditFile(workspace(), StopCheck.NEVER),
                        path(file.toString())));

        assertEquals("write outside this project", ask.action());
        assertEquals(outside.toRealPath(), ask.alwaysFolder());
    }

    @Test
    void aDeleteOutsideAsks() {
        Path file = outside.resolve("old.txt");

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new DeleteFile(workspace()), path(file.toString())));

        assertEquals("write outside this project", ask.action());
    }

    @Test
    void aCommandAsksAndOffersNoFolder() {
        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new Running(), MAPPER.createObjectNode()));

        assertEquals("run a command", ask.action());
        assertEquals("run_command", ask.subject());
        assertNull(ask.alwaysFolder(), "a command has no folder, so always is not offered");
    }

    @Test
    void aCallWithNoUsablePathOffersNoFolder() {
        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new EditFile(workspace(), StopCheck.NEVER),
                        MAPPER.createObjectNode()));

        assertEquals("edit_file", ask.subject());
        assertNull(ask.alwaysFolder());
    }

    @Test
    void aPathThatIsNotTextIsNotAPath() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("path", 123);

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new EditFile(workspace(), StopCheck.NEVER), args));

        assertEquals("edit_file", ask.subject());
        assertNull(ask.alwaysFolder());
    }

    @Test
    void listingAFolderRemembersThatFolderAndNotItsParent() throws IOException {
        Path folder = Files.createDirectories(outside.resolve("logs"));

        Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                policy().check(new ListFiles(workspace(), StopCheck.NEVER),
                        path(folder.toString())));

        assertEquals(folder, ask.alwaysFolder(), "always must cover the folder the user listed");
    }

    @Test
    void aLinkInsideTheProjectDoesNotOfferTheProject() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "x");
        Path link = Files.createSymbolicLink(root.resolve("a.txt"), secret);

        try {
            Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                    policy().check(new ReadFile(workspace(), StopCheck.NEVER),
                            path(link.toString())));

            assertEquals(secret.toRealPath().toString(), ask.subject());
            assertEquals(outside.toRealPath(), ask.alwaysFolder());
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aBrokenLinkOffersNoFolder() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("dangling"), outside.resolve("gone"));

        try {
            Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                    policy().check(new ReadFile(workspace(), StopCheck.NEVER),
                            path(link.toString())));

            assertNull(ask.alwaysFolder(), "a call that reaches nothing offers no always");
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aWriteThroughALinkAsksAboutTheEntry() throws IOException {
        Path inside = Files.writeString(root.resolve("real.txt"), "x");
        Path link = Files.createSymbolicLink(outside.resolve("link.txt"), inside);

        try {
            Decision.Ask ask = assertInstanceOf(Decision.Ask.class,
                    policy().check(new EditFile(workspace(), StopCheck.NEVER),
                            path(link.toString())));

            assertEquals(outside.toRealPath(), ask.alwaysFolder(),
                    "a write replaces the entry, so the entry's folder is what is approved");
        } finally {
            Files.delete(link);
        }
    }

    /**
     * Pins the verb {@link dev.konacode.cli.RichUi} actually reads out of {@code action}:
     * {@code action.split(" ", 2)[0]}. Reads a real {@link Decision.Ask} from the policy for
     * each of the three actions it can produce, rather than asserting a literal against itself.
     */
    @Test
    void everyActionBeginsWithAnImperativeVerb() {
        Path file = outside.resolve("secret.txt");

        Decision.Ask read = assertInstanceOf(Decision.Ask.class,
                policy().check(new ReadFile(workspace(), StopCheck.NEVER), path(file.toString())));
        Decision.Ask write = assertInstanceOf(Decision.Ask.class,
                policy().check(new EditFile(workspace(), StopCheck.NEVER), path(file.toString())));
        Decision.Ask run = assertInstanceOf(Decision.Ask.class,
                policy().check(new Running(), MAPPER.createObjectNode()));

        assertEquals("read", verbOf(read));
        assertEquals("write", verbOf(write));
        assertEquals("run", verbOf(run));
    }

    private static String verbOf(Decision.Ask ask) {
        return ask.action().split(" ", 2)[0];
    }
}
