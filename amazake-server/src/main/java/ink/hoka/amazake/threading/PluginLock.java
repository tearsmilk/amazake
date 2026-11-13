package ink.hoka.amazake.threading;

import ink.hoka.amazake.plugin.PluginTickStats;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;

/**
 * The plugin lock: one global reentrant lock, so that only one thread at a time runs the code of plugins
 * that are not thread-safe (amazake.yml {@code plugin-threading}).
 *
 * <p>Bukkit plugins assume one main thread and keep their state in plain fields. Once the server ticks on
 * several threads (later phases of {@code multithreading}), their sync code must still never run on two
 * threads at once. The lock is held for:</p>
 * <ul>
 *   <li>the whole dispatch of a sync event (not per listener: plugins keep state between the priorities of
 *       one event and call each other through Vault, PlaceholderAPI or the ServicesManager without a
 *       {@code depend}), when any listener of the event needs it; async events never take it;</li>
 *   <li>each sync scheduler task (Bukkit scheduler, Folia global, region and entity schedulers);</li>
 *   <li>Bukkit command execution and tab completion of plugin commands.</li>
 * </ul>
 *
 * <p>Inactive (the default while {@code multithreading.mode} is off), every hook costs one volatile read.
 * The lock is only ever taken on a tick thread. Hooks follow the pattern
 * {@code long token = PluginLock.lock(plugin); try { ... } finally { PluginLock.unlock(token); }}; the token
 * says whether this call locked, so turning the lock off by /amazake reload while it is held is safe.</p>
 *
 * <p>The lock is never taken on Spigot's watchdog thread (a TickThread too), and while the server stops a
 * waiting thread gives up after one stall interval: a forced shutdown by the watchdog, when the holder is
 * stuck in plugin code, must still finish as on vanilla Paper.</p>
 *
 * <p>The caches hold plugins and listener arrays only weakly, so a disabled or unloaded plugin's class
 * loader is never kept alive by them, and a plugin reloaded under the same name is looked at again.</p>
 *
 * <p>While {@link PluginTickStats} records, the time threads waited for the lock is charged to the plugin
 * whose code was about to run, and the time it was held (outermost holds only) is summed.</p>
 */
public final class PluginLock {

    /** amazake.yml {@code plugin-threading.mode}. */
    public enum Mode {
        /** Lock only when multithreading is on. */
        AUTO("auto"),
        /** Always lock (also with one tick thread: then it is never contended). */
        GLOBAL_LOCK("global-lock"),
        /** Never lock: every plugin is trusted to be thread-safe. */
        OFF("off");

        public final String key;

        Mode(final String key) {
            this.key = key;
        }

        /** The mode of a config value, or null when unknown. */
        public static Mode parse(final String value) {
            if (value == null) {
                return null;
            }
            final String text = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
            for (final Mode mode : values()) {
                if (mode.key.equals(text)) {
                    return mode;
                }
            }
            return null;
        }
    }

    /** Which plugins need the lock; plugin names are kept lower case. */
    record Settings(Mode mode, boolean trustFoliaSupported, Set<String> threadSafe, Set<String> forceLocked) {
        static final Settings DEFAULT = new Settings(Mode.AUTO, true, Set.of(), Set.of());
    }

    /** Token of a call that did not lock: its unlock does nothing. */
    public static final long NOT_LOCKED = 0L;
    /** Token of a call that locked (outermost or reentrant). */
    public static final long LOCKED = 1L;

    /** After this long waiting for the lock, a thread logs who holds it (diagnostic only). */
    static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(5);
    /** {@link #STALL_NANOS}; tests shorten it. */
    static long stallNanos = STALL_NANOS;
    /** At most one stall report per this interval. */
    static final long STALL_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private static final Logger LOGGER = Logger.getLogger("Amazake");
    private static final ReentrantLock LOCK = new ReentrantLock(false);

