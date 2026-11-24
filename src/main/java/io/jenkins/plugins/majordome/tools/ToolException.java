package io.jenkins.plugins.majordome.tools;

/** An expected tool failure whose message is safe and useful to show to the model and the user. */
public class ToolException extends Exception {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }
}
