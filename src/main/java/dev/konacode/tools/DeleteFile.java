package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * Removes one file, so the model can reverse a file it created.
 *
 * <p>A directory is refused. A recursive delete is a different tool, and a far more dangerous
 * one.
 */
public final class DeleteFile implements Tool {

    private final Workspace workspace;

    public DeleteFile(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public String name() {
        return "delete_file";
    }

    @Override
    public String description() {
        return """
                Delete the file at a given relative path. \
                Use this to remove a file that is no longer wanted, for example one you created \
                by mistake. The delete cannot be undone. Do not use this with a directory.""";
    }

    @Override
    public ObjectNode inputSchema() {
        return Schemas.object()
                .requiredString("path", "The relative path of the file to delete.")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode pathNode = args.path("path");
        if (!pathNode.isTextual() || pathNode.asText().isBlank()) {
            return ToolResult.err(
                    "Invalid arguments for delete_file. Expected: {\"path\": \"...\"}");
        }

        Path file;
        try {
            file = workspace.resolve(pathNode.asText());
        } catch (IllegalArgumentException e) {
            return ToolResult.err(e.getMessage());
        }

        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.err("Path not found: " + file);
        }
        // NOFOLLOW_LINKS: a link to a directory is deleted as a link, and the target survives.
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResult.err("Path is a directory, not a file: " + file);
        }

        try {
            workspace.delete(file);
            return ToolResult.ok("deleted file " + pathNode.asText());
        } catch (IOException e) {
            return ToolResult.err("Could not delete file at path: " + pathNode.asText() + ". " + e);
        }
    }

    @Override
    public boolean stopsOnInterrupt() {
        return false;
    }
}
