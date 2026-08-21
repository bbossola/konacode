package dev.konacode.tools;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** The set of tools an agent may call, keyed by the name the model uses. */
public final class ToolRegistry {

    private final Map<String, Tool> byName;

    private ToolRegistry(Map<String, Tool> byName) {
        this.byName = byName;
    }

    public static ToolRegistry of(Tool... tools) {
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : tools) {
            if (byName.putIfAbsent(tool.name(), tool) != null) {
                throw new IllegalArgumentException("Duplicate tool name: " + tool.name());
            }
        }
        return new ToolRegistry(Collections.unmodifiableMap(byName));
    }

    public Optional<Tool> lookup(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** Registration order, which is the order tools are advertised to the model. */
    public Collection<Tool> all() {
        return byName.values();
    }
}
