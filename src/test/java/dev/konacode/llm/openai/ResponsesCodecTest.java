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
import dev.konacode.llm.http.Usage;
import dev.konacode.tools.Schemas;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesCodecTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ResponsesCodec codec = new ResponsesCodec(mapper);

    private static String fixture(String name) throws IOException {
        try (InputStream in = ResponsesCodecTest.class.getResourceAsStream("/openai/" + name)) {
            if (in == null) {
                throw new IllegalStateException("Missing fixture: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static List<Message> history() {
        return List.of(
                new SystemMessage("You are konacode."),
                new UserMessage("what is here?"),
                new AssistantMessage("", List.of(new ToolCall("call_1", "list_files", "{\"path\":\".\"}"))),
                new ToolMessage("call_1", "pom.xml\nsrc/"),
                new AssistantMessage("Two entries.", List.of()));
    }

    private static List<ToolSpec> oneTool() {
        return List.of(new ToolSpec("list_files", "List files.", Schemas.object().build()));
    }

    @Test
    void namesItsPathAndItsAcceptHeader() {
        assertEquals("/responses", codec.path());
        assertEquals("text/event-stream", codec.accept());
    }

    @Test
    void theSystemMessageBecomesTheInstructionsAndTheRestBecomesTheInput() {
        ObjectNode request = codec.encodeRequest("gpt-5.5", history(), List.of());

        assertEquals("gpt-5.5", request.get("model").asText());
        assertEquals("You are konacode.", request.get("instructions").asText());
        JsonNode input = request.get("input");
        assertEquals(4, input.size(), input.toString());
        assertEquals("message", input.get(0).get("type").asText());
        assertEquals("user", input.get(0).get("role").asText());
        assertEquals("input_text", input.get(0).get("content").get(0).get("type").asText());
        assertEquals("what is here?", input.get(0).get("content").get(0).get("text").asText());
        assertEquals("function_call", input.get(1).get("type").asText());
        assertEquals("call_1", input.get(1).get("call_id").asText());
        assertEquals("list_files", input.get(1).get("name").asText());
        assertEquals("{\"path\":\".\"}", input.get(1).get("arguments").asText());
        assertEquals("function_call_output", input.get(2).get("type").asText());
        assertEquals("call_1", input.get(2).get("call_id").asText());
        assertEquals("pom.xml\nsrc/", input.get(2).get("output").asText());
        assertEquals("message", input.get(3).get("type").asText());
        assertEquals("assistant", input.get(3).get("role").asText());
        assertEquals("output_text", input.get(3).get("content").get(0).get("type").asText());
        assertEquals("Two entries.", input.get(3).get("content").get(0).get("text").asText());
    }

    @Test
    void anAssistantMessageWithTextAndToolCallsWritesBoth() {
        List<Message> history = List.of(new AssistantMessage("Looking.", List.of(new ToolCall("c1", "read_file", "{}"))));

        JsonNode input = codec.encodeRequest("gpt-5.5", history, List.of()).get("input");

        assertEquals(2, input.size(), input.toString());
        assertEquals("message", input.get(0).get("type").asText());
        assertEquals("function_call", input.get(1).get("type").asText());
    }

    @Test
    void twoSystemMessagesJoinWithABlankLine() {
        List<Message> history = List.of(new SystemMessage("One."), new SystemMessage("Two."));

        ObjectNode request = codec.encodeRequest("gpt-5.5", history, List.of());

        assertEquals("One.\n\nTwo.", request.get("instructions").asText());
        assertEquals(0, request.get("input").size());
    }

    @Test
    void noSystemMessageMeansNoInstructionsField() {
        ObjectNode request = codec.encodeRequest("gpt-5.5", List.of(new UserMessage("hi")), List.of());

        assertFalse(request.has("instructions"), request.toString());
    }

    @Test
    void toolsAreFlatAndToolChoiceIsAuto() {
        ObjectNode request = codec.encodeRequest("gpt-5.5", List.of(), oneTool());

        JsonNode tool = request.get("tools").get(0);
        assertEquals("function", tool.get("type").asText());
        assertEquals("list_files", tool.get("name").asText());
        assertEquals("List files.", tool.get("description").asText());
        assertEquals("object", tool.get("parameters").get("type").asText());
        assertFalse(tool.has("function"), "the Responses API takes the name at the top of the tool");
        assertEquals("auto", request.get("tool_choice").asText());
    }

    @Test
    void noToolMeansNoToolsFieldAndNoToolChoice() {
        ObjectNode request = codec.encodeRequest("gpt-5.5", List.of(), List.of());

        assertFalse(request.has("tools"), request.toString());
        assertFalse(request.has("tool_choice"), request.toString());
    }

    @Test
    void everyRequestStreamsAndStoresNothing() {
        ObjectNode request = codec.encodeRequest("gpt-5.5", List.of(), List.of());

        assertTrue(request.get("stream").asBoolean());
        assertFalse(request.get("store").asBoolean());
        assertFalse(request.has("include"), request.toString());
        assertFalse(request.has("reasoning"), request.toString());
    }

    /** The fixture is a reply the Codex backend sent on 2026-09-21, with the safety identifier redacted. */
    @Test
    void decodesTheTextOfTheFinishedMessageAndIgnoresTheDeltas() throws IOException {
        AssistantMessage reply = codec.decodeResponse(fixture("responses-text.sse"));

        assertTrue(reply.text().startsWith("Files/directories here:\n\n- `.git/`"), reply.text());
        assertTrue(reply.text().endsWith("- `target/`"), reply.text());
        assertFalse(reply.hasToolCalls());
    }

    @Test
    void decodesTheFinishedToolCallWithItsCallId() throws IOException {
        AssistantMessage reply = codec.decodeResponse(fixture("responses-tool-call.sse"));

        assertEquals(List.of(new ToolCall("call_abc", "list_files", "{\"path\":\".\"}")), reply.toolCalls());
        assertEquals("Listing the folder.", reply.text());
    }

    @Test
    void aFailedResponseIsAnLlmExceptionWithTheMessageOfTheProvider() throws IOException {
        String body = fixture("responses-failed.sse");

        LlmException thrown = assertThrows(LlmException.class, () -> codec.decodeResponse(body));

        assertTrue(thrown.getMessage().contains("The model is overloaded."), thrown.getMessage());
    }

    @Test
    void anIncompleteResponseIsAnLlmExceptionWithTheReason() {
        String body = "data: {\"type\":\"response.incomplete\",\"response\":{\"incomplete_details\":{\"reason\":\"max_output_tokens\"}}}\n";

        LlmException thrown = assertThrows(LlmException.class, () -> codec.decodeResponse(body));

        assertTrue(thrown.getMessage().contains("max_output_tokens"), thrown.getMessage());
    }

    @Test
    void joinsTheTextOfTwoFinishedMessagesAndReadsWindowsLineEnds() {
        String body = "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"A\"}]}}\r\n"
                + "data:\r\n"
                + "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"B\"}]}}\r\n"
                + "data: {\"type\":\"response.completed\",\"response\":{}}\r\n"
                + "data: [DONE]\r\n";

        AssistantMessage reply = codec.decodeResponse(body);

        assertEquals("AB", reply.text());
    }

    @Test
    void aNullBodyIsAnLlmException() {
        assertThrows(LlmException.class, () -> codec.decodeResponse(null));
    }

    @Test
    void aStreamWithNoCompletedEventIsAnLlmException() {
        String body = "event: response.created\ndata: {\"type\":\"response.created\",\"response\":{\"id\":\"r\"}}\n\n";

        LlmException thrown = assertThrows(LlmException.class, () -> codec.decodeResponse(body));

        assertTrue(thrown.getMessage().contains("response.completed"), thrown.getMessage());
    }

    @Test
    void aToolCallWithNoCallIdIsAnLlmException() {
        String body = "data: {\"type\":\"response.output_item.done\",\"item\":{\"type\":\"function_call\",\"name\":\"x\",\"arguments\":\"{}\"}}\n"
                + "data: {\"type\":\"response.completed\",\"response\":{}}\n";

        assertThrows(LlmException.class, () -> codec.decodeResponse(body));
    }

    @Test
    void aDataLineThatIsNotJsonIsAnLlmException() {
        String body = "data: not json\n";

        assertThrows(LlmException.class, () -> codec.decodeResponse(body));
    }

    @Test
    void aBodyThatIsNotAStreamIsAnLlmException() {
        assertThrows(LlmException.class, () -> codec.decodeResponse("{\"error\":\"boom\"}"));
    }

    @Test
    void readsTheUsageFromTheCompletedEvent() throws IOException {
        assertEquals(Optional.of(new Usage(964, 86, 1050)), codec.decodeUsage(fixture("responses-text.sse")));
    }

    @Test
    void usageIsEmptyWhenTheStreamHasNoneAndNeverThrows() {
        assertEquals(Optional.empty(), codec.decodeUsage("data: not json\n"));
        assertEquals(Optional.empty(), codec.decodeUsage("data: {\"type\":\"response.completed\",\"response\":{}}\n"));
        assertEquals(Optional.empty(), codec.decodeUsage(null));
        assertEquals(Optional.empty(), codec.decodeUsage(""));
    }
}
