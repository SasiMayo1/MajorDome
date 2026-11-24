package io.jenkins.plugins.majordome.tools;

import io.jenkins.plugins.majordome.llm.ToolSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Builds the tool set for the current user. */
public final class ToolRegistry {

    private static final Logger LOGGER = Logger.getLogger(ToolRegistry.class.getName());

    static final String WRITE_NOTE = " This action is NOT executed when you call it: it creates a proposal"
            + " that the user must confirm in the chat. Tell the user what you proposed and why.";

    private ToolRegistry() {}

    /**
     * Tools the current user may use, keyed and sorted by name. A stable order keeps the tool list
     * byte-identical between requests so providers can cache it.
     */
    public static Map<String, AgentTool> availableToCurrentUser() {
        Map<String, AgentTool> tools = new TreeMap<>();
        for (AgentTool tool : AgentTool.all()) {
            try {
                if (tool.isAvailable()) {
                    AgentTool previous = tools.putIfAbsent(tool.getName(), tool);
                    if (previous != null) {
                        LOGGER.warning("Duplicate MajorDome tool name '" + tool.getName() + "' from "
                                + tool.getClass().getName() + "; keeping " + previous.getClass().getName());
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Tool " + tool.getClass().getName() + " failed its availability check", e);
            }
        }
        return Collections.unmodifiableMap(tools);
    }

    public static List<ToolSpec> specs(Map<String, AgentTool> tools) {
        List<ToolSpec> specs = new ArrayList<>(tools.size());
        for (AgentTool tool : tools.values()) {
            String description = tool.getDescription();
            if (tool instanceof WriteTool) {
                description = description + WRITE_NOTE;
            }
            specs.add(new ToolSpec(tool.getName(), description, tool.getInputSchema()));
        }
        return specs;
    }
}
