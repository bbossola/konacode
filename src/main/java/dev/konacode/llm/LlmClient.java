package dev.konacode.llm;

import dev.konacode.llm.Message.AssistantMessage;

import java.util.List;

/**
 * The entire provider SPI. Blocking by design — the agent loop is strictly sequential, so
 * asynchrony would buy nothing and cost clarity.
 *
 * <p>Implementations throw {@link LlmException} for transport and protocol failures. They never
 * signal a tool failure; that is the agent's concern.
 */
public interface LlmClient {

    AssistantMessage chat(List<Message> history, List<ToolSpec> tools);
}
