package dev.konacode.cli;

import dev.konacode.agent.Cancellation;
import dev.konacode.agent.ToolApproval.Answer;
import dev.konacode.agent.TurnBudget;
import dev.konacode.llm.LlmClient;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.ToolMessage;
import dev.konacode.llm.ToolCall;
import dev.konacode.llm.ToolSpec;
import dev.konacode.llm.openai.OpenAiConfig;
import dev.konacode.skills.SkillRegistry;
import dev.konacode.tools.Workspace;
import dev.konacode.trace.Level;
import dev.konacode.trace.RecordingTrace;
import dev.konacode.trace.Trace;
import dev.konacode.trace.TraceEvent;
import dev.konacode.trace.TraceEvent.FromAgent;
import dev.konacode.trace.TraceEvent.RequestSent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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

    /** Runs one session that types {@code /policy} only, and gives back what the screen showed. */
    private String policyLine(boolean canAsk) {
        Workspace workspace = new Workspace(root);
        SkillRegistry skills = new SkillRegistry(new Workspace(root.resolve("skills")));
        RecordingUi ui = new RecordingUi("/policy");
        ui.canAsk = canAsk;

        Main.build(new ScriptedClient(), new ScriptedClient(), skills, ui, Level.OFF,
                new Cancellation(), new TurnBudget(8, 24), Trace.NONE, workspace, Duration.ofSeconds(600)).run();

        return ui.answers.get(ui.answers.size() - 1);
    }

    @Test
    void bothInterfacesStartWithTheJudge() {
        assertTrue(policyLine(true).contains("uses `judge`"), policyLine(true));
        assertTrue(policyLine(false).contains("uses `judge`"), policyLine(false));
    }

    @Mock
    HttpClient http;

    @Mock
    HttpResponse<String> response;

    @Test
    void theLoopAndTheJudgeAreTwoClientsWithTwoNames() throws Exception {
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}");
        when(http.send(any(HttpRequest.class), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
        OpenAiConfig config = new OpenAiConfig("sk-test", "big", "small", "https://example.test/v1", Duration.ofSeconds(1));
        List<TraceEvent> events = new ArrayList<>();

        Main.Clients clients = Main.clients(config, http, events::add);

        assertNotSame(clients.loop(), clients.judge(), "the judge needs its own trace, so it needs its own client");
        clients.loop().chat(List.of(), List.of());
        assertTrue(events.stream().allMatch(event -> event instanceof FromAgent named && named.agent().equals("kona")), events.toString());
        assertTrue(requestModel(events).contains("\"model\":\"big\""), requestModel(events));
        events.clear();
        clients.judge().chat(List.of(), List.of());
        assertTrue(events.stream().allMatch(event -> event instanceof FromAgent named && named.agent().equals("judge")), events.toString());
        assertTrue(requestModel(events).contains("\"model\":\"small\""), requestModel(events));
    }

    private static String requestModel(List<TraceEvent> events) {
        return events.stream()
                .filter(FromAgent.class::isInstance)
                .map(event -> ((FromAgent) event).event())
                .filter(RequestSent.class::isInstance)
                .map(event -> ((RequestSent) event).bodyJson())
                .findFirst()
                .orElseThrow();
    }

    @Test
    void theRegistryHoldsRunCommand() {
        Workspace workspace = new Workspace(root);
        SkillRegistry skills = new SkillRegistry(new Workspace(root.resolve("skills")));
        RecordingUi ui = new RecordingUi("/tools");

        Main.build(new ScriptedClient(), new ScriptedClient(), skills, ui, Level.OFF, new Cancellation(), new TurnBudget(8, 24), Trace.NONE,
                workspace, Duration.ofSeconds(600)).run();

        assertTrue(ui.answers.get(0).contains("run_command"), ui.answers.get(0));
    }

    @Test
    void theLoopNamesEveryEventKona() {
        Workspace workspace = new Workspace(root);
        SkillRegistry skills = new SkillRegistry(new Workspace(root.resolve("skills")));
        ScriptedClient client = new ScriptedClient().reply(new AssistantMessage("done", List.of()));
        RecordingTrace trace = new RecordingTrace();

        Main.build(client, new ScriptedClient(), skills, new RecordingUi("hello"), Level.OFF, new Cancellation(), new TurnBudget(8, 24), trace,
                workspace, Duration.ofSeconds(600)).run();

        assertFalse(trace.events().isEmpty());
        assertTrue(trace.events().stream().allMatch(event ->
                event instanceof FromAgent named && named.agent().equals("kona")), trace.events().toString());
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

        Main.build(client, new ScriptedClient(), skills, ui, Level.OFF, new Cancellation(), new TurnBudget(8, 24), Trace.NONE,
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

        Main.build(client, new ScriptedClient(), skills, ui, Level.OFF, new Cancellation(), new TurnBudget(8, 24), Trace.NONE,
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

    @Test
    void theSystemPromptAsksForAPlan() {
        assertTrue(Main.systemPrompt(root).contains("Use the plan tool before"), Main.systemPrompt(root));
    }

    @Test
    void theSystemPromptTellsTheModelToReadBeforeItEdits() {
        assertTrue(Main.systemPrompt(root).contains("Read a file before you edit"), Main.systemPrompt(root));
    }

    @Test
    void theSystemPromptSaysWhatAnErrorMeans() {
        assertTrue(Main.systemPrompt(root).contains("<error>"), Main.systemPrompt(root));
    }

    @Test
    void theSystemPromptNamesTheWorkingDirectory() {
        assertTrue(Main.systemPrompt(root).contains(root.toString()), Main.systemPrompt(root));
    }

    @Test
    void theSystemPromptStaysUnderFiveLines() {
        assertTrue(Main.systemPrompt(root).lines().count() <= 5, Main.systemPrompt(root));
    }

    @Test
    void theLoopReceivesThePromptOfTheWorkingDirectory() {
        Workspace workspace = new Workspace(root);
        SkillRegistry skills = new SkillRegistry(new Workspace(root.resolve("skills")));
        ScriptedClient client = new ScriptedClient().reply(new AssistantMessage("hi", List.of()));

        Main.build(client, new ScriptedClient(), skills, new RecordingUi("hello"), Level.OFF,
                new Cancellation(), new TurnBudget(8, 24), Trace.NONE, workspace, Duration.ofSeconds(600)).run();

        assertEquals(new Message.SystemMessage(Main.systemPrompt(workspace.root())), client.histories.get(0).get(0));
    }

    @Test
    void theMaximumWhenPlanningDefaultsTo24() {
        withProperty("konacode.maxIterations.whenPlanning", null,
                () -> assertEquals(24, Main.maxIterationsWhenPlanning()));
    }

    @Test
    void aConfiguredMaximumWhenPlanningIsUsed() {
        withProperty("konacode.maxIterations.whenPlanning", "40",
                () -> assertEquals(40, Main.maxIterationsWhenPlanning()));
    }

    @Test
    void aWrongMaximumWhenPlanningFailsLoudly() {
        withProperty("konacode.maxIterations.whenPlanning", "many", () -> {
            IllegalArgumentException e =
                    assertThrows(IllegalArgumentException.class, Main::maxIterationsWhenPlanning);
            assertTrue(e.getMessage().contains("konacode.maxIterations.whenPlanning"), e.getMessage());
        });
    }

    @Test
    void theMaximumWhenPlanningFollowsARaisedOrdinaryMaximum() {
        withProperty("konacode.maxIterations", "30",
                () -> withProperty("konacode.maxIterations.whenPlanning", null, () -> {
                    assertEquals(30, Main.maxIterationsWhenPlanning(), "a default must not refuse a value the user set");
                    assertDoesNotThrow(Main::budget);
                }));
    }

    @Test
    void aMaximumWhenPlanningBelowTheOrdinaryOneNamesBothProperties() {
        withProperty("konacode.maxIterations", "30",
                () -> withProperty("konacode.maxIterations.whenPlanning", "10", () -> {
                    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, Main::budget);
                    assertTrue(thrown.getMessage().contains("konacode.maxIterations.whenPlanning (10)"), thrown.getMessage());
                    assertTrue(thrown.getMessage().contains("konacode.maxIterations (30)"), thrown.getMessage());
                }));
    }

    @Test
    void theBudgetDefaultsToEightAndTwentyFour() {
        withProperty("konacode.maxIterations", null,
                () -> withProperty("konacode.maxIterations.whenPlanning", null, () -> {
                    assertEquals(8, requests(Main.budget(), readCall("1", root.resolve("missing.txt").toString())),
                            "a turn that records no plan stops at the ordinary maximum");
                    assertEquals(24, requests(Main.budget(), planCall()),
                            "a turn that records a plan stops at the planned maximum");
                }));
    }

    @Test
    void thePlanToolAndTheLoopShareOneBudget() {
        Workspace workspace = new Workspace(root);
        SkillRegistry skills = new SkillRegistry(new Workspace(root.resolve("skills")));
        ScriptedClient client = new ScriptedClient()
                .reply(planCall()).reply(planCall()).reply(planCall()).reply(planCall());
        RecordingUi ui = new RecordingUi("do the work");

        Main.build(client, new ScriptedClient(), skills, ui, Level.OFF, new Cancellation(),
                new TurnBudget(2, 4), Trace.NONE, workspace, Duration.ofSeconds(600)).run();

        assertEquals(4, client.histories.size(), "the maximum the plan tool raises must be the maximum the loop reads");
    }

    private static AssistantMessage planCall() {
        return new AssistantMessage("", List.of(new ToolCall("1", "plan",
                "{\"steps\":[{\"text\":\"do the work\",\"state\":\"doing\"}]}")));
    }

    /** Runs one turn against a model that repeats one call, and gives back the number of requests. */
    private int requests(TurnBudget budget, AssistantMessage call) {
        Workspace workspace = new Workspace(root);
        SkillRegistry skills = new SkillRegistry(new Workspace(root.resolve("skills")));
        ScriptedClient client = new ScriptedClient();
        for (int i = 0; i < 40; i++) {
            client.reply(call);
        }

        Main.build(client, new ScriptedClient(), skills, new RecordingUi("do the work"), Level.OFF,
                new Cancellation(), budget, Trace.NONE, workspace, Duration.ofSeconds(600)).run();

        return client.histories.size();
    }

    @Test
    void theRegistryHoldsPlan() {
        Workspace workspace = new Workspace(root);
        SkillRegistry skills = new SkillRegistry(new Workspace(root.resolve("skills")));
        RecordingUi ui = new RecordingUi("/tools");

        Main.build(new ScriptedClient(), new ScriptedClient(), skills, ui, Level.OFF,
                new Cancellation(), new TurnBudget(8, 24), Trace.NONE, workspace,
                Duration.ofSeconds(600)).run();

        assertTrue(String.join("\n", ui.answers).contains("plan"), ui.answers.toString());
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
