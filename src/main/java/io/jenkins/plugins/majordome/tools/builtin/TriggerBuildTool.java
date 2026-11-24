package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.PasswordParameterDefinition;
import hudson.model.SimpleParameterDefinition;
import io.jenkins.plugins.majordome.tools.Args;
import io.jenkins.plugins.majordome.tools.Lookup;
import io.jenkins.plugins.majordome.tools.Schema;
import io.jenkins.plugins.majordome.tools.ToolException;
import io.jenkins.plugins.majordome.tools.WriteTool;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import jenkins.model.ParameterizedJobMixIn;

@Extension
public class TriggerBuildTool extends WriteTool {

    @Override
    public String getName() {
        return "trigger_build";
    }

    @Override
    public String getDescription() {
        return "Start a build of a job, optionally with parameter values (strings). Parameters not given"
                + " use their defaults. Password parameters cannot be set. Use get_job first to learn the"
                + " parameters.";
    }

    @Override
    public ObjectNode getInputSchema() {
        return Schema.object()
                .string("job", "Full name of the job", true)
                .stringMap("parameters", "Parameter values by name, as strings", false)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Jenkins.READ);
    }

    private record Plan(ParameterizedJobMixIn.ParameterizedJob<?, ?> job, List<ParameterValue> values,
            Map<String, String> shown) {}

    private Plan plan(JsonNode args) throws ToolException {
        Job<?, ?> job = Lookup.job(Args.requireString(args, "job"));
        if (!(job instanceof ParameterizedJobMixIn.ParameterizedJob<?, ?> pj)) {
            throw new ToolException("'" + job.getFullName() + "' cannot be started this way");
        }
        job.checkPermission(Item.BUILD);
        if (!job.isBuildable()) {
            throw new ToolException("'" + job.getFullName() + "' is not buildable (disabled?)");
        }

        Map<String, String> requested = new LinkedHashMap<>();
        JsonNode params = args.get("parameters");
        if (params != null && !params.isNull()) {
            if (!params.isObject()) {
                throw new ToolException("'parameters' must be an object of name → string value");
            }
            for (Iterator<Map.Entry<String, JsonNode>> it = params.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                requested.put(e.getKey(), e.getValue().isValueNode() ? e.getValue().asText() : e.getValue().toString());
            }
        }

        List<ParameterValue> values = new ArrayList<>();
        Map<String, String> shown = new LinkedHashMap<>();
        ParametersDefinitionProperty defs = job.getProperty(ParametersDefinitionProperty.class);
        if (defs == null) {
            if (!requested.isEmpty()) {
                throw new ToolException("'" + job.getFullName() + "' has no parameters");
            }
            return new Plan(pj, values, shown);
        }
        for (String name : requested.keySet()) {
            if (defs.getParameterDefinition(name) == null) {
                throw new ToolException("Unknown parameter '" + name + "'. Known: "
                        + defs.getParameterDefinitionNames());
            }
        }
        for (ParameterDefinition def : defs.getParameterDefinitions()) {
            String raw = requested.get(def.getName());
            ParameterValue value;
            if (raw != null) {
                if (def instanceof PasswordParameterDefinition) {
                    throw new ToolException("Password parameter '" + def.getName() + "' cannot be set by the assistant");
                }
                if (!(def instanceof SimpleParameterDefinition simple)) {
                    throw new ToolException("Parameter '" + def.getName() + "' (" + def.getClass().getSimpleName()
                            + ") cannot be set by the assistant; omit it to use its default");
                }
                try {
                    value = simple.createValue(raw);
                } catch (IllegalArgumentException e) {
                    throw new ToolException("Invalid value for '" + def.getName() + "': " + e.getMessage());
                }
                shown.put(def.getName(), raw);
            } else {
                value = def.getDefaultParameterValue();
                shown.put(def.getName(), value == null ? "(no default)"
                        : value.isSensitive() ? "(default, hidden)" : "(default) " + value.getValue());
            }
            if (value != null) {
                values.add(value);
            }
        }
        return new Plan(pj, values, shown);
    }

    @Override
    public String preview(JsonNode args) throws Exception {
        Plan plan = plan(args);
        String name = ((Job<?, ?>) plan.job()).getFullName();
        if (plan.shown().isEmpty()) {
            return "Start a build of '" + name + "'";
        }
        return "Start a build of '" + name + "' with parameters: "
                + plan.shown().entrySet().stream()
                        .map(e -> e.getKey() + " = " + e.getValue())
                        .collect(Collectors.joining(", "));
    }

    @Override
    public String perform(JsonNode args) throws Exception {
        Plan plan = plan(args);
        List<Action> actions = new ArrayList<>();
        actions.add(new CauseAction(new Cause.UserIdCause()));
        if (!plan.values().isEmpty()) {
            actions.add(new ParametersAction(plan.values()));
        }
        var future = plan.job().scheduleBuild2(0, actions.toArray(new Action[0]));
        String name = ((Job<?, ?>) plan.job()).getFullName();
        if (future == null) {
            return "Jenkins did not schedule a new build of '" + name + "' (an identical build may already be queued)";
        }
        return "Build of '" + name + "' scheduled";
    }
}
