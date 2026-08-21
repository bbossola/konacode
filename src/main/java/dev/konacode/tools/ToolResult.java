package dev.konacode.tools;

/**
 * The outcome of running a tool. Sealed rather than a bare string so the agent loop and the
 * policy layer can distinguish failure without inspecting message text.
 */
public sealed interface ToolResult {

    record Ok(String text) implements ToolResult {}

    record Err(String message) implements ToolResult {}

    static ToolResult ok(String text) {
        return new Ok(text);
    }

    static ToolResult err(String message) {
        return new Err(message);
    }

    /** The string the model sees. The only place typed results become prose. */
    default String render() {
        return switch (this) {
            case Ok ok -> ok.text();
            case Err err -> "<error> " + err.message();
        };
    }
}
