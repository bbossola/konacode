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
 *
 * <p>{@link #onPath} takes the two questions as a pair of arguments, and nothing forces the pair
 * to agree. The three entry points below name each pairing, so a caller cannot pass a read
 * question with a write question by mistake.
 */
final class Actions {

    private Actions() {
    }

    /** A tool that reads the file a link reaches. */
    static Action read(String toolName, Workspace workspace, JsonNode pathNode) {
        return onPath(toolName, workspace, pathNode,
                Effect.READS_INSIDE, Effect.READS_OUTSIDE,
                workspace::readable, workspace::readTarget);
    }

    /** A tool that replaces the entry, and reads nothing. */
    static Action write(String toolName, Workspace workspace, JsonNode pathNode) {
        return onPath(toolName, workspace, pathNode,
                Effect.WRITES_INSIDE, Effect.WRITES_OUTSIDE,
                workspace::writable, workspace::writeTarget);
    }

    /**
     * A tool that reads the file and then writes it. Both tests must pass, or a link that points
     * into the project would give one call disclosure of any file on the disk.
     */
    static Action readThenWrite(String toolName, Workspace workspace, JsonNode pathNode) {
        return onPath(toolName, workspace, pathNode,
                Effect.WRITES_INSIDE, Effect.WRITES_OUTSIDE,
                path -> workspace.writable(path) && workspace.readable(path),
                workspace::writeTarget);
    }

    /**
     * @param toolName the tool that asks
     * @param workspace resolves and judges the path
     * @param pathNode the {@code path} argument, which may be absent or not text
     * @param insideEffect the effect when the path stays inside the workspace
     * @param outsideEffect the effect when it does not
     * @param staysInside {@code Workspace::readable} for a read, {@code Workspace::writable} for
     *     a write
     * @param reaches {@code Workspace::readTarget} for a read, {@code Workspace::writeTarget} for
     *     a write
     */
    private static Action onPath(String toolName,
                                 Workspace workspace,
                                 JsonNode pathNode,
                                 Effect insideEffect,
                                 Effect outsideEffect,
                                 Predicate<Path> staysInside,
                                 Function<Path, Optional<Path>> reaches) {
        Optional<Path> resolved = workspace.tryResolve(pathNode);
        if (resolved.isEmpty()) {
            return Action.once(toolName, outsideEffect, pathNode.isTextual() ? pathNode.asText() : toolName);
        }
        Path path = resolved.get();
        if (staysInside.test(path)) {
            return Action.once(toolName, insideEffect, path.toString());
        }
        Optional<Path> target = reaches.apply(path);
        if (target.isEmpty()) {
            return Action.once(toolName, outsideEffect, path.toString());
        }
        Path reached = target.get();
        return workspace.folderOf(reached)
                .<Action>map(folder -> Action.of(toolName, outsideEffect, reached.toString(), new Permission.InFolder(toolName, folder)))
                // Defensive: reached is already resolved, but folderOf can still fail to resolve
                // its folder, for example when toRealPath meets a permission error. No portable
                // test can force that, so this branch stands with no test of its own.
                .orElseGet(() -> Action.once(toolName, outsideEffect, reached.toString()));
    }
}
