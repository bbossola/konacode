package dev.konacode.llm;

import java.util.List;

/**
 * One entry in the conversation, in a form no provider owns.
 *
 * <p>Only {@link AssistantMessage} normalises nulls. That asymmetry is deliberate: it is the one
 * record built by decoding provider output, where a missing field is an external-input problem to
 * absorb. The other three are constructed by konacode's own code, where a null would be a
 * programming error and is better left to fail loudly than quietly become {@code ""}.
 */
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
