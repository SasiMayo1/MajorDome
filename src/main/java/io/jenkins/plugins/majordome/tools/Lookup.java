package io.jenkins.plugins.majordome.tools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.Run;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import jenkins.model.Jenkins;

/**
 * Finds Jenkins objects as the current user. {@code getItemByFullName} already hides items the user
 * cannot read, so "not found" and "not visible" look the same, as in the Jenkins UI.
 */
public final class Lookup {

    private Lookup() {}

    public static Job<?, ?> job(String fullName) throws ToolException {
        String name = fullName.replaceAll("^/+|/+$", "");
        Job<?, ?> job = Jenkins.get().getItemByFullName(name, Job.class);
        if (job == null) {
            List<String> similar = similarJobNames(name, 3);
            throw new ToolException("Job '" + name + "' not found or not visible to you."
                    + (similar.isEmpty()
                            ? " Use list_jobs to find the full name."
                            : " Did you mean: " + String.join(", ", similar.stream().map(s -> "'" + s + "'").toList())
                                    + "? Retry with the exact full name."));
        }
        return job;
    }

    /** At most this many jobs are scanned for suggestions, to keep a typo cheap on huge instances. */
    static final int MAX_SCANNED_FOR_SUGGESTIONS = 5_000;

    /**
     * Full names of visible jobs that look like {@code wanted}: same name ignoring case, one contains the
     * other, or a small edit distance, compared on both the full name and the last path segment. Only
     * jobs the current user can read are considered, so suggestions never reveal hidden jobs.
     */
    public static List<String> similarJobNames(String wanted, int max) {
        String w = wanted.toLowerCase(Locale.ROOT);
        String wLeaf = leaf(w);
        record Scored(String name, int score) {}
        List<Scored> scored = new ArrayList<>();
        int scanned = 0;
        for (Job<?, ?> job : Jenkins.get().allItems(Job.class)) {
            if (++scanned > MAX_SCANNED_FOR_SUGGESTIONS) {
                break;
            }
            String full = job.getFullName();
            String f = full.toLowerCase(Locale.ROOT);
            int score = Math.min(score(w, f), score(wLeaf, leaf(f)) + 1);
            if (score < Integer.MAX_VALUE / 2) {
                scored.add(new Scored(full, score));
            }
        }
        return scored.stream()
                .sorted(Comparator.comparingInt(Scored::score).thenComparing(Scored::name))
                .limit(max)
                .map(Scored::name)
                .toList();
    }

    private static String leaf(String name) {
        int slash = name.lastIndexOf('/');
        return slash < 0 ? name : name.substring(slash + 1);
    }

    /** Lower is closer; {@code Integer.MAX_VALUE / 2} means "not similar". */
    private static int score(String wanted, String candidate) {
        if (wanted.isEmpty() || candidate.isEmpty()) {
            return Integer.MAX_VALUE / 2;
        }
        if (wanted.equals(candidate)) {
            return 0;
        }
        if (candidate.contains(wanted) || wanted.contains(candidate)) {
            return 1 + Math.abs(candidate.length() - wanted.length()) / 4;
        }
        int limit = Math.max(2, wanted.length() / 3);
        int d = editDistance(wanted, candidate, limit);
        return d <= limit ? 2 + d : Integer.MAX_VALUE / 2;
    }

    /** Levenshtein distance, giving up (returning {@code limit + 1}) once it exceeds {@code limit}. */
    static int editDistance(String a, String b, int limit) {
        if (Math.abs(a.length() - b.length()) > limit) {
            return limit + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > limit) {
                return limit + 1;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }

    /** A build by number, or the last build when {@code number} is null. */
    public static Run<?, ?> run(Job<?, ?> job, @CheckForNull Integer number) throws ToolException {
        Run<?, ?> run = number == null ? job.getLastBuild() : job.getBuildByNumber(number);
        if (run == null) {
            throw new ToolException(number == null
                    ? "Job '" + job.getFullName() + "' has no builds"
                    : "Build #" + number + " of '" + job.getFullName() + "' not found");
        }
        return run;
    }

    /** A node's computer. The built-in node is addressed as "built-in". */
    public static Computer computer(String name) throws ToolException {
        String lookup = "built-in".equals(name) || "master".equals(name) ? "" : name;
        Computer computer = Jenkins.get().getComputer(lookup);
        if (computer == null) {
            throw new ToolException("Node '" + name + "' not found. Use list_nodes to see node names.");
        }
        return computer;
    }

    public static String nodeName(Computer computer) {
        return computer.getName().isEmpty() ? "built-in" : computer.getName();
    }
}
