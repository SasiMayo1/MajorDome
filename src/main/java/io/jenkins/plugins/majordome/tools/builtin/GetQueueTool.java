package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.Queue;
import io.jenkins.plugins.majordome.tools.ReadTool;
import io.jenkins.plugins.majordome.util.Json;
import java.time.Instant;
import jenkins.model.Jenkins;

@Extension
public class GetQueueTool extends ReadTool {

    @Override
    public String getName() {
        return "get_queue";
    }

    @Override
    public String getDescription() {
        return "List builds waiting in the queue and why each one is waiting (e.g. no free executor,"
                + " waiting for a label, blocked by another build).";
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Jenkins.READ);
    }

    @Override
    public String execute(JsonNode args) {
        ObjectNode out = Json.object();
        ArrayNode items = out.putArray("items");
        // Queue.getItems() only returns items the current user can see.
        for (Queue.Item item : Jenkins.get().getQueue().getItems()) {
            ObjectNode i = items.addObject();
            i.put("id", item.getId());
            i.put("task", item.task.getFullDisplayName());
            i.put("why", item.getWhy());
            i.put("inQueueSince", Instant.ofEpochMilli(item.getInQueueSince()).toString());
            i.put("stuck", item.isStuck());
            i.put("blocked", item.isBlocked());
        }
        return Json.write(out);
    }
}
