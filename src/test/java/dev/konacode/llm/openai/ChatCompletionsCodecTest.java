package dev.konacode.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.Message.ToolMessage;
import dev.konacode.llm.Message.UserMessage;
import dev.konacode.llm.ToolCall;
import dev.konacode.llm.ToolSpec;
import dev.konacode.tools.Schemas;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCompletionsCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ChatCompletionsCodec codec = new ChatCompletionsCodec(mapper);

    private static String fixture(String name) throws IOException {
        try (InputStream in = ChatCompletionsCodecTest.class
                .getResourceAsStream("/openai/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void encodesEachMessageRoleInTheShapeTheApiExpects() {
        List<Message> history = List.of(
                new SystemMessage("You are konacode."),
                new UserMessage("what is here?"),
                new AssistantMessage("", List.of(new ToolCall("call_1", "list_files", "{\"path\":\".\"}"))),
                new ToolMessage("call_1", "directory /tmp"));

        ObjectNode request = codec.encodeRequest("gpt-5-mini", history, List.of());
        JsonNode messages = request.get("messages");

        assertEquals("gpt-5-mini", request.get("model").asText());
        assertEquals(4, messages.size());

        assertEquals("system", messages.get(0).get("role").asText());
        assertEquals("You are konacode.", messages.get(0).get("content").asText());

        assertEquals("user", messages.get(1).get("role").asText());
        assertEquals("what is here?", messages.get(1).get("content").asText());

        JsonNode assistant = messages.get(2);
        assertEquals("assistant", assistant.get("role").asText());
        assertTrue(assistant.get("content").isNull(), "empty assistant text must encode as null");
        JsonNode toolCall = assistant.get("tool_calls").get(0);
        assertEquals("call_1", toolCall.get("id").asText());
        assertEquals("function", toolCall.get("type").asText());
        assertEquals("list_files", toolCall.get("function").get("name").asText());
        assertEquals("{\"path\":\".\"}", toolCall.get("function").get("arguments").asText());

        JsonNode tool = messages.get(3);
        assertEquals("tool", tool.get("role").asText());
        assertEquals("call_1", tool.get("tool_call_id").asText());
        assertEquals("directory /tmp", tool.get("content").asText());
    }

    @Test
    void encodesToolSpecsAsFunctionDefinitions() {
        ToolSpec spec = new ToolSpec(
                "read_file",
                "Read a file.",
                Schemas.object().requiredString("path", "Which file.").build());

        ObjectNode request = codec.encodeRequest("gpt-5-mini", List.of(), List.of(spec));

        JsonNode tool = request.get("tools").get(0);
        assertEquals("function", tool.get("type").asText());
        assertEquals("read_file", tool.get("function").get("name").asText());
        assertEquals("Read a file.", tool.get("function").get("description").asText());
        assertEquals("object", tool.get("function").get("parameters").get("type").asText());
        assertEquals("auto", request.get("tool_choice").asText());
    }

    @Test
    void omitsToolsEntirelyWhenThereAreNone() {
        ObjectNode request = codec.encodeRequest("gpt-5-mini", List.of(), List.of());

        assertTrue(request.path("tools").isMissingNode());
        assertTrue(request.path("tool_choice").isMissingNode());
    }

    @Test
    void decodesATextOnlyResponse() throws IOException {
        AssistantMessage message = codec.decodeResponse(fixture("response-text-only.json"));

        assertEquals("It is a Java CLI agent.", message.text());
        assertEquals(List.of(), message.toolCalls());
    }

    @Test
    void decodesToolCallsAndKeepsArgumentsAsARawString() throws IOException {
        AssistantMessage message = codec.decodeResponse(fixture("response-with-tool-calls.json"));

        assertEquals("", message.text());
        assertEquals(2, message.toolCalls().size());
        assertEquals(new ToolCall("call_abc123", "read_file", "{\"path\":\"pom.xml\"}"),
                message.toolCalls().get(0));
        assertEquals("list_files", message.toolCalls().get(1).name());
    }

    @Test
    void raisesLlmExceptionOnAResponseWithNoChoices() {
        LlmException thrown = assertThrows(LlmException.class,
                () -> codec.decodeResponse("{\"choices\":[]}"));

        assertTrue(thrown.getMessage().contains("no choices"), thrown.getMessage());
    }

    @Test
    void raisesLlmExceptionOnUnparseableJson() {
        assertThrows(LlmException.class, () -> codec.decodeResponse("not json at all"));
    }
}
