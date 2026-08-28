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
        return Actions.read("read_file", workspace, args.path("path"));
    }

    private Action write(ObjectNode args) {
        Workspace workspace = new Workspace(root);
        return Actions.write("delete_file", workspace, args.path("path"));
    }

    private Action readThenWrite(ObjectNode args) {
        Workspace workspace = new Workspace(root);
        return Actions.readThenWrite("edit_file", workspace, args.path("path"));
    }

    @Test
    void aPathInsideTheRootReadsInside() {
        Action action = read(path("notes.txt"));

        assertEquals(Effect.READS_INSIDE, action.effect());
        assertEquals(root.resolve("notes.txt").toString(), action.toolOperand());
        assertTrue(action.standingPermission().isEmpty(), "a call inside is never asked about");
    }

    @Test
    void aPathOutsideTheRootOffersItsRealFolder() throws IOException {
        Path file = outside.toRealPath().resolve("secret.txt");

        Action action = read(path(file.toString()));

        assertEquals(Effect.READS_OUTSIDE, action.effect());
        assertEquals(file.toString(), action.toolOperand());
        assertEquals(new Permission.InFolder("read_file", outside.toRealPath()),
                action.standingPermission().orElseThrow());
    }

    @Test
    void aLinkInsideTheProjectOffersTheFolderItReaches() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "x");
        Path link = Files.createSymbolicLink(root.resolve("a.txt"), secret);

        try {
            Action action = read(path(link.toString()));

            assertEquals(secret.toRealPath().toString(), action.toolOperand());
            assertEquals(new Permission.InFolder("read_file", outside.toRealPath()),
                    action.standingPermission().orElseThrow());
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
            assertTrue(action.standingPermission().isEmpty(), "a call that reaches nothing offers none");
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
        assertEquals("read_file", action.toolOperand());
        assertTrue(action.standingPermission().isEmpty());
    }

    @Test
    void aPathThisFilesystemRefusesGivesTheTextBack() {
        String withNul = "a" + (char) 0 + "b";

        Action action = read(path(withNul));

        assertEquals(Effect.READS_OUTSIDE, action.effect());
        assertEquals(withNul, action.toolOperand(),
                "the operand is the path as the model wrote it");
        assertTrue(action.standingPermission().isEmpty());
    }

    @Test
    void aWriteThroughALinkNamesTheEntryAndNotTheTarget() throws IOException {
        Path inside = Files.writeString(root.resolve("real.txt"), "x");
        Path link = Files.createSymbolicLink(outside.resolve("link.txt"), inside);

        try {
            Action action = write(path(link.toString()));

            assertEquals(Effect.WRITES_OUTSIDE, action.effect());
            assertEquals(link.toString(), action.toolOperand(),
                    "a write replaces the entry, so the entry is the operand");
            assertEquals(new Permission.InFolder("delete_file", outside.toRealPath()),
                    action.standingPermission().orElseThrow());
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void anEditThroughALinkThatLeavesTheProjectAsks() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "key");
        Path link = Files.createSymbolicLink(root.resolve("notes.txt"), secret);

        try {
            Action action = readThenWrite(path(link.toString()));

            assertEquals(Effect.WRITES_OUTSIDE, action.effect(),
                    "edit_file reads the target, so it must ask");
            assertEquals(link.toString(), action.toolOperand(),
                    "the write replaces the entry, so the entry is the operand");
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void aDeleteOfThatSameLinkStaysInside() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "key");
        Path link = Files.createSymbolicLink(root.resolve("notes.txt"), secret);

        try {
            assertEquals(Effect.WRITES_INSIDE, write(path(link.toString())).effect(),
                    "delete_file removes the link and reads nothing");
        } finally {
            Files.delete(link);
        }
    }
}
