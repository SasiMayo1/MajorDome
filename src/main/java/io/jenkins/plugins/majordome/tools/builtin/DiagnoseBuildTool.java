package io.jenkins.plugins.majordome.tools.builtin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import hudson.Extension;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.scm.ChangeLogSet;
import hudson.tasks.junit.CaseResult;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.tasks.test.TestResult;
import io.jenkins.plugins.majordome.MajorDomeConfiguration;
import io.jenkins.plugins.majordome.tools.Args;
import io.jenkins.plugins.majordome.tools.Lookup;
import io.jenkins.plugins.majordome.tools.ReadTool;
import io.jenkins.plugins.majordome.tools.Schema;
import io.jenkins.plugins.majordome.tools.ToolException;
import io.jenkins.plugins.majordome.util.Json;
import io.jenkins.plugins.majordome.util.Redactor;
import io.jenkins.plugins.majordome.util.Text;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import jenkins.model.Jenkins;
import jenkins.scm.RunWithSCM;

/**
 * Everything needed to explain a failure in one call. Large models can assemble this from get_build,
 * get_build_log and older builds; small self-hosted models often cannot, so the tool does the
 * legwork: last success, changes since then, new vs. old test failures and the log lines that matter.
 */
@Extension
public class DiagnoseBuildTool extends ReadTool {

    static final int MAX_BUILDS_WALKED = 50;
    static final int MAX_CHANGES = 40;
    static final int MAX_FAILED_TESTS = 25;
    static final int LOG_SCAN_LINES = 5_000;
    static final int MAX_ERROR_LINES = 40;
    static final int TAIL_LINES = 40;
    static final int MAX_LINE_CHARS = 400;

    /** Lines that usually carry the reason a build failed. */
    static final Pattern ERROR_LINE = Pattern.compile(
            "(?i)(\\berror\\b|\\berrors?:|exception|\\bfailed\\b|failure|\\bfatal\\b|cannot |could not|"
                    + "not found|permission denied|exit code [1-9]|returned non-zero|\\bkilled\\b|timed? ?out)");

    @Override
    public String getName() {
        return "diagnose_build";
    }

    @Override
    public String getDescription() {
        return "Explain a build in one call: result, how long the job has been failing, the last successful"
                + " build, commits since then, failing tests (new vs. already failing) and the error lines of"
                + " the log (secrets redacted). Defaults to the last completed build.";
    }

    @Override
    public ObjectNode getInputSchema() {
        return Schema.object()
                .string("job", "Full name of the job", true)
                .integer("number", "Build number; omit for the last completed build", false)
                .build();
    }

    @Override
    public boolean isAvailable() {
        return Jenkins.get().hasPermission(Jenkins.READ);
    }

    @Override
    public String execute(JsonNode args) throws Exception {
        Job<?, ?> job = Lookup.job(Args.requireString(args, "job"));
        Integer number = Args.optInt(args, "number");
        Run<?, ?> run;
        if (number != null) {
            run = Lookup.run(job, number);
        } else {
            run = job.getLastCompletedBuild();
            if (run == null) {
                throw new ToolException("Job '" + job.getFullName() + "' has no completed builds");
            }
        }

        ObjectNode out = Json.object();
        out.put("job", job.getFullName());
        ObjectNode build = out.putObject("build");
        build.setAll(BuildInfo.summary(run));
        build.put("displayName", run.getDisplayName());

        history(run, out);
        tests(run, out);
        if (MajorDomeConfiguration.get().isAllowBuildLogs()) {
            log(run, out);
        } else {
            out.put("log", "Not available: an administrator disabled sending build logs to the model.");
        }
        return Json.write(out);
    }

