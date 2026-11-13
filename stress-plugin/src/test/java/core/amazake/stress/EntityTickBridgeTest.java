package core.amazake.stress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EntityTickBridgeTest {

    /** Stand-in for core.amazake.tick.EntityTickStats with the same static signatures. */
    public static final class FakeStats {
        static int holders;
        static long tick;
        static boolean throwOnSnapshot;

        public static void acquire() {
            holders++;
        }

        public static void release() {
            holders--;
        }

        public static Map<String, Object> snapshot() {
            if (throwOnSnapshot) {
                throw new IllegalStateException("boom");
            }
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("tick", tick);
            return out;
        }

        public static Map<String, Object> summarize(final Map<String, Object> from, final Map<String, Object> to, final int topTypes) {
            final long ticks = (Long) to.get("tick") - (Long) from.get("tick");
            if (ticks <= 0) {
                return null;
            }
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("ticks", ticks);
            out.put("top", topTypes);
            return out;
        }

        public static Map<String, Object> sample(final Map<String, Object> from, final Map<String, Object> to) {
            final long ticks = (Long) to.get("tick") - (Long) from.get("tick");
            return ticks <= 0 ? null : Map.of("loop", (double) ticks);
        }
    }

    /** Wrong shape: instance method instead of static. */
    public static final class NotStatic {
        public void acquire() {
        }

        public static void release() {
        }

        public static Map<String, Object> snapshot() {
            return Map.of();
        }

        public static Map<String, Object> summarize(final Map<String, Object> a, final Map<String, Object> b, final int n) {
            return Map.of();
        }

        public static Map<String, Object> sample(final Map<String, Object> a, final Map<String, Object> b) {
            return Map.of();
        }
    }

    private final List<LogRecord> logs = new ArrayList<>();
    private Logger logger;

    @BeforeEach
    void reset() {
        FakeStats.holders = 0;
        FakeStats.tick = 100L;
        FakeStats.throwOnSnapshot = false;
        this.logs.clear();
        this.logger = Logger.getAnonymousLogger();
        this.logger.setUseParentHandlers(false);
        this.logger.addHandler(new Handler() {
            @Override
            public void publish(final LogRecord record) {
                EntityTickBridgeTest.this.logs.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        });
    }

    @Test
    void absentClassIsOffWithOneInfoLine() {
        final EntityTickBridge bridge = EntityTickBridge.load(this.logger);
        assertFalse(bridge.isAvailable());
        assertFalse(bridge.acquire());
        bridge.release();
        assertNull(bridge.snapshot());
        assertNull(bridge.summarize(Map.of("tick", 1L), Map.of("tick", 2L), 40));
        assertNull(bridge.sample(Map.of("tick", 1L), Map.of("tick", 2L)));
        assertEquals(1, this.logs.size());
        assertEquals(java.util.logging.Level.INFO, this.logs.get(0).getLevel());
    }

    @Test
    void unboundBridgeIsOff() {
        final EntityTickBridge bridge = EntityTickBridge.bind(null, null);
        assertFalse(bridge.isAvailable());
        assertNull(bridge.snapshot());
    }

    @Test
    void acquireSnapshotSummarizeRelease() {
        final EntityTickBridge bridge = EntityTickBridge.bind(FakeStats.class, this.logger);
        assertTrue(bridge.isAvailable());
        assertNull(bridge.snapshot(), "no snapshot while this plugin does not hold the counters");

        final int gen0 = bridge.generation();
        assertTrue(bridge.acquire());
        assertEquals(1, FakeStats.holders);
        assertTrue(bridge.isHeld());
        assertNotEquals(gen0, bridge.generation());

        final Map<String, Object> first = bridge.snapshot();
        FakeStats.tick = 160L;
        final Map<String, Object> second = bridge.snapshot();
        assertEquals(60L, bridge.summarize(first, second, 15).get("ticks"));
        assertEquals(15, bridge.summarize(first, second, 15).get("top"));
        assertEquals(60.0, bridge.sample(first, second).get("loop"));
        assertNull(bridge.summarize(second, first, 40), "no ticks -> null");
        assertNull(bridge.summarize(first, null, 40));
        assertNull(bridge.sample(null, second));

        bridge.release();
        bridge.release(); // extra release must not reach the server
        assertEquals(0, FakeStats.holders);
        assertFalse(bridge.isHeld());
        assertNull(bridge.snapshot());
    }

    @Test
    void failingServerClassTurnsOffAndStillReleases() {
        final EntityTickBridge bridge = EntityTickBridge.bind(FakeStats.class, this.logger);
        assertTrue(bridge.acquire());
        FakeStats.throwOnSnapshot = true;
        assertNull(bridge.snapshot());
        assertFalse(bridge.isAvailable());
        assertEquals(1, this.logs.size(), "one warning");
        assertNull(bridge.snapshot());
        assertEquals(1, this.logs.size());
        bridge.release();
        assertEquals(0, FakeStats.holders, "the server must not keep counting");
    }

    @Test
    void unexpectedShapeIsOff() {
        final EntityTickBridge bridge = EntityTickBridge.bind(NotStatic.class, this.logger);
        assertFalse(bridge.isAvailable());
        assertFalse(bridge.acquire());
        assertEquals(1, this.logs.size());
    }

    @Test
    void newerPicksHigherTick() {
        final Map<String, Object> a = Map.of("tick", 10L);
        final Map<String, Object> b = Map.of("tick", 20L);
        assertSame(b, EntityTickBridge.newer(a, b));
        assertSame(b, EntityTickBridge.newer(b, a));
        assertSame(a, EntityTickBridge.newer(a, null));
        assertSame(b, EntityTickBridge.newer(null, b));
        assertNull(EntityTickBridge.newer(null, null));
    }
}
