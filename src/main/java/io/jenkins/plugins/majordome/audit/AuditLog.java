package io.jenkins.plugins.majordome.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.ExtensionList;
import io.jenkins.plugins.majordome.util.Json;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/**
 * Append-only JSON Lines log of everything the assistant proposed and executed, in
 * {@code $JENKINS_HOME/majordome/audit.jsonl}. Each entry includes the hash of the previous one, so
 * editing or deleting a past line breaks the chain and {@link #verify()} reports it.
 */
@Extension
public class AuditLog {

    private static final Logger LOGGER = Logger.getLogger(AuditLog.class.getName());
    private static final String GENESIS = "0".repeat(64);

    private File file;
    private String lastHash;

    public static AuditLog get() {
        return ExtensionList.lookupSingleton(AuditLog.class);
    }

    public File getFile() {
        if (file == null) {
            file = new File(new File(Jenkins.get().getRootDir(), "majordome"), "audit.jsonl");
        }
        return file;
    }

    /** Records an event. Failures are logged, never thrown: auditing must not break the chat. */
    public synchronized void append(String event, String actor, ObjectNode detail) {
        try {
            File f = getFile();
            if (lastHash == null) {
                lastHash = readLastHash(f);
            }
            ObjectNode entry = Json.object();
            entry.put("ts", Instant.now().toString());
            entry.put("event", event);
            entry.put("actor", actor);
            entry.set("detail", detail);
            entry.put("prev", lastHash);
            String hash = hash(lastHash, Json.write(entry));
            entry.put("hash", hash);

            Files.createDirectories(f.getParentFile().toPath());
            try (BufferedWriter w = Files.newBufferedWriter(
                    f.toPath(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(Json.write(entry));
                w.write('\n');
            }
            lastHash = hash;
        } catch (IOException | RuntimeException e) {
            LOGGER.log(Level.WARNING, "Cannot write MajorDome audit entry " + event, e);
        }
    }

    /** Returns the 1-based number of the first line that breaks the chain, or -1 if intact. */
    public synchronized int verify() throws IOException {
        File f = getFile();
        if (!f.exists()) {
            return -1;
        }
        String prev = GENESIS;
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            try {
                ObjectNode entry = (ObjectNode) Json.parse(lines.get(i));
                String stored = entry.path("hash").asText();
                entry.remove("hash");
                if (!prev.equals(entry.path("prev").asText()) || !stored.equals(hash(prev, Json.write(entry)))) {
                    return i + 1;
                }
                prev = stored;
            } catch (IOException | ClassCastException e) {
                return i + 1;
            }
        }
        return -1;
    }

    /** The most recent entries, newest first. */
    public synchronized List<JsonNode> tail(int max) throws IOException {
        File f = getFile();
        if (!f.exists()) {
            return List.of();
        }
        List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
        List<JsonNode> out = new java.util.ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && out.size() < max; i--) {
            try {
                out.add(Json.parse(lines.get(i)));
            } catch (IOException e) {
                // skip unreadable line; verify() reports it
            }
        }
        return out;
    }

    private static String readLastHash(File f) throws IOException {
        if (!f.exists()) {
            return GENESIS;
        }
        String last = null;
        for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            if (!line.isBlank()) {
                last = line;
            }
        }
        if (last == null) {
            return GENESIS;
        }
        @CheckForNull String hash = Json.parse(last).path("hash").asText(null);
        return hash == null ? GENESIS : hash;
    }

    private static String hash(String prev, String entryJson) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(prev.getBytes(StandardCharsets.UTF_8));
            sha.update((byte) '\n');
            sha.update(entryJson.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
