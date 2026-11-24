package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.majordome.tools.Args;
import io.jenkins.plugins.majordome.tools.ReadTool;
import io.jenkins.plugins.majordome.tools.Schema;
import io.jenkins.plugins.majordome.util.Json;
import java.util.Locale;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

@Extension
public class ListJobsTool extends ReadTool {

    @Override
    public String getName() {
        return "list_jobs";
    }

    @Override
    public String getDescription() {
        return "List the jobs visible to the user (full name, last result, whether building or disabled)."
                + " Optionally filter by a case-insensitive substring of the full name, or by last result.";
    }

    @Override
    public ObjectNode getInputSchema() {
        return Schema.object()
                .string("filter", "Substring of the job full name, e.g. 'backend' or 'team-a/'", false)
                .string("lastResult", "Only jobs whose last completed build has this result: SUCCESS, UNSTABLE, FAILURE, ABORTED", false)
                .integer("limit", "Maximum number of jobs to return (default 50, max 200)", false)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Jenkins.READ);
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        String filter = Args.optString(args, "filter");
        String lastResult = Args.optString(args, "lastResult");
        int limit = Args.intInRange(args, "limit", 50, 1, 200);
        String needle = filter == null ? null : filter.toLowerCase(Locale.ROOT);

        ObjectNode out = Json.object();
        ArrayNode jobs = out.putArray("jobs");
        int matched = 0;
        // getAllItems only returns items the current user can read.
        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            if (needle != null && !job.getFullName().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            Run<?, ?> last = job.getLastCompletedBuild();
            Result result = last == null ? null : last.getResult();
            if (lastResult != null && (result == null || !result.toString().equalsIgnoreCase(lastResult))) {
                continue;
            }
            matched++;
            if (jobs.size() >= limit) {
                continue;
            }
            ObjectNode j = jobs.addObject();
            j.put("fullName", job.getFullName());
            j.put("type", job.getClass().getSimpleName());
            j.put("lastResult", result == null ? null : result.toString());
            j.put("lastBuild", last == null ? null : last.getNumber());
            j.put("building", job.isBuilding());
            if (job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> pj) {
                j.put("disabled", pj.isDisabled());
            }
        }
        out.put("matched", matched);
        out.put("truncated", matched > jobs.size());
        return Json.write(out);
    }
}
