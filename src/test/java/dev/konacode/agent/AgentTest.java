package dev.konacode.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.Message.ToolMessage;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
                new AppendOnlyConversation(new SystemMessage("You are konacode.")),
                listener,
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
}
