package core.amazake.tick;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

/**
 * Where the entity tick time goes: per entity type, per class (mob, other, player) and per phase
 * (goals, brain, pathfinding, movement, ...), see docs/profiler-format.md {@code entities}.
 *
 * <p>Off by default: the hooks in the Minecraft sources cost one volatile read each until a consumer calls
 * {@link #acquire()}; while recording, a hook costs at most one {@code ThreadLocal.get}. Counters are
 * cumulative and never reset; consumers take {@link #snapshot()}s and diff them with {@link #summarize}
 * and {@link #sample}.</p>
 *
 * <p>Entities may tick on several threads (later multithreading phases). Each thread records into its own
 * {@link Recorder} (its own phase stack, stamp and per-type counters, no locks); {@link #snapshot()} sums all
 * recorders. It reads other threads' plain long counters without synchronisation: on a 64-bit JVM such a
 * read never tears, it can only miss the last few increments, and snapshots are taken at tick boundaries.
 * Phase times are self times: entering a phase charges the time since the last stamp to the phase on top
 * of a small stack.</p>
 *
 * <p>The reading API uses only plain Java types, so a plugin can reach it through reflection.</p>
 */
public final class EntityTickStats {

    // Phase ids == index in PHASES (display order, also the report's "phases").
    public static final int BASE = 0, GOALS = 1, BRAIN = 2, SENSORS = 3, PATHFIND = 4, NAVIGATION = 5,
        TRAVEL = 6, MOVE = 7, PUSH = 8, LOOT = 9, FLUIDS = 10, MERGE = 11, INACTIVE = 12;
    public static final String[] PHASES = {"base", "goals", "brain", "sensors", "pathfind", "navigation", "travel",
        "move", "push", "loot", "fluids", "merge", "inactive"};
    public static final int MOB = 0, OTHER = 1, PLAYER = 2;
    public static final String[] CLASSES = {"mob", "other", "player"};
    // Loop-level counters.
    public static final int LOOP = 0, EAR = 1, DESPAWN = 2;

    /** Phase stack capacity; deeper enters charge the current top and do not push. */
    static final int STACK_CAPACITY = 16;
    /** Token of an enter beyond the stack capacity: its exit does nothing. */
    static final int OVERFLOW_TOKEN = Integer.MAX_VALUE;

    private static final int PHASE_COUNT = PHASES.length;

    /** Guards {@link #users}; acquire/release are rare, a lock keeps {@code enabled} in step with the count. */
    private static final Object USERS_LOCK = new Object();
    private static int users;
    private static volatile boolean enabled;

    // ---- recording state (per thread, see Recorder) ----
    /** Every recorder ever created, also of threads that died: the counters are cumulative. */
    private static final CopyOnWriteArrayList<Recorder> RECORDERS = new CopyOnWriteArrayList<>();
    /** The current thread's recorder; no initial value, so a thread that never records creates none. Replaced by resetForTest. */
    private static ThreadLocal<Recorder> current = new ThreadLocal<>();
    private static volatile String[] typeNames;

    private EntityTickStats() {
    }

    // ------------------------------------------------------------------------------------------
    // Consumers
    // ------------------------------------------------------------------------------------------

    /** Turns recording on for one more consumer. Any thread. */
    public static void acquire() {
        synchronized (USERS_LOCK) {
            users++;
            enabled = users > 0;
        }
    }

    /** One consumer less; recording stops when none is left. Any thread. */
    public static void release() {
        synchronized (USERS_LOCK) {
            users = Math.max(0, users - 1);
            enabled = users > 0;
        }
    }

    public static boolean enabled() {
        return enabled;
    }

    // ------------------------------------------------------------------------------------------
    // Hooks (any tick thread; each thread records into its own Recorder)
    // ------------------------------------------------------------------------------------------

    /** System.nanoTime(), or 0 when disabled. */
    public static long loopStart() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void loopEnd(final int counter, final long start) {
        if (counter == LOOP) {
            // Runs once per world tick whatever happened inside: no entity tick stays open past the loop
            // (also one that threw while recording stopped, see abortEntity).
            final Recorder recorder = current.get();
            if (recorder != null) {
                recorder.recording = false;
            }
        }
        if (start != 0L) {
            recorder().loopNanos[counter] += System.nanoTime() - start;
        }
    }

