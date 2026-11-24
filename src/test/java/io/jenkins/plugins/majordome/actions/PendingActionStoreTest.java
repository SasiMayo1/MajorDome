package io.jenkins.plugins.majordome.actions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.jenkins.plugins.majordome.util.Json;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PendingActionStoreTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-24T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private PendingActionStore store;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        store = new PendingActionStore();
        clock = new MutableClock();
        store.setClock(clock);
    }

    private PendingAction propose(String user) {
        return store.propose(user, "trigger_build", Json.object().put("job", "app"), "Start app", Duration.ofMinutes(10));
    }

    @Test
    void idsAreRandomAndUnguessable() {
        PendingAction a = propose("dev");
        PendingAction b = propose("dev");
        assertNotEquals(a.id(), b.id());
        assertEquals(32, a.id().length());
    }

    @Test
    void onlyTheOwnerCanTakeAndOnlyOnce() throws Exception {
        PendingAction a = propose("dev");
        ActionException notYours = assertThrows(ActionException.class, () -> store.take(a.id(), "mallory"));
        assertEquals(ActionException.Reason.NOT_YOURS, notYours.getReason());

        assertEquals(a.id(), store.take(a.id(), "dev").id());
        ActionException gone = assertThrows(ActionException.class, () -> store.take(a.id(), "dev"));
        assertEquals(ActionException.Reason.NOT_FOUND, gone.getReason());
    }

    @Test
    void expiredProposalsCannotBeTaken() {
        PendingAction a = propose("dev");
        clock.now = clock.now.plus(Duration.ofMinutes(11));
        ActionException e = assertThrows(ActionException.class, () -> store.take(a.id(), "dev"));
        assertEquals(ActionException.Reason.EXPIRED, e.getReason());
    }

    @Test
    void argumentsCannotBeMutatedAfterProposal() {
        var args = Json.object().put("job", "app");
        PendingAction a = store.propose("dev", "trigger_build", args, "Start app", Duration.ofMinutes(10));
        args.put("job", "prod-deploy");
        ((com.fasterxml.jackson.databind.node.ObjectNode) a.args()).put("job", "prod-deploy");
        assertEquals("app", a.args().get("job").asText());
    }
}
