package dev.konacode.agent;

import dev.konacode.policy.Decision;
import dev.konacode.tools.Permission;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The answers the user gave during this session.
 *
 * <p>The memory sits here and not in the policy, so {@code /policy} changes the policy and the
 * answers stay. Nothing is written to disk, so the memory ends when konacode ends.
 *
 * <p>Coverage is equality. This class compares two permissions, and it never examines one.
 */
public final class Approvals {

    private final ToolApproval approval;
    private final Set<Permission> given = new HashSet<>();

    public Approvals(ToolApproval approval) {
        this.approval = Objects.requireNonNull(approval, "approval");
    }

    /** True when the call may run. */
    public boolean approve(Decision.Ask ask) {
        Optional<Permission> permission = ask.standingPermission();
        if (permission.isPresent() && given.contains(permission.get())) {
            return true;
        }
        return switch (approval.ask(ask)) {
            case YES -> true;
            case NO -> false;
            case ALWAYS -> {
                // With no permission there is nothing to remember, so the answer counts once.
                permission.ifPresent(given::add);
                yield true;
            }
        };
    }
}
