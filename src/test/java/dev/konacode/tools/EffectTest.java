package dev.konacode.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EffectTest {

    @TempDir
    Path root;

    /**
     * A second temporary folder. Never use {@code root.getParent()} for a path outside the root:
     * that is the shared temporary directory, so the test would litter it and two runs at once
     * would collide.
     */
    @TempDir
    Path outside;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode path(String value) {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("path", value);
        return args;
    }

    private Workspace workspace() {
        return new Workspace(root);
    }

    @Test
    void listFilesReadsInsideByDefault() {
        assertEquals(Effect.READS_INSIDE,
                new ListFiles(workspace(), StopCheck.NEVER).computeAction(MAPPER.createObjectNode()).effect());
    }

    @Test
    void listFilesReadsOutsideForASiblingFolder() {
        assertEquals(Effect.READS_OUTSIDE,
                new ListFiles(workspace(), StopCheck.NEVER).computeAction(path(outside.toString())).effect());
    }

    @Test
    void listFilesReadsInsideForABlankPath() {
        assertEquals(Effect.READS_INSIDE,
                new ListFiles(workspace(), StopCheck.NEVER).computeAction(path("   ")).effect());
    }

    @Test
    void readFileReadsInside() {
        assertEquals(Effect.READS_INSIDE,
                new ReadFile(workspace(), StopCheck.NEVER).computeAction(path("notes.txt")).effect());
    }

    @Test
    void readFileReadsOutside() {
        assertEquals(Effect.READS_OUTSIDE,
                new ReadFile(workspace(), StopCheck.NEVER).computeAction(path("/etc/passwd")).effect());
    }

    @Test
    void readFileWithNoPathReadsOutside() {
        assertEquals(Effect.READS_OUTSIDE,
                new ReadFile(workspace(), StopCheck.NEVER).computeAction(MAPPER.createObjectNode()).effect());
    }

    @Test
    void editFileWritesInside() {
        assertEquals(Effect.WRITES_INSIDE,
                new EditFile(workspace(), StopCheck.NEVER).computeAction(path("src/Main.java")).effect());
    }

    @Test
    void editFileWritesOutside() {
        assertEquals(Effect.WRITES_OUTSIDE,
                new EditFile(workspace(), StopCheck.NEVER)
                        .computeAction(path(outside.resolve("other.txt").toString())).effect());
    }

    @Test
    void editFileWithNoPathWritesOutside() {
        assertEquals(Effect.WRITES_OUTSIDE,
                new EditFile(workspace(), StopCheck.NEVER).computeAction(MAPPER.createObjectNode()).effect());
    }

    @Test
    void deleteFileWritesInside() {
        assertEquals(Effect.WRITES_INSIDE,
                new DeleteFile(workspace()).computeAction(path("build/old.txt")).effect());
    }

    @Test
    void deleteFileWritesOutside() {
        assertEquals(Effect.WRITES_OUTSIDE,
                new DeleteFile(workspace()).computeAction(path("~/notes.txt")).effect());
    }

    @Test
    void deleteFileWithNoPathWritesOutside() {
        assertEquals(Effect.WRITES_OUTSIDE,
                new DeleteFile(workspace()).computeAction(MAPPER.createObjectNode()).effect());
    }

    @Test
    void aReadableFolderReadsInsideAndWritesOutside() {
        Path skills = outside.resolve("skills");
        Workspace workspace = new Workspace(root, List.of(skills));
        String file = skills.resolve("one/SKILL.md").toString();

        assertEquals(Effect.READS_INSIDE,
                new ReadFile(workspace, StopCheck.NEVER).computeAction(path(file)).effect());
        assertEquals(Effect.READS_INSIDE,
                new ListFiles(workspace, StopCheck.NEVER).computeAction(path(skills.toString())).effect());
        assertEquals(Effect.WRITES_OUTSIDE,
                new EditFile(workspace, StopCheck.NEVER).computeAction(path(file)).effect());
        assertEquals(Effect.WRITES_OUTSIDE,
                new DeleteFile(workspace).computeAction(path(file)).effect());
    }

    @Test
    void aPathThisFilesystemRefusesIsOutside() {
        String withNul = "a" + (char) 0 + "b";

        assertEquals(Effect.READS_OUTSIDE,
                new ReadFile(workspace(), StopCheck.NEVER).computeAction(path(withNul)).effect());
        assertEquals(Effect.WRITES_OUTSIDE,
                new EditFile(workspace(), StopCheck.NEVER).computeAction(path(withNul)).effect());
    }

    @Test
    void aPathAboveTheRootIsOutsideForEveryTool() {
        assertEquals(Effect.READS_OUTSIDE,
                new ReadFile(workspace(), StopCheck.NEVER).computeAction(path("../secret.txt")).effect());
        assertEquals(Effect.READS_OUTSIDE,
                new ListFiles(workspace(), StopCheck.NEVER).computeAction(path("..")).effect());
        assertEquals(Effect.WRITES_OUTSIDE,
                new EditFile(workspace(), StopCheck.NEVER).computeAction(path("../secret.txt")).effect());
        assertEquals(Effect.WRITES_OUTSIDE,
                new DeleteFile(workspace()).computeAction(path("../secret.txt")).effect());
    }

    @Test
    void aWriteThroughALinkIntoTheRootAsks() throws IOException {
        Path inside = Files.writeString(root.resolve("real.txt"), "hello");
        Path link = Files.createSymbolicLink(outside.resolve("link.txt"), inside);

        try {
            assertEquals(Effect.WRITES_OUTSIDE,
                    new EditFile(workspace(), StopCheck.NEVER).computeAction(path(link.toString())).effect());
            assertEquals(Effect.WRITES_OUTSIDE,
                    new DeleteFile(workspace()).computeAction(path(link.toString())).effect());
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void editReadsBeforeItWritesAndDeleteDoesNot() throws IOException {
        Path secret = Files.writeString(outside.resolve("secret.txt"), "key");
        Path link = Files.createSymbolicLink(root.resolve("notes.txt"), secret);

        try {
            assertEquals(Effect.WRITES_OUTSIDE,
                    new EditFile(workspace(), StopCheck.NEVER).computeAction(path(link.toString())).effect(),
                    "edit_file reads the target, so it must ask");
            assertEquals(Effect.WRITES_INSIDE,
                    new DeleteFile(workspace()).computeAction(path(link.toString())).effect(),
                    "delete_file removes the link and reads nothing");
        } finally {
            Files.delete(link);
        }
    }
}
