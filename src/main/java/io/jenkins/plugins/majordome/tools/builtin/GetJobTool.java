package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.HealthReport;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.Run;
import io.jenkins.plugins.majordome.tools.Args;
import io.jenkins.plugins.majordome.tools.Lookup;
import io.jenkins.plugins.majordome.tools.ReadTool;
import io.jenkins.plugins.majordome.tools.Schema;
import io.jenkins.plugins.majordome.util.Json;
import io.jenkins.plugins.majordome.util.Text;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

@Extension
public class GetJobTool extends ReadTool {

    @Override
    public String getName() {
        return "get_job";
    }

    @Override
    public String getDescription() {
        return "Get details of a job: description, health, whether it is buildable or disabled,"
                + " its build parameters with defaults, and its most recent builds.";
    }

    @Override
    public ObjectNode getInputSchema() {
        return Schema.object()
                .string("job", "Full name of the job, e.g. 'folder/my-pipeline'", true)
                .integer("recentBuilds", "How many recent builds to include (default 10, max 50)", false)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Jenkins.READ);
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        Job<?, ?> job = Lookup.job(Args.requireString(args, "job"));
        int recent = Args.intInRange(args, "recentBuilds", 10, 1, 50);

        ObjectNode out = Json.object();
        out.put("fullName", job.getFullName());
        out.put("type", job.getClass().getSimpleName());
        out.put("description", Text.truncate(job.getDescription(), 1000));
        out.put("buildable", job.isBuildable());
        if (job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> pj) {
            out.put("disabled", pj.isDisabled());
        }
        out.put("inQueue", job.isInQueue());
        HealthReport health = job.getBuildHealth();
        if (health != null) {
            out.put("healthScore", health.getScore());
            out.put("health", health.getDescription());
        }

        ParametersDefinitionProperty params = job.getProperty(ParametersDefinitionProperty.class);
        if (params != null) {
            ArrayNode defs = out.putArray("parameters");
            for (ParameterDefinition def : params.getParameterDefinitions()) {
                ObjectNode d = defs.addObject();
                d.put("name", def.getName());
                d.put("type", def.getClass().getSimpleName());
                d.put("description", Text.truncate(def.getDescription(), 300));
                if (!(def instanceof PasswordParameterDefinition)) {
                    ParameterValue defaultValue = def.getDefaultParameterValue();
                    if (defaultValue != null && !defaultValue.isSensitive()) {
                        d.put("default", Text.truncate(String.valueOf(defaultValue.getValue()), 300));
                    }
                }
            }
        }

        ArrayNode builds = out.putArray("recentBuilds");
        int count = 0;
        for (Run<?, ?> run : job.getBuilds()) {
            if (count++ >= recent) {
                break;
            }
            builds.add(BuildInfo.summary(run));
        }
        return Json.write(out);
    }
}
