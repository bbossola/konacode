package dev.konacode.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Builds tool input schemas without repeating Jackson boilerplate in every tool. */
public final class Schemas {

    private Schemas() {
    }

    public static Builder object() {
        return new Builder();
    }

    public static final class Builder {

        private final ObjectNode schema = JsonNodeFactory.instance.objectNode();
        private final ObjectNode properties;
        private final ArrayNode required;

        private Builder() {
            schema.put("type", "object");
            properties = schema.putObject("properties");
            required = JsonNodeFactory.instance.arrayNode();
        }

        public Builder requiredString(String name, String description) {
            optionalString(name, description);
            required.add(name);
            return this;
        }

        public Builder optionalString(String name, String description) {
            properties.putObject(name)
                    .put("type", "string")
                    .put("description", description);
            return this;
        }

        /** One array of objects. The item schema comes from a second {@link Schemas#object()}. */
        public Builder requiredArray(String name, String description, ObjectNode items) {
            ObjectNode array = properties.putObject(name);
            array.put("type", "array");
            array.put("description", description);
            array.set("items", items);
            required.add(name);
            return this;
        }

        public ObjectNode build() {
            if (!required.isEmpty()) {
                schema.set("required", required);
            }
            return schema;
        }
    }
}
