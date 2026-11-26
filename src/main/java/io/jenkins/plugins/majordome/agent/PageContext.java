package io.jenkins.plugins.majordome.agent;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import io.jenkins.plugins.majordome.util.Text;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import jenkins.model.Jenkins;

/**
 * Turns the path of the page the user is looking at (e.g. {@code /jenkins/job/team/job/app/42/console})
 * into a description of the job and build on it, so the model does not have to guess from a URL.
 *
 * <p>Resolution runs as the current user: a job the user cannot read resolves to nothing, exactly as
 * if the page did not exist, so the context never reveals hidden items.
 */
final class PageContext {

    private PageContext() {}

    private static final Map<String, Function<Job<?, ?>, Run<?, ?>>> PERMALINKS = Map.of(
            "lastBuild", Job::getLastBuild,
            "lastCompletedBuild", Job::getLastCompletedBuild,
            "lastSuccessfulBuild", Job::getLastSuccessfulBuild,
            "lastStableBuild", Job::getLastStableBuild,
            "lastFailedBuild", Job::getLastFailedBuild,
            "lastUnsuccessfulBuild", Job::getLastUnsuccessfulBuild,
            "lastUnstableBuild", Job::getLastUnstableBuild);

    /** A line such as {@code Job on this page: team/app, build #42 (FAILURE)}, or null. */
    @CheckForNull
    static String describe(@CheckForNull String path) {
        if (Text.isBlank(path)) {
            return null;
        }
        String[] segments = path.split("[?#]", 2)[0].split("/");
        List<String> names = new ArrayList<>();
        int i = 0;
        // The job is the longest run of "job/<name>" pairs; anything before it (context path, views) is skipped.
        while (i < segments.length && !"job".equals(segments[i])) {
            i++;
        }
        while (i + 1 < segments.length && "job".equals(segments[i])) {
            names.add(decode(segments[i + 1]));
            i += 2;
        }
        if (names.isEmpty()) {
            return null;
        }
        Job<?, ?> job = Jenkins.get().getItemByFullName(String.join("/", names), Job.class);
        if (job == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("Job on this page: ").append(job.getFullName());
        Run<?, ?> run = i < segments.length ? build(job, segments[i]) : null;
        if (run != null) {
            Result result = run.getResult();
            sb.append(", build #").append(run.getNumber())
                    .append(" (").append(run.isBuilding() ? "BUILDING" : result == null ? "UNKNOWN" : result.toString())
                    .append(')');
        }
        return sb.toString();
    }

    @CheckForNull
    private static Run<?, ?> build(Job<?, ?> job, String segment) {
        if (segment.matches("\\d{1,9}")) {
            return job.getBuildByNumber(Integer.parseInt(segment));
        }
        Function<Job<?, ?>, Run<?, ?>> permalink = PERMALINKS.get(segment);
        return permalink == null ? null : permalink.apply(job);
    }

    private static String decode(String segment) {
        try {
            return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return segment;
        }
    }
}
