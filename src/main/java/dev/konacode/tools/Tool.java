package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A capability the model may invoke.
 *
 * <p>Note that {@link #description()} is prompt text — the model reads it to decide when to call
 * this tool. Changing it changes agent behavior.
 *
 * <p>Implementations must not throw. Every failure, including malformed arguments, is a
 * {@link ToolResult.Err} the model can read and recover from.
 */
public interface Tool {

    String name();

    String description();

    ObjectNode inputSchema();

    ToolResult execute(JsonNode args);

    boolean stopsOnInterrupt();

    /**
     * What this call does. Abstract and never a default, so a new tool must answer it, the way
     * {@link #stopsOnInterrupt()} already does.
     *
     * <p>The answer must name the place that {@link #execute} will touch. A tool that cannot name
     * that place answers the {@code OUTSIDE} value of its kind, and konacode then asks the user.
     *
     * <p>A tool that gives no permission says that no standing "always" can describe this call.
     */
    Action computeAction(JsonNode args);
}
