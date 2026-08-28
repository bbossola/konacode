package dev.konacode.agent;

import dev.konacode.policy.Decision;
import dev.konacode.tools.Action;
import dev.konacode.tools.Effect;
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

    private static Action actionAbout(String toolName, String file) {
        return Action.of(toolName, Effect.WRITES_OUTSIDE, file, new Permission.InFolder(toolName, Path.of(file).getParent()));
    }

    private static Decision.Ask askToRun(String command) {
        return new Decision.Ask("run_command", "run a command", command, Optional.of(new Permission.ExactCommand("run_command", command)), "");
    }

    private static Action actionToRun(String command) {
        return Action.of("run_command", Effect.RUNS, command, new Permission.ExactCommand("run_command", command));
    }

    @Test
    void coversIsFalseWhenTheActionOffersNoPermission() {
        Approvals approvals = new Approvals(new ScriptedApproval());

        assertFalse(approvals.covers(Action.once("run_command", Effect.RUNS, "mvn -q test")));
    }

    @Test
    void coversIsTrueOnlyAfterTheUserAnsweredAlways() {
        Approvals approvals = new Approvals(new ScriptedApproval(ToolApproval.Answer.ALWAYS));

        assertFalse(approvals.covers(actionToRun("mvn -q test")));
        assertTrue(approvals.approve(askToRun("mvn -q test")));

        assertTrue(approvals.covers(actionToRun("mvn -q test")));
    }

    @Test
    void coversIsFalseForAnotherPermission() {
        Approvals approvals = new Approvals(new ScriptedApproval(ToolApproval.Answer.ALWAYS));
        approvals.approve(askToRun("mvn -q test"));

        assertFalse(approvals.covers(actionToRun("git push")));
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

        assertTrue(approvals.covers(actionAbout("edit_file", "/notes/b.txt")));
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

        assertTrue(approvals.covers(Action.of("edit_file", Effect.WRITES_OUTSIDE, "/notes/./b.txt", new Permission.InFolder("edit_file", Path.of("/notes/./")))));
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
