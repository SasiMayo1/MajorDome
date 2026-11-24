package io.jenkins.plugins.majordome.actions;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/**
 * A change proposed by the model and waiting for the user's decision. Bound to the user who was
 * chatting; single-use; expires.
 */
public record PendingAction(
        String id, String userId, String toolName, JsonNode args, String preview, Instant createdAt, Instant expiresAt) {

    public PendingAction {
        args = args.deepCopy();
    }

    @Override
    public JsonNode args() {
        return args.deepCopy();
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(expiresAt);
    }
}