    public static void beginEntity(final Entity entity) {
        if (!enabled) {
            return; // a recording flag left by a thrown tick is cleared by loopEnd(LOOP); enter() checks enabled first
        }
        final int cls = entity instanceof Player ? PLAYER : entity instanceof Mob ? MOB : OTHER;
        final int type = BuiltInRegistries.ENTITY_TYPE.getId(entity.getType());
        if (type < 0) {
            return;
        }
        final Recorder recorder = recorder();
        if (recorder.typeTicks.length == 0) {
            recorder.ensureTypes(BuiltInRegistries.ENTITY_TYPE.size());
        }
        recorder.beginAt(type, cls, System.nanoTime());
    }

    public static void endEntity(final boolean active) {
        if (!enabled) {
            return;
        }
        final Recorder recorder = current.get();
        if (recorder != null && recorder.recording) {
            recorder.endAt(active, System.nanoTime());
        }
    }

    /**
     * Closes the current entity tick without counting it, if one is still open (its tick threw and
     * {@link #endEntity} was skipped). Called from a finally around the entity tick. While not recording it
     * does nothing (one volatile read); a flag it leaves is harmless and cleared by {@link #loopEnd}.
     */
    public static void abortEntity() {
        if (enabled) {
            final Recorder recorder = current.get();
            if (recorder != null) {
                recorder.recording = false;
            }
        }
    }

    public static int enter(final int phase) {
        if (!enabled) {
            return -1;
        }
        final Recorder recorder = current.get();
        if (recorder == null || !recorder.recording) {
            return -1;
        }
        return recorder.enterAt(phase, System.nanoTime());
    }

    public static void exit(final int token) {
        if (token >= 0 && token != OVERFLOW_TOKEN) {
            final Recorder recorder = current.get();
            if (recorder != null && recorder.recording) {
                recorder.exitAt(token, System.nanoTime());
            }
        }
    }

    // ---- the accounting on the current thread's recorder, with an explicit clock (unit-tested) ----

    /** The current thread's recorder, created (and registered) on first use. */
    static Recorder recorder() {
        final ThreadLocal<Recorder> local = current;
        Recorder recorder = local.get();
        if (recorder == null) {
            recorder = new Recorder();
            local.set(recorder);
            RECORDERS.add(recorder);
        }
        return recorder;
    }

    static void beginAt(final int type, final int cls, final long now) {
        recorder().beginAt(type, cls, now);
    }

    static int enterAt(final int phase, final long now) {
        return recorder().enterAt(phase, now);
    }

    static void exitAt(final int token, final long now) {
        recorder().exitAt(token, now);
    }

    static void endAt(final boolean active, final long now) {
        recorder().endAt(active, now);
    }

    static void ensureTypes(final int size) {
        recorder().ensureTypes(size);
    }

    /** Test only: forget everything. */
    static void resetForTest() {
        synchronized (USERS_LOCK) {
            users = 0;
        }
        enabled = false;
        RECORDERS.clear();
        current = new ThreadLocal<>();
        typeNames = null;
    }

    static void addLoopNanos(final int counter, final long nanos) {
        recorder().loopNanos[counter] += nanos;
    }

