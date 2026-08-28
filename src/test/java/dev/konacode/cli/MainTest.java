package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import dev.konacode.agent.ToolApproval.Answer;
import dev.konacode.llm.LlmClient;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.ToolMessage;
import dev.konacode.llm.ToolCall;
import dev.konacode.llm.ToolSpec;
import dev.konacode.policy.AllowAllPolicy;
import dev.konacode.policy.EffectPolicy;
import dev.konacode.skills.SkillRegistry;
import dev.konacode.tools.Workspace;
import dev.konacode.trace.Level;
import dev.konacode.trace.Trace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @TempDir
    Path root;

    @TempDir
    Path elsewhere;

    /** Returns scripted replies in order, and records the history each call received. */
    private static final class ScriptedClient implements LlmClient {
        private final Deque<AssistantMessage> script = new ArrayDeque<>();
        final List<List<Message>> histories = new ArrayList<>();

        ScriptedClient reply(AssistantMessage message) {
            script.add(message);
            return this;
        }

        @Override
        public AssistantMessage chat(List<Message> history, List<ToolSpec> tools) {
            histories.add(List.copyOf(history));
            AssistantMessage next = script.poll();
            return next != null ? next : new AssistantMessage("out of replies", List.of());
        }
    }

    private static ToolMessage lastToolMessage(List<Message> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i) instanceof ToolMessage toolMessage) {
                return toolMessage;
            }
        }
        throw new AssertionError("no tool message in " + history);
    }

    private static AssistantMessage readCall(String id, String path) {
        String argumentsJson = "{\"path\":\"" + path.replace("\\", "\\\\") + "\"}";
        return new AssistantMessage("", List.of(new ToolCall(id, "read_file", argumentsJson)));
    }

    @AfterEach
    void clearTheProperty() {
        System.clearProperty("konacode.ui");
    }

    @Test
    void theSkillsRootSitsUnderTheHomeFolder() {
        assertTrue(Main.skillsRoot().endsWith(Path.of(".konacode", "skills")),
                Main.skillsRoot().toString());
    }

    @Test
    void choosesThePlainInterfaceWhenAsked() throws Exception {
        System.setProperty("konacode.ui", "plain");

        assertInstanceOf(PlainUi.class, Main.selectUi(new Cancellation()));
    }

    @Test
    void choosesThePlainInterfaceForAPipe() throws Exception {
        System.setProperty("konacode.ui", "auto");

        assertInstanceOf(PlainUi.class, Main.selectUi(new Cancellation()));
    }

    @Test
    void defaultsToAuto() throws Exception {
        assertInstanceOf(PlainUi.class, Main.selectUi(new Cancellation()));
    }

    @Test
    void refusesAValueItCannotRead() {
        System.setProperty("konacode.ui", "rihc");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> Main.selectUi(new Cancellation()));

        assertTrue(thrown.getMessage().contains("rihc"), thrown.getMessage());
    }

    @Test
    void theSkillsFolderMayBeRead() {
        Workspace workspace = Main.workspace();

        assertTrue(workspace.readable(Main.skillsRoot().resolve("one/SKILL.md")),
                "a skill must load without a question");
    }

    @Test
    void anInterfaceThatCanAskGetsTheNewPolicy() {
        assertInstanceOf(EffectPolicy.class, Main.defaultPolicy(true));
        assertInstanceOf(AllowAllPolicy.class, Main.defaultPolicy(false));
    }

    @Test
    void theRegistryHoldsRunCommand() {
        Workspace workspace = new Workspace(root);
        SkillRegistry skills = new SkillRegistry(new Workspace(root.resolve("skills")));
        RecordingUi ui = new RecordingUi("/tools");

        Main.build(new ScriptedClient(), skills, ui, Level.OFF, new Cancellation(), 8, Trace.NONE,
                workspace, Duration.ofSeconds(600)).run();

        assertTrue(ui.answers.get(0).contains("run_command"), ui.answers.get(0));
    }

    @Test
    void theLoopAndTheCommandShareOnePolicy() throws IOException {
        Path outside = elsewhere.resolve("secret.txt");
        Files.writeString(outside, "OUTSIDE-CONTENT");
        Workspace workspace = new Workspace(root);
        SkillRegistry skills = new SkillRegistry(new Workspace(root.resolve("skills")));
        ScriptedClient client = new ScriptedClient()
                .reply(readCall("1", outside.toString()))
                .reply(new AssistantMessage("first turn done", List.of()))
                .reply(readCall("2", outside.toString()))
                .reply(new AssistantMessage("second turn done", List.of()));
        RecordingUi ui = new RecordingUi("/policy allow-all", "read it", "/policy effect",
                "read it");

        Main.build(client, skills, ui, Level.OFF, new Cancellation(), 8, Trace.NONE,
                workspace, Duration.ofSeconds(600)).run();

        assertEquals(4, client.histories.size(), "each turn calls chat twice");
        assertTrue(lastToolMessage(client.histories.get(1)).content().contains("OUTSIDE-CONTENT"),
                "allow-all lets the loop read outside the project");
        assertTrue(
                lastToolMessage(client.histories.get(3)).content().contains("has no approval"),
                "the /policy effect the command chose must reach the loop's own check");
    }

    @Test
    void anAlwaysAnswerSurvivesAChangeOfPolicy() throws IOException {
        Path outside = elsewhere.resolve("secret.txt");
        Files.writeString(outside, "OUTSIDE-CONTENT");
        Workspace workspace = new Workspace(root);
        SkillRegistry skills = new SkillRegistry(new Workspace(root.resolve("skills")));
        ScriptedClient client = new ScriptedClient()
                .reply(readCall("1", outside.toString()))
                .reply(new AssistantMessage("first turn done", List.of()))
                .reply(readCall("2", outside.toString()))
                .reply(new AssistantMessage("second turn done", List.of()));
        RecordingUi ui = new RecordingUi("read it", "/policy allow-all", "/policy effect",
                "read it", "/policy");
        ui.nextAsk = Answer.ALWAYS;

        Main.build(client, skills, ui, Level.OFF, new Cancellation(), 8, Trace.NONE,
                workspace, Duration.ofSeconds(600)).run();

        assertEquals(1, ui.askCount, "the memory in Approvals must survive the policy change");
        assertTrue(lastToolMessage(client.histories.get(3)).content().contains("OUTSIDE-CONTENT"),
                "the remembered ALWAYS must still approve the call");
        assertTrue(ui.answers.get(ui.answers.size() - 1).contains("uses `effect`"),
                "the policy the second read passed a check against must still be effect");
    }

    @Test
    void readsTheIterationCeilingFromASystemProperty() {
        withProperty("konacode.maxIterations", "42", () -> assertEquals(42, Main.maxIterations()));
    }

    @Test
    void theIterationCeilingDefaultsToEight() {
        withProperty("konacode.maxIterations", null, () -> assertEquals(8, Main.maxIterations()));
    }

    @Test
    void rejectsAMalformedMaxIterationsPropertyRatherThanSilentlyDefaulting() {
        withProperty("konacode.maxIterations", "eihgt", () -> assertThrows(IllegalArgumentException.class, Main::maxIterations));
    }

    @Test
    void theTraceFileCountDefaultsToOneHundred() {
        withProperty("konacode.trace.maxFiles", null, () -> assertEquals(100, Main.maxTraceFiles()));
    }

    @Test
    void aTraceFileCountThatIsNotAWholeNumberIsAnError() {
        withProperty("konacode.trace.maxFiles", "many", () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, Main::maxTraceFiles);
            assertTrue(e.getMessage().contains("konacode.trace.maxFiles"), e.getMessage());
        });
    }

    @Test
    void aTraceFileCountBelowOneIsAnError() {
        withProperty("konacode.trace.maxFiles", "0", () -> assertThrows(IllegalArgumentException.class, Main::maxTraceFiles));
    }

    @Test
    void theCommandTimeoutDefaultsToTenMinutes() {
        withProperty("konacode.command.timeoutSeconds", null, () -> assertEquals(Duration.ofSeconds(600), Main.commandTimeout()));
    }

    @Test
    void aConfiguredCommandTimeoutIsUsed() {
        withProperty("konacode.command.timeoutSeconds", "5", () -> assertEquals(Duration.ofSeconds(5), Main.commandTimeout()));
    }

    @Test
    void aWrongCommandTimeoutFailsLoudly() {
        withProperty("konacode.command.timeoutSeconds", "soon", () -> {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, Main::commandTimeout);
            assertTrue(e.getMessage().contains("konacode.command.timeoutSeconds"), e.getMessage());
        });
    }

    @Test
    void aCommandTimeoutBelowOneSecondFailsLoudly() {
        withProperty("konacode.command.timeoutSeconds", "0", () -> assertThrows(IllegalArgumentException.class, Main::commandTimeout));
    }

    /** Sets one system property, runs the body, and puts the property back as it was. */
    private static void withProperty(String name, String value, Runnable body) {
        String previous = System.getProperty(name);
        try {
            if (value == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, value);
            }
            body.run();
        } finally {
            if (previous == null) {
                System.clearProperty(name);
            } else {
                System.setProperty(name, previous);
            }
        }
    }
}
