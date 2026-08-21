package dev.konacode.agent;

import dev.konacode.llm.ToolSpec;
import dev.konacode.tools.Tool;
import dev.konacode.tools.ToolRegistry;

import java.util.List;

/**
 * The one place {@code tools} and {@code llm} meet. Keeping the translation here is what lets a
 * tool be written without importing anything from the provider layer.
 */
public final class ToolSpecs {

    private ToolSpecs() {
    }

    public static List<ToolSpec> from(ToolRegistry registry) {
        return registry.all().stream()
                .map(ToolSpecs::from)
                .toList();
    }

    public static ToolSpec from(Tool tool) {
        return new ToolSpec(tool.name(), tool.description(), tool.inputSchema());
    }
}
