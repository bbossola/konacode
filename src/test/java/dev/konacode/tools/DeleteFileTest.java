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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteFileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path root;

    private DeleteFile tool;

    @BeforeEach
    void setUp() {
        tool = new DeleteFile(new Workspace(root));
    }

    private static JsonNode args(String path) {
        return MAPPER.createObjectNode().put("path", path);
    }

    @Test
    void deletesAFile() throws IOException {
        Files.writeString(root.resolve("scratch.txt"), "temporary", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(args("scratch.txt"));

        assertEquals(ToolResult.ok("deleted file scratch.txt"), result);
        assertFalse(Files.exists(root.resolve("scratch.txt")));
    }

    @Test
    void refusesAMissingPath() {
        ToolResult result = tool.execute(args("absent.txt"));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(error.message().startsWith("Path not found:"), error.message());
    }

    @Test
    void refusesADirectory() throws IOException {
        Files.createDirectory(root.resolve("src"));

        ToolResult result = tool.execute(args("src"));

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(error.message().startsWith("Path is a directory"), error.message());
        assertTrue(Files.isDirectory(root.resolve("src")));
    }

    @Test
    void refusesArgumentsWithoutAPath() {
        ToolResult result = tool.execute(MAPPER.createObjectNode());

        ToolResult.Err error = assertInstanceOf(ToolResult.Err.class, result);
        assertTrue(error.message().startsWith("Invalid arguments for delete_file"),
                error.message());
    }

    @Test
    void deletesALinkAndLeavesItsTarget() throws IOException {
        Path target = root.resolve("target.txt");
        Files.writeString(target, "keep me", StandardCharsets.UTF_8);
        Files.createSymbolicLink(root.resolve("link.txt"), target);

        ToolResult result = tool.execute(args("link.txt"));

        assertInstanceOf(ToolResult.Ok.class, result);
        assertFalse(Files.exists(root.resolve("link.txt"), java.nio.file.LinkOption.NOFOLLOW_LINKS));
        assertEquals("keep me", Files.readString(target));
    }

    @Test
    void neverStopsOnAnInterrupt() {
        assertFalse(tool.stopsOnInterrupt());
    }
}
