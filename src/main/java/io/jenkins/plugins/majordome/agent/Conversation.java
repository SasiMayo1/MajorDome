package io.jenkins.plugins.majordome.agent;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import io.jenkins.plugins.majordome.llm.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One user's conversation. Callers synchronize on the instance for the whole duration of a turn
 * or decision, so a user cannot run two turns in parallel on the same history.
 */
public final class Conversation {

    static final int MAX_MESSAGES = 80;
    static final int MAX_TRANSCRIPT = 200;

    private final String userId;
    private String providerFingerprint;
    private final List<ChatMessage> messages = new ArrayList<>();
    private final List<TranscriptEntry> transcript = new ArrayList<>();
    /** Facts the model must learn on the next turn, e.g. outcomes of confirmed actions. */
    private final List<String> notes = new ArrayList<>();

    Conversation(String userId) {
        this.userId = userId;
    }

    public String getUserId() {
        return userId;
    }

    public synchronized List<TranscriptEntry> getTranscript() {
        return List.copyOf(transcript);
    }

    List<ChatMessage> messages() {
        return messages;
    }

    void addTranscript(TranscriptEntry entry) {
        transcript.add(entry);
        if (transcript.size() > MAX_TRANSCRIPT) {
            transcript.subList(0, transcript.size() - MAX_TRANSCRIPT).clear();
        }
    }

    Optional<TranscriptEntry> proposal(String actionId) {
        return transcript.stream()
                .filter(e -> actionId.equals(e.getActionId()))
                .findFirst();
    }

    void addNote(String note) {
        notes.add(note);
    }

    List<String> drainNotes() {
        List<String> out = List.copyOf(notes);
        notes.clear();
        return out;
    }

    /** Clears the model history when the provider changed: native message copies are not portable. */
    void ensureProvider(String fingerprint) {
        if (providerFingerprint != null && !providerFingerprint.equals(fingerprint)) {
            messages.clear();
        }
        providerFingerprint = fingerprint;
    }

    void clear() {
        messages.clear();
        transcript.clear();
        notes.clear();
    }

    /**
     * Drops the oldest turns beyond {@link #MAX_MESSAGES}. Cuts only before a plain user message,
     * so a tool call is never separated from its result.
     */
    void trim() {
        if (messages.size() <= MAX_MESSAGES) {
            return;
        }
        int start = messages.size() - MAX_MESSAGES;
        while (start < messages.size() && !messages.get(start).isPlainUserText()) {
            start++;
        }
        if (start < messages.size()) {
            messages.subList(0, start).clear();
        }
    }

    @CheckForNull
    String getProviderFingerprint() {
        return providerFingerprint;
    }
}
