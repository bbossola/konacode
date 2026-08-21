package dev.konacode.llm;

import java.util.List;

/** One entry in the conversation, in a form no provider owns. */
public sealed interface Message {

    /** The standing instruction. First in history, never removed. */
    record SystemMessage(String text) implements Message {}

    /** One line typed by the human. */
    record UserMessage(String text) implements Message {}

    /** The model's reply: text, tool calls, or both. */
    record AssistantMessage(String text, List<ToolCall> toolCalls) implements Message {

        public AssistantMessage {
            text = text == null ? "" : text;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        }

        public boolean hasToolCalls() {
            return !toolCalls.isEmpty();
        }
    }

    /** The result of one tool call, keyed back to the call that produced it. */
    record ToolMessage(String toolCallId, String content) implements Message {}
}
