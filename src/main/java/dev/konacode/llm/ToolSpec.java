package dev.konacode.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** What a provider advertises to the model so it knows a tool exists. */
public record ToolSpec(String name, String description, ObjectNode schema) {
}
