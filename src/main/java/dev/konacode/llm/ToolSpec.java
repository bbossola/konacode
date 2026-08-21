package dev.konacode.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What a provider advertises to the model so it knows a tool exists.
 *
 * <p>The schema is deep-copied on construction. A record promises value semantics and
 * {@link ObjectNode} is mutable, so without the copy a {@code ToolSpec} would alias whatever the
 * caller passed in — and the codec grafts this node straight into the request tree it builds.
 * Nothing aliases it today only because every {@code inputSchema()} happens to return a fresh
 * node; that is safe by luck rather than by construction.
 */
public record ToolSpec(String name, String description, ObjectNode schema) {

    public ToolSpec {
        schema = schema == null ? null : schema.deepCopy();
    }
}
