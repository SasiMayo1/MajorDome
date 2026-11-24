package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Node;
import io.jenkins.plugins.majordome.tools.Lookup;
import io.jenkins.plugins.majordome.tools.ReadTool;
import io.jenkins.plugins.majordome.util.Json;
import jenkins.model.Jenkins;

@Extension
public class ListNodesTool extends ReadTool {

    @Override
    public String getName() {
        return "list_nodes";
    }

    @Override
    public String getDescription() {
        return "List build nodes (agents and the built-in node): online/offline with reason,"
                + " executors and how many are busy, labels.";
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Jenkins.READ);
    }

    @Override
    public String execute(JsonNode args) {
        ObjectNode out = Json.object();
        ArrayNode nodes = out.putArray("nodes");
        for (Computer computer : Jenkins.get().getComputers()) {
            if (!computer.hasPermission(Computer.EXTENDED_READ) && !computer.hasPermission(Jenkins.READ)) {
                continue;
            }
            ObjectNode n = nodes.addObject();
            n.put("name", Lookup.nodeName(computer));
            n.put("online", computer.isOnline());
            n.put("temporarilyOffline", computer.isTemporarilyOffline());
            if (computer.isOffline()) {
                n.put("offlineReason", computer.getOfflineCauseReason());
            }
            n.put("executors", computer.getNumExecutors());
            n.put("busyExecutors", computer.countBusy());
            Node node = computer.getNode();
            if (node != null) {
                n.put("labels", node.getLabelString());
            }
        }
        return Json.write(out);
    }
}
