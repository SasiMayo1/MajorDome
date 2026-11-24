package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.PluginWrapper;
import hudson.model.UpdateSite;
import io.jenkins.plugins.majordome.tools.Args;
import io.jenkins.plugins.majordome.tools.ReadTool;
import io.jenkins.plugins.majordome.tools.Schema;
import io.jenkins.plugins.majordome.util.Json;
import java.util.Comparator;
import java.util.List;
import jenkins.model.Jenkins;

/** Administrators only. */
@Extension
public class ListPluginsTool extends ReadTool {

    @Override
    public String getName() {
        return "list_plugins";
    }

    @Override
    public String getDescription() {
        return "Administrator view of installed plugins with versions, active state and available updates.";
    }

    @Override
    public ObjectNode getInputSchema() {
        return Schema.object()
                .bool("onlyWithUpdates", "Only list plugins that have an update available", false)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Jenkins.SYSTEM_READ);
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        Jenkins.get().checkPermission(Jenkins.SYSTEM_READ);
        boolean onlyUpdates = Boolean.TRUE.equals(Args.optBool(args, "onlyWithUpdates"));

        List<PluginWrapper> plugins = Jenkins.get().getPluginManager().getPlugins().stream()
                .sorted(Comparator.comparing(PluginWrapper::getShortName))
                .toList();
        ObjectNode out = Json.object();
        ArrayNode list = out.putArray("plugins");
        for (PluginWrapper p : plugins) {
            UpdateSite.Plugin update = p.getUpdateInfo();
            if (onlyUpdates && update == null) {
                continue;
            }
            ObjectNode o = list.addObject();
            o.put("name", p.getShortName());
            o.put("version", p.getVersion());
            o.put("active", p.isActive());
            if (update != null) {
                o.put("updateVersion", update.version);
            }
        }
        return Json.write(out);
    }
}