    static boolean recording() {
        final Recorder recorder = current.get();
        return recorder != null && recorder.recording;
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    /**
     * A copy of the counters, all threads' recorders summed. Any thread; meant to be taken on the server
     * thread at a tick boundary (see the class comment on reading other threads' counters).
     */
    public static Map<String, Object> snapshot() {
        return snapshot(net.minecraft.server.MinecraftServer.currentTick, null);
    }

    /** @param names entity type names by id, or null to look them up in the registry */
    static Map<String, Object> snapshot(final long tick, final String[] names) {
        int n = 0;
        for (final Recorder recorder : RECORDERS) {
            n = Math.max(n, recorder.typeTicks.length);
        }
        final long[] loop = new long[LOOP_NANOS_SIZE];
        final long[] nanos = new long[n];
        final long[] ticks = new long[n];
        final long[] inactive = new long[n];
        final long[][] phases = new long[n][PHASE_COUNT];
        final byte[] classes = new byte[n];
        Arrays.fill(classes, (byte) -1);
        for (final Recorder recorder : RECORDERS) {
            for (int c = 0; c < LOOP_NANOS_SIZE; c++) {
                loop[c] += recorder.loopNanos[c];
            }
            // typeTicks is replaced last when a recorder grows: every array is at least that long.
            final long[] rTicks = recorder.typeTicks;
            final long[] rNanos = recorder.typeNanos;
            final long[] rInactive = recorder.typeInactive;
            final long[][] rPhases = recorder.typePhaseNanos;
            final byte[] rClass = recorder.typeClass;
            for (int i = 0; i < rTicks.length; i++) {
                ticks[i] += rTicks[i];
                nanos[i] += rNanos[i];
                inactive[i] += rInactive[i];
                final long[] p = rPhases[i];
                for (int k = 0; k < PHASE_COUNT; k++) {
                    phases[i][k] += p[k];
                }
                if (classes[i] < 0) {
                    classes[i] = rClass[i];
                }
            }
        }
        final String[] typeNames = names != null ? names : typeNames(n);
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("tick", tick);
        out.put("loopNanos", loop);
        out.put("types", Arrays.copyOf(typeNames == null ? new String[0] : typeNames, n));
        out.put("typeClass", classes);
        out.put("typeNanos", nanos);
        out.put("typeTicks", ticks);
        out.put("typeInactive", inactive);
        out.put("typePhaseNanos", phases);
        return out;
    }

    private static String[] typeNames(final int n) {
        final String[] known = typeNames;
        if (known != null && known.length >= n) {
            return known;
        }
        final String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            final EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.byId(i);
            final Identifier key = type == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(type);
            names[i] = key == null ? null : key.toString();
        }
        typeNames = names;
        return names;
    }

    /**
     * The report object of docs/profiler-format.md {@code entities} between two snapshots, or null
     * when no tick passed (or a snapshot is missing).
     */
    public static Map<String, Object> summarize(final Map<String, Object> from, final Map<String, Object> to, final int topTypes) {
        final long ticks = ticksBetween(from, to);
        if (ticks <= 0L) {
            return null;
        }
        final Diff diff = new Diff(from, to);

        final long[][] classPhases = new long[CLASSES.length][PHASE_COUNT];
        final long[] classTicks = new long[CLASSES.length];
        final long[] classInactive = new long[CLASSES.length];
        final List<TypeRow> rows = new ArrayList<>();
        for (int i = 0; i < diff.types; i++) {
            final int cls = diff.typeClass(i);
            if (cls < 0 || cls >= CLASSES.length) {
                continue;
            }
            final long[] phases = diff.phases(i);
            long nanos = 0L;
            for (int p = 0; p < PHASE_COUNT; p++) {
                classPhases[cls][p] += phases[p];
                nanos += phases[p];
            }
            final long entityTicks = diff.ticks(i);
            final long inactive = diff.inactive(i);
            classTicks[cls] += entityTicks;
            classInactive[cls] += inactive;
            if (nanos > 0L || entityTicks > 0L) {
                rows.add(new TypeRow(diff.name(i), cls, nanos, entityTicks, inactive, phases));
            }
        }

        double classesMs = 0.0;
        final List<Map<String, Object>> classes = new ArrayList<>(CLASSES.length);
        for (int c = 0; c < CLASSES.length; c++) {
            long nanos = 0L;
            for (final long value : classPhases[c]) {
                nanos += value;
            }
            classesMs += msPerTick(nanos, ticks);
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("class", CLASSES[c]);
            putCommon(entry, nanos, classTicks[c], classInactive[c], classPhases[c], ticks);
            classes.add(entry);
        }

        final double loopMs = msPerTick(diff.loop(LOOP), ticks);
        final double earMs = msPerTick(diff.loop(EAR), ticks);
        final double despawnMs = msPerTick(diff.loop(DESPAWN), ticks);
        final Map<String, Object> loop = new LinkedHashMap<>();
        loop.put("loop", round(loopMs));
        loop.put("ear", round(earMs));
        loop.put("despawn", round(despawnMs));
        // Clamped: a snapshot taken by a task run in the middle of the loop can count an entity before its loop.
        loop.put("overhead", round(Math.max(0.0, loopMs - earMs - despawnMs - classesMs)));

        rows.sort((a, b) -> Long.compare(b.nanos, a.nanos));
        final List<Map<String, Object>> types = new ArrayList<>(Math.min(rows.size(), Math.max(0, topTypes)));
        for (int i = 0; i < rows.size() && i < topTypes; i++) {
            final TypeRow row = rows.get(i);
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", row.name);
            entry.put("class", CLASSES[row.cls]);
            putCommon(entry, row.nanos, row.ticks, row.inactive, row.phases, ticks);
            final Object ms = entry.remove("phases");
            entry.put("usPerEntity", row.ticks <= 0L ? 0.0 : round(row.nanos / 1000.0 / row.ticks));
            entry.put("phases", ms);
            types.add(entry);
        }

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticks", ticks);
        out.put("phases", List.of(PHASES));
        out.put("msPerTick", loop);
        out.put("classes", classes);
        out.put("types", types);
        return out;
    }

