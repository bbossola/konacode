package dev.konacode.policy;

import com.fasterxml.jackson.databind.JsonNode;
import dev.konacode.tools.Tool;

/** Consulted before every tool execution. */
public interface ToolPolicy {

    Decision check(Tool tool, JsonNode args);
}
