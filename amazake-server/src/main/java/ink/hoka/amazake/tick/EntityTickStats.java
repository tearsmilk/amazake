package ink.hoka.amazake.tick;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * <p>Off by default: the hooks in the Minecraft sources cost one volatile read per entity (and a plain
 * static read per phase) until a consumer calls {@link #acquire()}. Counters are cumulative and never
 * reset; consumers take {@link #snapshot()}s and diff them with {@link #summarize} and {@link #sample}.</p>
 *
 * <p>All recording methods run on the server (tick) thread only, without locks. Phase times are self
 * times: entering a phase charges the time since the last stamp to the phase on top of a small stack.</p>
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

    // ---- tick-thread state (plain fields: only the tick thread touches them) ----
    /** True between beginEntity and endEntity of an entity that is being recorded. */
    private static boolean recording;
    private static int currentType;
    private static long entityStart;
    private static long stamp;
    private static final int[] STACK = new int[STACK_CAPACITY];
    private static int depth; // index of the top of STACK

    // ---- counters (written by the tick thread, read by snapshot() on the same thread) ----
    private static final long[] LOOP_NANOS = new long[3];
    private static long[] typeNanos = new long[0];
    private static long[] typeTicks = new long[0];
    private static long[] typeInactive = new long[0];
    private static long[][] typePhaseNanos = new long[0][];
    private static byte[] typeClass = new byte[0];
    private static String[] typeNames;

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
    // Hooks (tick thread)
    // ------------------------------------------------------------------------------------------

    /** System.nanoTime(), or 0 when disabled. */
    public static long loopStart() {
        return enabled ? System.nanoTime() : 0L;
    }

    public static void loopEnd(final int counter, final long start) {
        if (counter == LOOP) {
            // Runs every tick whatever happened inside: no entity tick is open past the loop.
            recording = false;
        }
        if (start != 0L) {
            LOOP_NANOS[counter] += System.nanoTime() - start;
        }
    }

    public static void beginEntity(final Entity entity) {
        if (!enabled) {
            recording = false; // an entity tick that threw while recording must not leak past the measurement
            return;
        }
        final int cls = entity instanceof Player ? PLAYER : entity instanceof Mob ? MOB : OTHER;
        final int type = BuiltInRegistries.ENTITY_TYPE.getId(entity.getType());
        if (type < 0) {
            return;
        }
        if (typeNanos.length == 0) {
            ensureTypes(BuiltInRegistries.ENTITY_TYPE.size());
        }
        beginAt(type, cls, System.nanoTime());
    }

    public static void endEntity(final boolean active) {
        if (recording) {
            endAt(active, System.nanoTime());
        }
    }

    /**
     * Closes the current entity tick without counting it, if one is still open (its tick threw and
     * {@link #endEntity} was skipped). Called from a finally around the entity tick.
     */
    public static void abortEntity() {
        recording = false;
    }

    public static int enter(final int phase) {
        if (!recording) {
            return -1;
        }
        return enterAt(phase, System.nanoTime());
    }

    public static void exit(final int token) {
        if (recording && token >= 0 && token != OVERFLOW_TOKEN) {
            exitAt(token, System.nanoTime());
        }
    }

    // ---- the accounting, with an explicit clock (unit-tested) ----

    static void beginAt(final int type, final int cls, final long now) {
        if (type >= typeNanos.length) {
            ensureTypes(type + 1);
        }
        typeClass[type] = (byte) cls;
        currentType = type;
        entityStart = now;
        stamp = now;
        depth = 0;
        STACK[0] = BASE;
        recording = true;
    }

    static int enterAt(final int phase, final long now) {
        charge(now);
        if (depth + 1 >= STACK_CAPACITY) {
            return OVERFLOW_TOKEN; // counted into the current top
        }
        final int token = depth;
        STACK[++depth] = phase;
        return token;
    }

    static void exitAt(final int token, final long now) {
        charge(now);
        if (token < depth) {
            depth = token; // also pops frames whose exit an exception skipped
        }
    }

    static void endAt(final boolean active, final long now) {
        charge(now);
        final int type = currentType;
        typeNanos[type] += now - entityStart;
        typeTicks[type]++;
        if (!active) {
            typeInactive[type]++;
        }
        recording = false;
    }

    private static void charge(final long now) {
        typePhaseNanos[currentType][STACK[depth]] += now - stamp;
        stamp = now;
    }

    static void ensureTypes(final int size) {
        if (size <= typeNanos.length) {
            return;
        }
        final int old = typeNanos.length;
        typeNanos = Arrays.copyOf(typeNanos, size);
        typeTicks = Arrays.copyOf(typeTicks, size);
        typeInactive = Arrays.copyOf(typeInactive, size);
        typePhaseNanos = Arrays.copyOf(typePhaseNanos, size);
        typeClass = Arrays.copyOf(typeClass, size);
        for (int i = old; i < size; i++) {
            typePhaseNanos[i] = new long[PHASE_COUNT];
            typeClass[i] = -1;
        }
    }

    /** Test only: forget everything. */
    static void resetForTest() {
        synchronized (USERS_LOCK) {
            users = 0;
        }
        enabled = false;
        recording = false;
        depth = 0;
        Arrays.fill(LOOP_NANOS, 0L);
        typeNanos = new long[0];
        typeTicks = new long[0];
        typeInactive = new long[0];
        typePhaseNanos = new long[0][];
        typeClass = new byte[0];
        typeNames = null;
    }

    static void addLoopNanos(final int counter, final long nanos) {
        LOOP_NANOS[counter] += nanos;
    }

    static boolean recording() {
        return recording;
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    /** A consistent copy of the counters. Main thread only. */
    public static Map<String, Object> snapshot() {
        return snapshot(net.minecraft.server.MinecraftServer.currentTick, typeNames());
    }

    static Map<String, Object> snapshot(final long tick, final String[] names) {
        final int n = typeNanos.length;
        final long[][] phases = new long[n][];
        for (int i = 0; i < n; i++) {
            phases[i] = typePhaseNanos[i].clone();
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("tick", tick);
        out.put("loopNanos", LOOP_NANOS.clone());
        out.put("types", Arrays.copyOf(names == null ? new String[0] : names, n));
        out.put("typeClass", typeClass.clone());
        out.put("typeNanos", typeNanos.clone());
        out.put("typeTicks", typeTicks.clone());
        out.put("typeInactive", typeInactive.clone());
        out.put("typePhaseNanos", phases);
        return out;
    }

    private static String[] typeNames() {
        final int n = typeNanos.length;
        if (typeNames != null && typeNames.length >= n) {
            return typeNames;
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
}
