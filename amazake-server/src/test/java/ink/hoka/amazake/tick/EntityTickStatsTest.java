package ink.hoka.amazake.tick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The pure parts of EntityTickStats: self-time accounting with a fake clock and the report math. */
class EntityTickStatsTest {

    private static final long MS = 1_000_000L;
    private static final String[] NAMES = {"minecraft:zombie", "minecraft:item", "minecraft:player", "minecraft:villager"};
    private static final int ZOMBIE = 0, ITEM = 1, PLAYER = 2, VILLAGER = 3;

    @BeforeEach
    @AfterEach
    void reset() {
        EntityTickStats.resetForTest();
    }

    private static long[] phases(final int type) {
        return ((long[][]) EntityTickStats.snapshot(0L, NAMES).get("typePhaseNanos"))[type];
    }

    @Test
    void selfTimeIsChargedToTheInnermostPhase() {
        EntityTickStats.ensureTypes(NAMES.length);
        EntityTickStats.beginAt(ZOMBIE, EntityTickStats.MOB, 0L);           // base 0..1
        final int goals = EntityTickStats.enterAt(EntityTickStats.GOALS, 1 * MS); // goals 1..3
        final int path = EntityTickStats.enterAt(EntityTickStats.PATHFIND, 3 * MS); // pathfind 3..7
        EntityTickStats.exitAt(path, 7 * MS);                                  // goals 7..8
        EntityTickStats.exitAt(goals, 8 * MS);                                 // base 8..10
        final int move = EntityTickStats.enterAt(EntityTickStats.MOVE, 10 * MS); // move 10..12
        EntityTickStats.exitAt(move, 12 * MS);                                 // base 12..15
        EntityTickStats.endAt(true, 15 * MS);
        assertFalse(EntityTickStats.recording());

        final long[] p = phases(ZOMBIE);
        assertEquals(1 * MS + 2 * MS + 3 * MS, p[EntityTickStats.BASE]);
        assertEquals(3 * MS, p[EntityTickStats.GOALS]);
        assertEquals(4 * MS, p[EntityTickStats.PATHFIND]);
        assertEquals(2 * MS, p[EntityTickStats.MOVE]);
        final Map<String, Object> snap = EntityTickStats.snapshot(0L, NAMES);
        assertEquals(15 * MS, ((long[]) snap.get("typeNanos"))[ZOMBIE]);
        assertEquals(1L, ((long[]) snap.get("typeTicks"))[ZOMBIE]);
        assertEquals(0L, ((long[]) snap.get("typeInactive"))[ZOMBIE]);
        assertEquals(EntityTickStats.MOB, ((byte[]) snap.get("typeClass"))[ZOMBIE]);
        assertEquals(-1, ((byte[]) snap.get("typeClass"))[VILLAGER]);
    }

    @Test
    void outerExitPopsFramesWhoseExitWasSkipped() {
        EntityTickStats.ensureTypes(NAMES.length);
        EntityTickStats.beginAt(ZOMBIE, EntityTickStats.MOB, 0L);
        final int goals = EntityTickStats.enterAt(EntityTickStats.GOALS, 0L);
        EntityTickStats.enterAt(EntityTickStats.PATHFIND, 1 * MS); // its exit is "skipped by an exception"
        EntityTickStats.exitAt(goals, 2 * MS);                    // pathfind 1..2, back to base
        EntityTickStats.endAt(false, 5 * MS);                     // base 2..5

        final long[] p = phases(ZOMBIE);
        assertEquals(1 * MS, p[EntityTickStats.GOALS]);
        assertEquals(1 * MS, p[EntityTickStats.PATHFIND]);
        assertEquals(3 * MS, p[EntityTickStats.BASE]);
        assertEquals(1L, ((long[]) EntityTickStats.snapshot(0L, NAMES).get("typeInactive"))[ZOMBIE]);
    }

    @Test
    void enterBeyondCapacityChargesTheTop() {
        EntityTickStats.ensureTypes(NAMES.length);
        EntityTickStats.beginAt(ITEM, EntityTickStats.OTHER, 0L);
        final int[] tokens = new int[EntityTickStats.STACK_CAPACITY + 4];
        long t = 0L;
        for (int i = 0; i < tokens.length; i++) {
            tokens[i] = EntityTickStats.enterAt(i < EntityTickStats.STACK_CAPACITY - 2 ? EntityTickStats.MOVE : EntityTickStats.MERGE, t);
            t += MS;
        }
        // Enters that did not fit return the overflow token; the public exit() ignores it.
        assertEquals(EntityTickStats.OVERFLOW_TOKEN, tokens[tokens.length - 1]);
        for (int i = tokens.length - 1; i >= 0; i--) {
            if (tokens[i] != EntityTickStats.OVERFLOW_TOKEN) {
                EntityTickStats.exitAt(tokens[i], t);
            }
        }
        EntityTickStats.endAt(true, t);
        long sum = 0L;
        for (final long value : phases(ITEM)) {
            sum += value;
        }
        assertEquals(t, sum); // nothing lost or counted twice
        assertEquals(((long[]) EntityTickStats.snapshot(0L, NAMES).get("typeNanos"))[ITEM], sum);
    }

