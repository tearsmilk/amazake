package ink.hoka.amazake.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The pure parts of PluginTickStats: self-time accounting with a fake clock and the report math. */
class PluginTickStatsTest {

    private static final long MS = 1_000_000L;
    private static final long SECOND = 1_000_000_000L;

    @BeforeEach
    @AfterEach
    void reset() {
        PluginTickStats.resetForTest();
    }

    private static long[] nanos(final Map<String, Object> snapshot, final int plugin) {
        return ((long[][]) snapshot.get("nanos"))[plugin];
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> plugins(final Map<String, Object> summary) {
        return (List<Map<String, Object>>) summary.get("plugins");
    }

    @Test
    void nestedPluginCodeIsChargedAsSelfTime() {
        final int a = PluginTickStats.register("A");
        final int b = PluginTickStats.register("B");
        final int task = PluginTickStats.enterAt(a, PluginTickStats.TASKS, 0L);   // A task 0..2
        final int event = PluginTickStats.enterAt(b, PluginTickStats.EVENTS, 2 * MS); // B listener 2..5
        PluginTickStats.exitAt(event, 5 * MS);                                     // A task 5..6
        PluginTickStats.exitAt(task, 6 * MS);
        assertEquals(0, PluginTickStats.depth());
        // Outside any plugin: not charged.
        final int cmd = PluginTickStats.enterAt(b, PluginTickStats.COMMANDS, 10 * MS); // B command 10..11
        PluginTickStats.exitAt(cmd, 11 * MS);

        final Map<String, Object> snap = PluginTickStats.snapshot(0L, 0L);
        assertEquals(3 * MS, nanos(snap, a)[PluginTickStats.TASKS]);
        assertEquals(0L, nanos(snap, a)[PluginTickStats.EVENTS]);
        assertEquals(3 * MS, nanos(snap, b)[PluginTickStats.EVENTS]);
        assertEquals(1 * MS, nanos(snap, b)[PluginTickStats.COMMANDS]);
        assertEquals(1L, ((long[]) snap.get("calls"))[a]);
        assertEquals(2L, ((long[]) snap.get("calls"))[b]);
    }

    @Test
    void outerExitPopsFramesWhoseExitWasSkipped() {
        final int a = PluginTickStats.register("A");
        final int b = PluginTickStats.register("B");
        final int outer = PluginTickStats.enterAt(a, PluginTickStats.TASKS, 0L);
        PluginTickStats.enterAt(b, PluginTickStats.EVENTS, 1 * MS); // its exit never comes
        PluginTickStats.exitAt(outer, 4 * MS);
        assertEquals(0, PluginTickStats.depth());
        final Map<String, Object> snap = PluginTickStats.snapshot(0L, 0L);
        assertEquals(1 * MS, nanos(snap, a)[PluginTickStats.TASKS]);
        assertEquals(3 * MS, nanos(snap, b)[PluginTickStats.EVENTS]);
    }

    @Test
    void deepNestingDoesNotOverflow() {
        final int a = PluginTickStats.register("A");
        final int first = PluginTickStats.enterAt(a, PluginTickStats.EVENTS, 0L);
        int last = first;
        for (int i = 0; i < PluginTickStats.STACK_CAPACITY * 2; i++) {
            last = PluginTickStats.enterAt(a, PluginTickStats.EVENTS, i * MS);
        }
        assertEquals(PluginTickStats.OVERFLOW_TOKEN, last);
        PluginTickStats.exit(last); // no-op
        PluginTickStats.exitAt(first, 100 * MS);
        assertEquals(0, PluginTickStats.depth());
        assertEquals(100 * MS, nanos(PluginTickStats.snapshot(0L, 0L), a)[PluginTickStats.EVENTS]);
    }

    @Test
    void disabledHooksDoNothing() {
        assertFalse(PluginTickStats.enabled());
        assertEquals(-1, PluginTickStats.enter(null, PluginTickStats.TASKS));
        assertEquals(-1L, PluginTickStats.asyncStart());
        PluginTickStats.exit(-1);
        PluginTickStats.asyncEnd(null, 5L);
        PluginTickStats.acquire();
        PluginTickStats.acquire();
        PluginTickStats.release();
        assertTrue(PluginTickStats.enabled());
        assertEquals(-1, PluginTickStats.enter(null, PluginTickStats.TASKS)); // no plugin
        PluginTickStats.release();
        PluginTickStats.release();
        assertFalse(PluginTickStats.enabled());
    }

    @Test
    void summarizeDiffsSnapshotsMergesNamesAndSorts() {
        final int a = PluginTickStats.register("A");
        final Map<String, Object> from = PluginTickStats.snapshot(100L, 0L);
        PluginTickStats.exitAt(PluginTickStats.enterAt(a, PluginTickStats.TASKS, 0L), 10 * MS);
        final int b = PluginTickStats.register("B");
        PluginTickStats.exitAt(PluginTickStats.enterAt(b, PluginTickStats.EVENTS, 20 * MS), 60 * MS);
        final int a2 = PluginTickStats.register("A"); // reloaded plugin, same name
        PluginTickStats.exitAt(PluginTickStats.enterAt(a2, PluginTickStats.COMMANDS, 60 * MS), 70 * MS);
        PluginTickStats.addAsync("C", 500 * MS); // async only
        PluginTickStats.addAsync("A", 100 * MS);
        final Map<String, Object> to = PluginTickStats.snapshot(110L, 2 * SECOND);

        final Map<String, Object> summary = PluginTickStats.summarize(from, to, 50);
        assertEquals(10L, summary.get("ticks"));
        assertEquals(6.0, summary.get("msPerTick"));
        final List<Map<String, Object>> rows = plugins(summary);
        assertEquals(3, rows.size());
        assertEquals("B", rows.get(0).get("name"));
        assertEquals(4.0, rows.get(0).get("msPerTick"));
        assertEquals(4.0, rows.get(0).get("events"));
        assertEquals(0.1, rows.get(0).get("callsPerTick"));
        assertEquals("A", rows.get(1).get("name"));
        assertEquals(2.0, rows.get(1).get("msPerTick"));
        assertEquals(1.0, rows.get(1).get("tasks"));
        assertEquals(1.0, rows.get(1).get("commands"));
        assertEquals(0.2, rows.get(1).get("callsPerTick"));
        assertEquals(50.0, rows.get(1).get("asyncCpuMsPerSecond"));
        assertEquals("C", rows.get(2).get("name"));
        assertEquals(0.0, rows.get(2).get("msPerTick"));
        assertEquals(250.0, rows.get(2).get("asyncCpuMsPerSecond"));

        assertEquals(1, plugins(PluginTickStats.summarize(from, to, 1)).size());
        assertEquals(6.0, PluginTickStats.sample(from, to));
    }

    @Test
    void noTicksOrMissingSnapshotGiveNull() {
        final Map<String, Object> snap = PluginTickStats.snapshot(5L, 0L);
        assertNull(PluginTickStats.summarize(snap, snap, 50));
        assertNull(PluginTickStats.summarize(null, snap, 50));
        assertNull(PluginTickStats.sample(snap, null));
    }

    @Test
    void emptyIntervalHasZeroTotalAndNoRows() {
        PluginTickStats.register("Idle");
        final Map<String, Object> from = PluginTickStats.snapshot(0L, 0L);
        final Map<String, Object> to = PluginTickStats.snapshot(20L, SECOND);
        final Map<String, Object> summary = PluginTickStats.summarize(from, to, 50);
        assertEquals(0.0, summary.get("msPerTick"));
        assertTrue(plugins(summary).isEmpty());
        assertEquals(0.0, PluginTickStats.sample(from, to));
    }
}
