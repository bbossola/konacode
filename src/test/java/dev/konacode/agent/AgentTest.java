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
import dev.konacode.tools.Schemas;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolRegistry;
import dev.konacode.tools.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTest {

    /** Echoes its arguments, so tests can see exactly what the loop passed through. */
    private record EchoTool(String name) implements Tool {
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
            return ToolResult.ok("echo:" + args.path("value").asText(""));
        }

        @Override
        public boolean stopsOnInterrupt() {
            return false;
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
    }

    private static ToolCall call(String id, String name, String argumentsJson) {
        return new ToolCall(id, name, argumentsJson);
    }

    private Agent agent(FakeLlmClient client, ToolRegistry registry, ToolPolicy policy,
                        RecordingToolCallListener listener, int maxIterations) {
        return new Agent(
                client,
                registry,
                policy,
                new Conversation(new SystemMessage("You are konacode.")),
                listener,
                new Cancellation(),
                maxIterations);
    }

    @Test
    void returnsTextWhenTheModelDoesNotCallATool() {
        FakeLlmClient client = new FakeLlmClient().replyText("Hello.");
        RecordingToolCallListener listener = new RecordingToolCallListener();

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), listener, 8).respond("hi");

        assertEquals("Hello.", answer);
        assertEquals(List.of(), listener.calls());
    }

    @Test
    void runsAToolThenReturnsTheFollowUpText() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{\"value\":\"hi\"}"))))
                .replyText("Done.");
        RecordingToolCallListener listener = new RecordingToolCallListener();

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), listener, 8).respond("go");

        assertEquals("Done.", answer);
        assertEquals(List.of("echo({\"value\":\"hi\"})"), listener.calls());
        assertEquals(List.of(ToolResult.ok("echo:hi")), listener.results());
    }

    @Test
    void appendsTheAssistantMessageBeforeTheToolResult() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))))
                .replyText("Done.");

        agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), new RecordingToolCallListener(), 8).respond("go");

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
        RecordingToolCallListener listener = new RecordingToolCallListener();

        agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), listener, 8).respond("go");

        assertEquals(List.of(ToolResult.ok("echo:one"), ToolResult.ok("echo:two")),
                listener.results());
    }

    @Test
    void reportsAnUnknownToolBackToTheModelRatherThanFailing() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "nonexistent", "{}"))))
                .replyText("Understood.");
        RecordingToolCallListener listener = new RecordingToolCallListener();

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), listener, 8).respond("go");

        assertEquals("Understood.", answer);
        assertTrue(assertInstanceOf(ToolResult.Err.class, listener.results().get(0))
                .message().contains("Unknown tool"));
    }

    @Test
    void reportsMalformedArgumentsBackToTheModel() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{not json"))))
                .replyText("Understood.");
        RecordingToolCallListener listener = new RecordingToolCallListener();

        agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), listener, 8).respond("go");

        assertTrue(assertInstanceOf(ToolResult.Err.class, listener.results().get(0))
                .message().contains("parse"));
    }

    @Test
    void turnsAPolicyDenialIntoAnErrorTheModelCanRouteAround() {
        ToolPolicy denyEverything = (tool, args) -> Decision.deny("not permitted here");
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))))
                .replyText("Understood.");
        RecordingToolCallListener listener = new RecordingToolCallListener();

        agent(client, ToolRegistry.of(new EchoTool("echo")), denyEverything, listener, 8)
                .respond("go");

        assertEquals(ToolResult.err("not permitted here"), listener.results().get(0));
    }

    @Test
    void survivesAToolThatThrows() {
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "explode", "{}"))))
                .replyText("Recovered.");
        RecordingToolCallListener listener = new RecordingToolCallListener();

        String answer = agent(client, ToolRegistry.of(new ExplodingTool("explode")),
                new AllowAllPolicy(), listener, 8).respond("go");

        assertEquals("Recovered.", answer);
        assertTrue(assertInstanceOf(ToolResult.Err.class, listener.results().get(0))
                .message().contains("boom"));
    }

    @Test
    void stopsAtTheIterationCeilingWithoutThrowing() {
        FakeLlmClient client = new FakeLlmClient();
        for (int i = 0; i < 5; i++) {
            client.reply(new AssistantMessage("", List.of(call("c" + i, "echo", "{}"))));
        }

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), new RecordingToolCallListener(), 3).respond("go");

        assertTrue(answer.startsWith("<error> Exceeded maximum tool iterations"), answer);
        assertEquals(3, client.receivedHistories().size());
    }

    @Test
    void surfacesTransportFailuresToTheHumanWithoutThrowing() {
        FakeLlmClient client = new FakeLlmClient().failWith(new LlmException("HTTP 401: bad key"));

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), new RecordingToolCallListener(), 8).respond("go");

        assertEquals("<error> HTTP 401: bad key", answer);
    }

    @Test
    void readsTheIterationCeilingFromASystemProperty() {
        String previous = System.getProperty("konacode.maxIterations");
        try {
            System.setProperty("konacode.maxIterations", "42");
            assertEquals(42, Agent.configuredMaxIterations());
        } finally {
            if (previous == null) {
                System.clearProperty("konacode.maxIterations");
            } else {
                System.setProperty("konacode.maxIterations", previous);
            }
        }
    }

    @Test
    void survivesAPolicyThatThrows() {
        ToolPolicy brokenPolicy = (tool, args) -> {
            throw new IllegalStateException("policy bug");
        };
        FakeLlmClient client = new FakeLlmClient()
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))))
                .replyText("Recovered.");
        RecordingToolCallListener listener = new RecordingToolCallListener();

        String answer = agent(client, ToolRegistry.of(new EchoTool("echo")),
                brokenPolicy, listener, 8).respond("go");

        assertEquals("Recovered.", answer);
        assertTrue(assertInstanceOf(ToolResult.Err.class, listener.results().get(0))
                .message().contains("policy bug"));
    }

    @Test
    void answersEveryUserMessageEvenWhenTheTransportFails() {
        FakeLlmClient client = new FakeLlmClient().failWith(new LlmException("HTTP 500"));
        Conversation conversation =
                new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), conversation, new RecordingToolCallListener(),
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
                new AllowAllPolicy(), conversation, new RecordingToolCallListener(),
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
        RecordingToolCallListener listener = new RecordingToolCallListener();

        agent(client, ToolRegistry.of(new EchoTool("echo"), new ExplodingTool("explode")),
                new AllowAllPolicy(), listener, 8).respond("go");

        assertEquals(2, listener.results().size());
        assertInstanceOf(ToolResult.Err.class, listener.results().get(0));
        assertEquals(ToolResult.ok("echo:still ran"), listener.results().get(1));
    }

    @Test
    void rejectsAMaxIterationsBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new Agent(new FakeLlmClient(), ToolRegistry.of(new EchoTool("echo")),
                        new AllowAllPolicy(),
                        new Conversation(new SystemMessage("s")),
                        new RecordingToolCallListener(), new Cancellation(), 0));
    }

    @Test
    void rejectsAMalformedMaxIterationsPropertyRatherThanSilentlyDefaulting() {
        String previous = System.getProperty("konacode.maxIterations");
        try {
            System.setProperty("konacode.maxIterations", "eihgt");
            assertThrows(IllegalArgumentException.class, Agent::configuredMaxIterations);
        } finally {
            if (previous == null) {
                System.clearProperty("konacode.maxIterations");
            } else {
                System.setProperty("konacode.maxIterations", previous);
            }
        }
    }

    @Test
    void stopsWhenTheUserStopsDuringTheProviderCall() {
        Cancellation cancellation = new Cancellation();
        FakeLlmClient client = new FakeLlmClient()
                .beforeReply(cancellation::request)
                .reply(new AssistantMessage("", List.of(call("c1", "echo", "{}"))));
        Conversation conversation = new Conversation(new SystemMessage("You are konacode."));
        Agent agent = new Agent(client, ToolRegistry.of(new EchoTool("echo")),
                new AllowAllPolicy(), conversation, new RecordingToolCallListener(),
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
                new AllowAllPolicy(), conversation, new RecordingToolCallListener(),
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
        RecordingToolCallListener listener = new RecordingToolCallListener();
        Agent agent = new Agent(client,
                ToolRegistry.of(new StoppingTool("stop", cancellation), new EchoTool("echo")),
                new AllowAllPolicy(), conversation, listener, cancellation, 8);

        assertEquals("Stopped.", agent.respond("do two things"));

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
                new AllowAllPolicy(), conversation, new RecordingToolCallListener(),
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
                new AllowAllPolicy(), conversation, new RecordingToolCallListener(),
                cancellation, 8);

        assertEquals("hello", agent.respond("hello"));
    }
}
