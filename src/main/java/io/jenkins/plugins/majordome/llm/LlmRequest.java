package io.jenkins.plugins.majordome.llm;

import java.util.List;

/**
 * One request to a model. {@code system} must stay identical across requests of a conversation so
 * providers can cache it; volatile context (user, current page) travels in the user message.
 */
public record LlmRequest(String system, List<ChatMessage> messages, List<ToolSpec> tools) {

    public LlmRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
