package io.jenkins.plugins.majordome.tools;

import com.fasterxml.jackson.databind.JsonNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;

/** Validation of model-supplied tool arguments. Never trust them. */
public final class Args {

    private Args() {}

    public static String requireString(JsonNode args, String name) throws ToolException {
        String value = optString(args, name);
        if (value == null) {
            throw new ToolException("Missing required argument '" + name + "'");
        }
        return value;
    }

    @CheckForNull
    public static String optString(JsonNode args, String name) throws ToolException {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new ToolException("Argument '" + name + "' must be a string");
        }
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    @CheckForNull
    public static Integer optInt(JsonNode args, String name) throws ToolException {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber()) {
            return node.asInt();
        }
        if (node.isTextual() && node.asText().trim().matches("-?\\d{1,9}")) {
            return Integer.valueOf(node.asText().trim());
        }
        throw new ToolException("Argument '" + name + "' must be an integer");
    }

    public static int intInRange(JsonNode args, String name, int def, int min, int max) throws ToolException {
        Integer value = optInt(args, name);
        if (value == null) {
            return def;
        }
        return Math.max(min, Math.min(max, value));
    }

    public static boolean requireBool(JsonNode args, String name) throws ToolException {
        Boolean value = optBool(args, name);
        if (value == null) {
            throw new ToolException("Missing required argument '" + name + "'");
        }
        return value;
    }

    @CheckForNull
    public static Boolean optBool(JsonNode args, String name) throws ToolException {
        JsonNode node = args == null ? null : args.get(name);
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isTextual() && (node.asText().equals("true") || node.asText().equals("false"))) {
            return Boolean.valueOf(node.asText());
        }
        throw new ToolException("Argument '" + name + "' must be a boolean");
    }
}
