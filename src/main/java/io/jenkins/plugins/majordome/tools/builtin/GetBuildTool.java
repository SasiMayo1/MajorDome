package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import io.jenkins.plugins.majordome.tools.Args;
import io.jenkins.plugins.majordome.tools.Lookup;
import io.jenkins.plugins.majordome.tools.ReadTool;
import io.jenkins.plugins.majordome.tools.Schema;
import io.jenkins.plugins.majordome.util.Json;
import jenkins.model.Jenkins;

@Extension
public class GetBuildTool extends ReadTool {

    @Override
    public String getName() {
        return "get_build";
    }

    @Override
    public String getDescription() {
        return "Get details of one build: result, duration, causes, parameters (secrets masked),"
                + " test results with failed tests, and SCM changes. Defaults to the last build.";
    }

    @Override
    public ObjectNode getInputSchema() {
        return Schema.object()
                .string("job", "Full name of the job", true)
                .integer("number", "Build number; omit for the last build", false)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Jenkins.READ);
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        Job<?, ?> job = Lookup.job(Args.requireString(args, "job"));
        Run<?, ?> run = Lookup.run(job, Args.optInt(args, "number"));
        return Json.write(BuildInfo.details(run));
    }
}
