package dev.konacode.policy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.konacode.tools.Tool;
import dev.konacode.tools.Workspace;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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
        this.workspace = Objects.requireNonNull(workspace, "workspace");
    }

    @Override
    public Decision check(Tool tool, JsonNode args) {
        return switch (tool.effect(args)) {
            case READS_INSIDE, WRITES_INSIDE -> Decision.allow();
            case READS_OUTSIDE -> ask("read outside this project", tool, args, workspace::readTarget);
            case WRITES_OUTSIDE -> ask("write outside this project", tool, args, workspace::writeTarget);
            case RUNS -> Decision.ask("run a command", tool.name(), null);
        };
    }

    /**
     * The question names the place the call reaches, and not the path as written. A link inside the
     * project that points outside must not offer an approval for the project.
     */
    private Decision ask(String action, Tool tool, JsonNode args,
                         Function<Path, Optional<Path>> target) {
        Path reached = workspace.tryResolve(args.path("path")).flatMap(target).orElse(null);
        if (reached == null) {
            return Decision.ask(action, tool.name(), null);
        }
        return Decision.ask(action, reached.toString(), workspace.folderOf(reached).orElse(null));
    }
}
