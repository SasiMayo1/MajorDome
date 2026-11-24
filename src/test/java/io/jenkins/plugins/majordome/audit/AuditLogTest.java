package io.jenkins.plugins.majordome.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.jenkins.plugins.majordome.util.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class AuditLogTest {

    @Test
    void hashChainDetectsTampering(JenkinsRule j) throws Exception {
        AuditLog log = AuditLog.get();
        log.append("action_proposed", "alice", Json.object().put("tool", "trigger_build"));
        log.append("action_executed", "alice", Json.object().put("tool", "trigger_build"));
        log.append("action_rejected", "bob", Json.object().put("tool", "abort_build"));
        assertEquals(-1, log.verify());

        Path file = log.getFile().toPath();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        lines.set(1, lines.get(1).replace("\"alice\"", "\"mallory\""));
        Files.write(file, lines, StandardCharsets.UTF_8);

        assertEquals(2, log.verify());
    }
}
