package io.jenkins.plugins.majordome.agent;

import edu.umd.cs.findbugs.annotations.CheckForNull;

/** What the chat UI shows. Separate from the model history, which also holds tool traffic. */
public final class TranscriptEntry {

    public enum Kind {
        USER,
        ASSISTANT,
        PROPOSAL,
        ERROR
    }

    public enum Status {
        PENDING,
        EXECUTED,
        FAILED,
        REJECTED,
        EXPIRED
    }

    private final Kind kind;
    private final String text;
    private final String actionId;
    private final String toolName;
    private volatile Status status;
    private volatile String result;

    private TranscriptEntry(Kind kind, String text, String actionId, String toolName, Status status) {
        this.kind = kind;
        this.text = text;
        this.actionId = actionId;
        this.toolName = toolName;
        this.status = status;
    }

    public static TranscriptEntry user(String text) {
        return new TranscriptEntry(Kind.USER, text, null, null, null);
    }

    public static TranscriptEntry assistant(String text) {
        return new TranscriptEntry(Kind.ASSISTANT, text, null, null, null);
    }

    public static TranscriptEntry error(String text) {
        return new TranscriptEntry(Kind.ERROR, text, null, null, null);
    }

    public static TranscriptEntry proposal(String actionId, String toolName, String preview) {
        return new TranscriptEntry(Kind.PROPOSAL, preview, actionId, toolName, Status.PENDING);
    }

    public Kind getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }

    @CheckForNull
    public String getActionId() {
        return actionId;
    }

    @CheckForNull
    public String getToolName() {
        return toolName;
    }

    @CheckForNull
    public Status getStatus() {
        return status;
    }

    @CheckForNull
    public String getResult() {
        return result;
    }

    void resolve(Status status, @CheckForNull String result) {
        this.status = status;
        this.result = result;
    }
}
