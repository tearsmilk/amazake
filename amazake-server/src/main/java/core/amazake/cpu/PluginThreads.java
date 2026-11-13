package core.amazake.cpu;

import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Finds the threads plugins created themselves ({@link ThreadClassifier#PLUGINS_OWN}), so the core layout
 * can put them on the plugins CPUs. Read-only: it never touches a thread, only looks at it.
 *
 * <p>Called from the {@link AffinityManager}'s scan thread (never from the main thread). Each call lists
 * the platform threads of the root thread group (no safepoint) and decides, once per Java thread:</p>
 * <ul>
 *   <li>the thread's own class or its context class loader is a plugin's class loader
 *       ({@link ConfiguredPluginClassLoader}): a plugin thread;</li>
 *   <li>otherwise, for a thread whose comm no name rule knows ({@link ThreadClassifier#OTHER}), its stack is
 *       read: a frame whose class loader is named after a plugin jar makes it a plugin thread. Reading stacks
 *       is a stop-the-world safepoint on JDK 21 ({@code ThreadDump}), so all stacks of one call are read with
 *       a single {@link ThreadMXBean#getThreadInfo(long[], int)} (one safepoint per call, at most
 *       {@value #STACKS_PER_SCAN} threads), and only when needed: once for a new thread, then again at most
 *       every {@value #RECHECK_SECONDS} s and only if the thread used CPU since its last check
 *       ({@link ThreadMXBean#getThreadCpuTime(long)}, no safepoint). An idle pool thread costs nothing.</li>
 * </ul>
 *
 * <p>Java threads are matched to Linux threads by name: the kernel comm is the first 15 bytes of the Java
 * name the thread had when it started. When several live Java threads share a comm and not all of them are
 * plugin threads, that comm is not marked. A plugin thread stays one until it ends.</p>
 */
public final class PluginThreads {

    private static final Logger LOGGER = Logger.getLogger("Amazake");
    static final long RECHECK_SECONDS = 30L;
    static final int STACKS_PER_SCAN = 16;
    static final int STACK_DEPTH = 48;
    /** How often the jar name index of the loaded plugins is rebuilt. */
    private static final long INDEX_MS = 10_000L;

    /** What is known about one Java thread. */
    private static final class Verdict {
        boolean plugin;
        /** System.nanoTime() of the last stack check; Long.MIN_VALUE when the stack was never read. */
        long checkedAt = Long.MIN_VALUE;
        /** The thread's CPU time (ns) at the last stack check; -1 when unknown. */
        long cpuAt = -1L;
    }

    /**
     * A live Java thread for {@link #pluginComms}.
     *
     * @param comm   the kernel comm its name gives
     * @param plugin whether it is a plugin thread
     */
    record Seen(String comm, boolean plugin) {
    }

    private final ThreadMXBean mx = ManagementFactory.getThreadMXBean();
    private final Map<Long, Verdict> verdicts = new HashMap<>();
    private Set<String> jarNames = Set.of();
    private long indexAt = Long.MIN_VALUE;
    private boolean failureLogged;

    private volatile Set<String> comms = Set.of();
    private volatile Set<Long> javaIds = Set.of();

    /** Comms of plugin threads as the last {@link #refresh()} found them. */
    public Set<String> comms() {
        return this.comms;
    }

    /** Java thread ids of plugin threads as the last {@link #refresh()} found them (for the profiler). */
    public Set<Long> javaIds() {
        return this.javaIds;
    }

    /** How many live plugin threads the last {@link #refresh()} found. */
    public int count() {
        return this.javaIds.size();
    }

    /** Looks at the threads again; returns the comms of plugin threads. Only on one thread at a time. */
    public Set<String> refresh() {
        try {
            return this.refresh0();
        } catch (final RuntimeException | LinkageError e) {
            if (!this.failureLogged) {
                this.failureLogged = true;
                LOGGER.log(Level.WARNING, "Раскладка ядер: не удалось найти потоки плагинов, они остаются в группе other", e);
            }
            return this.comms;
        }
    }

    private Set<String> refresh0() {
        final long now = System.nanoTime();
        if (this.indexAt == Long.MIN_VALUE || now - this.indexAt >= TimeUnit.MILLISECONDS.toNanos(INDEX_MS)) {
            this.indexAt = now;
            this.jarNames = jarNames();
        }
        final Thread[] threads = liveThreads();
        final Set<Long> alive = new HashSet<>();
        final List<Seen> seen = new java.util.ArrayList<>(threads.length);
        final Set<Long> ids = new HashSet<>();
        final long[] candidates = new long[STACKS_PER_SCAN];
        final Verdict[] candidateVerdicts = new Verdict[STACKS_PER_SCAN];
        final int[] candidateSeen = new int[STACKS_PER_SCAN];
        int budget = 0;
        for (final Thread thread : threads) {
            if (thread == null) {
                continue;
            }
            final long id = thread.threadId();
            alive.add(id);
            final String comm = comm(thread.getName());
            final Verdict verdict = this.verdicts.computeIfAbsent(id, k -> new Verdict());
            if (!verdict.plugin && byLoader(thread)) {
                verdict.plugin = true;
            }
            if (!verdict.plugin && budget < STACKS_PER_SCAN && ThreadClassifier.OTHER.equals(ThreadClassifier.classify(comm))
                && (verdict.checkedAt == Long.MIN_VALUE || now - verdict.checkedAt >= TimeUnit.SECONDS.toNanos(RECHECK_SECONDS))) {
                final long cpu = this.cpuTime(id);
                if (verdict.checkedAt == Long.MIN_VALUE || cpu < 0 || verdict.cpuAt < 0 || cpu != verdict.cpuAt) {
                    verdict.checkedAt = now;
                    verdict.cpuAt = cpu;
                    candidates[budget] = id;
                    candidateVerdicts[budget] = verdict;
                    candidateSeen[budget] = seen.size();
                    budget++;
                }
            }
            if (verdict.plugin) {
                ids.add(id);
            }
            seen.add(new Seen(comm, verdict.plugin));
        }
        if (budget > 0) {
            final boolean[] plugin = this.byStack(java.util.Arrays.copyOf(candidates, budget));
            for (int i = 0; i < budget; i++) {
                if (plugin[i]) {
                    candidateVerdicts[i].plugin = true;
                    ids.add(candidates[i]);
                    seen.set(candidateSeen[i], new Seen(seen.get(candidateSeen[i]).comm(), true));
                }
            }
        }
        this.verdicts.keySet().retainAll(alive);
        final Set<String> result = pluginComms(seen);
        this.comms = result;
        this.javaIds = Set.copyOf(ids);
        return result;
    }

    /** Whether the thread's own class or its context class loader belongs to a plugin. */
    private static boolean byLoader(final Thread thread) {
        if (thread.getClass().getClassLoader() instanceof ConfiguredPluginClassLoader) {
            return true;
        }
        try {
            return thread.getContextClassLoader() instanceof ConfiguredPluginClassLoader;
        } catch (final RuntimeException e) {
            return false;
        }
    }

    /** The thread's CPU time in ns (no safepoint), or -1 when unknown or not supported. */
    private long cpuTime(final long id) {
        try {
            return this.mx.isThreadCpuTimeSupported() && this.mx.isThreadCpuTimeEnabled() ? this.mx.getThreadCpuTime(id) : -1L;
        } catch (final RuntimeException e) {
            return -1L;
        }
    }

    /**
     * For each thread id, whether its stack right now has a frame of a class from a plugin jar. All stacks
     * are read with one call, which is one safepoint ({@code ThreadDump}) on JDK 21.
     */
    private boolean[] byStack(final long[] ids) {
        final boolean[] out = new boolean[ids.length];
        if (this.jarNames.isEmpty()) {
            return out;
        }
        final ThreadInfo[] infos = this.mx.getThreadInfo(ids, STACK_DEPTH);
        for (int i = 0; i < infos.length && i < out.length; i++) {
            if (infos[i] == null) {
                continue; // ended meanwhile
            }
            for (final StackTraceElement frame : infos[i].getStackTrace()) {
                final String loader = frame.getClassLoaderName();
                if (loader != null && this.jarNames.contains(loader)) {
                    out[i] = true;
                    break;
                }
            }
        }
        return out;
    }

    /** Names of the loaded plugins' class loaders: the plugin jar file names ("LuckPerms-5.4.jar"). */
    private static Set<String> jarNames() {
        final Set<String> out = new HashSet<>();
        final Plugin[] plugins;
        try {
            plugins = Bukkit.getPluginManager().getPlugins();
        } catch (final RuntimeException | LinkageError e) {
            return Set.of(); // no server (tests) or the plugin list is changing
        }
        for (final Plugin plugin : plugins) {
            if (plugin == null) {
                continue;
            }
            final ClassLoader loader = plugin.getClass().getClassLoader();
            if (loader instanceof ConfiguredPluginClassLoader && loader.getName() != null) {
                out.add(loader.getName());
            }
        }
        return Set.copyOf(out);
    }

    /** Every live platform thread of the JVM (virtual threads have no fixed Linux thread anyway). */
    private static Thread[] liveThreads() {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) {
            root = root.getParent();
        }
        Thread[] threads = new Thread[Math.max(16, root.activeCount() * 2)];
        int n;
        while ((n = root.enumerate(threads, true)) == threads.length) {
            threads = new Thread[threads.length * 2];
        }
        return java.util.Arrays.copyOf(threads, n);
    }

    /**
     * The comms to mark as plugin threads: those whose every live Java thread is a plugin thread. Pure.
     */
    static Set<String> pluginComms(final Collection<Seen> seen) {
        final Map<String, Boolean> all = new HashMap<>();
        for (final Seen thread : seen) {
            all.merge(thread.comm(), thread.plugin(), Boolean::logicalAnd);
        }
        final Set<String> out = new HashSet<>();
        all.forEach((comm, plugin) -> {
            if (plugin) {
                out.add(comm);
            }
        });
        return Set.copyOf(out);
    }

    /**
     * The kernel comm a thread with this Java name gets, as {@link #decodeComm} reads it: the JVM cuts the
     * UTF-8 name to its first 15 bytes, even inside a character, so a broken tail becomes U+FFFD on both sides.
     */
    static String comm(final String name) {
        if (name == null) {
            return "";
        }
        final byte[] bytes = name.getBytes(StandardCharsets.UTF_8);
        return decodeComm(bytes, Math.min(ThreadClassifier.COMM_LENGTH, bytes.length));
    }

    /**
     * Decodes comm bytes leniently (a character cut by the kernel becomes U+FFFD instead of an error) and strips
     * them ("Server console handler" -> "Server console"). Used for both the Java name and /proc/.../comm.
     */
    static String decodeComm(final byte[] bytes, final int length) {
        int end = length;
        while (end > 0 && bytes[end - 1] == '\n') {
            end--; // /proc/.../comm ends with a newline: drop it before decoding so both sides decode the same bytes
        }
        return new String(bytes, 0, end, StandardCharsets.UTF_8).strip();
    }
}
