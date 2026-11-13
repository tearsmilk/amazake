package core.amazake.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Thread groups and roles by comm, plugin-created threads and the spread of plugin threads. */
class ThreadClassifierTest {

    @Test
    void groupsByComm() {
        final Map<String, String> expected = Map.ofEntries(
            Map.entry("Server thread", ThreadClassifier.MAIN),
            Map.entry("Paper Common Wo", ThreadClassifier.CHUNK_WORKERS),
            Map.entry("Netty Epoll Ser", ThreadClassifier.NETTY),
            Map.entry("Craft Scheduler", ThreadClassifier.PLUGINS_ASYNC),
            Map.entry("Craft Async Sch", ThreadClassifier.PLUGINS_ASYNC),
            Map.entry("Folia Async Sch", ThreadClassifier.PLUGINS_ASYNC),
            Map.entry("Async Chat Thre", ThreadClassifier.CHAT),
            Map.entry("Async Tab Compl", ThreadClassifier.CHAT),
            Map.entry("User Authentica", ThreadClassifier.PLUGINS_LOGIN),
            Map.entry("Configuration T", ThreadClassifier.PLUGINS_LOGIN),
            Map.entry("Paper Async Com", ThreadClassifier.PLUGINS_LOGIN),
            Map.entry("Paper Async Tas", ThreadClassifier.PAPER_ASYNC),
            Map.entry("Paper Plugin Re", ThreadClassifier.PAPER_ASYNC),
            Map.entry("Server console", ThreadClassifier.PAPER_ASYNC),
            Map.entry("Paper Watchdog", ThreadClassifier.PAPER_ASYNC),
            Map.entry("Server Watchdog", ThreadClassifier.PAPER_ASYNC),
            Map.entry("Timer hack thre", ThreadClassifier.PAPER_ASYNC),
            Map.entry("Chat-Filter-Wor", ThreadClassifier.PAPER_ASYNC),
            Map.entry("Download-1", ThreadClassifier.PAPER_ASYNC),
            Map.entry("spark-worker-po", ThreadClassifier.SPARK),
            Map.entry("Amazake Cores", ThreadClassifier.AMAZAKE),
            Map.entry("AmazakeStress W", ThreadClassifier.OTHER),
            Map.entry("pool-3-thread-1", ThreadClassifier.OTHER));
        expected.forEach((comm, group) -> assertEquals(group, ThreadClassifier.classify(comm), comm));
    }

    @Test
    void pluginCommsOnlyReplaceOther() {
        final Set<String> plugins = Set.of("AmazakeStress W", "Server thread", "Amazake Cores");
        assertEquals(ThreadClassifier.PLUGINS_OWN, ThreadClassifier.classify("AmazakeStress W", plugins));
        // Name rules win: a comm a rule knows never becomes plugins-own.
        assertEquals(ThreadClassifier.MAIN, ThreadClassifier.classify("Server thread", plugins));
        assertEquals(ThreadClassifier.AMAZAKE, ThreadClassifier.classify("Amazake Cores", plugins));
        assertEquals(ThreadClassifier.OTHER, ThreadClassifier.classify("pool-3-thread-1", plugins));
        assertEquals(ThreadClassifier.OTHER, ThreadClassifier.classify(null, plugins));
    }

    @Test
    void roles() {
        for (final String group : new String[] {ThreadClassifier.PLUGINS_ASYNC, ThreadClassifier.CHAT,
            ThreadClassifier.PLUGINS_LOGIN, ThreadClassifier.PLUGINS_OWN}) {
            assertEquals(Role.PLUGINS, ThreadClassifier.roleOf(group), group);
        }
        for (final String group : new String[] {ThreadClassifier.PAPER_ASYNC, ThreadClassifier.SPARK,
            ThreadClassifier.AMAZAKE, ThreadClassifier.OTHER}) {
            assertEquals(Role.BACKGROUND, ThreadClassifier.roleOf(group), group);
        }
        assertEquals(Role.TICK, ThreadClassifier.roleOf(ThreadClassifier.MAIN));
        assertEquals(Role.GC, ThreadClassifier.roleOf(ThreadClassifier.JVM));
        // Every group /amazake cores lists has a role.
        for (final String group : ThreadClassifier.GROUPS) {
            ThreadClassifier.roleOf(group);
        }
    }

    @Test
    void spread() {
        assertEquals(ThreadClassifier.Spread.SUPPORT, ThreadClassifier.spread("Craft Scheduler", ThreadClassifier.PLUGINS_ASYNC));
        assertEquals(ThreadClassifier.Spread.SUPPORT, ThreadClassifier.spread("AmazakeStress W", ThreadClassifier.PLUGINS_OWN));
        assertEquals(ThreadClassifier.Spread.ROLE, ThreadClassifier.spread("spark-worker-po", ThreadClassifier.SPARK));
        assertEquals(ThreadClassifier.Spread.ALL, ThreadClassifier.spread("GC Thread#3", ThreadClassifier.GC));
        assertEquals(ThreadClassifier.Spread.ROLE, ThreadClassifier.spread("Server thread", ThreadClassifier.MAIN));
    }

