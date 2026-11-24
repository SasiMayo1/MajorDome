package io.jenkins.plugins.majordome.util;

import edu.umd.cs.findbugs.annotations.CheckForNull;

public final class Text {

    private Text() {}

    /** Truncates to {@code max} characters, saying so, so the model knows the data is partial. */
    public static String truncate(@CheckForNull String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "\n…[truncated " + (s.length() - max) + " characters]";
    }

    public static boolean isBlank(@CheckForNull String s) {
        return s == null || s.isBlank();
    }
}
