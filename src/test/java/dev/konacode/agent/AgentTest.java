package dev.konacode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.Message.ToolMessage;
import dev.konacode.llm.Message.UserMessage;
import dev.konacode.llm.ToolCall;
import dev.konacode.policy.AllowAllPolicy;
import dev.konacode.policy.Decision;
import dev.konacode.policy.ToolPolicy;
import dev.konacode.tools.Action;
import dev.konacode.tools.Effect;
import dev.konacode.tools.Permission;
import dev.konacode.tools.Schemas;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.ToolResult;
import dev.konacode.trace.TraceEvent.IterationStarted;
import dev.konacode.trace.TraceEvent.Outcome;
import dev.konacode.trace.TraceEvent.TurnStarted;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {

    /**
     * Echoes its arguments, so tests can see exactly what the loop passed through. Counts its
     * calls, so a test can confirm a refused call never runs.
     */
    private static final class EchoTool implements Tool {
        private final String name;
        private int calls;

        EchoTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "Echoes its input.";
        }

        @Override
        public ObjectNode inputSchema() {
            return Schemas.object().optionalString("value", "Anything.").build();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            calls++;
            return ToolResult.ok("echo:" + args.path("value").asText(""));
        }

        @Override
        public boolean stopsOnInterrupt() {
            return false;
        }

        @Override
        public Action computeAction(JsonNode args) {
            return Action.once(Effect.READS_INSIDE, name());
        }

        int calls() {
            return calls;
        }
    }

    /** Stops the turn from inside a tool, which is what an ESC during a tool call does. */
    private record StoppingTool(String name, Cancellation cancellation) implements Tool {
        @Override
        public String description() {
            return "Stops the turn.";
        }

        @Override
        public ObjectNode inputSchema() {
            return Schemas.object().build();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            cancellation.request();
            return ToolResult.ok("ran");
        }

        @Override
        public boolean stopsOnInterrupt() {
            return false;
        }

        @Override
        public Action computeAction(JsonNode args) {
            return Action.once(Effect.READS_INSIDE, name());
        }
    }

    /** Declares that an interrupt is safe, and records whether the loop delivered one. */
    private static final class InterruptibleTool implements Tool {
        private final Cancellation cancellation;
        private final boolean declares;
        boolean sawInterrupt;

        InterruptibleTool(Cancellation cancellation, boolean declares) {
            this.cancellation = cancellation;
            this.declares = declares;
        }

        @Override
        public String name() {
            return "blocking";
        }

        @Override
        public String description() {
            return "Waits.";
        }

        @Override
        public ObjectNode inputSchema() {
            return Schemas.object().build();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            cancellation.request();
            sawInterrupt = Thread.currentThread().isInterrupted();
            return ToolResult.err("Stopped by the user. Nothing was changed.");
        }

        @Override
        public boolean stopsOnInterrupt() {
            return declares;
        }

        @Override
        public Action computeAction(JsonNode args) {
            return Action.once(Effect.READS_INSIDE, name());
        }
    }

    private record ExplodingTool(String name) implements Tool {
        @Override
        public String description() {
            return "Always throws.";
        }

        @Override
        public ObjectNode inputSchema() {
            return Schemas.object().build();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            throw new IllegalStateException("boom");
        }

        @Override
        public boolean stopsOnInterrupt() {
            return false;
        }

        @Override
        public Action computeAction(JsonNode args) {
            return Action.once(Effect.READS_INSIDE, name());
        }
    }

    private static ToolCall call(String id, String name, String argumentsJson) {
        return new ToolCall(id, name, argumentsJson);
    }

    /** Answers every question with the same fixed answer. */
    private record FixedApproval(ToolApproval.Answer answer) implements ToolApproval {
        @Override
        public ToolApproval.Answer ask(Decision.Ask ask) {
            return answer;
        }

        @Override
        public boolean canAsk() {
            return true;
        }
    }

    /** No test before this task expects a question, so every ask is refused. */
    private static Approvals refusesToAsk() {
        return new Approvals(new FixedApproval(ToolApproval.Answer.NO));
    }

    private Agent agent(FakeLlmClient client, ToolRegistry registry, ToolPolicy policy,
                        RecordingTrace trace, int maxIterations) {
        return new Agent(
                client,
                registry,
                policy,
                refusesToAsk(),
                new Conversation(new SystemMessage("You are konacode.")),
                trace,
                new Cancellation(),
                maxIterations);
    }

    @Test
    void reportsAnAnsweredTurn() {
        FakeLlmClient client = new FakeLlmClient().replyText("Hello.");
        RecordingTrace trace = new RecordingTrace();

        agent(client, ToolRegistry.of(new EchoTool("echo")), new AllowAllPolicy(), trace, 8)
                .respond("hi");

        assertEquals(List.of(Outcome.ANSWERED), trace.outcomes());
        assertEquals(new TurnStarted(1, "hi"), trace.events().get(0));
        assertEquals(new IterationStarted(1, 1, 8), trace.events().get(1));
    }

    @Test
    void reportsATurnThatRanOutOfIterations() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))))
                .reply(new AssistantMessage("", List.of(call("c2", "echo", "{}"))));
        RecordingTrace trace = new RecordingTrace();

        agent(client, ToolRegistry.of(new EchoTool("echo")), new AllowAllPolicy(), trace, 2)
                .respond("go");

        assertEquals(List.of(Outcome.EXHAUSTED), trace.outcomes());
    }

    @Test
    void reportsATurnThatFailed() {
        FakeLlmClient client = new FakeLlmClient().failWith(new LlmException("HTTP 401"));
        RecordingTrace trace = new RecordingTrace();

        agent(client, ToolRegistry.of(new EchoTool("echo")), new AllowAllPolicy(), trace, 8)
                .respond("go");

        assertEquals(List.of(Outcome.FAILED), trace.outcomes());
    }

    @Test
    void countsTheTurns() {
        FakeLlmClient client = new FakeLlmClient().replyText("One.").replyText("Two.");
        RecordingTrace trace = new RecordingTrace();
        Agent agent = agent(client, ToolRegistry.of(new EchoTool("echo")), new AllowAllPolicy(),
                trace, 8);

        agent.respond("first");
        agent.respond("second");

        assertEquals(new TurnStarted(1, "first"), trace.events().get(0));
        assertEquals(new TurnStarted(2, "second"), trace.events().get(3));
    }

    @Test
    void returnsTextWhenTheModelDoesNotCallATool() {
        FakeLlmClient client = new FakeLlmClient().replyText("Hello.");
        RecordingTrace trace = new RecordingTrace();

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), trace, 8).respond("hi");

        assertEquals("Hello.", answer);
        assertEquals(List.of(), trace.calls());
    }

    @Test
    void runsAToolThenReturnsTheFollowUpText() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{\"value\":\"hi\"}"))))
                .replyText("Done.");
        RecordingTrace trace = new RecordingTrace();

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), trace, 8).respond("go");

        assertEquals("Done.", answer);
        assertEquals(List.of("echo({\"value\":\"hi\"})"), trace.calls());
        assertEquals(List.of(ToolResult.ok("echo:hi")), trace.results());
    }

    @Test
    void appendsTheAssistantMessageBeforeTheToolResult() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))))
                .replyText("Done.");

        agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), new RecordingTrace(), 8).respond("go");

        // The history sent on the second request must carry the assistant message that made the
        // call, immediately before its result. Providers reject the result otherwise.
        List<Message> secondRequest = client.receivedHistories().get(1);
        assertInstanceOf(AssistantMessage.class, secondRequest.get(secondRequest.size() - 2));
        ToolMessage toolMessage =
                assertInstanceOf(ToolMessage.class, secondRequest.get(secondRequest.size() - 1));
        assertEquals("c1", toolMessage.toolCallId());
    }

    @Test
    void executesEveryToolCallInASingleAssistantMessage() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(
                        call("c1", "echo", "{\"value\":\"one\"}"),
                        call("c2", "echo", "{\"value\":\"two\"}"))))
                .replyText("Both done.");
        RecordingTrace trace = new RecordingTrace();

        agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), trace, 8).respond("go");

        assertEquals(List.of(ToolResult.ok("echo:one"), ToolResult.ok("echo:two")),
                trace.results());
    }

    @Test
    void reportsAnUnknownToolBackToTheModelRatherThanFailing() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "nonexistent", "{}"))))
                .replyText("Understood.");
        RecordingTrace trace = new RecordingTrace();

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), trace, 8).respond("go");

        assertEquals("Understood.", answer);
        assertTrue(assertInstanceOf(ToolResult.Err.class, trace.results().get(0))
                .message().contains("Unknown tool"));
    }

    @Test
    void reportsMalformedArgumentsBackToTheModel() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{not json"))))
                .replyText("Understood.");
        RecordingTrace trace = new RecordingTrace();

        agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), trace, 8).respond("go");

        assertTrue(assertInstanceOf(ToolResult.Err.class, trace.results().get(0))
                .message().contains("parse"));
    }

    @Test
    void turnsAPolicyDenialIntoAnErrorTheModelCanRouteAround() {
        ToolPolicy denyEverything = (tool, args) -> Decision.deny("not permitted here");
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))))
                .replyText("Understood.");
        RecordingTrace trace = new RecordingTrace();

        agent(client, ToolRegistry.of(new EchoTool("echo")), denyEverything, trace, 8)
                .respond("go");

        assertEquals(ToolResult.err("not permitted here"), trace.results().get(0));
    }

    @Test
    void aRefusedAskBecomesAnError() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("1", "echo", "{}"))))
                .replyText("done");
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                (tool, args) -> Decision.ask("echo", "read outside this project", "/etc/passwd",
                        Optional.empty()),
                refusesToAsk(), conversation, new RecordingTrace(), new Cancellation(), 8);

        agent.respond("do it");

        ToolMessage result = conversation.messages().stream()
                .filter(m -> m instanceof ToolMessage)
                .map(ToolMessage.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals("<error> konacode has no approval for this call: echo on /etc/passwd."
                + " This answers one call and sets no rule. Call the tool again when the user"
                + " asks, and let konacode put the question.", result.content());
    }

    @Test
    void anAskDoesNotRunTheTool() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("1", "echo", "{}"))))
                .replyText("done");
        EchoTool echo = new EchoTool("echo");
        RecordingTrace trace = new RecordingTrace();

        agent(client, ToolRegistry.of(echo),
                (tool, args) -> Decision.ask("echo", "read outside this project", "/etc/passwd",
                        Optional.empty()),
                trace, 8).respond("do it");

        assertEquals(0, echo.calls(), "a refused call must not run");
    }

    @Test
    void anApprovedAskRunsTheTool() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("1", "echo", "{}"))))
                .replyText("done");
        EchoTool echo = new EchoTool("echo");
        Agent agent = new Agent(client, ToolRegistry.of(echo),
                (tool, args) -> Decision.ask("echo", "write outside this project", "/etc/hosts",
                        Optional.of(new Permission.InFolder("echo", Path.of("/etc")))),
                new Approvals(new FixedApproval(ToolApproval.Answer.YES)),
                new Conversation(new SystemMessage("You are konacode.")), new RecordingTrace(),
                new Cancellation(), 8);

        agent.respond("do it");

        assertEquals(1, echo.calls(), "an approved call must run");
    }

    @Test
    void survivesAToolThatThrows() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "explode", "{}"))))
                .replyText("Recovered.");
        RecordingTrace trace = new RecordingTrace();

        String answer = agent(client, ToolRegistry.of(new ExplodingTool("explode")),
                new AllowAllPolicy(), trace, 8).respond("go");

        assertEquals("Recovered.", answer);
        assertTrue(assertInstanceOf(ToolResult.Err.class, trace.results().get(0))
                .message().contains("boom"));
    }

    @Test
    void stopsAtTheIterationCeilingWithoutThrowing() {
        FakeLlmClient client = new FakeLlmClient();
        for (int i = 0; i < 5; i++) {
            client.reply(new AssistantMessage("", List.of(call("c" + i, "echo", "{}"))));
        }

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), new RecordingTrace(), 3).respond("go");

        assertTrue(answer.startsWith("<error> Exceeded maximum tool iterations"), answer);
        assertEquals(3, client.receivedHistories().size());
    }

    @Test
    void surfacesTransportFailuresToTheHumanWithoutThrowing() {
        FakeLlmClient client = new FakeLlmClient().failWith(new LlmException("HTTP 401: bad key"));

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), new RecordingTrace(), 8).respond("go");

        assertEquals("<error> HTTP 401: bad key", answer);
    }

    @Test
    void survivesAPolicyThatThrows() {
        ToolPolicy brokenPolicy = (tool, args) -> {
            throw new IllegalStateException("policy bug");
        };
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))))
                .replyText("Recovered.");
        RecordingTrace trace = new RecordingTrace();

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                brokenPolicy, trace, 8).respond("go");

        assertEquals("Recovered.", answer);
        assertTrue(assertInstanceOf(ToolResult.Err.class, trace.results().get(0))
                .message().contains("policy bug"));
    }

    @Test
    void survivesAnApprovalThatThrows() {
        ToolApproval brokenApproval = new ToolApproval() {
            @Override
            public Answer ask(Decision.Ask ask) {
                throw new IllegalStateException("terminal bug");
            }

            @Override
            public boolean canAsk() {
                return true;
            }
        };
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))))
                .replyText("Recovered.");
        RecordingTrace trace = new RecordingTrace();
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                (tool, args) -> Decision.ask("echo", "write outside this project", "/etc/hosts",
                        Optional.of(new Permission.InFolder("echo", Path.of("/etc")))),
                new Approvals(brokenApproval),
                new Conversation(new SystemMessage("You are konacode.")), trace,
                new Cancellation(), 8);

        String answer = agent.respond("go");

        assertEquals("Recovered.", answer);
        assertTrue(assertInstanceOf(ToolResult.Err.class, trace.results().get(0))
                .message().contains("terminal bug"));
    }

    @Test
    void answersEveryUserMessageEvenWhenTheTransportFails() {
        FakeLlmClient client = new FakeLlmClient().failWith(new LlmException("HTTP 500"));
        Conversation conversation =
                new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), refusesToAsk(), conversation, new RecordingTrace(),
                new Cancellation(), 8);

        agent.respond("first");
        agent.respond("second");

        // Two consecutive user turns are rejected by providers that enforce alternation.
        List<Message> history = conversation.messages();
        for (int i = 1; i < history.size(); i++) {
            assertFalse(history.get(i - 1) instanceof UserMessage
                            && history.get(i) instanceof UserMessage,
                    "two consecutive user messages at index " + i + ": " + history);
        }
    }

    @Test
    void recordsTheIterationCeilingInTheConversation() {
        FakeLlmClient client = new FakeLlmClient();
        for (int i = 0; i < 5; i++) {
            client.reply(new AssistantMessage("", List.of(call("c" + i, "echo", "{}"))));
        }
        Conversation conversation =
                new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), refusesToAsk(), conversation, new RecordingTrace(),
                new Cancellation(), 2);

        agent.respond("go");

        List<Message> history = conversation.messages();
        AssistantMessage last = assertInstanceOf(
                AssistantMessage.class, history.get(history.size() - 1));
        assertTrue(last.text().contains("Exceeded maximum tool iterations"), last.text());
    }

    @Test
    void runsRemainingToolCallsAfterOneFails() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(
                        call("c1", "explode", "{}"),
                        call("c2", "echo", "{\"value\":\"still ran\"}"))))
                .replyText("Done.");
        RecordingTrace trace = new RecordingTrace();

        agent(client, ToolRegistry.of(new EchoTool("echo"), new ExplodingTool("explode")),
                new AllowAllPolicy(), trace, 8).respond("go");

        assertEquals(2, trace.results().size());
        assertInstanceOf(ToolResult.Err.class, trace.results().get(0));
        assertEquals(ToolResult.ok("echo:still ran"), trace.results().get(1));
    }

    @Test
    void rejectsAMaxIterationsBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new Agent(new FakeLlmClient(), ToolRegistry.of(new EchoTool("echo")),
                        new AllowAllPolicy(), refusesToAsk(),
                        new Conversation(new SystemMessage("s")),
                        new RecordingTrace(), new Cancellation(), 0));
    }

    @Test
    void stopsWhenTheUserStopsDuringTheProviderCall() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(cancellation::request)
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))));
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), refusesToAsk(), conversation, new RecordingTrace(),
                cancellation, 8);

        String answer = agent.respond("list the files");

        assertEquals("Stopped.", answer);
        List<Message> messages = conversation.messages();
        assertInstanceOf(ToolMessage.class, messages.get(messages.size() - 2));
        assertEquals(new AssistantMessage("Stopped by the user.", List.of()),
                messages.get(messages.size() - 1));
    }

    @Test
    void answersEveryToolCallThatNeverRan() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(cancellation::request)
                .reply(new AssistantMessage("",
                        List.of(call("c1", "echo", "{}"), call("c2", "echo", "{}"))));
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), refusesToAsk(), conversation, new RecordingTrace(),
                cancellation, 8);

        agent.respond("do two things");

        List<String> toolMessages = conversation.messages().stream()
                .filter(ToolMessage.class::isInstance)
                .map(message -> ((ToolMessage) message).content())
                .toList();
        assertEquals(2, toolMessages.size());
        assertTrue(toolMessages.get(0).contains("Stopped by the user before this tool ran."));
        assertTrue(toolMessages.get(1).contains("Stopped by the user before this tool ran."));
    }

    @Test
    void runsTheToolThatStartedAndStopsBeforeTheNextOne() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("",
                        List.of(call("c1", "stop", "{}"), call("c2", "echo", "{}"))));
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        RecordingTrace trace = new RecordingTrace();
        Agent agent = new Agent(client,
                ToolRegistry.of(new StoppingTool("stop", cancellation), new EchoTool("echo")),
                new AllowAllPolicy(), refusesToAsk(), conversation, trace, cancellation, 8);

        assertEquals("Stopped.", agent.respond("do two things"));

        assertEquals(List.of(Outcome.STOPPED), trace.outcomes());
        List<String> toolMessages = conversation.messages().stream()
                .filter(ToolMessage.class::isInstance)
                .map(message -> ((ToolMessage) message).content())
                .toList();
        assertEquals(2, toolMessages.size());
        assertEquals("ran", toolMessages.get(0));
        assertTrue(toolMessages.get(1).contains("Stopped by the user before this tool ran."));
    }

    @Test
    void treatsAnAbortedRequestAsAStopAndNotAFailure() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(cancellation::request)
                .failWith(new LlmException("Request was interrupted."));
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), refusesToAsk(), conversation, new RecordingTrace(),
                cancellation, 8);

        assertEquals("Stopped.", agent.respond("hello"));
    }

    @Test
    void clearsTheStopBeforeEachTurn() {
        Cancellation cancellation = new Cancellation();
        cancellation.request();
        FakeLlmClient client = new FakeLlmClient().replyText("hello");
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), refusesToAsk(), conversation, new RecordingTrace(),
                cancellation, 8);

        assertEquals("hello", agent.respond("hello"));
    }

    @Test
    void interruptsTheThreadThatWaitsForTheProvider() {
        Cancellation cancellation = new Cancellation();
        boolean[] wasInterrupted = {false};
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(() -> {
                    cancellation.request();
                    wasInterrupted[0] = Thread.currentThread().isInterrupted();
                })
                .replyText("hello");
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), refusesToAsk(),
                new Conversation(new SystemMessage("You are konacode.")),
                new RecordingTrace(), cancellation, 8);

        agent.respond("hello");

        assertTrue(wasInterrupted[0], "the thread inside chat must be interrupted");
    }

    @Test
    void leavesNoInterruptBehindAfterAStop() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(cancellation::request)
                .replyText("hello");
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), refusesToAsk(),
                new Conversation(new SystemMessage("You are konacode.")),
                new RecordingTrace(), cancellation, 8);

        agent.respond("hello");

        assertFalse(Thread.interrupted(), "disarm must clear the interrupt status");
    }

    @Test
    void armsAroundAToolThatStopsOnInterrupt() {
        Cancellation cancellation = new Cancellation();
        InterruptibleTool tool = new InterruptibleTool(cancellation, true);
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "blocking", "{}"))));
        Agent agent = new Agent(client, ToolRegistry.of(tool), new AllowAllPolicy(),
                refusesToAsk(), new Conversation(new SystemMessage("You are konacode.")),
                new RecordingTrace(), cancellation, 8);

        agent.respond("fetch it");

        assertTrue(tool.sawInterrupt, "a tool that declares stopsOnInterrupt must be armed");
        assertFalse(Thread.interrupted(), "disarm must clear the interrupt status");
    }

    @Test
    void doesNotArmAroundAToolThatDoesNotStopOnInterrupt() {
        Cancellation cancellation = new Cancellation();
        InterruptibleTool tool = new InterruptibleTool(cancellation, false);
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "blocking", "{}"))));
        Agent agent = new Agent(client, ToolRegistry.of(tool), new AllowAllPolicy(),
                refusesToAsk(), new Conversation(new SystemMessage("You are konacode.")),
                new RecordingTrace(), cancellation, 8);

        agent.respond("fetch it");

        assertFalse(tool.sawInterrupt, "a tool that does not declare it must never be interrupted");
    }
}
