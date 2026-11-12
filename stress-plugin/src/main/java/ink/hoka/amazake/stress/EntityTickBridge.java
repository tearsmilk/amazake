package ink.hoka.amazake.stress;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jspecify.annotations.Nullable;

/**
 * Reflection access to Amazake's per-entity tick counters ({@code ink.hoka.amazake.tick.EntityTickStats}),
 * the source of the report's {@code entities}, {@code samples[].entityTicks} and
 * {@code stress.stageResults[].entities}. The plugin is compiled against the public Paper API only: on servers
 * without that class (Paper, Leaf, ...) the bridge is simply unavailable and every method is a no-op that
 * returns null.
 *
 * <p>Server contract (static methods): {@code acquire()}, {@code release()} (thread-safe, the server counts
 * while at least one consumer holds it), {@code Map<String,Object> snapshot()} (main thread only, cumulative
 * counters), {@code Map<String,Object> summarize(Map, Map, int)} and {@code Map<String,Object> sample(Map, Map)}
 * (pure functions over two snapshots, null when no tick passed between them).
 *
 * <p>The bridge also tracks this plugin's own holds: {@link #generation()} changes on every
 * acquire, so a consumer can tell whether counting ran without a gap between two of its snapshots.
 */
public final class EntityTickBridge {

    public static final String CLASS_NAME = "ink.hoka.amazake.tick.EntityTickStats";

    private final @Nullable Method acquire;
    private final @Nullable Method release;
    private final @Nullable Method snapshot;
    private final @Nullable Method summarize;
    private final @Nullable Method sample;
    private final @Nullable Logger logger;
    /** Holds of this plugin (acquire minus release calls that reached the server). */
    private final AtomicInteger holds = new AtomicInteger();
    /** Incremented on every acquire of this plugin. */
    private final AtomicInteger generation = new AtomicInteger();
    /** One warning about a broken server class is enough; then the bridge turns itself off. */
    private final AtomicBoolean broken = new AtomicBoolean();

    private EntityTickBridge(final @Nullable Class<?> type, final @Nullable Logger logger) {
        this.logger = logger;
        Method acq = null;
        Method rel = null;
        Method snap = null;
        Method sum = null;
        Method smp = null;
        if (type != null) {
            try {
                acq = staticMethod(type, "acquire");
                rel = staticMethod(type, "release");
                snap = staticMethod(type, "snapshot");
                sum = staticMethod(type, "summarize", Map.class, Map.class, int.class);
                smp = staticMethod(type, "sample", Map.class, Map.class);
            } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
                if (logger != null) {
                    logger.warning("Entity tick breakdown: " + CLASS_NAME + " has an unexpected shape (" + e + "), turned off");
                }
                acq = rel = snap = sum = smp = null;
            }
        }
        this.acquire = acq;
        this.release = rel;
        this.snapshot = snap;
        this.summarize = sum;
        this.sample = smp;
    }

    /** Looks up the server class and logs one INFO line saying whether the entity breakdown is available. */
    public static EntityTickBridge load(final Logger logger) {
        Class<?> type = null;
        try {
            type = Class.forName(CLASS_NAME, false, EntityTickBridge.class.getClassLoader());
        } catch (final ClassNotFoundException | LinkageError e) {
            // not Amazake: the feature is off
        }
        final EntityTickBridge bridge = new EntityTickBridge(type, logger);
        logger.info(bridge.isAvailable()
            ? "Entity tick breakdown available (" + CLASS_NAME + "): reports get 'entities'"
            : "Entity tick breakdown not available on this server (no " + CLASS_NAME + "): reports have no 'entities'");
        return bridge;
    }

    /** For tests and other servers: binds to a class with the same static methods; null gives an unavailable bridge. */
    public static EntityTickBridge bind(final @Nullable Class<?> type, final @Nullable Logger logger) {
        return new EntityTickBridge(type, logger);
    }

    private static Method staticMethod(final Class<?> type, final String name, final Class<?>... params) throws NoSuchMethodException {
        final Method method = type.getMethod(name, params);
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException(name + " is not static");
        }
        return method;
    }

    public boolean isAvailable() {
        return this.acquire != null && !this.broken.get();
    }

    /** True while this plugin holds the counters on (at least one acquire not yet released). */
    public boolean isHeld() {
        return this.holds.get() > 0 && this.isAvailable();
    }

    /** Changes on every {@link #acquire()}. */
    public int generation() {
        return this.generation.get();
    }

    /** Any thread. Returns true if the server now counts for this caller (must then call {@link #release()}). */
    public boolean acquire() {
        if (!this.isAvailable()) {
            return false;
        }
        if (this.invoke(this.acquire) == INVOKE_FAILED) {
            return false;
        }
        this.generation.incrementAndGet();
        this.holds.incrementAndGet();
        return true;
    }

    /** Any thread. Call once per successful {@link #acquire()}. */
    public void release() {
        if (this.release == null || this.holds.get() <= 0) {
            return;
        }
        this.holds.decrementAndGet();
        this.invoke(this.release); // also after "broken": the server must not keep counting
    }

    /** Main thread only. Null when unavailable or not held by this plugin (counters would not move). */
    public @Nullable Map<String, Object> snapshot() {
        if (!this.isHeld()) {
            return null;
        }
        return asMap(this.invoke(this.snapshot));
    }

    /** The report's {@code entities} object between two snapshots, or null. */
    public @Nullable Map<String, Object> summarize(final @Nullable Map<String, Object> from, final @Nullable Map<String, Object> to,
                                                   final int topTypes) {
        if (from == null || to == null || !this.isAvailable()) {
            return null;
        }
        return asMap(this.invoke(this.summarize, from, to, topTypes));
    }

    /** The per-sample {@code entityTicks} object between two snapshots, or null. */
    public @Nullable Map<String, Object> sample(final @Nullable Map<String, Object> from, final @Nullable Map<String, Object> to) {
        if (from == null || to == null || !this.isAvailable()) {
            return null;
        }
        return asMap(this.invoke(this.sample, from, to));
    }

    /** The snapshot with the higher {@code "tick"} (the first one on a tie); either may be null. */
    public static @Nullable Map<String, Object> newer(final @Nullable Map<String, Object> a, final @Nullable Map<String, Object> b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return tick(b) > tick(a) ? b : a;
    }

    private static long tick(final Map<String, Object> snapshot) {
        return snapshot.get("tick") instanceof Number n ? n.longValue() : Long.MIN_VALUE;
    }

        private static final Object INVOKE_FAILED = new Object();

    private @Nullable Object invoke(final @Nullable Method method, final Object... args) {
        if (method == null) {
            return INVOKE_FAILED;
        }
        try {
            return method.invoke(null, args);
        } catch (final InvocationTargetException e) {
            this.fail(method, e.getCause() != null ? e.getCause() : e);
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError e) {
            this.fail(method, e);
        }
        return INVOKE_FAILED;
    }

    private void fail(final Method method, final Throwable t) {
        if (this.broken.compareAndSet(false, true) && this.logger != null) {
            this.logger.log(Level.WARNING, "Entity tick breakdown: " + CLASS_NAME + "." + method.getName()
                + " failed, the breakdown is turned off until restart", t);
        }
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Map<String, Object> asMap(final @Nullable Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }
}