    /** {"loop","mob","other","player"} in ms per tick between two snapshots, or null. */
    public static Map<String, Object> sample(final Map<String, Object> from, final Map<String, Object> to) {
        final long ticks = ticksBetween(from, to);
        if (ticks <= 0L) {
            return null;
        }
        final Diff diff = new Diff(from, to);
        final long[] classNanos = new long[CLASSES.length];
        for (int i = 0; i < diff.types; i++) {
            final int cls = diff.typeClass(i);
            if (cls < 0 || cls >= CLASSES.length) {
                continue;
            }
            for (final long value : diff.phases(i)) {
                classNanos[cls] += value;
            }
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("loop", round(msPerTick(diff.loop(LOOP), ticks)));
        for (int c = 0; c < CLASSES.length; c++) {
            out.put(CLASSES[c], round(msPerTick(classNanos[c], ticks)));
        }
        return out;
    }

    private static void putCommon(final Map<String, Object> entry, final long nanos, final long entityTicks, final long inactive,
                                  final long[] phases, final long ticks) {
        entry.put("msPerTick", round(msPerTick(nanos, ticks)));
        entry.put("entitiesPerTick", round((double) entityTicks / ticks));
        entry.put("inactiveShare", entityTicks <= 0L ? 0.0 : round((double) inactive / entityTicks));
        final Map<String, Object> phaseMs = new LinkedHashMap<>();
        for (int p = 0; p < PHASE_COUNT; p++) {
            if (phases[p] > 0L) {
                phaseMs.put(PHASES[p], round(msPerTick(phases[p], ticks)));
            }
        }
        entry.put("phases", phaseMs);
    }

    private static long ticksBetween(final Map<String, Object> from, final Map<String, Object> to) {
        if (from == null || to == null || !(from.get("tick") instanceof Number a) || !(to.get("tick") instanceof Number b)) {
            return 0L;
        }
        return b.longValue() - a.longValue();
    }

    private static double msPerTick(final long nanos, final long ticks) {
        return nanos / 1_000_000.0 / ticks;
    }

    static double round(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    private record TypeRow(String name, int cls, long nanos, long ticks, long inactive, long[] phases) {
    }

    /** Counter differences between two snapshots; arrays may differ in length (missing = 0). */
    private static final class Diff {
        final int types;
        private final long[] fromLoop;
        private final long[] toLoop;
        private final String[] names;
        private final byte[] fromClass;
        private final byte[] toClass;
        private final long[] fromTicks;
        private final long[] toTicks;
        private final long[] fromInactive;
        private final long[] toInactive;
        private final long[][] fromPhases;
        private final long[][] toPhases;

        Diff(final Map<String, Object> from, final Map<String, Object> to) {
            this.fromLoop = longs(from.get("loopNanos"));
            this.toLoop = longs(to.get("loopNanos"));
            this.names = to.get("types") instanceof String[] s ? s : new String[0];
            this.fromClass = bytes(from.get("typeClass"));
            this.toClass = bytes(to.get("typeClass"));
            this.fromTicks = longs(from.get("typeTicks"));
            this.toTicks = longs(to.get("typeTicks"));
            this.fromInactive = longs(from.get("typeInactive"));
            this.toInactive = longs(to.get("typeInactive"));
            this.fromPhases = from.get("typePhaseNanos") instanceof long[][] p ? p : new long[0][];
            this.toPhases = to.get("typePhaseNanos") instanceof long[][] p ? p : new long[0][];
            this.types = Math.max(this.toTicks.length, this.toPhases.length);
        }

        long loop(final int counter) {
            return Math.max(0L, at(this.toLoop, counter) - at(this.fromLoop, counter));
        }

        int typeClass(final int i) {
            if (i < this.toClass.length && this.toClass[i] >= 0) {
                return this.toClass[i];
            }
            return i < this.fromClass.length ? this.fromClass[i] : -1;
        }

        String name(final int i) {
            final String name = i < this.names.length ? this.names[i] : null;
            return name != null ? name : "unknown#" + i;
        }

        long ticks(final int i) {
            return Math.max(0L, at(this.toTicks, i) - at(this.fromTicks, i));
        }

        long inactive(final int i) {
            return Math.max(0L, at(this.toInactive, i) - at(this.fromInactive, i));
        }

        long[] phases(final int i) {
            final long[] to = i < this.toPhases.length ? this.toPhases[i] : null;
            final long[] from = i < this.fromPhases.length ? this.fromPhases[i] : null;
            final long[] out = new long[PHASE_COUNT];
            for (int p = 0; p < PHASE_COUNT; p++) {
                out[p] = Math.max(0L, (to == null ? 0L : at(to, p)) - (from == null ? 0L : at(from, p)));
            }
            return out;
        }

        private static long at(final long[] values, final int i) {
            return i < values.length ? values[i] : 0L;
        }

        private static long[] longs(final Object value) {
            return value instanceof long[] l ? l : new long[0];
        }

        private static byte[] bytes(final Object value) {
            return value instanceof byte[] b ? b : new byte[0];
        }
    }

    private static final int LOOP_NANOS_SIZE = 3;

    /**
     * One thread's recording state and counters. Only its own thread writes it; {@link #snapshot} reads the
     * counters from other threads (benign races, see the class comment). The array fields are volatile so a
     * reader that sees a grown array also sees its copied contents.
     */
    static final class Recorder {
        /** True between beginEntity and endEntity of an entity that is being recorded. */
        boolean recording;
        private int currentType;
        private long entityStart;
        private long stamp;
        private final int[] stack = new int[STACK_CAPACITY];
        private int depth; // index of the top of stack

        final long[] loopNanos = new long[LOOP_NANOS_SIZE];
        volatile long[] typeNanos = new long[0];
        volatile long[] typeInactive = new long[0];
        volatile long[][] typePhaseNanos = new long[0][];
        volatile byte[] typeClass = new byte[0];
        /** Replaced last when growing, so readers can bound every array by its length. */
        volatile long[] typeTicks = new long[0];

        void beginAt(final int type, final int cls, final long now) {
            if (type >= this.typeTicks.length) {
                this.ensureTypes(type + 1);
            }
            this.typeClass[type] = (byte) cls;
            this.currentType = type;
            this.entityStart = now;
            this.stamp = now;
            this.depth = 0;
            this.stack[0] = BASE;
            this.recording = true;
        }

        int enterAt(final int phase, final long now) {
            this.charge(now);
            if (this.depth + 1 >= STACK_CAPACITY) {
                return OVERFLOW_TOKEN; // counted into the current top
            }
            final int token = this.depth;
            this.stack[++this.depth] = phase;
            return token;
        }

        void exitAt(final int token, final long now) {
            this.charge(now);
            if (token < this.depth) {
                this.depth = token; // also pops frames whose exit an exception skipped
            }
        }

        void endAt(final boolean active, final long now) {
            this.charge(now);
            final int type = this.currentType;
            this.typeNanos[type] += now - this.entityStart;
            this.typeTicks[type]++;
            if (!active) {
                this.typeInactive[type]++;
            }
            this.recording = false;
        }

        private void charge(final long now) {
            this.typePhaseNanos[this.currentType][this.stack[this.depth]] += now - this.stamp;
            this.stamp = now;
        }

        void ensureTypes(final int size) {
            final int old = this.typeTicks.length;
            if (size <= old) {
                return;
            }
            this.typeNanos = Arrays.copyOf(this.typeNanos, size);
            this.typeInactive = Arrays.copyOf(this.typeInactive, size);
            final long[][] phases = Arrays.copyOf(this.typePhaseNanos, size);
            final byte[] classes = Arrays.copyOf(this.typeClass, size);
            for (int i = old; i < size; i++) {
                phases[i] = new long[PHASE_COUNT];
                classes[i] = -1;
            }
            this.typePhaseNanos = phases;
            this.typeClass = classes;
            this.typeTicks = Arrays.copyOf(this.typeTicks, size);
        }
    }
}
