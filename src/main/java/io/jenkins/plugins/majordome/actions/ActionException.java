package io.jenkins.plugins.majordome.actions;

/** A confirm/reject request that cannot be honoured. */
public class ActionException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        NOT_FOUND,
        EXPIRED,
        NOT_YOURS
    }

    private final Reason reason;

    public ActionException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
