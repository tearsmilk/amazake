package core.amazake.core.modules.essentials;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import core.amazake.core.modules.essentials.TeleportRequests.Request;
import core.amazake.core.modules.essentials.TeleportRequests.Type;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TeleportRequestsTest {

    private static Request req(final UUID requester, final UUID target, final long expiry) {
        return new Request(requester, "Req", target, "Tgt", Type.TPA, expiry);
    }

    @Test
    void storesAndRetrievesByTarget() {
        final TeleportRequests store = new TeleportRequests();
        final UUID requester = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        store.put(req(requester, target, 1000L));
        assertNotNull(store.get(target));
        assertEquals(requester, store.get(target).requester());
    }

    @Test
    void newRequestToSameTargetOverwrites() {
        final TeleportRequests store = new TeleportRequests();
        final UUID target = UUID.randomUUID();
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        store.put(req(first, target, 1000L));
        store.put(req(second, target, 2000L));
        assertEquals(1, store.size());
        assertEquals(second, store.get(target).requester());
    }

    @Test
    void expiryBoundaryIsInclusive() {
        final Request r = req(UUID.randomUUID(), UUID.randomUUID(), 1000L);
        assertTrue(r.isExpired(1000L));
        assertTrue(r.isExpired(1001L));
        assertEquals(false, r.isExpired(999L));
    }

    @Test
    void sweepRemovesOnlyExpiredAndReturnsThem() {
        final TeleportRequests store = new TeleportRequests();
        final UUID liveTarget = UUID.randomUUID();
        final UUID deadTarget = UUID.randomUUID();
        store.put(req(UUID.randomUUID(), liveTarget, 5000L));
        store.put(req(UUID.randomUUID(), deadTarget, 1000L));

        final List<Request> expired = store.sweepExpired(2000L);
        assertEquals(1, expired.size());
        assertEquals(deadTarget, expired.get(0).target());
        assertNull(store.get(deadTarget));
        assertNotNull(store.get(liveTarget));
    }

    @Test
    void removeByRequesterFindsOutgoing() {
        final TeleportRequests store = new TeleportRequests();
        final UUID requester = UUID.randomUUID();
        final UUID target = UUID.randomUUID();
        store.put(req(requester, target, 5000L));
        final Request removed = store.removeByRequester(requester);
        assertNotNull(removed);
        assertEquals(target, removed.target());
        assertEquals(0, store.size());
        assertNull(store.removeByRequester(requester));
    }
}