    @Test
    void hooksDoNothingWhileDisabled() {
        assertFalse(EntityTickStats.enabled());
        assertEquals(0L, EntityTickStats.loopStart());
        assertEquals(-1, EntityTickStats.enter(EntityTickStats.GOALS));
        EntityTickStats.exit(-1);
        EntityTickStats.endEntity(true);
        EntityTickStats.loopEnd(EntityTickStats.LOOP, 0L);
        assertEquals(0L, ((long[]) EntityTickStats.snapshot(0L, NAMES).get("loopNanos"))[EntityTickStats.LOOP]);

        EntityTickStats.acquire();
        EntityTickStats.acquire();
        EntityTickStats.release();
        assertTrue(EntityTickStats.enabled());
        assertTrue(EntityTickStats.loopStart() != 0L);
        EntityTickStats.release();
        EntityTickStats.release(); // one too many must not go negative
        assertFalse(EntityTickStats.enabled());
        EntityTickStats.acquire();
        assertTrue(EntityTickStats.enabled());
    }

    private static void tickEntity(final int type, final int cls, final boolean active, final long start, final long phase, final int phaseId, final long base) {
        EntityTickStats.beginAt(type, cls, start);
        final int token = EntityTickStats.enterAt(phaseId, start + base);
        EntityTickStats.exitAt(token, start + base + phase);
        EntityTickStats.endAt(active, start + base + phase);
    }

    @Test
    @SuppressWarnings("unchecked")
    void summarizeDiffsTwoSnapshots() {
        // Before the window: some history that must be subtracted.
        tickEntity(ZOMBIE, EntityTickStats.MOB, true, 0L, 5 * MS, EntityTickStats.GOALS, MS);
        EntityTickStats.addLoopNanos(EntityTickStats.LOOP, 100 * MS);
        final Map<String, Object> from = EntityTickStats.snapshot(10L, NAMES);

        // 2 ticks: 4 zombie ticks (one inactive), 10 item ticks, 2 player ticks.
        for (int i = 0; i < 4; i++) {
            tickEntity(ZOMBIE, EntityTickStats.MOB, i != 0, 0L, 2 * MS, EntityTickStats.GOALS, MS); // 3 ms each
        }
        for (int i = 0; i < 10; i++) {
            tickEntity(ITEM, EntityTickStats.OTHER, true, 0L, MS / 2, EntityTickStats.MERGE, MS / 2); // 1 ms each
        }
        tickEntity(PLAYER, EntityTickStats.PLAYER, true, 0L, 0L, EntityTickStats.MOVE, MS);
        tickEntity(PLAYER, EntityTickStats.PLAYER, true, 0L, 0L, EntityTickStats.MOVE, MS);
        EntityTickStats.addLoopNanos(EntityTickStats.LOOP, 40 * MS);
        EntityTickStats.addLoopNanos(EntityTickStats.EAR, 2 * MS);
        EntityTickStats.addLoopNanos(EntityTickStats.DESPAWN, 4 * MS);
        final Map<String, Object> to = EntityTickStats.snapshot(12L, NAMES);

        final Map<String, Object> report = EntityTickStats.summarize(from, to, 40);
        assertEquals(2L, report.get("ticks"));
        assertEquals(List.of(EntityTickStats.PHASES), report.get("phases"));

        final Map<String, Object> loop = (Map<String, Object>) report.get("msPerTick");
        assertEquals(20.0, loop.get("loop"));
        assertEquals(1.0, loop.get("ear"));
        assertEquals(2.0, loop.get("despawn"));
        // classes: mob 12 ms, other 10 ms, player 2 ms over 2 ticks = 6 + 5 + 1 = 12 ms/tick
        assertEquals(20.0 - 1.0 - 2.0 - 12.0, loop.get("overhead"));

        final List<Map<String, Object>> classes = (List<Map<String, Object>>) report.get("classes");
        assertEquals(3, classes.size());
        final Map<String, Object> mob = classes.get(0);
        assertEquals("mob", mob.get("class"));
        assertEquals(6.0, mob.get("msPerTick"));
        assertEquals(2.0, mob.get("entitiesPerTick"));
        assertEquals(0.25, mob.get("inactiveShare"));
        assertEquals(Map.of("base", 2.0, "goals", 4.0), mob.get("phases"));
        assertEquals("other", classes.get(1).get("class"));
        assertEquals(5.0, classes.get(1).get("msPerTick"));
        assertEquals(5.0, classes.get(1).get("entitiesPerTick"));
        assertEquals("player", classes.get(2).get("class"));
        assertEquals(1.0, classes.get(2).get("msPerTick"));
        assertEquals(Map.of("base", 1.0), classes.get(2).get("phases"));

        final List<Map<String, Object>> types = (List<Map<String, Object>>) report.get("types");
        assertEquals(3, types.size()); // the villager never ticked
        assertEquals("minecraft:zombie", types.get(0).get("type"));
        assertEquals(3000.0, types.get(0).get("usPerEntity"));
        assertEquals("minecraft:item", types.get(1).get("type"));
        assertEquals(1000.0, types.get(1).get("usPerEntity"));
        assertEquals("minecraft:player", types.get(2).get("type"));
        assertEquals("player", types.get(2).get("class"));

        final Map<String, Object> top1 = EntityTickStats.summarize(from, to, 1);
        assertEquals(1, ((List<?>) top1.get("types")).size());

        final Map<String, Object> sample = EntityTickStats.sample(from, to);
        assertEquals(Map.of("loop", 20.0, "mob", 6.0, "other", 5.0, "player", 1.0), sample);
        assertEquals(List.of("loop", "mob", "other", "player"), List.copyOf(sample.keySet()));
    }

