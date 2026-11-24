package io.jenkins.plugins.majordome.tools;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Computer;
import hudson.model.Job;
import hudson.model.Run;
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
            throw new ToolException("Job '" + name + "' not found or not visible to you. Use list_jobs to find the full name.");
        }
        return job;
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
