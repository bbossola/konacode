package dev.konacode.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SchemasTest {

    @Test
    void buildsAnObjectSchemaWithRequiredAndOptionalStrings() {
        ObjectNode schema = Schemas.object()
                .requiredString("path", "Where to look.")
                .optionalString("depth", "How deep.")
                .build();

        assertEquals("object", schema.get("type").asText());
        assertEquals("string", schema.get("properties").get("path").get("type").asText());
        assertEquals("Where to look.", schema.get("properties").get("path").get("description").asText());
        assertEquals("string", schema.get("properties").get("depth").get("type").asText());
        assertEquals(1, schema.get("required").size());
        assertEquals("path", schema.get("required").get(0).asText());
    }

    @Test
    void omitsRequiredEntirelyWhenNothingIsRequired() {
        ObjectNode schema = Schemas.object().optionalString("path", "Optional.").build();

        assertFalse(schema.has("required"));
    }
}
