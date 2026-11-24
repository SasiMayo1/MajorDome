package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.PluginWrapper;
import hudson.model.Computer;
import io.jenkins.plugins.majordome.tools.ReadTool;
import io.jenkins.plugins.majordome.util.Json;
import jenkins.model.Jenkins;

/** Administrators only (Overall/SystemRead, implied by Overall/Administer). */
@Extension
public class GetSystemInfoTool extends ReadTool {

    @Override
    public String getName() {
        return "get_system_info";
    }

    @Override
    public String getDescription() {
        return "Administrator overview of the controller: Jenkins and Java versions, memory, nodes and"
                + " executors, queue length, quiet-down state, plugin counts and pending plugin updates.";
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Jenkins.SYSTEM_READ);
    }

    @Override
    public String execute(JsonNode args) {
        Jenkins jenkins = Jenkins.get();
        jenkins.checkPermission(Jenkins.SYSTEM_READ);
        Runtime rt = Runtime.getRuntime();

        int executors = 0;
        int busy = 0;
        int offline = 0;
        for (Computer c : jenkins.getComputers()) {
            executors += c.getNumExecutors();
            busy += c.countBusy();
            if (c.isOffline()) {
                offline++;
            }
        }
        int plugins = 0;
        int updates = 0;
        int inactive = 0;
        for (PluginWrapper p : jenkins.getPluginManager().getPlugins()) {
            plugins++;
            if (p.hasUpdate()) {
                updates++;
            }
            if (!p.isActive()) {
                inactive++;
            }
        }

        ObjectNode out = Json.object();
        out.put("jenkinsVersion", Jenkins.VERSION);
        out.put("rootUrl", jenkins.getRootUrl());
        out.put("javaVersion", System.getProperty("java.version"));
        out.put("javaVendor", System.getProperty("java.vendor"));
        out.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        out.put("heapUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        out.put("heapMaxMb", rt.maxMemory() / (1024 * 1024));
        out.put("availableProcessors", rt.availableProcessors());
        out.put("nodes", jenkins.getComputers().length);
        out.put("offlineNodes", offline);
        out.put("executors", executors);
        out.put("busyExecutors", busy);
        out.put("queueLength", jenkins.getQueue().getItems().length);
        out.put("quietingDown", jenkins.isQuietingDown());
        out.put("plugins", plugins);
        out.put("pluginsWithUpdates", updates);
        out.put("inactivePlugins", inactive);
        return Json.write(out);
    }
}
