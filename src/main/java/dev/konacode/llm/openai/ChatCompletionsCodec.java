package dev.konacode.llm.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.konacode.llm.LlmException;
import dev.konacode.llm.Message;
import dev.konacode.llm.Message.AssistantMessage;
import dev.konacode.llm.Message.SystemMessage;
import dev.konacode.llm.Message.ToolMessage;
import dev.konacode.llm.Message.UserMessage;
import dev.konacode.llm.ToolCall;
import dev.konacode.llm.ToolSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Translation between konacode's message model and the OpenAI Chat Completions wire format.
 *
 * <p>Deliberately free of HTTP, so the wire format can be tested against recorded fixtures with
 * no network and no mocking framework.
 */
public final class ChatCompletionsCodec {

    private final ObjectMapper mapper;

    public ChatCompletionsCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public ObjectNode encodeRequest(String model, List<Message> history, List<ToolSpec> tools) {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", model);

        ArrayNode messages = request.putArray("messages");
        for (Message message : history) {
            messages.add(encodeMessage(message));
        }

        if (!tools.isEmpty()) {
            ArrayNode toolArray = request.putArray("tools");
            for (ToolSpec spec : tools) {
                ObjectNode tool = toolArray.addObject();
                tool.put("type", "function");
                ObjectNode function = tool.putObject("function");
                function.put("name", spec.name());
                function.put("description", spec.description());
                function.set("parameters", spec.schema());
            }
            request.put("tool_choice", "auto");
        }

        return request;
    }

    private ObjectNode encodeMessage(Message message) {
        ObjectNode node = mapper.createObjectNode();
        switch (message) {
            case SystemMessage system -> {
                node.put("role", "system");
                node.put("content", system.text());
            }
            case UserMessage user -> {
                node.put("role", "user");
                node.put("content", user.text());
            }
            case AssistantMessage assistant -> {
                node.put("role", "assistant");
                if (assistant.text().isEmpty()) {
                    node.putNull("content");
                } else {
                    node.put("content", assistant.text());
                }
                if (assistant.hasToolCalls()) {
                    ArrayNode calls = node.putArray("tool_calls");
                    for (ToolCall call : assistant.toolCalls()) {
                        ObjectNode encoded = calls.addObject();
                        encoded.put("id", call.id());
                        encoded.put("type", "function");
                        ObjectNode function = encoded.putObject("function");
                        function.put("name", call.name());
                        function.put("arguments", call.argumentsJson());
                    }
                }
            }
            case ToolMessage tool -> {
                node.put("role", "tool");
                node.put("tool_call_id", tool.toolCallId());
                node.put("content", tool.content());
            }
        }
        return node;
    }

    public AssistantMessage decodeResponse(String body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new LlmException(
                    "Could not parse the response as JSON: " + e.getOriginalMessage(), e);
        }

        JsonNode choices = root.path("choices");
        if (!choices.isArray() || choices.isEmpty()) {
            throw new LlmException("Response contained no choices.");
        }

        JsonNode message = choices.get(0).path("message");
        String text = message.path("content").isTextual() ? message.get("content").asText() : "";

        List<ToolCall> toolCalls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            String id = call.path("id").asText("");
            if (id.isBlank()) {
                throw new LlmException(
                        "Provider returned a tool call with no id, which cannot be correlated "
                                + "to its result: " + call);
            }
            toolCalls.add(new ToolCall(
                    id,
                    function.path("name").asText(""),
                    function.path("arguments").asText("")));
        }

        return new AssistantMessage(text, toolCalls);
    }

    /**
     * The token counts of a reply, when the provider reported them.
     *
     * <p>It never throws. The counts are a diagnostic, so a reply konacode cannot read here is a
     * reply with no counts, and never a failed turn. This is why it differs from
     * {@link #decodeResponse}, which throws: a reply with no choices carries no answer.
     *
     * <p>It parses the body a second time on purpose. This class stays free of state, and each
     * method is testable alone.
     */
    public Optional<Usage> decodeUsage(String body) {
        if (body == null) {
            return Optional.empty();
        }
        JsonNode usage;
        try {
            usage = mapper.readTree(body).path("usage");
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
        if (!usage.isObject()) {
            return Optional.empty();
        }
        return Optional.of(new Usage(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                usage.path("total_tokens").asInt(0)));
    }
}
