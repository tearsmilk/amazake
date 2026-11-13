package core.amazake.threading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.spottedleaf.moonrise.common.util.TickThread;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** PluginLock: the needsLock rules, activation, tokens, reentrancy, mutual exclusion and wait/hold accounting. */
class PluginLockTest {

    private static final long MS = 1_000_000L;

    @BeforeEach
    @AfterEach
    void reset() {
        PluginLock.resetForTest();
    }

    private static Plugin plugin(final String name) {
        final Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn(name);
        return plugin;
    }

    /** Runs {@code body} on a fresh TickThread (the lock is only ever taken on tick threads) and rethrows its failure. */
    private static void onTickThread(final ThrowingRunnable body) throws Throwable {
        final Throwable[] failure = new Throwable[1];
        final Thread thread = new TickThread(() -> {
            try {
                body.run();
            } catch (final Throwable e) {
                failure[0] = e;
            }
        }, "plugin-lock-test");
        thread.start();
        thread.join();
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private interface ThrowingRunnable {
        void run() throws Throwable;
    }

    private static final class TestEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();

        TestEvent(final boolean async) {
            super(async);
        }

        @Override
        public @NotNull HandlerList getHandlers() {
            return HANDLERS;
        }
    }

    private static RegisteredListener listener(final Plugin plugin) {
        return new RegisteredListener(new Listener() { }, (l, e) -> { }, EventPriority.NORMAL, plugin, false);
    }

    @Test
    void needsLockRules() {
        final PluginLock.Settings s = new PluginLock.Settings(PluginLock.Mode.GLOBAL_LOCK, true,
            Set.of("safe", "both"), Set.of("forced", "both"));
        assertTrue(PluginLock.decide("Ordinary", false, s));
        assertFalse(PluginLock.decide("FoliaReady", true, s));
        assertFalse(PluginLock.decide("Safe", false, s));         // thread-safe list, case-insensitive
        assertTrue(PluginLock.decide("Forced", true, s));         // force-locked beats folia-supported
        assertTrue(PluginLock.decide("BOTH", false, s));          // and beats thread-safe
        final PluginLock.Settings distrust = new PluginLock.Settings(PluginLock.Mode.GLOBAL_LOCK, false, Set.of(), Set.of());
        assertTrue(PluginLock.decide("FoliaReady", true, distrust));
    }

    @Test
    void needsLockReadsFoliaSupportedAndCachesPerName() {
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of("LuckPerms"), List.of());
        final Plugin folia = plugin("Regions");
        when(folia.getResource("plugin.yml")).thenAnswer(i -> new ByteArrayInputStream(
            "name: Regions\nmain: a.B\nfolia-supported: true\n".getBytes(StandardCharsets.UTF_8)));
        final Plugin impostor = plugin("Other"); // a plugin.yml of another jar must not count
        when(impostor.getResource("plugin.yml")).thenAnswer(i -> new ByteArrayInputStream(
            "name: Regions\nfolia-supported: true\n".getBytes(StandardCharsets.UTF_8)));
        assertFalse(PluginLock.needsLock(folia));
        assertTrue(PluginLock.needsLock(impostor));
        assertFalse(PluginLock.needsLock(plugin("luckperms")));
        assertTrue(PluginLock.needsLock(plugin("NoDescription")));

