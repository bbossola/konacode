package dev.konacode.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.konacode.tools.ListFiles;
import dev.konacode.tools.StopCheck;
import dev.konacode.tools.Workspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class AllowAllPolicyTest {

    @TempDir
    Path root;

    @Test
    void allowsEveryToolCall() {
        ToolPolicy policy = new AllowAllPolicy();

        ListFiles tool = new ListFiles(new Workspace(root), StopCheck.NEVER);

        Decision decision = policy.check(tool.computeAction(new ObjectMapper().createObjectNode()), "list the files");

        assertInstanceOf(Decision.Allow.class, decision);
    }

    @Test
    void denyCarriesTheReasonTheModelWillRead() {
        Decision decision = Decision.deny("path escapes the workspace");

        assertEquals("path escapes the workspace",
                assertInstanceOf(Decision.Deny.class, decision).reason());
    }
}
