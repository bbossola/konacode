package dev.konacode.llm;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ToolSpecTest {

    @Test
    void deepCopiesTheSchemaSoLaterMutationCannotReachIt() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("type", "object");

        ToolSpec spec = new ToolSpec("read_file", "Read a file.", schema);
        schema.put("type", "tampered");
        schema.putObject("properties").putObject("injected");

        assertEquals("object", spec.schema().get("type").asText());
        assertFalse(spec.schema().has("properties"));
    }
}
