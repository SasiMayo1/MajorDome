package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.User;
import hudson.slaves.OfflineCause;
import io.jenkins.plugins.majordome.tools.Args;
import io.jenkins.plugins.majordome.tools.Lookup;
import io.jenkins.plugins.majordome.tools.Schema;
import io.jenkins.plugins.majordome.tools.ToolException;
import io.jenkins.plugins.majordome.tools.WriteTool;
import java.util.Objects;
import jenkins.model.Jenkins;

/** Users allowed to disconnect agents (usually administrators). */
@Extension
public class SetNodeOfflineTool extends WriteTool {

    @Override
    public String getName() {
        return "set_node_offline";
    }

    @Override
    public String getDescription() {
        return "Mark a node temporarily offline (it finishes running builds but takes no new ones),"
                + " or bring it back online.";
    }

    @Override
    public ObjectNode getInputSchema() {
        return Schema.object()
                .string("node", "Node name as listed by list_nodes ('built-in' for the controller)", true)
                .bool("offline", "true to take the node offline, false to bring it back", true)
                .string("reason", "Why the node is taken offline (shown in Jenkins)", false)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Computer.DISCONNECT);
    }

    private Computer target(JsonNode args, boolean offline) throws ToolException {
        Computer computer = Lookup.computer(Args.requireString(args, "node"));
        computer.checkPermission(offline ? Computer.DISCONNECT : Computer.CONNECT);
        if (computer.isTemporarilyOffline() == offline) {
            throw new ToolException("Node '" + Lookup.nodeName(computer) + "' is already "
                    + (offline ? "temporarily offline" : "not temporarily offline"));
        }
        return computer;
    }

    @Override
    public String preview(JsonNode args) throws Exception {
        boolean offline = Args.requireBool(args, "offline");
        Computer computer = target(args, offline);
        String reason = Args.optString(args, "reason");
        return offline
                ? "Take node '" + Lookup.nodeName(computer) + "' temporarily offline"
                        + (reason == null ? "" : " (reason: " + reason + ")")
                : "Bring node '" + Lookup.nodeName(computer) + "' back online";
    }

    @Override
    @SuppressWarnings("deprecation")
    public String perform(JsonNode args) throws Exception {
        boolean offline = Args.requireBool(args, "offline");
        Computer computer = target(args, offline);
        String reason = Objects.requireNonNullElse(Args.optString(args, "reason"), "Requested via MajorDome");
        if (offline) {
            computer.setTemporarilyOffline(true, new OfflineCause.UserCause(User.current(), reason));
        } else {
            computer.setTemporarilyOffline(false, null);
        }
        return "Node '" + Lookup.nodeName(computer) + "' is now " + (offline ? "temporarily offline" : "back online");
    }
}
