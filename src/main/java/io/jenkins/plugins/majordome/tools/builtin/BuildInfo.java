package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.model.Cause;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.PasswordParameterValue;
import hudson.model.Result;
import hudson.model.Run;
import hudson.scm.ChangeLogSet;
import hudson.tasks.test.AbstractTestResultAction;
import io.jenkins.plugins.majordome.util.Json;
import io.jenkins.plugins.majordome.util.Text;
import jenkins.scm.RunWithSCM;

/** Shared JSON rendering of builds. */
final class BuildInfo {

    private BuildInfo() {}

    static ObjectNode summary(Run<?, ?> run) {
        ObjectNode b = Json.object();
        b.put("number", run.getNumber());
        Result result = run.getResult();
        b.put("result", result == null ? null : result.toString());
        b.put("building", run.isBuilding());
        b.put("startTime", run.getTimestamp().toInstant().toString());
        b.put("durationMs", run.isBuilding() ? System.currentTimeMillis() - run.getStartTimeInMillis() : run.getDuration());
        return b;
    }

    static ObjectNode details(Run<?, ?> run) {
        ObjectNode b = summary(run);
        b.put("job", run.getParent().getFullName());
        b.put("displayName", run.getDisplayName());
        if (run.getDescription() != null) {
            b.put("description", Text.truncate(run.getDescription(), 1000));
        }
        ArrayNode causes = b.putArray("causes");
        for (Cause cause : run.getCauses()) {
            causes.add(cause.getShortDescription());
        }

        ParametersAction params = run.getAction(ParametersAction.class);
        if (params != null) {
            ObjectNode p = b.putObject("parameters");
            for (ParameterValue value : params.getParameters()) {
                // Never send secrets to the model, even if the user could see them.
                if (value instanceof PasswordParameterValue || value.isSensitive()) {
                    p.put(value.getName(), "****");
                } else {
                    p.put(value.getName(), Text.truncate(String.valueOf(value.getValue()), 300));
                }
            }
        }

        AbstractTestResultAction<?> tests = run.getAction(AbstractTestResultAction.class);
        if (tests != null) {
            ObjectNode t = b.putObject("tests");
            t.put("total", tests.getTotalCount());
            t.put("failed", tests.getFailCount());
            t.put("skipped", tests.getSkipCount());
            ArrayNode failed = t.putArray("failedTests");
            tests.getFailedTests().stream().limit(20).forEach(r -> {
                ObjectNode f = failed.addObject();
                f.put("name", r.getFullDisplayName());
                f.put("error", Text.truncate(r.getErrorDetails(), 500));
            });
        }

        if (run instanceof RunWithSCM<?, ?> withScm) {
            ArrayNode changes = b.putArray("changes");
            for (ChangeLogSet<? extends ChangeLogSet.Entry> set : withScm.getChangeSets()) {
                for (ChangeLogSet.Entry entry : set) {
                    if (changes.size() >= 30) {
                        break;
                    }
                    ObjectNode c = changes.addObject();
                    c.put("commit", entry.getCommitId());
                    c.put("author", entry.getAuthor().getFullName());
                    c.put("message", Text.truncate(entry.getMsg(), 300));
                }
            }
        }
        return b;
    }
}
