package io.jenkins.plugins.majordome.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.ExtensionList;
import hudson.ExtensionPoint;

/**
 * A capability the assistant can use. Do not extend directly: extend {@link ReadTool} or {@link
 * WriteTool}. Other plugins can contribute tools with {@code @Extension}.
 *
 * <p>Every method runs in the security context of the user who is chatting (or approving), so
 * implementations use ordinary Jenkins permission checks ({@code checkPermission}, {@code
 * getItemByFullName} visibility…) and must never escalate with {@code ACL.SYSTEM2}.
 */
public abstract sealed class AgentTool implements ExtensionPoint permits ReadTool, WriteTool {

    /** Unique tool name sent to the model: lowercase snake_case. */
    public abstract String getName();

    /** What the tool does and when to use it, written for the model. */
    public abstract String getDescription();

    /** JSON Schema (type object) of the arguments. */
    public ObjectNode getInputSchema() {
        return Schema.object().build();
    }

    /**
     * Whether the current user may see this tool at all. Tools the user cannot use are not even sent
     * to the model. Finer per-object checks still happen when the tool runs.
     */
    public abstract boolean isAvailable();

    public static ExtensionList<AgentTool> all() {
        return ExtensionList.lookup(AgentTool.class);
    }
}
