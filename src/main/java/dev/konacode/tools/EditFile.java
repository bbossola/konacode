package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Exact-match string replacement. Refuses to guess: zero matches and multiple matches are both
 * errors, so a mistaken edit fails loudly instead of silently changing the wrong line.
 */
public final class EditFile implements Tool {

    static final int CONTENT_PREVIEW_LIMIT = 100_000;

    /** Files above this size are refused rather than edited. Source files are far below it. */
    static final int MAX_EDITABLE_BYTES = 1_000_000;

    private static final String STOPPED =
            "Stopped by the user before the write. The file was not changed.";

    private final Workspace workspace;
    private final StopCheck stop;

    public EditFile(Workspace workspace, StopCheck stop) {
        this.workspace = workspace;
        this.stop = stop;
    }

    @Override
    public String name() {
        return "edit_file";
    }

    @Override
    public String description() {
        return """
                Make edits to a text file by replacing an exact match of `old_str` with `new_str`. \
                The replacement must be unique and `old_str` must differ from `new_str`. \
                Creates the file when it does not exist and `old_str` is empty.""";
    }

    @Override
    public ObjectNode inputSchema() {
        return Schemas.object()
                .requiredString("path", "The relative path of the file to edit.")
                .requiredString("old_str", "Text to replace. Must match exactly once. Empty to create a new file.")
                .requiredString("new_str", "Text to replace it with.")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode pathNode = args.path("path");
        JsonNode oldNode = args.path("old_str");
        JsonNode newNode = args.path("new_str");

        if (!pathNode.isTextual() || !oldNode.isTextual() || !newNode.isTextual()) {
            return ToolResult.err(
                    "Invalid arguments for edit_file. Expected: "
                            + "{\"path\": \"...\", \"old_str\": \"...\", \"new_str\": \"...\"}");
        }

        String rawPath = pathNode.asText().trim();
        String oldStr = oldNode.asText();
        String newStr = newNode.asText();

        if (rawPath.isEmpty()) {
            return ToolResult.err("Path must not be empty.");
        }
        if (oldStr.equals(newStr)) {
            return ToolResult.err("old_str and new_str must be different.");
        }

        Path file;
        try {
            file = workspace.resolve(rawPath);
        } catch (IllegalArgumentException e) {
            return ToolResult.err(e.getMessage());
        }

        return Files.exists(file)
                ? update(file, rawPath, oldStr, newStr)
                : create(file, rawPath, oldStr, newStr);
    }

    @Override
    public boolean stopsOnInterrupt() {
        return false;
    }

    @Override
    public Effect effect(JsonNode args) {
        JsonNode pathNode = args.path("path");
        if (!pathNode.isTextual()) {
            return Effect.WRITES_OUTSIDE;
        }
        return workspace.tryResolve(pathNode.asText())
                .filter(path -> workspace.writable(path) && workspace.readable(path))
                .map(path -> Effect.WRITES_INSIDE)
                .orElse(Effect.WRITES_OUTSIDE);
    }

    private ToolResult create(Path file, String rawPath, String oldStr, String newStr) {
        if (!oldStr.isEmpty()) {
            return ToolResult.err(
                    "File does not exist. Use an empty old_str to create a new file.");
        }
        if (stop.stopped()) {
            return ToolResult.err(STOPPED);
        }
        try {
            workspace.writeAtomic(file, newStr);
            return ToolResult.ok(success("created file " + rawPath, newStr));
        } catch (IOException e) {
            return ToolResult.err("Could not create file at path: " + rawPath + ". " + e);
        }
    }

    private ToolResult update(Path file, String rawPath, String oldStr, String newStr) {
        if (oldStr.isEmpty()) {
            return ToolResult.err("old_str must not be empty when editing an existing file.");
        }
        try {
            String original = workspace.readUtf8ForEditing(file, MAX_EDITABLE_BYTES);
            if (stop.stopped()) {
                return ToolResult.err(STOPPED);
            }
            int matches = countOccurrences(original, oldStr);

            if (matches == 0) {
                return ToolResult.err("old_str not found in " + rawPath + ".");
            }
            if (matches > 1) {
                return ToolResult.err(
                        "old_str must match exactly one occurrence. Found " + matches + ".");
            }

            // String.replace, never replaceAll: replaceAll would treat old_str as a regex and
            // new_str as a replacement template, so a $ or \ would corrupt the edit.
            String updated = original.replace(oldStr, newStr);
            if (stop.stopped()) {
                return ToolResult.err(STOPPED);
            }
            workspace.writeAtomic(file, updated);
            return ToolResult.ok(success("updated file " + rawPath, updated));
        } catch (IOException e) {
            return ToolResult.err("Could not edit file at path: " + rawPath + ". " + e);
        }
    }

    private static String success(String header, String content) {
        String preview = content.length() > CONTENT_PREVIEW_LIMIT
                ? content.substring(0, CONTENT_PREVIEW_LIMIT)
                : content;
        return header + "\n" + preview;
    }

    static int countOccurrences(String haystack, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = haystack.indexOf(needle);
        while (index != -1) {
            count++;
            index = haystack.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