    /** Last success before this build, how long it has been failing, and the changes in between. */
    private static void history(Run<?, ?> run, ObjectNode out) {
        Result runResult = run.getResult();
        boolean unsuccessful = runResult != null && runResult.isWorseThan(Result.SUCCESS);
        ArrayNode changes = out.putArray("changesSinceLastSuccess");
        Run<?, ?> lastSuccess = null;
        Run<?, ?> firstOfStreak = null;
        int walked = 0;
        int totalChanges = 0;
        for (Run<?, ?> r = run; r != null && walked < MAX_BUILDS_WALKED; r = r.getPreviousBuild(), walked++) {
            Result result = r.getResult();
            if (r != run && Result.SUCCESS.equals(result)) {
                lastSuccess = r;
                break;
            }
            if (result != null && result.isWorseThan(Result.SUCCESS)) {
                firstOfStreak = r;
            }
            if (r instanceof RunWithSCM<?, ?> withScm) {
                for (ChangeLogSet<? extends ChangeLogSet.Entry> set : withScm.getChangeSets()) {
                    for (ChangeLogSet.Entry entry : set) {
                        totalChanges++;
                        if (changes.size() < MAX_CHANGES) {
                            ObjectNode c = changes.addObject();
                            c.put("build", r.getNumber());
                            c.put("commit", entry.getCommitId());
                            c.put("author", entry.getAuthor().getFullName());
                            c.put("message", Text.truncate(entry.getMsg(), 300));
                            ArrayNode paths = c.putArray("paths");
                            entry.getAffectedPaths().stream().limit(10).forEach(paths::add);
                        }
                    }
                }
            }
        }
        if (totalChanges > changes.size()) {
            out.put("changesTruncated", totalChanges - changes.size());
        }
        if (lastSuccess != null) {
            out.set("lastSuccessfulBuild", BuildInfo.summary(lastSuccess));
        } else {
            out.put("lastSuccessfulBuild", walked >= MAX_BUILDS_WALKED
                    ? "none in the last " + MAX_BUILDS_WALKED + " builds"
                    : "none: the job never succeeded");
        }
        if (unsuccessful && firstOfStreak != null) {
            out.put("unsuccessfulSince", firstOfStreak.getNumber());
            out.put("unsuccessfulBuildsInARow", run.getNumber() - firstOfStreak.getNumber() + 1);
        }
    }

    private static void tests(Run<?, ?> run, ObjectNode out) {
        AbstractTestResultAction<?> action = run.getAction(AbstractTestResultAction.class);
        if (action == null) {
            out.put("tests", "No test results recorded for this build.");
            return;
        }
        ObjectNode t = out.putObject("tests");
        t.put("total", action.getTotalCount());
        t.put("failed", action.getFailCount());
        t.put("skipped", action.getSkipCount());
        ArrayNode newFailures = t.putArray("newFailures");
        ArrayNode oldFailures = t.putArray("alreadyFailing");
        List<? extends TestResult> failed = action.getFailedTests();
        for (TestResult r : failed.subList(0, Math.min(failed.size(), MAX_FAILED_TESTS))) {
            ObjectNode f = Json.object();
            f.put("name", r.getFullDisplayName());
            f.put("error", Text.truncate(Redactor.redact(String.valueOf(r.getErrorDetails())), 500));
            int age = r instanceof CaseResult cr ? cr.getAge() : 1;
            if (age > 1) {
                f.put("failingForBuilds", age);
                oldFailures.add(f);
            } else {
                newFailures.add(f);
            }
        }
    }

    /** The error lines of the log, plus its tail: usually the reason is in one of the two. */
    private static void log(Run<?, ?> run, ObjectNode out) throws Exception {
        // Redact the whole text first: secrets can span lines (PEM blocks) and must not be cut in half by
        // truncation before the patterns see them.
        List<String> lines = List.of(Redactor.redact(String.join("\n", run.getLog(LOG_SCAN_LINES))).split("\n", -1));
        Set<String> errors = new LinkedHashSet<>();
        for (String line : lines) {
            if (errors.size() >= MAX_ERROR_LINES) {
                break;
            }
            String stripped = line.strip();
            if (!stripped.isEmpty() && ERROR_LINE.matcher(stripped).find()) {
                errors.add(Text.truncate(stripped, MAX_LINE_CHARS));
            }
        }
        out.put("logErrorLines", String.join("\n", errors));
        List<String> tail = lines.subList(Math.max(0, lines.size() - TAIL_LINES), lines.size());
        out.put("logTail", String.join("\n", tail.stream().map(l -> Text.truncate(l, MAX_LINE_CHARS)).toList()));
    }
}
