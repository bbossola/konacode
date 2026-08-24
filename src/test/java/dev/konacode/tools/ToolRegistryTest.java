package dev.konacode.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    /** A tool that does nothing, used to exercise registry behavior only. */
    private record StubTool(String name) implements Tool {
        @Override
        public String description() {
            return "stub";
        }

        @Override
        public ObjectNode inputSchema() {
            return Schemas.object().build();
        }

        @Override
        public ToolResult execute(JsonNode args) {
            return ToolResult.ok("stub");
        }

        @Override
        public boolean stopsOnInterrupt() {
            return false;
        }

        @Override
        public Effect effect(JsonNode args) {
            return Effect.READS_INSIDE;
        }
    }

    @Test
    void looksUpARegisteredToolByName() {
        ToolRegistry registry = ToolRegistry.of(new StubTool("alpha"), new StubTool("beta"));

        assertTrue(registry.lookup("alpha").isPresent());
        assertEquals("alpha", registry.lookup("alpha").orElseThrow().name());
    }

    @Test
    void returnsEmptyForAnUnknownName() {
        ToolRegistry registry = ToolRegistry.of(new StubTool("alpha"));

        assertTrue(registry.lookup("nope").isEmpty());
    }

    @Test
    void preservesRegistrationOrderWhenEnumerating() {
        ToolRegistry registry = ToolRegistry.of(new StubTool("b"), new StubTool("a"));

        assertEquals(List.of("b", "a"), registry.all().stream().map(Tool::name).toList());
    }

    @Test
    void rejectsDuplicateToolNames() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class,
                () -> ToolRegistry.of(new StubTool("alpha"), new StubTool("alpha")));

        assertTrue(thrown.getMessage().contains("alpha"));
    }
}
