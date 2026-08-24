package dev.konacode.policy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.konacode.tools.Tool;
import dev.konacode.tools.Workspace;

import java.nio.file.Path;

/**
 * Allows a call inside the launch directory, and asks about every other one.
 *
 * <p>The tool decides whether a call reads or writes, and whether it stays inside. This class
 * decides what to do about the answer. It resolves the path a second time for one reason: the
 * question names a real path, and an approval is remembered against a real folder.
 */
public final class EffectPolicy implements ToolPolicy {

    private final Workspace workspace;

    public EffectPolicy(Workspace workspace) {
        this.workspace = workspace;
    }

    @Override
    public Decision check(Tool tool, JsonNode args) {
        return switch (tool.effect(args)) {
            case READS_INSIDE, WRITES_INSIDE -> Decision.allow();
            case READS_OUTSIDE -> ask("read outside this project", tool, args);
            case WRITES_OUTSIDE -> ask("write outside this project", tool, args);
            case RUNS -> Decision.ask("run a command", tool.name(), null);
        };
    }

    private Decision ask(String action, Tool tool, JsonNode args) {
        Path path = workspace.tryResolve(args.path("path").asText("")).orElse(null);
        if (path == null) {
            return Decision.ask(action, tool.name(), null);
        }
        return Decision.ask(action, path.toString(), workspace.folderOf(path).orElse(null));
    }
}
