package io.jenkins.plugins.majordome.llm;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** Provider-neutral tool declaration. {@code inputSchema} is a JSON Schema of type object. */
public record ToolSpec(String name, String description, ObjectNode inputSchema) {}
