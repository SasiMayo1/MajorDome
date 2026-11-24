package io.jenkins.plugins.majordome.util;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Best-effort removal of secrets from text before it leaves the controller (e.g. build logs sent to
 * the model). Jenkins already masks secrets bound through the Credentials plugin; this catches
 * values that leaked into logs by other means. It is a safety net, not a guarantee.
 */
public final class Redactor {

    private record Rule(Pattern pattern, String replacement) {}

    private static final List<Rule> RULES = List.of(
            new Rule(Pattern.compile(
                            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
                    "[REDACTED PRIVATE KEY]"),
            new Rule(Pattern.compile("(?i)\\b(bearer)\\s+[a-z0-9._~+/=-]{8,}"), "$1 [REDACTED]"),
            new Rule(Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"), "[REDACTED AWS KEY]"),
            new Rule(Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b"), "[REDACTED GITHUB TOKEN]"),
            new Rule(Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}\\b"), "[REDACTED GITLAB TOKEN]"),
            new Rule(Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b"), "[REDACTED API KEY]"),
            new Rule(Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b"), "[REDACTED SLACK TOKEN]"),
            new Rule(Pattern.compile("(://)[^/\\s:@]+:[^/\\s@]+@"), "$1[REDACTED]@"),
            new Rule(Pattern.compile(
                            // no \b: "DB_PASSWORD" must match too ('_' is a word character)
                            "(?i)(?<![a-z0-9])(password|passwd|pwd|secret|token|api[_-]?key|access[_-]?key|client[_-]?secret)"
                                    + "(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|\\S+)"),
                    "$1$2[REDACTED]"));

    private Redactor() {}

    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (Rule rule : RULES) {
            out = rule.pattern().matcher(out).replaceAll(rule.replacement());
        }
        return out;
    }
}
