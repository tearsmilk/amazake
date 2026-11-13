package ink.hoka.amazake.plugin;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import org.bukkit.plugin.Plugin;

/**
 * Each plugin's work on the main thread ("the bridge": sync scheduler tasks, sync event listeners,
 * commands) and the CPU time of its async scheduler tasks; see docs/profiler-format.md {@code plugins}.
 *
 * <p>Plugin code keeps running exactly where Bukkit runs it; this class only measures. Off by default:
 * the hooks cost one volatile read until a consumer calls {@link #acquire()}. Counters are cumulative and
 * never reset; consumers take {@link #snapshot()}s and diff them with {@link #summarize} and {@link #sample}.</p>
 *
 * <p>Main-thread times are self times: entering a plugin's code charges the time since the last stamp to the
 * plugin and kind on top of a small stack, so a listener fired from another plugin's task is charged to the
 * listener's plugin only. Recording happens on any tick thread ({@code TickThread}: today the server thread
 * and Paper's watchdog, later the region threads). Each thread records into its own {@link Recorder} (its own
 * stack, stamp and counters, no locks); {@link #snapshot()} sums all recorders. The snapshot reads other
 * threads' plain long counters without synchronisation: on a 64-bit JVM such a read never tears, it can only
 * miss the last few increments, and snapshots are taken at tick boundaries where the writers are idle anyway.
 * The async counters are concurrent.</p>
 *
 * <p>With {@link ink.hoka.amazake.threading.PluginLock} active it also records, per plugin, how long threads
 * waited for the lock before running that plugin's code, and how long the lock was held in all.</p>
 *
 * <p>The reading API uses only plain Java types, so a plugin can reach it through reflection.</p>
 */
public final class PluginTickStats {

    public static final int TASKS = 0, EVENTS = 1, COMMANDS = 2;
    public static final String[] KINDS = {"tasks", "events", "commands"};

    /** Stack capacity; deeper enters charge the current top and do not push. */
    static final int STACK_CAPACITY = 32;
    /** Token of an enter beyond the stack capacity: its exit does nothing. */
    static final int OVERFLOW_TOKEN = Integer.MAX_VALUE;

    private static final int KIND_COUNT = KINDS.length;
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    /** Guards {@link #users}; acquire/release are rare, a lock keeps {@code enabled} in step with the count. */
    private static final Object USERS_LOCK = new Object();
    private static int users;
    private static volatile boolean enabled;
    /** Null until the first async measurement checks whether per-thread CPU time is available. */
    private static volatile Boolean threadCpuSupported;

    // ---- recording state (per thread, see Recorder) ----
    /** Plugin slots by plugin name, not by Plugin: holding Plugin objects would keep a disabled or reloaded plugin's class loader alive. */
    private static final ConcurrentHashMap<String, Integer> INDEX = new ConcurrentHashMap<>();
    /** Slot -> plugin name; replaced (copy on write) under {@link #INDEX_LOCK} when a slot is added. */
    private static volatile String[] names = new String[0];
    private static final Object INDEX_LOCK = new Object();
    /** Every recorder ever created, also of threads that died: the counters are cumulative. */
    private static final CopyOnWriteArrayList<Recorder> RECORDERS = new CopyOnWriteArrayList<>();
    /** The current thread's recorder; no initial value, so a thread that never records creates none. Replaced by resetForTest. */
    private static ThreadLocal<Recorder> current = new ThreadLocal<>();

    // ---- async counters (any thread) ----
    private static final ConcurrentHashMap<String, LongAdder> ASYNC_CPU = new ConcurrentHashMap<>();

