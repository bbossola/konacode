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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadFileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private ReadFile tool;

    @BeforeEach
    void setUp() {
        tool = new ReadFile(new Workspace(root));
    }

    private static JsonNode args(String path) {
        return MAPPER.createObjectNode().put("path", path);
    }

    @Test
    void readsAFileRelativeToTheWorkspace() throws IOException {
        Files.writeString(root.resolve("notes.txt"), "line one\nline two", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(args("notes.txt"));

        assertEquals(ToolResult.ok("line one\nline two"), result);
    }

    @Test
    void reportsAMissingFileAsAnError() {
        ToolResult result = tool.execute(args("nope.txt"));

        ToolResult.Err err = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(err.message().contains("not found"), err.message());
    }

    @Test
    void refusesADirectory() throws IOException {
        Files.createDirectory(root.resolve("subdir"));

        ToolResult result = tool.execute(args("subdir"));

        ToolResult.Err err = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(err.message().contains("directory"), err.message());
    }

    @Test
    void truncatesAtTheHundredKilobyteCap() throws IOException {
        String oversized = "x".repeat(ReadFile.MAX_BYTES + 5_000);
        Files.writeString(root.resolve("big.txt"), oversized, StandardCharsets.UTF_8);

        ToolResult result = tool.execute(args("big.txt"));

        ToolResult.Ok ok = assertInstanceOf(ToolResult.Ok.class, result);
        assertEquals(ReadFile.MAX_BYTES, ok.text().length());
    }

    @Test
    void rejectsArgumentsWithoutAUsablePath() {
        ToolResult missing = tool.execute(MAPPER.createObjectNode());
        ToolResult blank = tool.execute(args("   "));

        assertInstanceOf(ToolResult.Err.class, missing);
        assertInstanceOf(ToolResult.Err.class, blank);
    }
}
