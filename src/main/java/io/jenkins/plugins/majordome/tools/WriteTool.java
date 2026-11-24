package io.jenkins.plugins.majordome.tools;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool that changes something. The model can only <em>propose</em> it: the agent loop calls
 * {@link #preview} and stores a pending action; {@link #perform} runs only after a human confirms,
 * as the confirming user. No code path in the agent loop calls {@link #perform}.
 */
public abstract non-sealed class WriteTool extends AgentTool {

    /**
     * Validates the arguments and the current user's permissions and returns an exact,
     * human-readable description of what {@link #perform} would do. Must not change anything.
     * Called again at confirmation time, so it also re-checks that the change is still valid.
     */
    public abstract String preview(JsonNode args) throws Exception;

    /** Applies the change as the current user and returns a short result for the user and model. */
    public abstract String perform(JsonNode args) throws Exception;
}
