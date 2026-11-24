package io.jenkins.plugins.majordome.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** A tool invocation requested by the model. {@code input} is model-controlled and must be validated. */
public record ToolCall(String id, String name, JsonNode input) {}