    private PluginTickStats() {
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
    // Hooks
    // ------------------------------------------------------------------------------------------

    /**
     * Starts timing {@code plugin}'s code of {@code kind} on the current tick thread.
     *
     * @return a token for {@link #exit}, or -1 when not recording, off a tick thread or without a plugin
     */
    public static int enter(final Plugin plugin, final int kind) {
        if (!enabled || plugin == null || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            return -1;
        }
        return enterAt(indexOf(plugin), kind, System.nanoTime());
    }

    /** Ends the code started by {@link #enter} on the same thread; also pops inner frames whose exit was skipped. */
    public static void exit(final int token) {
        // Not gated by enabled: a frame opened while recording is always closed, even if recording stopped meanwhile.
        if (token >= 0 && token != OVERFLOW_TOKEN) {
            exitAt(token, System.nanoTime());
        }
    }

    /**
     * Charges {@code nanos} of waiting for the plugin lock to {@code plugin} (the plugin whose code was about
     * to run). Called by PluginLock on a tick thread; does nothing when not recording.
     */
    public static void lockWait(final Plugin plugin, final long nanos) {
        if (enabled && plugin != null && nanos > 0L && ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            addLockWait(indexOf(plugin), nanos);
        }
    }

    /** Adds {@code nanos} the plugin lock was held (one outermost hold). Called by PluginLock on a tick thread. */
    public static void lockHeld(final long nanos) {
        if (enabled && nanos > 0L && ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            addLockHeld(nanos);
        }
    }

    /** CPU time of the current thread, or -1 when not recording. Call before an async task runs. */
    public static long asyncStart() {
        if (!enabled) {
            return -1L;
        }
        Boolean supported = threadCpuSupported;
        if (supported == null) {
            boolean ok;
            try {
                ok = THREADS.isCurrentThreadCpuTimeSupported();
                if (ok && !THREADS.isThreadCpuTimeEnabled()) {
                    THREADS.setThreadCpuTimeEnabled(true);
                }
            } catch (final RuntimeException e) {
                ok = false;
            }
            threadCpuSupported = supported = ok;
        }
        return supported ? THREADS.getCurrentThreadCpuTime() : -1L;
    }

    /** Charges the CPU time since {@link #asyncStart()} to {@code plugin}. Any thread. */
    public static void asyncEnd(final Plugin plugin, final long start) {
        if (start < 0L || plugin == null) {
            return;
        }
        final long now = THREADS.getCurrentThreadCpuTime();
        if (now > start) {
            addAsync(plugin.getName(), now - start);
        }
    }

    // ---- the accounting, with an explicit clock (unit-tested) ----

    static int indexOf(final Plugin plugin) {
        final String name = plugin.getName();
        final Integer known = INDEX.get(name);
        if (known != null) {
            return known;
        }
        return INDEX.computeIfAbsent(name, PluginTickStats::register);
    }

    /** A new plugin slot (one per plugin name). Any thread. */
    static int register(final String name) {
        synchronized (INDEX_LOCK) {
            final String[] next = Arrays.copyOf(names, names.length + 1);
            next[next.length - 1] = name;
            names = next;
            return next.length - 1;
        }
    }

    /** The current thread's recorder, created (and registered) on first use. */
    static Recorder recorder() {
        final ThreadLocal<Recorder> local = current;
        Recorder recorder = local.get();
        if (recorder == null) {
            recorder = new Recorder(Thread.currentThread().getName());
            local.set(recorder);
            RECORDERS.add(recorder);
        }
        return recorder;
    }

    static int enterAt(final int plugin, final int kind, final long now) {
        return recorder().enterAt(plugin, kind, now);
    }

    static void exitAt(final int token, final long now) {
        final Recorder recorder = current.get();
        if (recorder != null) {
            recorder.exitAt(token, now);
        }
    }

    static void addLockWait(final int plugin, final long nanos) {
        final Recorder recorder = recorder();
        recorder.ensure(plugin);
        recorder.lockWait[plugin] += nanos;
    }

    static void addLockHeld(final long nanos) {
        recorder().lockHeld += nanos;
    }

    static void addAsync(final String name, final long cpuNanos) {
        ASYNC_CPU.computeIfAbsent(name, n -> new LongAdder()).add(cpuNanos);
    }

    /** The current thread's stack depth (0 = no plugin code open). */
    static int depth() {
        final Recorder recorder = current.get();
        return recorder == null ? 0 : recorder.depth;
    }

    /** Test only: forget everything. */
    static void resetForTest() {
        synchronized (USERS_LOCK) {
            users = 0;
        }
        enabled = false;
        INDEX.clear();
        names = new String[0];
        RECORDERS.clear();
        current = new ThreadLocal<>();
        ASYNC_CPU.clear();
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    /**
     * A copy of the counters, all threads' recorders summed. Any thread; meant to be taken on the server
     * thread at a tick boundary (see the class comment on reading other threads' counters).
     */
    public static Map<String, Object> snapshot() {
        return snapshot(net.minecraft.server.MinecraftServer.currentTick, System.nanoTime(),
            ink.hoka.amazake.threading.PluginLock.active());
    }

    static Map<String, Object> snapshot(final long tick, final long wallNanos) {
        return snapshot(tick, wallNanos, false);
    }

    static Map<String, Object> snapshot(final long tick, final long wallNanos, final boolean lockActive) {
        final String[] slots = names;
        final int n = slots.length;
        final long[][] sumNanos = new long[n][KIND_COUNT];
        final long[] sumCalls = new long[n];
        final long[] sumWait = new long[n];
        long held = 0L;
        for (final Recorder recorder : RECORDERS) {
            final long[][] nanos = recorder.nanos;
            final long[] calls = recorder.calls;
            final long[] wait = recorder.lockWait;
            for (int i = 0; i < n; i++) {
                if (i < nanos.length) {
                    final long[] kinds = nanos[i];
                    for (int k = 0; k < KIND_COUNT; k++) {
                        sumNanos[i][k] += kinds[k];
                    }
                }
                if (i < calls.length) {
                    sumCalls[i] += calls[i];
                }
                if (i < wait.length) {
                    sumWait[i] += wait[i];
                }
            }
            held += recorder.lockHeld;
        }
        final Map<String, Long> async = new LinkedHashMap<>();
        ASYNC_CPU.forEach((name, adder) -> async.put(name, adder.sum()));
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("tick", tick);
        out.put("wallNanos", wallNanos);
        out.put("names", slots.clone());
        out.put("nanos", sumNanos);
        out.put("calls", sumCalls);
        out.put("asyncCpuNanos", async);
        out.put("lockWaitNanos", sumWait);
        out.put("lockHeldNanos", held);
        out.put("lockActive", lockActive);
        return out;
    }

    /**
     * The report object of docs/profiler-format.md {@code plugins} between two snapshots, or null when no
     * tick passed (or a snapshot is missing).
     */
    public static Map<String, Object> summarize(final Map<String, Object> from, final Map<String, Object> to, final int top) {
        final long ticks = ticksBetween(from, to);
        if (ticks <= 0L) {
            return null;
        }
        final Map<String, Row> rows = rows(from, to);
        final double seconds = Math.max(1e-9, (number(to.get("wallNanos")) - number(from.get("wallNanos"))) / 1e9);
        final long held = Math.max(0L, number(to.get("lockHeldNanos")) - number(from.get("lockHeldNanos")));
        // The lock fields are written when the plugin lock was on at either end, or did anything in between.
        boolean lock = Boolean.TRUE.equals(from.get("lockActive")) || Boolean.TRUE.equals(to.get("lockActive")) || held > 0L;

        long total = 0L;
        final List<Row> list = new ArrayList<>();
        for (final Row row : rows.values()) {
            total += row.mainNanos();
            lock |= row.lockWaitNanos > 0L;
            if (row.mainNanos() > 0L || row.calls > 0L || row.asyncNanos > 0L || row.lockWaitNanos > 0L) {
                list.add(row);
            }
        }
        list.sort((a, b) -> {
            final int main = Long.compare(b.mainNanos(), a.mainNanos());
            return main != 0 ? main : Long.compare(b.asyncNanos, a.asyncNanos);
        });

        final List<Map<String, Object>> plugins = new ArrayList<>();
        for (int i = 0; i < list.size() && i < top; i++) {
            final Row row = list.get(i);
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", row.name);
            entry.put("msPerTick", round(msPerTick(row.mainNanos(), ticks)));
            for (int k = 0; k < KIND_COUNT; k++) {
                entry.put(KINDS[k], round(msPerTick(row.nanos[k], ticks)));
            }
            entry.put("callsPerTick", round((double) row.calls / ticks));
            entry.put("asyncCpuMsPerSecond", round(row.asyncNanos / 1_000_000.0 / seconds));
            if (lock) {
                entry.put("lockWaitMsPerTick", round(msPerTick(row.lockWaitNanos, ticks)));
            }
            plugins.add(entry);
        }

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticks", ticks);
        out.put("msPerTick", round(msPerTick(total, ticks)));
        if (lock) {
            out.put("lockHeldMsPerTick", round(msPerTick(held, ticks)));
        }
        out.put("plugins", plugins);
        return out;
    }

    /** All plugins' main-thread time in ms per tick between two snapshots, or null. */
    public static Double sample(final Map<String, Object> from, final Map<String, Object> to) {
        final long ticks = ticksBetween(from, to);
        if (ticks <= 0L) {
            return null;
        }
        long total = 0L;
        for (final Row row : rows(from, to).values()) {
            total += row.mainNanos();
        }
        return round(msPerTick(total, ticks));
    }

    /** Per plugin name: counter differences (slots of the same name merged, missing = 0). */
    private static Map<String, Row> rows(final Map<String, Object> from, final Map<String, Object> to) {
        final String[] toNames = to.get("names") instanceof String[] s ? s : new String[0];
        final long[][] toNanos = to.get("nanos") instanceof long[][] l ? l : new long[0][];
        final long[][] fromNanos = from.get("nanos") instanceof long[][] l ? l : new long[0][];
        final long[] toCalls = to.get("calls") instanceof long[] l ? l : new long[0];
        final long[] fromCalls = from.get("calls") instanceof long[] l ? l : new long[0];
        final long[] toWait = to.get("lockWaitNanos") instanceof long[] l ? l : new long[0];
        final long[] fromWait = from.get("lockWaitNanos") instanceof long[] l ? l : new long[0];

        final Map<String, Row> rows = new LinkedHashMap<>();
        for (int i = 0; i < toNames.length; i++) {
            final String name = toNames[i] == null ? "unknown#" + i : toNames[i];
            final Row row = rows.computeIfAbsent(name, Row::new);
            final long[] t = i < toNanos.length ? toNanos[i] : null;
            final long[] f = i < fromNanos.length ? fromNanos[i] : null;
            for (int k = 0; k < KIND_COUNT; k++) {
                row.nanos[k] += Math.max(0L, at(t, k) - at(f, k));
            }
            row.calls += Math.max(0L, at(toCalls, i) - at(fromCalls, i));
            row.lockWaitNanos += Math.max(0L, at(toWait, i) - at(fromWait, i));
        }
        final Map<?, ?> toAsync = to.get("asyncCpuNanos") instanceof Map<?, ?> m ? m : Map.of();
        final Map<?, ?> fromAsync = from.get("asyncCpuNanos") instanceof Map<?, ?> m ? m : Map.of();
        for (final Map.Entry<?, ?> entry : toAsync.entrySet()) {
            final long delta = number(entry.getValue()) - number(fromAsync.get(entry.getKey()));
            if (delta > 0L) {
                rows.computeIfAbsent(String.valueOf(entry.getKey()), Row::new).asyncNanos += delta;
            }
        }
        return rows;
    }

    private static long at(final long[] values, final int i) {
        return values != null && i < values.length ? values[i] : 0L;
    }

    private static long number(final Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    private static long ticksBetween(final Map<String, Object> from, final Map<String, Object> to) {
        if (from == null || to == null || !(from.get("tick") instanceof Number a) || !(to.get("tick") instanceof Number b)) {
            return 0L;
        }
        return b.longValue() - a.longValue();
    }

    private static double msPerTick(final long value, final long ticks) {
        return value / 1_000_000.0 / ticks;
    }

    static double round(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static final class Row {
        final String name;
        final long[] nanos = new long[KIND_COUNT];
        long calls;
        long asyncNanos;
        long lockWaitNanos;

        Row(final String name) {
            this.name = name;
        }

        long mainNanos() {
            long sum = 0L;
            for (final long value : this.nanos) {
                sum += value;
            }
            return sum;
        }
    }

    /**
     * One thread's recording state and counters. Only its own thread writes it; {@link #snapshot} reads the
     * counters from other threads (benign races, see the class comment). The array fields are volatile so a
     * reader that sees a grown array also sees its copied contents.
     */
    static final class Recorder {
        final String threadName;
        /** Slot -> kind -> self nanos. */
        volatile long[][] nanos = new long[0][];
        volatile long[] calls = new long[0];
        volatile long[] lockWait = new long[0];
        long lockHeld;
        private long stamp;
        /** Frame i (1..depth) is plugin stackPlugin[i] doing stackKind[i]; frame 0 is "no plugin". */
        private final int[] stackPlugin = new int[STACK_CAPACITY];
        private final int[] stackKind = new int[STACK_CAPACITY];
        int depth;

        Recorder(final String threadName) {
            this.threadName = threadName;
        }

        void ensure(final int plugin) {
            if (plugin < this.calls.length) {
                return;
            }
            final int size = Math.max(plugin + 1, names.length);
            final long[][] grown = Arrays.copyOf(this.nanos, size);
            for (int i = this.nanos.length; i < size; i++) {
                grown[i] = new long[KIND_COUNT];
            }
            this.nanos = grown;
            this.lockWait = Arrays.copyOf(this.lockWait, size);
            this.calls = Arrays.copyOf(this.calls, size); // last: ensure() checks calls.length
        }

        int enterAt(final int plugin, final int kind, final long now) {
            this.ensure(plugin);
            this.charge(now);
            this.calls[plugin]++;
            if (this.depth + 1 >= STACK_CAPACITY) {
                return OVERFLOW_TOKEN; // its time stays with the current top
            }
            final int token = this.depth;
            this.depth++;
            this.stackPlugin[this.depth] = plugin;
            this.stackKind[this.depth] = kind;
            return token;
        }

        void exitAt(final int token, final long now) {
            this.charge(now);
            if (token < this.depth) {
                this.depth = token;
            }
        }

        private void charge(final long now) {
            if (this.depth > 0) {
                this.nanos[this.stackPlugin[this.depth]][this.stackKind[this.depth]] += now - this.stamp;
            }
            this.stamp = now;
        }
    }
}
