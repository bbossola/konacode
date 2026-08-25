package dev.konacode.agent;

import dev.konacode.policy.Decision;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
        public Answer ask(String toolName, Decision.Ask ask) {
            asked.add(toolName + " " + ask.subject());
            if (answers.isEmpty()) {
                throw new AssertionError(
                        "asked a question the script did not expect: " + toolName + " "
                                + ask.subject());
            }
            return answers.remove(0);
        }

        @Override
        public boolean canAsk() {
            return true;
        }
    }

    private static Decision.Ask askAbout(String file) {
        return new Decision.Ask("write outside this project", file, Path.of(file).getParent());
    }

    @Test
    void yesRunsOnceAndAsksAgain() {
        ScriptedApproval ui = new ScriptedApproval(
                ToolApproval.Answer.YES, ToolApproval.Answer.YES);
        Approvals approvals = new Approvals(ui);

        assertTrue(approvals.approve("edit_file", askAbout("/notes/a.txt")));
        assertTrue(approvals.approve("edit_file", askAbout("/notes/a.txt")));

        assertEquals(2, ui.asked.size());
    }

    @Test
    void noRefuses() {
        Approvals approvals = new Approvals(new ScriptedApproval(ToolApproval.Answer.NO));

        assertFalse(approvals.approve("edit_file", askAbout("/notes/a.txt")));
    }

    @Test
    void alwaysCoversTheFolderAndDoesNotAskAgain() {
        ScriptedApproval ui = new ScriptedApproval(ToolApproval.Answer.ALWAYS);
        Approvals approvals = new Approvals(ui);

        assertTrue(approvals.approve("edit_file", askAbout("/notes/a.txt")));
        assertTrue(approvals.approve("edit_file", askAbout("/notes/b.txt")));

        assertEquals(1, ui.asked.size());
    }

    @Test
    void alwaysDoesNotCoverAnotherFolder() {
        ScriptedApproval ui = new ScriptedApproval(
                ToolApproval.Answer.ALWAYS, ToolApproval.Answer.NO);
        Approvals approvals = new Approvals(ui);

        approvals.approve("edit_file", askAbout("/notes/a.txt"));

        assertFalse(approvals.approve("edit_file", askAbout("/other/b.txt")));
        assertEquals(2, ui.asked.size());
    }

    @Test
    void alwaysDoesNotCoverAnotherTool() {
        ScriptedApproval ui = new ScriptedApproval(
                ToolApproval.Answer.ALWAYS, ToolApproval.Answer.NO);
        Approvals approvals = new Approvals(ui);

        approvals.approve("edit_file", askAbout("/notes/a.txt"));

        assertFalse(approvals.approve("delete_file", askAbout("/notes/b.txt")));
        assertEquals(2, ui.asked.size());
    }

    @Test
    void alwaysIsNotRememberedWhenThereIsNoFolder() {
        ScriptedApproval ui = new ScriptedApproval(
                ToolApproval.Answer.ALWAYS, ToolApproval.Answer.NO);
        Approvals approvals = new Approvals(ui);
        Decision.Ask noFolder = new Decision.Ask("run a command", "run_command", null);

        assertTrue(approvals.approve("run_command", noFolder));

        assertFalse(approvals.approve("run_command", noFolder));
        assertEquals(2, ui.asked.size());
    }

    @Test
    void twoSpellingsOfOneFolderAreOneMemory() {
        ScriptedApproval ui = new ScriptedApproval(ToolApproval.Answer.ALWAYS);
        Approvals approvals = new Approvals(ui);

        approvals.approve("edit_file", askAbout("/notes/a.txt"));

        assertTrue(approvals.approve("edit_file",
                new Decision.Ask("write outside this project", "/notes/./b.txt",
                        Path.of("/notes/./"))));
        assertEquals(1, ui.asked.size());
    }
}
