package io.jenkins.plugins.majordome.llm;

/** A model call failed (network, authentication, rate limit, malformed response…). */
public class LlmException extends Exception {

    private static final long serialVersionUID = 1L;

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
