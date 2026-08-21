package dev.konacode.llm;

/**
 * A model request to run a tool.
 *
 * <p>{@code argumentsJson} stays a raw string, exactly as the model emitted it. Parsing it here
 * would turn a model mistake into a transport failure; instead the agent parses it and reports a
 * failure the model can correct.
 */
public record ToolCall(String id, String name, String argumentsJson) {
}
