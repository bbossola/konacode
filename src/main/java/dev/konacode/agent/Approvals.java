package dev.konacode.agent;

import dev.konacode.policy.Decision;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * The answers the user gave during this session.
 *
 * <p>The memory sits here and not in the policy, so {@code /policy} changes the policy and the
 * answers stay. Nothing is written to disk, so the memory ends when konacode ends.
 */
public final class Approvals {

    private record Allowed(String toolName, Path folder) {}

    private final ToolApproval approval;
    private final Set<Allowed> allowed = new HashSet<>();

    public Approvals(ToolApproval approval) {
        this.approval = Objects.requireNonNull(approval, "approval");
    }

    /** True when the call may run. */
    public boolean approve(String toolName, Decision.Ask ask) {
        // Path.equals compares the spelling, so two spellings of one folder must be one memory.
        Path folder = ask.alwaysFolder() == null ? null : ask.alwaysFolder().normalize();
        if (folder != null && allowed.contains(new Allowed(toolName, folder))) {
            return true;
        }
        return switch (approval.ask(toolName, ask)) {
            case YES -> true;
            case NO -> false;
            case ALWAYS -> {
                // A folder is what "always" covers. With no folder there is nothing to remember,
                // so the answer counts once and konacode asks again.
                if (folder != null) {
                    allowed.add(new Allowed(toolName, folder));
                }
                yield true;
            }
        };
    }
}
