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
     */
    Effect effect(JsonNode args);
}
