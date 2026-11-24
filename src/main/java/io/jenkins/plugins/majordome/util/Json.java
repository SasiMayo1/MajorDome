package io.jenkins.plugins.majordome.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Shared Jackson helpers. We deliberately avoid {@code net.sf.json}, which re-parses JSON-looking strings. */
public final class Json {

    public static final ObjectMapper MAPPER =
            new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private Json() {}

    public static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    public static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize value to JSON", e);
        }
    }

    public static JsonNode parse(String json) throws JsonProcessingException {
        return MAPPER.readTree(json);
    }
}
