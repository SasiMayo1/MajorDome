package io.jenkins.plugins.majordome.llm;

/** Result of one model call: the assistant message to append to the history, and why it stopped. */
public record LlmResponse(ChatMessage message, StopReason stopReason, long inputTokens, long outputTokens) {

    public enum StopReason {
        END_TURN,
        TOOL_USE,
        MAX_TOKENS,
        /** The model or its safety systems declined the request. */
        REFUSAL,
        OTHER
    }
}