    @Test
    void pluginThreadComms() {
        assertEquals("AmazakeStress W", PluginThreads.comm("AmazakeStress Worker #1"));
        assertEquals("short", PluginThreads.comm("short"));
        assertEquals("Server console", PluginThreads.comm("Server console handler"));
        // 15 bytes, cut like the kernel comm even inside a character ("Поток" is 2 bytes per letter): the
        // broken tail decodes to U+FFFD, as AffinityManager reads /proc/.../comm.
        assertEquals("ПотокПо\uFFFD", PluginThreads.comm("ПотокПотокПоток"));
        assertEquals("AПоток п\uFFFD", PluginThreads.comm("AПоток плагина 1"));
        final byte[] proc = {0x41, (byte) 0xd0, (byte) 0x9f, (byte) 0xd0, (byte) 0xbe, (byte) 0xd1, (byte) 0x82, (byte) 0xd0,
            (byte) 0xbe, (byte) 0xd0, (byte) 0xba, 0x20, (byte) 0xd0, (byte) 0xbf, (byte) 0xd0, 0x0a}; // real comm file bytes
        assertEquals(PluginThreads.comm("AПоток плагина 1"), PluginThreads.decodeComm(proc, proc.length));
        final Set<String> comms = PluginThreads.pluginComms(java.util.List.of(
            new PluginThreads.Seen("AmazakeStress W", true),
            new PluginThreads.Seen("AmazakeStress W", true),
            new PluginThreads.Seen("pool-12-thread-", true),
            new PluginThreads.Seen("pool-12-thread-", false), // another pool with the same prefix: not marked
            new PluginThreads.Seen("Thread-7", false)));
        assertEquals(Set.of("AmazakeStress W"), comms);
    }

    @Test
    void cyrillicCommMatchesKernel() throws Exception {
        final java.nio.file.Path tasks = java.nio.file.Path.of("/proc/self/task");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isDirectory(tasks), "Linux only");
        final String name = "AПоток плагина 1";
        final java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch stop = new java.util.concurrent.CountDownLatch(1);
        final Thread thread = new Thread(() -> {
            started.countDown();
            try {
                stop.await();
            } catch (final InterruptedException ignored) {
                // test ends
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        started.await();
        try {
            final Set<String> found = new java.util.HashSet<>();
            try (var dirs = java.nio.file.Files.list(tasks)) {
                for (final java.nio.file.Path dir : (Iterable<java.nio.file.Path>) dirs::iterator) {
                    try {
                        final byte[] bytes = java.nio.file.Files.readAllBytes(dir.resolve("comm"));
                        found.add(PluginThreads.decodeComm(bytes, bytes.length));
                    } catch (final java.io.IOException ignored) {
                        // thread ended
                    }
                }
            }
            assertEquals(true, found.contains(PluginThreads.comm(name)), () -> "comms: " + found);
        } finally {
            stop.countDown();
        }
    }

    @Test
    void supportThreadsThatMayBeDemoted() {
        assertEquals(true, AffinityManager.demotable("spark-worker-po", ThreadClassifier.SPARK));
        assertEquals(true, AffinityManager.demotable("Timer hack thre", ThreadClassifier.PAPER_ASYNC));
        assertEquals(false, AffinityManager.demotable("Server console", ThreadClassifier.PAPER_ASYNC)); // runs tab-complete listeners
        assertEquals(false, AffinityManager.demotable("Paper Async Tas", ThreadClassifier.PAPER_ASYNC));
        assertEquals(false, AffinityManager.demotable("Paper Watchdog", ThreadClassifier.PAPER_ASYNC));
        assertEquals(false, AffinityManager.demotable("Server Watchdog", ThreadClassifier.PAPER_ASYNC));
        assertEquals(false, AffinityManager.demotable("Craft Scheduler", ThreadClassifier.PLUGINS_ASYNC));
        assertEquals(false, AffinityManager.demotable("Netty Epoll Ser", ThreadClassifier.NETTY));
        // Unknown threads may be idle plugin pools: never demoted (nice cannot be undone and is inherited).
        assertEquals(false, AffinityManager.demotable("pool-3-thread-1", ThreadClassifier.OTHER));
        assertEquals(false, AffinityManager.demotable("Log4j2-AsyncApp", ThreadClassifier.OTHER));
        assertEquals(true, AffinityManager.mustKeepPriority("ForkJoinPool.co", Role.BACKGROUND));
        assertEquals(true, AffinityManager.mustKeepPriority("pool-3-thread-1", Role.PLUGINS));
        assertEquals(false, AffinityManager.mustKeepPriority("spark-worker-po", Role.BACKGROUND));
        // Plugins use the common pool implicitly (CompletableFuture.supplyAsync, parallel streams).
        assertEquals(false, AffinityManager.demotable("ForkJoinPool.co", ThreadClassifier.OTHER));
        assertEquals(false, AffinityManager.demotable("ForkJoinPool-1-", ThreadClassifier.OTHER));
        assertEquals(5, AffinityManager.niceOf("123 (Server (x) t) S 1 1 1 0 -1 4194624 0 0 0 0 0 0 0 0 20 5 1 0 100 0 0"));
        assertEquals(Integer.MIN_VALUE, AffinityManager.niceOf("garbage"));
    }
}