    private static volatile boolean active;
    private static volatile Settings settings = Settings.DEFAULT;
    /** needsLock per plugin name, valid for the plugin instance it was computed for; cleared by {@link #configure}. */
    private static final ConcurrentHashMap<String, PluginEntry> PLUGINS = new ConcurrentHashMap<>();
    /** The first listener's plugin that needs the lock, per handler list and listener array (rebaked on change). */
    private static final ConcurrentHashMap<HandlerList, EventEntry> EVENTS = new ConcurrentHashMap<>();

    /** The thread holding the lock (outermost), for the stall report. */
    private static volatile Thread holder;
    // Written and read only by the holder, under the lock.
    private static boolean holdTimed;
    private static long holdStart;
    private static volatile long nextStallLog = Long.MIN_VALUE;

    /** Where wait and hold times go; tests swap it. */
    static Accounting accounting = Accounting.STATS;
    /** System.nanoTime; tests swap it. */
    static LongSupplier clock = System::nanoTime;
    /** Whether the server is stopping or has stopped; tests swap it. */
    static BooleanSupplier stopping = PluginLock::serverStopping;

    private PluginLock() {
    }

    // ------------------------------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------------------------------

    /**
     * Applies amazake.yml (at start and on /amazake reload). Clears the per-plugin and per-event caches.
     *
     * @param multithreading whether ticks run on several threads (the effective {@code multithreading.mode} is not off)
     */
    public static synchronized void configure(final Mode mode, final boolean multithreading, final boolean trustFoliaSupported,
                                              final Collection<String> threadSafe, final Collection<String> forceLocked) {
        final Mode m = mode == null ? Mode.AUTO : mode;
        settings = new Settings(m, trustFoliaSupported, lower(threadSafe), lower(forceLocked));
        PLUGINS.clear();
        EVENTS.clear();
        active = m == Mode.GLOBAL_LOCK || (m == Mode.AUTO && multithreading);
    }

