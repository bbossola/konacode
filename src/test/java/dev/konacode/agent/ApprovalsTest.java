package dev.konacode.agent;

import dev.konacode.policy.Decision;
import dev.konacode.tools.Permission;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApprovalsTest {

    /** Answers from a script, and records every question. An empty script is a test bug. */
    private static final class ScriptedApproval implements ToolApproval {
        private final List<Answer> answers = new ArrayList<>();
        final List<String> asked = new ArrayList<>();

        ScriptedApproval(Answer... script) {
            Collections.addAll(answers, script);
        }

        @Override
        public Answer ask(Decision.Ask ask) {
            asked.add(ask.toolName() + " " + ask.toolOperand());
            if (answers.isEmpty()) {
                throw new AssertionError(
                        "asked a question the script did not expect: " + ask.toolName() + " "
                                + ask.toolOperand());
            }
            return answers.remove(0);
        }

        @Override
        public boolean canAsk() {
            return true;
        }
    }

    private static Decision.Ask askAbout(String toolName, String file) {
        return new Decision.Ask(toolName, "write outside this project", file,
                Optional.of(new Permission.InFolder(toolName, Path.of(file).getParent())), "");
    }

    private static Decision.Ask askAbout(String file) {
        return askAbout("edit_file", file);
    }

    @Test
    void yesRunsOnceAndAsksAgain() {
        ScriptedApproval ui = new ScriptedApproval(
                ToolApproval.Answer.YES, ToolApproval.Answer.YES);
        Approvals approvals = new Approvals(ui);

        assertTrue(approvals.approve(askAbout("/notes/a.txt")));
        assertTrue(approvals.approve(askAbout("/notes/a.txt")));

        assertEquals(2, ui.asked.size());
    }

    @Test
    void noRefuses() {
        Approvals approvals = new Approvals(new ScriptedApproval(ToolApproval.Answer.NO));

        assertFalse(approvals.approve(askAbout("/notes/a.txt")));
    }

    @Test
    void alwaysCoversTheFolderAndDoesNotAskAgain() {
        ScriptedApproval ui = new ScriptedApproval(ToolApproval.Answer.ALWAYS);
        Approvals approvals = new Approvals(ui);

        assertTrue(approvals.approve(askAbout("/notes/a.txt")));
        assertTrue(approvals.approve(askAbout("/notes/b.txt")));

        assertEquals(1, ui.asked.size());
    }

    @Test
    void alwaysDoesNotCoverAnotherFolder() {
        ScriptedApproval ui = new ScriptedApproval(
                ToolApproval.Answer.ALWAYS, ToolApproval.Answer.NO);
        Approvals approvals = new Approvals(ui);

        approvals.approve(askAbout("/notes/a.txt"));

        assertFalse(approvals.approve(askAbout("/other/b.txt")));
        assertEquals(2, ui.asked.size());
    }

    @Test
    void alwaysDoesNotCoverAnotherTool() {
        ScriptedApproval ui = new ScriptedApproval(
                ToolApproval.Answer.ALWAYS, ToolApproval.Answer.NO);
        Approvals approvals = new Approvals(ui);

        approvals.approve(askAbout("edit_file", "/notes/a.txt"));

        assertFalse(approvals.approve(askAbout("delete_file", "/notes/b.txt")));
        assertEquals(2, ui.asked.size());
    }

    @Test
    void alwaysIsNotRememberedWhenThereIsNoPermission() {
        ScriptedApproval ui = new ScriptedApproval(
                ToolApproval.Answer.ALWAYS, ToolApproval.Answer.NO);
        Approvals approvals = new Approvals(ui);
        Decision.Ask noPermission = new Decision.Ask("run_command", "run a command", "run_command",
                Optional.empty(), "");

        assertTrue(approvals.approve(noPermission));

        assertFalse(approvals.approve(noPermission));
        assertEquals(2, ui.asked.size());
    }

    @Test
    void twoSpellingsOfOneFolderAreOneMemory() {
        ScriptedApproval ui = new ScriptedApproval(ToolApproval.Answer.ALWAYS);
        Approvals approvals = new Approvals(ui);

        approvals.approve(askAbout("/notes/a.txt"));

        assertTrue(approvals.approve(new Decision.Ask("edit_file", "write outside this project",
                "/notes/./b.txt",
                Optional.of(new Permission.InFolder("edit_file", Path.of("/notes/./"))), "")));
        assertEquals(1, ui.asked.size());
    }

    @Test
    void aFolderApprovalNeverCoversACommand() {
        ScriptedApproval ui = new ScriptedApproval(
                ToolApproval.Answer.ALWAYS, ToolApproval.Answer.ALWAYS);
        Approvals approvals = new Approvals(ui);
        approvals.approve(new Decision.Ask("run_command", "run a command", "mvn test",
                Optional.of(new Permission.InFolder("run_command", Path.of("/tmp"))), ""));

        approvals.approve(new Decision.Ask("run_command", "run a command", "mvn test",
                Optional.of(new Permission.ExactCommand("run_command", "mvn test")), ""));

        assertEquals(2, ui.asked.size(), "a different kind of permission must ask again");
    }
}
