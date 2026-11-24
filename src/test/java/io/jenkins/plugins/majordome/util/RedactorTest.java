package io.jenkins.plugins.majordome.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RedactorTest {

    @Test
    void redactsCommonSecretShapes() {
        String log = String.join("\n",
                "export DB_PASSWORD=s3cr3t!",
                "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig",
                "aws key AKIAIOSFODNN7EXAMPLE",
                "token: ghp_abcdefghijklmnopqrstuvwxyz0123456789",
                "git clone https://bob:hunter2@git.example.com/repo.git",
                "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----");
        String out = Redactor.redact(log);
        for (String secret : new String[] {"s3cr3t!", "eyJhbGciOiJIUzI1NiJ9", "AKIAIOSFODNN7EXAMPLE",
                "ghp_abcdefghijklmnopqrstuvwxyz0123456789", "hunter2", "MIIEow"}) {
            assertFalse(out.contains(secret), "leaked " + secret + " in:\n" + out);
        }
        assertTrue(out.contains("git.example.com/repo.git"));
    }

    @Test
    void leavesOrdinaryLogLinesAlone() {
        String line = "[INFO] BUILD FAILURE: Tests run: 42, Failures: 1";
        assertEquals(line, Redactor.redact(line));
    }
}
