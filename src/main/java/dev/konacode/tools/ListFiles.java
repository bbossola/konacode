package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A snapshot of a directory, capped so a large tree cannot flood the context window. */
public final class ListFiles implements Tool {

    static final int ENTRY_LIMIT = 200;

    private final Workspace workspace;
    private final StopCheck stop;

    public ListFiles(Workspace workspace, StopCheck stop) {
        this.workspace = workspace;
        this.stop = stop;
    }

    @Override
    public String name() {
        return "list_files";
    }

    @Override
    public String description() {
        return """
                List files and directories at a given relative path. \
                Use this when you need to inspect the project structure. \
                Defaults to the current working directory when no path is supplied.""";
    }

    @Override
    public ObjectNode inputSchema() {
        return Schemas.object()
                .optionalString("path", "The relative path to list. Defaults to the current directory.")
                .build();
    }

    @Override
    public ToolResult execute(JsonNode args) {
        JsonNode pathNode = args.path("path");
        String rawPath = pathNode.isTextual() && !pathNode.asText().isBlank()
                ? pathNode.asText()
                : ".";

        Path target;
        try {
            target = workspace.resolve(rawPath);
        } catch (IllegalArgumentException e) {
            return ToolResult.err(e.getMessage());
        }

        if (!Files.exists(target)) {
            return ToolResult.err("Path not found: " + target);
        }
        if (!Files.isDirectory(target)) {
            return ToolResult.ok("file " + target);
        }

        try {
            List<Path> entries = workspace.listSorted(target, stop);
            if (stop.stopped()) {
                return ToolResult.err("Stopped by the user after " + entries.size()
                        + " entries. Nothing was changed.");
            }
            return ToolResult.ok(render(target, entries));
        } catch (IOException e) {
            return ToolResult.err("Could not list path " + target + ". " + e);
        }
    }

    @Override
    public boolean stopsOnInterrupt() {
        return false;
    }

    @Override
    public Effect effect(JsonNode args) {
        JsonNode pathNode = args.path("path");
        if (!pathNode.isTextual() || pathNode.asText().isBlank()) {
            // No path means the root, and a root is inside itself. execute() reaches the same
            // place through resolve("."), so the two agree while a Workspace has one root.
            return Effect.READS_INSIDE;
        }
        return workspace.tryResolve(pathNode.asText())
                .filter(workspace::readable)
                .map(path -> Effect.READS_INSIDE)
                .orElse(Effect.READS_OUTSIDE);
    }

    private String render(Path directory, List<Path> entries) {
        List<String> lines = new ArrayList<>();
        lines.add("directory " + directory);

        if (entries.isEmpty()) {
            lines.add("<empty>");
            return String.join("\n", lines);
        }

        entries.stream().limit(ENTRY_LIMIT).map(ListFiles::format).forEach(lines::add);
        if (entries.size() > ENTRY_LIMIT) {
            lines.add("… " + (entries.size() - ENTRY_LIMIT) + " more");
        }
        return String.join("\n", lines);
    }

    private static String format(Path entry) {
        StringBuilder name = new StringBuilder(entry.getFileName().toString());
        if (Files.isDirectory(entry)) {
            name.append('/');
        }
        if (Files.isSymbolicLink(entry)) {
            name.append('@');
        }
        return name.toString();
    }
}