        // Cached by name until the next configure (= /amazake reload).
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of(), List.of("Regions"));
        assertTrue(PluginLock.needsLock(folia));
    }

    @Test
    void modesAndActivation() {
        PluginLock.configure(PluginLock.Mode.AUTO, false, true, List.of(), List.of());
        assertFalse(PluginLock.active());
        PluginLock.configure(PluginLock.Mode.AUTO, true, true, List.of(), List.of());
        assertTrue(PluginLock.active());
        PluginLock.configure(PluginLock.Mode.OFF, true, true, List.of(), List.of());
        assertFalse(PluginLock.active());
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of(), List.of());
        assertTrue(PluginLock.active());
        assertEquals(PluginLock.Mode.GLOBAL_LOCK, PluginLock.Mode.parse(" Global-Lock "));
        assertEquals(PluginLock.Mode.GLOBAL_LOCK, PluginLock.Mode.parse("global_lock"));
        assertNull(PluginLock.Mode.parse("sometimes"));
    }

    @Test
    void inactiveOrOffTickThreadNeverLocks() {
        final Plugin p = plugin("P");
        assertEquals(PluginLock.NOT_LOCKED, PluginLock.lock(p)); // inactive
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of(), List.of());
        assertEquals(PluginLock.NOT_LOCKED, PluginLock.lock(p)); // the test thread is not a tick thread
        assertFalse(PluginLock.heldByCurrentThread());
        PluginLock.unlock(PluginLock.NOT_LOCKED); // no-op
    }

    @Test
    void reentrantTokensAndReloadWhileHeld() throws Throwable {
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of("Safe"), List.of());
        final Plugin locked = plugin("Locked");
        final Plugin safe = plugin("Safe");
        onTickThread(() -> {
            assertEquals(PluginLock.NOT_LOCKED, PluginLock.lock(safe));
            assertFalse(PluginLock.heldByCurrentThread());
            final long outer = PluginLock.lock(locked);
            assertEquals(PluginLock.LOCKED, outer);
            assertSame(Thread.currentThread(), PluginLock.holder());
            final long inner = PluginLock.lock(locked); // a nested event or task in a listener
            assertEquals(PluginLock.LOCKED, inner);
            assertEquals(2, PluginLock.holdCount());
            final long innerSafe = PluginLock.lock(safe);
            PluginLock.unlock(innerSafe);
            PluginLock.unlock(inner);
            assertTrue(PluginLock.heldByCurrentThread());
            // /amazake reload turns the lock off while it is held: the token still releases it.
            PluginLock.configure(PluginLock.Mode.OFF, false, true, List.of(), List.of());
            assertEquals(PluginLock.NOT_LOCKED, PluginLock.lock(locked));
            PluginLock.unlock(outer);
            assertFalse(PluginLock.heldByCurrentThread());
            assertNull(PluginLock.holder());
        });
    }

    @Test
    void eventsLockWhenAnyListenerNeedsItAndNeverWhenAsync() throws Throwable {
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of("SafeA", "SafeB"), List.of());
        final Plugin safeA = plugin("SafeA");
        final Plugin safeB = plugin("SafeB");
        final Plugin unsafe = plugin("Unsafe");
        final HandlerList handlers = new HandlerList();
        final RegisteredListener[] allSafe = {listener(safeA), listener(safeB)};
        final RegisteredListener[] mixed = {listener(safeA), listener(unsafe), listener(safeB)};
        assertNull(PluginLock.firstLocked(handlers, allSafe));
        assertSame(unsafe, PluginLock.firstLocked(handlers, mixed)); // a new array (rebaked) is looked at again
        assertSame(unsafe, PluginLock.firstLocked(handlers, mixed));
        onTickThread(() -> {
            assertEquals(PluginLock.NOT_LOCKED, PluginLock.lockEvent(new TestEvent(true), handlers, mixed));
            assertEquals(PluginLock.NOT_LOCKED, PluginLock.lockEvent(new TestEvent(false), handlers, allSafe));
            assertEquals(PluginLock.NOT_LOCKED, PluginLock.lockEvent(new TestEvent(false), handlers, new RegisteredListener[0]));
            final long token = PluginLock.lockEvent(new TestEvent(false), handlers, mixed);
            assertEquals(PluginLock.LOCKED, token);
            PluginLock.unlock(token);
            assertFalse(PluginLock.heldByCurrentThread());
        });
    }

    @Test
    void onlyOneThreadRunsLockedCodeAtATime() throws Exception {
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of(), List.of());
        final Plugin p = plugin("Counter");
        final int threads = 6;
        final int rounds = 20_000;
        final int[] plain = {0}; // a plugin's unsynchronised state
        final int[] inside = {0};
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        final CyclicBarrier start = new CyclicBarrier(threads);
        final Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new TickThread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < rounds; i++) {
                        final long token = PluginLock.lock(p);
                        try {
                            if (++inside[0] != 1) {
                                throw new AssertionError("two threads inside the lock");
                            }
                            plain[0]++;
                            inside[0]--;
                        } finally {
                            PluginLock.unlock(token);
                        }
                    }
                } catch (final Throwable e) {
                    errors.add(e);
                }
            }, "plugin-lock-worker-" + t);
            workers[t].start();
        }
        for (final Thread worker : workers) {
            worker.join();
        }
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(threads * rounds, plain[0]);
    }

    @Test
    void waitIsChargedToThePluginAndHoldTimeIsSummed() throws Exception {
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of(), List.of());
        final AtomicLong clock = new AtomicLong(1_000 * MS);
        final List<String> waits = Collections.synchronizedList(new ArrayList<>());
        final AtomicLong held = new AtomicLong();
        PluginLock.clock = clock::get;
        PluginLock.accounting = new PluginLock.Accounting() {
            @Override
            public boolean recording() {
                return true;
            }

            @Override
            public void waited(final Plugin plugin, final long nanos) {
                waits.add(plugin.getName() + "=" + nanos);
            }

            @Override
            public void held(final long nanos) {
                held.addAndGet(nanos);
            }
        };
        final Plugin first = plugin("First");
        final Plugin second = plugin("Second");
        final CountDownLatch firstHolds = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        final Thread a = new TickThread(() -> {
            try {
                final long token = PluginLock.lock(first);
                firstHolds.countDown();
                release.await();
                clock.addAndGet(7 * MS); // A held the lock 7 ms in all, B waited those 7 ms
                PluginLock.unlock(token);
            } catch (final Throwable e) {
                errors.add(e);
            }
        }, "holder");
        final Thread b = new TickThread(() -> {
            try {
                firstHolds.await();
                final long token = PluginLock.lock(second);
                clock.addAndGet(2 * MS); // B holds 2 ms
                PluginLock.unlock(token);
            } catch (final Throwable e) {
                errors.add(e);
            }
        }, "waiter");
        a.start();
        b.start();
        firstHolds.await();
        while (!PluginLock.hasQueuedThreads()) {
            Thread.onSpinWait();
        }
        release.countDown();
        a.join();
        b.join();
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(List.of("Second=" + 7 * MS), waits);
        assertEquals(9 * MS, held.get());
    }

    @Test
    void stoppingServerDoesNotBlockASecondTickThreadForever() throws Exception {
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of(), List.of());
        PluginLock.stallNanos = 50 * MS;
        PluginLock.stopping = () -> true; // the watchdog shut the server down while the holder hangs
        final Plugin p = plugin("Hung");
        final CountDownLatch holds = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final long[] token = {-1L};
        final Thread hung = new TickThread(() -> {
            final long t = PluginLock.lock(p);
            holds.countDown();
            try {
                release.await();
            } catch (final InterruptedException ignored) {
                // test teardown
            }
            PluginLock.unlock(t);
        }, "hung-main");
        final Thread closer = new TickThread(() -> token[0] = PluginLock.lock(p), "closer");
        hung.start();
        holds.await();
        closer.start();
        closer.join(10_000);
        assertFalse(closer.isAlive(), "a stopping server must not wait for the lock forever");
        assertEquals(PluginLock.NOT_LOCKED, token[0]);
        release.countDown();
        hung.join();
        assertFalse(PluginLock.hasQueuedThreads());
    }

    @Test
    void runningServerKeepsWaitingPastAStall() throws Exception {
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of(), List.of());
        PluginLock.stallNanos = 20 * MS;
        PluginLock.stopping = () -> false;
        final Plugin p = plugin("Slow");
        final CountDownLatch holds = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final long[] token = {-1L};
        final Thread slow = new TickThread(() -> {
            final long t = PluginLock.lock(p);
            holds.countDown();
            try {
                release.await();
            } catch (final InterruptedException ignored) {
                // test teardown
            }
            PluginLock.unlock(t);
        }, "slow-holder");
        final Thread waiter = new TickThread(() -> {
            token[0] = PluginLock.lock(p);
            PluginLock.unlock(token[0]);
        }, "waiter");
        slow.start();
        holds.await();
        waiter.start();
        waiter.join(200); // several stall intervals
        assertTrue(waiter.isAlive());
        release.countDown();
        slow.join();
        waiter.join();
        assertEquals(PluginLock.LOCKED, token[0]);
    }

    @Test
    void watchdogThreadNeverLocks() {
        // The watchdog is a TickThread, but must never wait for the lock while it shuts a hung server down.
        // Its constructor needs Paper's global config, so a mock (no constructor run) stands in for it.
        final Thread watchdog = mock(org.spigotmc.WatchdogThread.class);
        assertTrue(watchdog instanceof TickThread);
        assertFalse(PluginLock.lockingThread(watchdog));
        assertTrue(PluginLock.lockingThread(new TickThread(() -> { }, "tick")));
        assertFalse(PluginLock.lockingThread(new Thread(() -> { })));
    }

    @Test
    void pluginReloadedUnderTheSameNameIsDecidedAgain() {
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of(), List.of());
        final Plugin old = plugin("Reloaded");
        when(old.getResource("plugin.yml")).thenReturn(null);
        assertTrue(PluginLock.needsLock(old));
        final Plugin fresh = plugin("Reloaded");
        when(fresh.getResource("plugin.yml")).thenReturn(new ByteArrayInputStream(
            "name: Reloaded\nfolia-supported: true\n".getBytes(StandardCharsets.UTF_8)));
        assertFalse(PluginLock.needsLock(fresh));
        assertFalse(PluginLock.needsLock(fresh)); // cached for this instance
    }

    @Test
    void eventCacheDoesNotKeepADisabledPluginAlive() throws Exception {
        PluginLock.configure(PluginLock.Mode.GLOBAL_LOCK, false, true, List.of(), List.of());
        final HandlerList handlers = new HandlerList();
        final java.lang.ref.WeakReference<Plugin> gone = registerAndForget(handlers);
        for (int i = 0; i < 50 && gone.get() != null; i++) {
            System.gc();
            Thread.sleep(20);
        }
        assertNull(gone.get(), "the cache must not keep a disabled plugin alive");
    }

    private static java.lang.ref.WeakReference<Plugin> registerAndForget(final HandlerList handlers) {
        // A plain proxy, not a Mockito mock: Mockito keeps its own references to mocks.
        final Plugin plugin = (Plugin) java.lang.reflect.Proxy.newProxyInstance(Plugin.class.getClassLoader(),
            new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getName" -> "Disabled";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "Disabled";
                default -> null;
            });
        final RegisteredListener[] listeners = {listener(plugin)};
        assertSame(plugin, PluginLock.firstLocked(handlers, listeners));
        assertTrue(PluginLock.needsLock(plugin));
        return new java.lang.ref.WeakReference<>(plugin);
    }
}
