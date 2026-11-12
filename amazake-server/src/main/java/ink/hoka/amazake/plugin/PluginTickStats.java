package ink.hoka.amazake.plugin;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * listener's plugin only. All main-thread recording runs on the tick thread without locks; the async
 * counters are concurrent.</p>
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

    // ---- tick-thread state (plain fields: only the tick thread touches them) ----
    private static final IdentityHashMap<Plugin, Integer> INDEX = new IdentityHashMap<>();
    private static String[] names = new String[0];
    private static long[][] nanos = new long[0][];
    private static long[] calls = new long[0];
    private static long stamp;
    /** Frame i (1..depth) is plugin STACK_PLUGIN[i] doing STACK_KIND[i]; frame 0 is "no plugin". */
    private static final int[] STACK_PLUGIN = new int[STACK_CAPACITY];
    private static final int[] STACK_KIND = new int[STACK_CAPACITY];
    private static int depth;

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
     * Starts timing {@code plugin}'s code of {@code kind} on the main thread.
     *
     * @return a token for {@link #exit}, or -1 when not recording, off the tick thread or without a plugin
     */
    public static int enter(final Plugin plugin, final int kind) {
        if (!enabled || plugin == null || !ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) {
            return -1;
        }
        return enterAt(indexOf(plugin), kind, System.nanoTime());
    }

    /** Ends the code started by {@link #enter}; also pops inner frames whose exit was skipped. */
    public static void exit(final int token) {
        // Not gated by enabled: a frame opened while recording is always closed, even if recording stopped meanwhile.
        if (token >= 0 && token != OVERFLOW_TOKEN) {
            exitAt(token, System.nanoTime());
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
        final Integer known = INDEX.get(plugin);
        if (known != null) {
            return known;
        }
        final int index = register(plugin.getName());
        INDEX.put(plugin, index);
        return index;
    }

    /** A new plugin slot (a reloaded plugin with the same name gets another slot; the report merges by name). */
    static int register(final String name) {
        final int index = names.length;
        names = Arrays.copyOf(names, index + 1);
        names[index] = name;
        nanos = Arrays.copyOf(nanos, index + 1);
        nanos[index] = new long[KIND_COUNT];
        calls = Arrays.copyOf(calls, index + 1);
        return index;
    }

    static int enterAt(final int plugin, final int kind, final long now) {
        charge(now);
        calls[plugin]++;
        if (depth + 1 >= STACK_CAPACITY) {
            return OVERFLOW_TOKEN; // its time stays with the current top
        }
        final int token = depth;
        depth++;
        STACK_PLUGIN[depth] = plugin;
        STACK_KIND[depth] = kind;
        return token;
    }

    static void exitAt(final int token, final long now) {
        charge(now);
        if (token < depth) {
            depth = token;
        }
    }

    private static void charge(final long now) {
        if (depth > 0) {
            nanos[STACK_PLUGIN[depth]][STACK_KIND[depth]] += now - stamp;
        }
        stamp = now;
    }

    static void addAsync(final String name, final long cpuNanos) {
        ASYNC_CPU.computeIfAbsent(name, n -> new LongAdder()).add(cpuNanos);
    }

    static int depth() {
        return depth;
    }

    /** Test only: forget everything. */
    static void resetForTest() {
        synchronized (USERS_LOCK) {
            users = 0;
        }
        enabled = false;
        INDEX.clear();
        names = new String[0];
        nanos = new long[0][];
        calls = new long[0];
        depth = 0;
        stamp = 0L;
        ASYNC_CPU.clear();
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    /** A consistent copy of the counters. Main thread only. */
    public static Map<String, Object> snapshot() {
        return snapshot(net.minecraft.server.MinecraftServer.currentTick, System.nanoTime());
    }

    static Map<String, Object> snapshot(final long tick, final long wallNanos) {
        final int n = names.length;
        final long[][] copy = new long[n][];
        for (int i = 0; i < n; i++) {
            copy[i] = nanos[i].clone();
        }
        final Map<String, Long> async = new LinkedHashMap<>();
        ASYNC_CPU.forEach((name, adder) -> async.put(name, adder.sum()));
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("tick", tick);
        out.put("wallNanos", wallNanos);
        out.put("names", names.clone());
        out.put("nanos", copy);
        out.put("calls", calls.clone());
        out.put("asyncCpuNanos", async);
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

        long total = 0L;
        final List<Row> list = new ArrayList<>();
        for (final Row row : rows.values()) {
            total += row.mainNanos();
            if (row.mainNanos() > 0L || row.calls > 0L || row.asyncNanos > 0L) {
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
            plugins.add(entry);
        }

        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticks", ticks);
        out.put("msPerTick", round(msPerTick(total, ticks)));
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
}
