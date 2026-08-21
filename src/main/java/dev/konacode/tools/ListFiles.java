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

    public ListFiles(Workspace workspace) {
        this.workspace = workspace;
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
            return ToolResult.ok(render(target));
        } catch (IOException e) {
            return ToolResult.err("Could not list path " + target + ". " + e);
        }
    }

    private String render(Path directory) throws IOException {
        List<Path> entries = workspace.listSorted(directory);

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
