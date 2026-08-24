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
                new ListFiles(workspace(), StopCheck.NEVER).effect(MAPPER.createObjectNode()));
    }

    @Test
    void listFilesReadsOutsideForASiblingFolder() {
        assertEquals(Effect.READS_OUTSIDE,
                new ListFiles(workspace(), StopCheck.NEVER).effect(path(outside.toString())));
    }

    @Test
    void listFilesReadsInsideForABlankPath() {
        assertEquals(Effect.READS_INSIDE,
                new ListFiles(workspace(), StopCheck.NEVER).effect(path("   ")));
    }

    @Test
    void readFileReadsInside() {
        assertEquals(Effect.READS_INSIDE,
                new ReadFile(workspace(), StopCheck.NEVER).effect(path("notes.txt")));
    }

    @Test
    void readFileReadsOutside() {
        assertEquals(Effect.READS_OUTSIDE,
                new ReadFile(workspace(), StopCheck.NEVER).effect(path("/etc/passwd")));
    }

    @Test
    void readFileWithNoPathReadsOutside() {
        assertEquals(Effect.READS_OUTSIDE,
                new ReadFile(workspace(), StopCheck.NEVER).effect(MAPPER.createObjectNode()));
    }

    @Test
    void editFileWritesInside() {
        assertEquals(Effect.WRITES_INSIDE,
                new EditFile(workspace(), StopCheck.NEVER).effect(path("src/Main.java")));
    }

    @Test
    void editFileWritesOutside() {
        assertEquals(Effect.WRITES_OUTSIDE,
                new EditFile(workspace(), StopCheck.NEVER)
                        .effect(path(outside.resolve("other.txt").toString())));
    }

    @Test
    void editFileWithNoPathWritesOutside() {
        assertEquals(Effect.WRITES_OUTSIDE,
                new EditFile(workspace(), StopCheck.NEVER).effect(MAPPER.createObjectNode()));
    }

    @Test
    void deleteFileWritesInside() {
        assertEquals(Effect.WRITES_INSIDE,
                new DeleteFile(workspace()).effect(path("build/old.txt")));
    }

    @Test
    void deleteFileWritesOutside() {
        assertEquals(Effect.WRITES_OUTSIDE,
                new DeleteFile(workspace()).effect(path("~/notes.txt")));
    }

    @Test
    void deleteFileWithNoPathWritesOutside() {
        assertEquals(Effect.WRITES_OUTSIDE,
                new DeleteFile(workspace()).effect(MAPPER.createObjectNode()));
    }

    @Test
    void aReadableFolderReadsInsideAndWritesOutside() {
        Path skills = outside.resolve("skills");
        Workspace workspace = new Workspace(root, List.of(skills));
        String file = skills.resolve("one/SKILL.md").toString();

        assertEquals(Effect.READS_INSIDE,
                new ReadFile(workspace, StopCheck.NEVER).effect(path(file)));
        assertEquals(Effect.READS_INSIDE,
                new ListFiles(workspace, StopCheck.NEVER).effect(path(skills.toString())));
        assertEquals(Effect.WRITES_OUTSIDE,
                new EditFile(workspace, StopCheck.NEVER).effect(path(file)));
        assertEquals(Effect.WRITES_OUTSIDE,
                new DeleteFile(workspace).effect(path(file)));
    }

    @Test
    void aPathThisFilesystemRefusesIsOutside() {
        String withNul = "a" + (char) 0 + "b";

        assertEquals(Effect.READS_OUTSIDE,
                new ReadFile(workspace(), StopCheck.NEVER).effect(path(withNul)));
        assertEquals(Effect.WRITES_OUTSIDE,
                new EditFile(workspace(), StopCheck.NEVER).effect(path(withNul)));
    }

    @Test
    void aPathAboveTheRootIsOutsideForEveryTool() {
        assertEquals(Effect.READS_OUTSIDE,
                new ReadFile(workspace(), StopCheck.NEVER).effect(path("../secret.txt")));
        assertEquals(Effect.WRITES_OUTSIDE,
                new EditFile(workspace(), StopCheck.NEVER).effect(path("../secret.txt")));
    }

    @Test
    void aWriteThroughALinkIntoTheRootAsks() throws IOException {
        Path inside = Files.writeString(root.resolve("real.txt"), "hello");
        Path link = Files.createSymbolicLink(outside.resolve("link.txt"), inside);

        try {
            assertEquals(Effect.WRITES_OUTSIDE,
                    new EditFile(workspace(), StopCheck.NEVER).effect(path(link.toString())));
            assertEquals(Effect.WRITES_OUTSIDE,
                    new DeleteFile(workspace()).effect(path(link.toString())));
        } finally {
            Files.delete(link);
        }
    }
}