    private static Set<String> lower(final Collection<String> names) {
        if (names == null) {
            return Set.of();
        }
        return names.stream().filter(n -> n != null && !n.isBlank()).map(n -> n.trim().toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    }

    /** Whether the lock is used at all. One volatile read. */
    public static boolean active() {
        return active;
    }

    /** The settings' mode (for /amazake and logs). */
    public static Mode mode() {
        return settings.mode();
    }

    /**
     * Whether {@code plugin}'s code must run under the lock: {@code force-locked} wins, then
     * {@code thread-safe}, then (with {@code trust-folia-supported}) {@code folia-supported: true} in the
     * plugin's description; every other plugin is locked. Cached per plugin name.
     */
    public static boolean needsLock(final Plugin plugin) {
        final String name = plugin.getName();
        final PluginEntry known = PLUGINS.get(name);
        if (known != null && known.plugin.get() == plugin) {
            return known.needsLock;
        }
        // Unknown, or another instance under this name (a plugin reloaded from a new jar): decide again.
        final Settings s = settings;
        final boolean needs = decide(name, s.trustFoliaSupported() && !listed(name, s) && foliaSupported(plugin), s);
        PLUGINS.put(name, new PluginEntry(new WeakReference<>(plugin), needs));
        return needs;
    }

    private static boolean listed(final String name, final Settings s) {
        final String key = name.toLowerCase(Locale.ROOT);
        return s.forceLocked().contains(key) || s.threadSafe().contains(key);
    }

    /** The needsLock rules on plain values (unit-tested). */
    static boolean decide(final String name, final boolean foliaSupported, final Settings s) {
        final String key = name.toLowerCase(Locale.ROOT);
        if (s.forceLocked().contains(key)) {
            return true;
        }
        if (s.threadSafe().contains(key)) {
            return false;
        }
        return !(s.trustFoliaSupported() && foliaSupported);
    }

    /**
     * {@code folia-supported: true} in the plugin's paper-plugin.yml or plugin.yml. Paper's API has no getter
     * for it (only Folia's has), so the file is read from the plugin jar once (the result is cached).
     */
    static boolean foliaSupported(final Plugin plugin) {
        for (final String file : new String[]{"paper-plugin.yml", "plugin.yml"}) {
            try (InputStream in = plugin.getResource(file)) {
                if (in == null) {
                    continue;
                }
                final Object yaml = new org.yaml.snakeyaml.Yaml().load(in);
                // A class loader may hand out another jar's file: only trust the plugin's own description.
                if (yaml instanceof Map<?, ?> map && plugin.getName().equals(String.valueOf(map.get("name")))) {
                    final Object value = map.get("folia-supported");
                    return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value).trim());
                }
            } catch (final Exception | LinkageError e) {
                // unreadable: treated as not thread-safe
            }
        }
        return false;
    }

    // ------------------------------------------------------------------------------------------
    // Hooks
    // ------------------------------------------------------------------------------------------

    /** Locks before running {@code plugin}'s sync task or command, when needed. Pair with {@link #unlock}. */
    public static long lock(final Plugin plugin) {
        if (!active || plugin == null || !lockingThread() || !needsLock(plugin)) {
            return NOT_LOCKED;
        }
        return acquire(plugin);
    }

    /** {@link #lock(Plugin)} for a Bukkit command: plugin commands only. */
    public static long lockCommand(final Command command) {
        if (!active || !(command instanceof PluginIdentifiableCommand owned)) {
            return NOT_LOCKED;
        }
        return lock(owned.getPlugin());
    }

    /**
     * Locks for the whole dispatch of a sync event when any of its listeners needs the lock; the wait is
     * charged to the first such listener's plugin. Async events never lock.
     */
    public static long lockEvent(final Event event, final HandlerList handlers, final RegisteredListener[] listeners) {
        if (!active || event.isAsynchronous() || listeners.length == 0 || !lockingThread()) {
            return NOT_LOCKED;
        }
        final Plugin first = firstLocked(handlers, listeners);
        return first == null ? NOT_LOCKED : acquire(first);
    }

    /**
     * Whether the lock may be taken on this thread: tick threads only, and never Spigot's watchdog (a
     * TickThread subclass), which shuts the server down from its own thread while the holder may hang.
     */
    private static boolean lockingThread() {
        return lockingThread(Thread.currentThread());
    }

    /** {@link #lockingThread()} for a given thread (TickThread.isTickThread() is an instanceof test too). */
    static boolean lockingThread(final Thread thread) {
        return thread instanceof ca.spottedleaf.moonrise.common.util.TickThread && !(thread instanceof org.spigotmc.WatchdogThread);
    }

    private static boolean serverStopping() {
        final net.minecraft.server.MinecraftServer server = net.minecraft.server.MinecraftServer.getServer();
        return server != null && (!server.isRunning() || server.hasStopped());
    }

    /** The plugin of the first listener that needs the lock, or null; cached per listener array. */
    static Plugin firstLocked(final HandlerList handlers, final RegisteredListener[] listeners) {
        final EventEntry cached = EVENTS.get(handlers);
        if (cached != null && cached.listeners.get() == listeners) {
            // While the array is the current one its listeners keep their plugins alive.
            final Plugin first = cached.first == null ? null : cached.first.get();
            if (first != null || cached.first == null) {
                return first;
            }
        }
        Plugin first = null;
        for (final RegisteredListener listener : listeners) {
            if (needsLock(listener.getPlugin())) {
                first = listener.getPlugin();
                break;
            }
        }
        EVENTS.put(handlers, new EventEntry(new WeakReference<>(listeners), first == null ? null : new WeakReference<>(first)));
        return first;
    }

    /** Releases what {@link #lock}, {@link #lockCommand} or {@link #lockEvent} took. */
    public static void unlock(final long token) {
        if (token != LOCKED) {
            return;
        }
        if (LOCK.getHoldCount() == 1) {
            if (holdTimed) {
                holdTimed = false;
                accounting.held(clock.getAsLong() - holdStart);
            }
            holder = null;
        }
        LOCK.unlock();
    }

    /**
     * Takes the lock (reentrant), measuring a contended wait. A long wait is only reported, except while the
     * server stops: then, after one stall interval, the thread gives up and returns {@link #NOT_LOCKED} (the
     * holder may hang in plugin code, and shutdown must not wait for it forever).
     */
    static long acquire(final Plugin attributeTo) {
        if (!LOCK.tryLock()) {
            final long start = clock.getAsLong();
            boolean interrupted = false;
            boolean locked = true;
            while (true) {
                try {
                    if (LOCK.tryLock(stallNanos, TimeUnit.NANOSECONDS)) {
                        break;
                    }
                } catch (final InterruptedException e) {
                    interrupted = true; // keep waiting: plugin code must not run unlocked; restored below
                    continue;
                }
                if (stopping.getAsBoolean()) {
                    locked = false;
                    final Thread owner = holder;
                    LOGGER.warning("Блокировка плагинов (plugin-threading): сервер останавливается, а поток «"
                        + (owner == null ? "?" : owner.getName()) + "» не отпускает её; поток «"
                        + Thread.currentThread().getName() + "» продолжает без неё.");
                    break;
                }
                reportStall(start);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
            accounting.waited(attributeTo, clock.getAsLong() - start);
            if (!locked) {
                return NOT_LOCKED;
            }
        }
        if (LOCK.getHoldCount() == 1) {
            holder = Thread.currentThread();
            holdTimed = accounting.recording();
            if (holdTimed) {
                holdStart = clock.getAsLong();
            }
        }
        return LOCKED;
    }

    /** Logs once per minute (in Russian, for server owners) which thread holds the lock and where it is. */
    private static void reportStall(final long waitingSince) {
        final long now = clock.getAsLong();
        if (now < nextStallLog) {
            return;
        }
        nextStallLog = now + STALL_LOG_INTERVAL_NANOS;
        final Thread owner = holder;
        final StringBuilder text = new StringBuilder()
            .append("Блокировка плагинов (plugin-threading): поток «").append(Thread.currentThread().getName())
            .append("» ждёт её уже ").append(TimeUnit.NANOSECONDS.toSeconds(now - waitingSince)).append(" с. ");
        if (owner == null) {
            text.append("Кто её держит, неизвестно.");
        } else {
            text.append("Её держит поток «").append(owner.getName()).append("», он сейчас здесь:");
            for (final StackTraceElement frame : owner.getStackTrace()) {
                text.append("\n    at ").append(frame);
            }
        }
        text.append("\nЕсли это повторяется, похоже на взаимную блокировку: код плагина ждёт другой поток,")
            .append(" которому самому нужна эта блокировка.");
        LOGGER.warning(text.toString());
    }

    // ------------------------------------------------------------------------------------------
    // Test support
    // ------------------------------------------------------------------------------------------

    static boolean heldByCurrentThread() {
        return LOCK.isHeldByCurrentThread();
    }

    static int holdCount() {
        return LOCK.getHoldCount();
    }

    static Thread holder() {
        return holder;
    }

    static boolean hasQueuedThreads() {
        return LOCK.hasQueuedThreads();
    }

    /** Test only: defaults back. */
    static void resetForTest() {
        configure(Mode.AUTO, false, true, Set.of(), Set.of());
        accounting = Accounting.STATS;
        clock = System::nanoTime;
        stopping = PluginLock::serverStopping;
        stallNanos = STALL_NANOS;
        nextStallLog = Long.MIN_VALUE;
    }

    /** Weak, so a disabled plugin's listeners and class loader are not kept alive by the cache. */
    private record EventEntry(WeakReference<RegisteredListener[]> listeners, WeakReference<Plugin> first) {
    }

    /** Weak, so an unloaded plugin is not kept alive and a reloaded one (same name) is decided again. */
    private record PluginEntry(WeakReference<Plugin> plugin, boolean needsLock) {
    }

    /** Sink for wait and hold times. */
    interface Accounting {
        Accounting STATS = new Accounting() {
            @Override
            public boolean recording() {
                return PluginTickStats.enabled();
            }

            @Override
            public void waited(final Plugin plugin, final long nanos) {
                PluginTickStats.lockWait(plugin, nanos);
            }

            @Override
            public void held(final long nanos) {
                PluginTickStats.lockHeld(nanos);
            }
        };

        boolean recording();

        void waited(Plugin plugin, long nanos);

        void held(long nanos);
    }
}
