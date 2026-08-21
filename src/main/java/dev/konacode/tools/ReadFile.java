package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Returns a file's contents, capped so one large file cannot flood the context window. */
public final class ReadFile implements Tool {

    static final int MAX_BYTES = 100_000;

    private final Workspace workspace;

    public ReadFile(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "read_file";
    }

    @Override
    public String description() {
        return """
                Read the contents of a given relative file path. \
                Use this when you want to see what's inside a file. \
                Do not use this with directory names.""";
    }

    @Override
    public ObjectNode inputSchema() {
        return Schemas.object()
                .requiredString("path", "The relative path of the file to read.")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode pathNode = args.path("path");
        if (!pathNode.isTextual() || pathNode.asText().isBlank()) {
            return ToolResult.err("Invalid arguments for read_file. Expected: {\"path\": \"...\"}");
        }

        Path file;
        try {
            file = workspace.resolve(pathNode.asText());
        } catch (IllegalArgumentException e) {
            return ToolResult.err(e.getMessage());
        }

        if (!Files.exists(file)) {
            return ToolResult.err("Path not found: " + file);
        }
        if (Files.isDirectory(file)) {
            return ToolResult.err("Path is a directory, not a file: " + file);
        }

        try {
            return ToolResult.ok(workspace.readUtf8Capped(file, MAX_BYTES));
        } catch (IOException e) {
            return ToolResult.err("Could not read file at path: " + pathNode.asText() + ". " + e);
        }
    }
}
