package io.jenkins.plugins.majordome.actions;

import com.fasterxml.jackson.databind.JsonNode;
import hudson.Extension;
import hudson.ExtensionList;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of proposals. Ids are random and unguessable; an id can be resolved once, only by
 * the user it was proposed to, and only before it expires. Proposals do not survive a restart, on
 * purpose: a restart is a good reason to re-ask.
 */
@Extension
public class PendingActionStore {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, PendingAction> actions = new ConcurrentHashMap<>();
    private Clock clock = Clock.systemUTC();

    public static PendingActionStore get() {
        return ExtensionList.lookupSingleton(PendingActionStore.class);
    }

    /** For tests. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    public PendingAction propose(String userId, String toolName, JsonNode args, String preview, Duration ttl) {
        purgeExpired();
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        Instant now = clock.instant();
        PendingAction action = new PendingAction(
                HexFormat.of().formatHex(bytes), userId, toolName, args, preview, now, now.plus(ttl));
        actions.put(action.id(), action);
        return action;
    }

    /** Removes and returns the action, which must belong to {@code userId} and be unexpired. */
    public PendingAction take(String id, String userId) throws ActionException {
        PendingAction action = actions.get(id);
        if (action == null) {
            throw new ActionException(ActionException.Reason.NOT_FOUND, "Unknown or already decided action");
        }
        if (!action.userId().equals(userId)) {
            // Not removed: the owner can still decide.
            throw new ActionException(ActionException.Reason.NOT_YOURS, "This action was proposed to another user");
        }
        if (!actions.remove(id, action)) {
            throw new ActionException(ActionException.Reason.NOT_FOUND, "Action already decided");
        }
        if (action.isExpired(clock.instant())) {
            throw new ActionException(ActionException.Reason.EXPIRED, "This proposal has expired; ask again");
        }
        return action;
    }

    public List<PendingAction> pendingFor(String userId) {
        purgeExpired();
        return actions.values().stream()
                .filter(a -> a.userId().equals(userId))
                .sorted((a, b) -> a.createdAt().compareTo(b.createdAt()))
                .toList();
    }

    public void discardAllFor(String userId) {
        actions.values().removeIf(a -> a.userId().equals(userId));
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        actions.values().removeIf(a -> a.isExpired(now));
    }
}
