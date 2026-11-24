package io.jenkins.plugins.majordome.tools;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.majordome.util.Json;

/** Small builder for the JSON Schemas of tool arguments. */
public final class Schema {

    private final ObjectNode root = Json.object();
    private final ObjectNode properties;
    private final ArrayNode required;

    private Schema() {
        root.put("type", "object");
        properties = root.putObject("properties");
        required = root.putArray("required");
    }

    public static Schema object() {
        return new Schema();
    }

    public Schema string(String name, String description, boolean isRequired) {
        return prop(name, "string", description, isRequired);
    }

    public Schema integer(String name, String description, boolean isRequired) {
        return prop(name, "integer", description, isRequired);
    }

    public Schema bool(String name, String description, boolean isRequired) {
        return prop(name, "boolean", description, isRequired);
    }

    /** An object whose values are strings, e.g. build parameters. */
    public Schema stringMap(String name, String description, boolean isRequired) {
        ObjectNode p = properties.putObject(name);
        p.put("type", "object");
        p.put("description", description);
        p.putObject("additionalProperties").put("type", "string");
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    private Schema prop(String name, String type, String description, boolean isRequired) {
        properties.putObject(name).put("type", type).put("description", description);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    public ObjectNode build() {
        return root.deepCopy();
    }
}
