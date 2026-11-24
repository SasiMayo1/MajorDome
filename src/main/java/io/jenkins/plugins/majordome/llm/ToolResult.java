package io.jenkins.plugins.majordome.llm;

/** The result we send back to the model for one {@link ToolCall}. */
public record ToolResult(String toolCallId, String content, boolean error) {}
