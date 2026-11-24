package io.jenkins.plugins.majordome.tools;

import com.fasterxml.jackson.databind.JsonNode;

/** A tool that only reads state. It runs as soon as the model calls it, as the current user. */
public abstract non-sealed class ReadTool extends AgentTool {

    /**
     * Returns the result for the model, usually compact JSON. Throw {@link ToolException} for
     * expected failures (not found, bad argument); its message is shown to the model.
     */
    public abstract String execute(JsonNode args) throws Exception;
}