    @Test
    void zeroTicksOrMissingSnapshotsGiveNull() {
        final Map<String, Object> a = EntityTickStats.snapshot(5L, NAMES);
        assertNull(EntityTickStats.summarize(a, a, 40));
        assertNull(EntityTickStats.sample(a, a));
        assertNull(EntityTickStats.summarize(null, a, 40));
        assertNull(EntityTickStats.sample(a, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotsOfDifferentSizesAndRounding() {
        final Map<String, Object> empty = EntityTickStats.snapshot(0L, NAMES); // before any entity: no arrays
        tickEntity(VILLAGER, EntityTickStats.MOB, true, 0L, 1L, EntityTickStats.BRAIN, 1_234_567L);
        final Map<String, Object> to = EntityTickStats.snapshot(3L, new String[0]); // name unknown
        final Map<String, Object> report = EntityTickStats.summarize(empty, to, 40);
        final List<Map<String, Object>> types = (List<Map<String, Object>>) report.get("types");
        assertEquals(1, types.size());
        assertEquals("unknown#3", types.get(0).get("type"));
        assertEquals(0.412, types.get(0).get("msPerTick")); // 1.234568 ms / 3 ticks
        assertEquals(0.333, types.get(0).get("entitiesPerTick"));
        assertEquals(0.0, ((Map<String, Object>) report.get("msPerTick")).get("overhead")); // clamped
    }

    @Test
    void aThrownEntityTickStopsRecordingAtTheLatestByTheLoopEnd() {
        EntityTickStats.acquire(); // abortEntity() only acts while recording (one volatile read otherwise)
        EntityTickStats.ensureTypes(NAMES.length);
        EntityTickStats.beginAt(ZOMBIE, EntityTickStats.MOB, 0L);
        EntityTickStats.enterAt(EntityTickStats.MOVE, 1 * MS); // the tick throws here: no exit, no endAt
        EntityTickStats.abortEntity();
        assertFalse(EntityTickStats.recording());
        assertEquals(-1, EntityTickStats.enter(EntityTickStats.MOVE)); // a move from outside an entity tick
        assertEquals(1 * MS, phases(ZOMBIE)[EntityTickStats.BASE]);
        assertEquals(0L, phases(ZOMBIE)[EntityTickStats.MOVE]);
        assertEquals(0L, ((long[]) EntityTickStats.snapshot(0L, NAMES).get("typeTicks"))[ZOMBIE]);

        EntityTickStats.beginAt(ZOMBIE, EntityTickStats.MOB, 2 * MS);
        EntityTickStats.loopEnd(EntityTickStats.LOOP, 0L); // also when the measurement already ended (start == 0)
        assertFalse(EntityTickStats.recording());
    }

    @Test
    void acquireAndReleaseCountConsumers() throws InterruptedException {
        EntityTickStats.acquire();
        EntityTickStats.acquire();
        EntityTickStats.release();
        assertTrue(EntityTickStats.enabled());
        EntityTickStats.release();
        assertFalse(EntityTickStats.enabled());
        EntityTickStats.release(); // extra release does not go negative
        EntityTickStats.acquire();
        assertTrue(EntityTickStats.enabled());
        EntityTickStats.release();

        final Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 10_000; i++) {
                    EntityTickStats.acquire();
                    EntityTickStats.release();
                }
            });
            threads[t].start();
        }
        EntityTickStats.acquire(); // a long-lived consumer racing with the short ones
        for (final Thread thread : threads) {
            thread.join();
        }
        assertTrue(EntityTickStats.enabled());
        EntityTickStats.release();
        assertFalse(EntityTickStats.enabled());
    }

    @Test
    @SuppressWarnings("unchecked")
    void threadsRecordIntoTheirOwnRecordersAndSnapshotsSumThem() throws Exception {
        final int threads = 4;
        final int entities = 20_000;
        final java.util.concurrent.CyclicBarrier start = new java.util.concurrent.CyclicBarrier(threads);
        final java.util.List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final int type = t % 2 == 0 ? ZOMBIE : ITEM;
            final int cls = t % 2 == 0 ? EntityTickStats.MOB : EntityTickStats.OTHER;
            workers[t] = new Thread(() -> {
                try {
                    start.await();
                    // Each entity: 1 ms base, 2 ms goals nested with 1 ms pathfind (own fake clock per thread).
                    long now = 0L;
                    for (int i = 0; i < entities; i++) {
                        EntityTickStats.beginAt(type, cls, now);
                        final int goals = EntityTickStats.enterAt(EntityTickStats.GOALS, now += MS);
                        final int path = EntityTickStats.enterAt(EntityTickStats.PATHFIND, now += MS);
                        EntityTickStats.exitAt(path, now += MS);
                        EntityTickStats.exitAt(goals, now += MS);
                        EntityTickStats.endAt(i % 4 != 0, now);
                    }
                    EntityTickStats.addLoopNanos(EntityTickStats.LOOP, 7L);
                } catch (final Throwable e) {
                    errors.add(e);
                }
            }, "entity-recorder-" + t);
            workers[t].start();
        }
        for (final Thread worker : workers) {
            worker.join();
        }
        assertTrue(errors.isEmpty(), errors.toString());

        final Map<String, Object> snap = EntityTickStats.snapshot(0L, NAMES);
        final long perType = (long) entities * (threads / 2);
        for (final int type : new int[]{ZOMBIE, ITEM}) {
            assertEquals(perType, ((long[]) snap.get("typeTicks"))[type]);
            assertEquals(perType / 4, ((long[]) snap.get("typeInactive"))[type]);
            assertEquals(perType * 4 * MS, ((long[]) snap.get("typeNanos"))[type]);
            final long[] p = ((long[][]) snap.get("typePhaseNanos"))[type];
            assertEquals(perType * MS, p[EntityTickStats.BASE]);
            assertEquals(perType * 2 * MS, p[EntityTickStats.GOALS]);
            assertEquals(perType * MS, p[EntityTickStats.PATHFIND]);
        }
        assertEquals(EntityTickStats.MOB, ((byte[]) snap.get("typeClass"))[ZOMBIE]);
        assertEquals(EntityTickStats.OTHER, ((byte[]) snap.get("typeClass"))[ITEM]);
        assertEquals(7L * threads, ((long[]) snap.get("loopNanos"))[EntityTickStats.LOOP]);
        // The test thread never recorded: its own state is untouched.
        assertFalse(EntityTickStats.recording());
    }

    @Test
    void aThreadInTheMiddleOfAnEntityDoesNotDisturbAnother() throws Exception {
        EntityTickStats.ensureTypes(NAMES.length);
        EntityTickStats.beginAt(ZOMBIE, EntityTickStats.MOB, 0L);
        final int goals = EntityTickStats.enterAt(EntityTickStats.GOALS, 1 * MS);
        final Thread other = new Thread(() -> {
            EntityTickStats.beginAt(VILLAGER, EntityTickStats.MOB, 100 * MS);
            EntityTickStats.enterAt(EntityTickStats.BRAIN, 101 * MS); // left open on purpose
            EntityTickStats.endAt(true, 105 * MS);
        });
        other.start();
        other.join();
        assertTrue(EntityTickStats.recording()); // this thread's entity is still open
        EntityTickStats.exitAt(goals, 3 * MS);
        EntityTickStats.endAt(true, 4 * MS);
        assertEquals(2 * MS, phases(ZOMBIE)[EntityTickStats.GOALS]);
        assertEquals(2 * MS, phases(ZOMBIE)[EntityTickStats.BASE]);
        assertEquals(4 * MS, phases(VILLAGER)[EntityTickStats.BRAIN]);
        assertEquals(1 * MS, phases(VILLAGER)[EntityTickStats.BASE]);
    }
}
