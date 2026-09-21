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
 * Translation between konacode's message model and the Responses API of the Codex backend.
 *
 * <p>The request always streams, because the Codex CLI never sends a request that does not, and
 * nothing proves that the endpoint accepts one. The client reads the whole stream after it closes,
 * so this codec parses the events of one finished stream and holds no state.
 *
 * <p>It sends {@code store: false} and asks for no reasoning item. The model reasons again on each
 * iteration, as it does on Chat Completions. The reasoning passthrough is the next change.
 */
public final class ResponsesCodec implements Codec {

    private final ObjectMapper mapper;

    public ResponsesCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String path() {
        return "/responses";
    }

    @Override
    public String accept() {
        return "text/event-stream";
    }

    @Override
    public ObjectNode encodeRequest(String model, List<Message> history, List<ToolSpec> tools) {
        ObjectNode request = mapper.createObjectNode();
        request.put("model", model);

        List<String> instructions = new ArrayList<>();
        ArrayNode input = mapper.createArrayNode();
        for (Message message : history) {
            switch (message) {
                case SystemMessage system -> instructions.add(system.text());
                case UserMessage user -> input.add(message("user", "input_text", user.text()));
                case AssistantMessage assistant -> {
                    if (!assistant.text().isEmpty()) {
                        input.add(message("assistant", "output_text", assistant.text()));
                    }
                    for (ToolCall call : assistant.toolCalls()) {
                        ObjectNode item = input.addObject();
                        item.put("type", "function_call");
                        item.put("call_id", call.id());
                        item.put("name", call.name());
                        item.put("arguments", call.argumentsJson());
                    }
                }
                case ToolMessage tool -> {
                    ObjectNode item = input.addObject();
                    item.put("type", "function_call_output");
                    item.put("call_id", tool.toolCallId());
                    item.put("output", tool.content());
                }
            }
        }
        if (!instructions.isEmpty()) {
            request.put("instructions", String.join("\n\n", instructions));
        }
        request.set("input", input);

        if (!tools.isEmpty()) {
            ArrayNode toolArray = request.putArray("tools");
            for (ToolSpec spec : tools) {
                ObjectNode tool = toolArray.addObject();
                tool.put("type", "function");
                tool.put("name", spec.name());
                tool.put("description", spec.description());
                tool.set("parameters", spec.schema());
            }
            request.put("tool_choice", "auto");
        }

        request.put("store", false);
        request.put("stream", true);
        return request;
    }

    private ObjectNode message(String role, String partType, String text) {
        ObjectNode item = mapper.createObjectNode();
        item.put("type", "message");
        item.put("role", role);
        ObjectNode part = item.putArray("content").addObject();
        part.put("type", partType);
        part.put("text", text);
        return item;
    }

    @Override
    public AssistantMessage decodeResponse(String body) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        boolean completed = false;
        for (JsonNode event : events(body)) {
            switch (event.path("type").asText("")) {
                case "response.output_item.done" -> readItem(event.path("item"), text, toolCalls);
                case "response.failed" -> throw new LlmException("The provider failed the response: "
                        + event.path("response").path("error").path("message").asText("no message"));
                case "response.completed" -> completed = true;
                default -> { }
            }
        }
        if (!completed) {
            throw new LlmException("The stream closed before response.completed.");
        }
        return new AssistantMessage(text.toString(), toolCalls);
    }

    /** A finished message adds its text, a finished call adds a tool call, and every other item is ignored. */
    private static void readItem(JsonNode item, StringBuilder text, List<ToolCall> toolCalls) {
        switch (item.path("type").asText("")) {
            case "message" -> {
                for (JsonNode part : item.path("content")) {
                    if (part.path("type").asText("").equals("output_text")) {
                        text.append(part.path("text").asText(""));
                    }
                }
            }
            case "function_call" -> {
                String id = item.path("call_id").asText("");
                if (id.isBlank()) {
                    throw new LlmException("Provider returned a tool call with no call_id, which cannot be correlated to its result: " + item);
                }
                toolCalls.add(new ToolCall(id, item.path("name").asText(""), item.path("arguments").asText("")));
            }
            default -> { }
        }
    }

    /**
     * Every {@code data:} line of the stream, as JSON. A body with no such line is a reply konacode
     * cannot read, and so is a line that is not JSON.
     */
    private List<JsonNode> events(String body) {
        List<JsonNode> events = new ArrayList<>();
        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring("data:".length()).strip();
            if (data.isEmpty() || data.equals("[DONE]")) {
                continue;
            }
            try {
                events.add(mapper.readTree(data));
            } catch (JsonProcessingException e) {
                throw new LlmException("Could not parse a stream event as JSON: " + e.getOriginalMessage(), e);
            }
        }
        if (events.isEmpty()) {
            throw new LlmException("The response is not an event stream.");
        }
        return events;
    }

    /**
     * The counts in the {@code response.completed} event. It never throws, for the reason
     * {@link ChatCompletionsCodec#decodeUsage} gives: a count is a diagnostic.
     */
    @Override
    public Optional<Usage> decodeUsage(String body) {
        if (body == null || body.isBlank()) {
            return Optional.empty();
        }
        List<JsonNode> events;
        try {
            events = events(body);
        } catch (LlmException e) {
            return Optional.empty();
        }
        for (JsonNode event : events) {
            if (event.path("type").asText("").equals("response.completed")) {
                JsonNode usage = event.path("response").path("usage");
                if (usage.isObject()) {
                    return Optional.of(new Usage(usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0), usage.path("total_tokens").asInt(0)));
                }
            }
        }
        return Optional.empty();
    }
}
