package ink.hoka.amazake.stress.races;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class RaceLedgerTest {

    /** The workload of one simulated event dispatch: LOW, NORMAL (count + transfer), MONITOR. */
    private static void dispatch(RaceLedger ledger, int i) {
        final Object event = new Object();
        ledger.stashPush(event);
        ledger.count("kind");
        ledger.count("kind:" + (i % 7));
        ledger.transfer(i, i * 7 + 3, 1 + (i & 15));
        if ((i & 3) == 0) { // nested dispatch inside NORMAL
            final Object nested = new Object();
            ledger.stashPush(nested);
            ledger.count("nested");
            ledger.stashPop(nested);
        }
        ledger.stashPop(event);
    }

    @Test
    void singleThreadHoldsEveryInvariant() {
        final RaceLedger ledger = new RaceLedger(8, 100, 50);
        for (int i = 0; i < 10_000; i++) {
            dispatch(ledger, i);
            if (i % 500 == 0) {
                assertTrue(ledger.check().ok());
            }
            ledger.scanLog();
        }
        final RaceLedger.Result result = ledger.check();
        assertTrue(result.ok(), result.violated()::toString);
        assertEquals(0, ledger.violations());
        assertEquals(0, ledger.exceptions());
        assertEquals(21, ledger.checks());
        assertEquals(2 * 10_000 + 2_500, ledger.counted());
        assertTrue(ledger.transfers() > 0);
        long total = 0;
        for (int i = 0; i < 8; i++) {
            total += ledger.balance(i);
            assertTrue(ledger.balance(i) >= 0);
        }
        assertEquals(800, total);
    }

    @Test
    void transferRefusesOverdraftAndBadAmounts() {
        final RaceLedger ledger = new RaceLedger(2, 10, 4);
        assertFalse(ledger.transfer(0, 1, 11));
        assertFalse(ledger.transfer(0, 1, 0));
        assertTrue(ledger.transfer(0, 1, 10));
        assertEquals(0, ledger.balance(0));
        assertEquals(20, ledger.balance(1));
        assertTrue(ledger.transfer(1, 1, 5)); // same account: balance unchanged
        assertEquals(20, ledger.balance(1));
        assertTrue(ledger.transfer(-1, 2, 5)); // indices wrap
        assertEquals(15, ledger.balance(1));
        assertEquals(5, ledger.balance(0));
        assertTrue(ledger.check().ok());
    }

    @Test
    void interleavedStashIsAViolationOnce() {
        final RaceLedger ledger = new RaceLedger(2, 0, 4);
        final Object a = new Object();
        final Object b = new Object();
        // thread A: LOW(a); thread B: LOW(b); thread A: MONITOR(a) -> a is not on top
        ledger.stashPush(a);
        ledger.stashPush(b);
        ledger.stashPop(a);
        ledger.stashPop(b);
        final RaceLedger.Result result = ledger.check();
        assertEquals(1, result.violated().size(), result.violated()::toString);
        assertTrue(result.violated().get(0).startsWith("stash:"));
        assertEquals(1, ledger.violations());
        assertTrue(ledger.check().ok(), "re-synchronised after the violation");
        assertEquals(1, ledger.violations());
    }

    @Test
    void monitorOfAnUnstashedEventIsIgnored() {
        final RaceLedger ledger = new RaceLedger(2, 0, 4);
        ledger.stashPop(new Object()); // filtered out at LOW (another world)
        assertTrue(ledger.check().ok());
    }

    @Test
    void eventLeftInFlightIsAViolation() {
        final RaceLedger ledger = new RaceLedger(2, 0, 4);
        ledger.stashPush(new Object());
        final RaceLedger.Result result = ledger.check();
        assertEquals(List.of("stash: 1 events left between LOW and MONITOR"), result.violated());
        assertTrue(ledger.check().ok());
    }

    @Test
    void logIsClearedAtItsCapAndStaysInSyncWithTheMirror() {
        final RaceLedger ledger = new RaceLedger(2, 0, 3);
        for (int i = 0; i < 10; i++) {
            ledger.count("k" + i);
        }
        assertEquals(2, ledger.scanLog()); // cleared at k3, k6, k9: only "k9" is left
        assertTrue(ledger.check().ok());
        assertEquals(10, ledger.counted());
    }

    @Test
    void exceptionsAreCountedWithConcurrentModificationsApart() {
        final RaceLedger ledger = new RaceLedger(2, 0, 100);
        ledger.exception(new ConcurrentModificationException("from a listener"));
        ledger.exception(new NullPointerException("lost account"));
        assertEquals(2, ledger.exceptions());
        assertEquals(1, ledger.concurrentModifications());
        assertEquals(List.of("exception: java.util.ConcurrentModificationException: from a listener",
            "exception: java.lang.NullPointerException: lost account"), ledger.details());
        assertTrue(ledger.check().ok(), "exceptions are not invariant violations");
    }

    @Test
    void reportHasTheDocumentedFields() {
        final RaceLedger ledger = new RaceLedger(4, 10, 8);
        dispatch(ledger, 1);
        ledger.check();
        final Map<String, Object> report = ledger.report();
        assertEquals(List.of("checks", "invariants", "violations", "exceptions", "concurrentModifications", "counted",
            "transfers", "details"), List.copyOf(report.keySet()));
        assertEquals(1L, report.get("checks"));
        assertEquals(0L, report.get("violations"));
        assertEquals(0L, report.get("exceptions"));
        assertEquals(RaceLedger.INVARIANTS, report.get("invariants"));
    }

    @Test
    void detailsAreCapped() {
        final RaceLedger ledger = new RaceLedger(2, 0, 4);
        for (int i = 0; i < 3 * RaceLedger.MAX_DETAILS; i++) {
            ledger.exception(new IllegalStateException("e" + i));
        }
        assertEquals(RaceLedger.MAX_DETAILS, ledger.details().size());
        assertEquals(3L * RaceLedger.MAX_DETAILS, ledger.exceptions());
    }

    @Test
    void twoThreadsUnderOneLockHaveNoViolations() throws Exception {
        // What Amazake's plugin lock does: whole dispatches of two threads, serialized.
        final RaceLedger ledger = new RaceLedger(16, 1_000, 256);
        final ReentrantLock lock = new ReentrantLock();
        final CyclicBarrier start = new CyclicBarrier(2);
        final Runnable worker = () -> {
            try {
                start.await();
            } catch (final Exception e) {
                throw new IllegalStateException(e);
            }
            for (int i = 0; i < 50_000; i++) {
                lock.lock();
                try {
                    dispatch(ledger, i);
                    if (i % 1000 == 0) {
                        ledger.scanLog();
                    }
                } finally {
                    lock.unlock();
                }
            }
        };
        final Thread t1 = new Thread(worker, "region-1");
        final Thread t2 = new Thread(worker, "region-2");
        t1.start();
        t2.start();
        t1.join();
        t2.join();
        final RaceLedger.Result result = ledger.check();
        assertTrue(result.ok(), result.violated()::toString);
        assertEquals(0, ledger.exceptions());
        assertEquals(2L * (2 * 50_000 + 12_500), ledger.counted());
    }

    @Test
    void twoThreadsWithoutTheLockAreCaught() throws Exception {
        // The same workload without the lock: lost HashMap updates, a broken stash or a changed bank total are
        // (practically) certain over many rounds; any of them, or an exception, means the checker works.
        for (int attempt = 0; attempt < 20; attempt++) {
            final RaceLedger ledger = new RaceLedger(4, 1_000_000, 64);
            final CyclicBarrier start = new CyclicBarrier(2);
            final Runnable worker = () -> {
                try {
                    start.await();
                } catch (final Exception e) {
                    throw new IllegalStateException(e);
                }
                for (int i = 0; i < 200_000; i++) {
                    try {
                        dispatch(ledger, i);
                    } catch (final RuntimeException e) {
                        ledger.exception(e);
                    }
                }
            };
            final Thread t1 = new Thread(worker, "racer-1");
            final Thread t2 = new Thread(worker, "racer-2");
            t1.start();
            t2.start();
            t1.join();
            t2.join();
            ledger.check();
            if (ledger.violations() + ledger.exceptions() > 0) {
                return;
            }
        }
        throw new AssertionError("20 unlocked two-thread runs and not a single violation or exception");
    }
}
