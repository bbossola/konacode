package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Builds the {@link Action} of a tool that acts on one path.
 *
 * <p>Four tools share one rule, and the rule is subtle: a read is judged at the file the link
 * reaches, and a write is judged at the entry the write replaces. One copy of the rule keeps the
 * four tools in agreement.
 */
final class Actions {

    private Actions() {
    }

    /**
     * @param toolName the tool that asks
     * @param pathNode the {@code path} argument, which may be absent or not text
     * @param insideEffect the effect when the path stays inside the workspace
     * @param outsideEffect the effect when it does not
     * @param staysInside {@code Workspace::readable} for a read, {@code Workspace::writable} for
     *     a write
     * @param reaches {@code Workspace::readTarget} for a read, {@code Workspace::writeTarget} for
     *     a write
     */
    static Action onPath(String toolName,
                         Workspace workspace,
                         JsonNode pathNode,
                         Effect insideEffect,
                         Effect outsideEffect,
                         Predicate<Path> staysInside,
                         Function<Path, Optional<Path>> reaches) {
        Optional<Path> resolved = workspace.tryResolve(pathNode);
        if (resolved.isEmpty()) {
            return Action.once(outsideEffect,
                    pathNode.isTextual() ? pathNode.asText() : toolName);
        }
        Path path = resolved.get();
        if (staysInside.test(path)) {
            return Action.once(insideEffect, path.toString());
        }
        Optional<Path> target = reaches.apply(path);
        if (target.isEmpty()) {
            return Action.once(outsideEffect, path.toString());
        }
        Path reached = target.get();
        return workspace.folderOf(reached)
                .<Action>map(folder -> Action.of(outsideEffect, reached.toString(),
                        new Permission.InFolder(toolName, folder)))
                .orElseGet(() -> Action.once(outsideEffect, reached.toString()));
    }
}
