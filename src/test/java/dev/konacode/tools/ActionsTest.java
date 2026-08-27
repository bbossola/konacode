package dev.konacode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionsTest {

    @TempDir
    Path root;

    /** A second temporary folder. Never use {@code root.getParent()}: that is shared. */
    @TempDir
    Path outside;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode path(String value) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("path", value);
        return args;
    }

    private Action read(ObjectNode args) {
        Workspace workspace = new Workspace(root);
        return Actions.onPath("read_file", workspace, args.path("path"),
                Effect.READS_INSIDE, Effect.READS_OUTSIDE,
                workspace::readable, workspace::readTarget);
    }

    @Test
    void aPathInsideTheRootReadsInside() {
        Action action = read(path("notes.txt"));

        assertEquals(Effect.READS_INSIDE, action.effect());
        assertEquals(root.resolve("notes.txt").toString(), action.operand());
        assertTrue(action.permission().isEmpty(), "a call inside is never asked about");
    }

    @Test
    void aPathOutsideTheRootOffersItsRealFolder() throws IOException {
        Path file = outside.toRealPath().resolve("secret.txt");

        Action action = read(path(file.toString()));

        assertEquals(Effect.READS_OUTSIDE, action.effect());
        assertEquals(file.toString(), action.operand());
        assertEquals(new Permission.InFolder("read_file", outside.toRealPath()),
                action.permission().orElseThrow());
    }

    @Test
    void aLinkInsideTheProjectOffersTheFolderItReaches() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "x");
        Path link = Files.createSymbolicLink(root.resolve("a.txt"), secret);

        try {
            Action action = read(path(link.toString()));

            assertEquals(secret.toRealPath().toString(), action.operand());
            assertEquals(new Permission.InFolder("read_file", outside.toRealPath()),
                    action.permission().orElseThrow());
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aBrokenLinkOffersNoPermission() throws IOException {
        Path link = Files.createSymbolicLink(root.resolve("dangling"), outside.resolve("gone"));

        try {
            Action action = read(path(link.toString()));

            assertEquals(Effect.READS_OUTSIDE, action.effect());
            assertTrue(action.permission().isEmpty(), "a call that reaches nothing offers none");
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aPathThatIsNotTextNamesTheTool() {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("path", 123);

        Action action = read(args);

        assertEquals(Effect.READS_OUTSIDE, action.effect());
        assertEquals("read_file", action.operand());
        assertTrue(action.permission().isEmpty());
    }
}
