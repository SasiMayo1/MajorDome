package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.Executor;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.majordome.tools.Args;
import io.jenkins.plugins.majordome.tools.Lookup;
import io.jenkins.plugins.majordome.tools.Schema;
import io.jenkins.plugins.majordome.tools.ToolException;
import io.jenkins.plugins.majordome.tools.WriteTool;
import jenkins.model.CauseOfInterruption;
import jenkins.model.Jenkins;

@Extension
public class AbortBuildTool extends WriteTool {

    @Override
    public String getName() {
        return "abort_build";
    }

    @Override
    public String getDescription() {
        return "Abort a running build.";
    }

    @Override
    public ObjectNode getInputSchema() {
        return Schema.object()
                .string("job", "Full name of the job", true)
                .integer("number", "Number of the running build", true)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Jenkins.READ);
    }

    private Run<?, ?> target(JsonNode args) throws ToolException {
        Job<?, ?> job = Lookup.job(Args.requireString(args, "job"));
        Integer number = Args.optInt(args, "number");
        if (number == null) {
            throw new ToolException("Missing required argument 'number'");
        }
        Run<?, ?> run = Lookup.run(job, number);
        job.checkPermission(Item.CANCEL);
        if (!run.isBuilding()) {
            throw new ToolException(run.getFullDisplayName() + " is not running");
        }
        return run;
    }

    @Override
    public String preview(JsonNode args) throws Exception {
        return "Abort running build " + target(args).getFullDisplayName();
    }

    @Override
    public String perform(JsonNode args) throws Exception {
        Run<?, ?> run = target(args);
        Executor executor = run.getExecutor();
        if (executor == null) {
            throw new ToolException("Cannot find the executor of " + run.getFullDisplayName());
        }
        executor.interrupt(Result.ABORTED,
                new CauseOfInterruption.UserInterruption(Jenkins.getAuthentication2().getName()));
        return "Abort requested for " + run.getFullDisplayName();
    }
}
