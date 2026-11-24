package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Job;
import io.jenkins.plugins.majordome.tools.Args;
import io.jenkins.plugins.majordome.tools.Lookup;
import io.jenkins.plugins.majordome.tools.Schema;
import io.jenkins.plugins.majordome.tools.ToolException;
import io.jenkins.plugins.majordome.tools.WriteTool;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

@Extension
public class SetJobEnabledTool extends WriteTool {

    @Override
    public String getName() {
        return "set_job_enabled";
    }

    @Override
    public String getDescription() {
        return "Enable or disable a job. A disabled job cannot be built.";
    }

    @Override
    public ObjectNode getInputSchema() {
        return Schema.object()
                .string("job", "Full name of the job", true)
                .bool("enabled", "true to enable, false to disable", true)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Jenkins.READ);
    }

    private ParameterizedJobMixIn.ParameterizedJob<?, ?> target(JsonNode args) throws ToolException {
        Job<?, ?> job = Lookup.job(Args.requireString(args, "job"));
        if (!(job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> pj) || !pj.supportsMakeDisabled()) {
            throw new ToolException("'" + job.getFullName() + "' cannot be enabled or disabled");
        }
        job.checkPermission(Item.CONFIGURE);
        return pj;
    }

    @Override
    public String preview(JsonNode args) throws Exception {
        ParameterizedJobMixIn.ParameterizedJob<?, ?> job = target(args);
        boolean enable = Args.requireBool(args, "enabled");
        String name = ((Job<?, ?>) job).getFullName();
        if (job.isDisabled() != enable) {
            throw new ToolException("'" + name + "' is already " + (enable ? "enabled" : "disabled"));
        }
        return (enable ? "Enable" : "Disable") + " job '" + name + "'";
    }

    @Override
    public String perform(JsonNode args) throws Exception {
        ParameterizedJobMixIn.ParameterizedJob<?, ?> job = target(args);
        boolean enable = Args.requireBool(args, "enabled");
        job.makeDisabled(!enable);
        return "Job '" + ((Job<?, ?>) job).getFullName() + "' " + (enable ? "enabled" : "disabled");
    }
}
