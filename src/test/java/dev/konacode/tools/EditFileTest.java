package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditFileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private EditFile tool;

    @BeforeEach
    void setUp() {
        tool = new EditFile(new Workspace(root), StopCheck.NEVER);
    }

    /**
     * Answers false until the given question, then true. A constant true stops the tool at its
     * first check, which leaves the check before the write untested.
     */
    private static final class StopsOnQuestion implements StopCheck {

        private final int stopAt;
        private int asked;

        StopsOnQuestion(int stopAt) {
            this.stopAt = stopAt;
        }

        @Override
        public boolean stopped() {
            return ++asked >= stopAt;
        }
    }

    private static JsonNode args(String path, String oldStr, String newStr) {
        return MAPPER.createObjectNode()
                .put("path", path)
                .put("old_str", oldStr)
                .put("new_str", newStr);
    }

    private String contents(String name) throws IOException {
        return Files.readString(root.resolve(name), StandardCharsets.UTF_8);
    }

    private static String errorOf(ToolResult result) {
        return assertInstanceOf(ToolResult.Err.class, result).message();
    }

    @Test
    void createsAFileWhenOldStrIsEmpty() throws IOException {
        ToolResult result = tool.execute(args("fresh.txt", "", "brand new"));

        assertInstanceOf(ToolResult.Ok.class, result);
        assertEquals("brand new", contents("fresh.txt"));
        assertTrue(assertInstanceOf(ToolResult.Ok.class, result).text().startsWith("created file"));
    }

    @Test
    void createsMissingParentDirectories() throws IOException {
        tool.execute(args("a/b/c/deep.txt", "", "nested"));

        assertEquals("nested", contents("a/b/c/deep.txt"));
    }

    @Test
    void replacesAUniqueMatch() throws IOException {
        Files.writeString(root.resolve("code.txt"), "alpha beta gamma", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(args("code.txt", "beta", "DELTA"));

        assertInstanceOf(ToolResult.Ok.class, result);
        assertEquals("alpha DELTA gamma", contents("code.txt"));
        assertTrue(assertInstanceOf(ToolResult.Ok.class, result).text().startsWith("updated file"));
    }

    @Test
    void refusesWhenOldStrIsNotFound() throws IOException {
        Files.writeString(root.resolve("code.txt"), "alpha", StandardCharsets.UTF_8);

        assertTrue(errorOf(tool.execute(args("code.txt", "missing", "x"))).contains("not found"));
        assertEquals("alpha", contents("code.txt"));
    }

    @Test
    void refusesWhenOldStrMatchesMoreThanOnce() throws IOException {
        Files.writeString(root.resolve("code.txt"), "dup dup", StandardCharsets.UTF_8);

        String message = errorOf(tool.execute(args("code.txt", "dup", "x")));

        assertTrue(message.contains("Found 2"), message);
        assertEquals("dup dup", contents("code.txt"));
    }

    @Test
    void refusesWhenOldStrEqualsNewStr() {
        assertTrue(errorOf(tool.execute(args("code.txt", "same", "same"))).contains("different"));
    }

    @Test
    void refusesAnEmptyPath() {
        assertTrue(errorOf(tool.execute(args("   ", "", "x"))).contains("empty"));
    }

    @Test
    void refusesAnEmptyOldStrOnAnExistingFile() throws IOException {
        Files.writeString(root.resolve("code.txt"), "content", StandardCharsets.UTF_8);

        assertTrue(errorOf(tool.execute(args("code.txt", "", "x"))).contains("must not be empty"));
        assertEquals("content", contents("code.txt"));
    }

    @Test
    void refusesANonEmptyOldStrOnAMissingFile() {
        String message = errorOf(tool.execute(args("ghost.txt", "something", "x")));

        assertTrue(message.contains("does not exist"), message);
        assertFalse(Files.exists(root.resolve("ghost.txt")));
    }

    @Test
    void treatsDollarSignsInNewStrLiterally() throws IOException {
        Files.writeString(root.resolve("code.txt"), "PLACEHOLDER", StandardCharsets.UTF_8);

        tool.execute(args("code.txt", "PLACEHOLDER", "cost is $1 and $0 more"));

        assertEquals("cost is $1 and $0 more", contents("code.txt"));
    }

    @Test
    void treatsBackslashesInNewStrLiterally() throws IOException {
        Files.writeString(root.resolve("code.txt"), "PLACEHOLDER", StandardCharsets.UTF_8);

        tool.execute(args("code.txt", "PLACEHOLDER", "C:\\temp\\file"));

        assertEquals("C:\\temp\\file", contents("code.txt"));
    }

    @Test
    void treatsRegexMetacharactersInOldStrLiterally() throws IOException {
        Files.writeString(root.resolve("code.txt"), "value = a.b(c)", StandardCharsets.UTF_8);

        tool.execute(args("code.txt", "a.b(c)", "replaced"));

        assertEquals("value = replaced", contents("code.txt"));
    }

    @Test
    void rejectsArgumentsThatAreNotAllStrings() {
        JsonNode malformed = MAPPER.createObjectNode().put("path", "code.txt").put("old_str", 7);

        assertTrue(errorOf(tool.execute(malformed)).contains("Invalid arguments"));
    }

    @Test
    void refusesToEditAnOversizedFileAndLeavesItUnchanged() throws IOException {
        String oversized = "x".repeat(EditFile.MAX_EDITABLE_BYTES + 1);
        Files.writeString(root.resolve("huge.txt"), oversized, StandardCharsets.UTF_8);

        ToolResult result = tool.execute(args("huge.txt", "x", "y"));

        assertInstanceOf(ToolResult.Err.class, result);
        assertEquals(oversized, contents("huge.txt"));
    }

    @Test
    void refusesToEditANonUtf8FileAndLeavesItUnchanged() throws IOException {
        Path file = root.resolve("binary.dat");
        byte[] original = {(byte) 0xC3, (byte) 0x28, (byte) 0xA9};
        Files.write(file, original);

        ToolResult result = tool.execute(args("binary.dat", "a", "b"));

        assertInstanceOf(ToolResult.Err.class, result);
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    @Test
    void stopsAfterTheReadAndLeavesTheFileUnchanged() throws IOException {
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "one two three", StandardCharsets.UTF_8);
        EditFile stopping = new EditFile(new Workspace(root), () -> true);

        ToolResult result = stopping.execute(args("notes.txt", "two", "TWO"));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertEquals("Stopped by the user before the write. The file was not changed.",
                error.message());
        assertEquals("one two three", Files.readString(file));
    }

    @Test
    void stopsAtTheLastCheckBeforeTheWrite() throws IOException {
        Path file = root.resolve("notes.txt");
        Files.writeString(file, "one two three", StandardCharsets.UTF_8);
        EditFile stopping = new EditFile(new Workspace(root), new StopsOnQuestion(2));

        ToolResult result = stopping.execute(args("notes.txt", "two", "TWO"));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertEquals("Stopped by the user before the write. The file was not changed.",
                error.message());
        assertEquals("one two three", Files.readString(file));
    }

    @Test
    void stopsBeforeCreatingAFile() {
        EditFile stopping = new EditFile(new Workspace(root), () -> true);

        ToolResult result = stopping.execute(args("new.txt", "", "content"));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertEquals("Stopped by the user before the write. The file was not changed.",
                error.message());
        assertFalse(Files.exists(root.resolve("new.txt")));
    }

    @Test
    void truncatesTheEchoedContentInTheSuccessMessageButNotTheFile() throws IOException {
        String body = "y".repeat(EditFile.CONTENT_PREVIEW_LIMIT + 500);
        Files.writeString(root.resolve("wide.txt"), "SEED" + body, StandardCharsets.UTF_8);

        ToolResult result = tool.execute(args("wide.txt", "SEED", "DONE"));

        ToolResult.Ok ok = assertInstanceOf(ToolResult.Ok.class, result);
        String echoed = ok.text().substring(ok.text().indexOf('\n') + 1);
        assertEquals(EditFile.CONTENT_PREVIEW_LIMIT, echoed.length());
        assertEquals("DONE" + body, contents("wide.txt"));
    }
}
