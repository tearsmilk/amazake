package core.amazake.stress.races;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The state of a "typical legacy plugin" that is NOT thread-safe on purpose, plus the invariant checker that
 * notices when it was touched by two threads at once.
 *
 * <p>Deliberately unsafe parts (plain collections and fields, no synchronization): counters in a {@link HashMap},
 * an event log in an {@link ArrayList}, a LOW-&gt;MONITOR stash ({@link ArrayDeque} of in-flight events) and a
 * "bank" of two {@link HashMap}s that moves amounts between accounts with non-atomic read-modify-write.
 * Thread-safe parts: the {@link AtomicLong} mirrors of the counters and of the log size, and all bookkeeping of
 * the checker itself (checks, violations, exceptions, details).
 *
 * <p>While every call comes from one thread at a time (vanilla Paper/Leaf: the main thread; Amazake with the
 * plugin lock: whichever thread holds it) all invariants hold exactly, so {@link #check()} reports no violation.
 * When the plugin code runs on two threads at once, lost updates, a broken stash order, a changed bank total or
 * a {@link ConcurrentModificationException} show up as violations / exceptions.
 *
 * <p>No Bukkit dependency, so it is unit-tested directly.
 */
public final class RaceLedger {

    /** How many violation / exception descriptions are kept for the report. */
    static final int MAX_DETAILS = 10;
    /** Invariants evaluated by one {@link #check()}. */
    static final int INVARIANTS = 5;

    // ---------------------------------------------------------------- unsafe "plugin" state (no locking on purpose)
    private final Map<String, Long> counters = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private final int logCap;
    private final ArrayDeque<Object> stash = new ArrayDeque<>();
    private final Map<String, Long> bankA = new HashMap<>();
    private final Map<String, Long> bankB = new HashMap<>();
    private final String[] accounts;
    private long expectedBankTotal;

    // ---------------------------------------------------------------- thread-safe mirrors and bookkeeping
    private final AtomicLong countMirror = new AtomicLong();
    private final AtomicLong logMirror = new AtomicLong();
    private final AtomicLong stashErrors = new AtomicLong();
    private long stashErrorsSeen; // read and written by check() only
    private final AtomicLong transfers = new AtomicLong();
    private final AtomicLong checks = new AtomicLong();
    private final AtomicLong violations = new AtomicLong();
    private final AtomicLong exceptions = new AtomicLong();
    private final AtomicLong concurrentModifications = new AtomicLong();
    private final List<String> details = new CopyOnWriteArrayList<>();

    /**
     * @param accountCount bank accounts (at least 2), alternately kept in the two maps
     * @param balancePerAccount starting balance of every account
     * @param logCap the event log is cleared when it reaches this size (at least 1)
     */
    public RaceLedger(int accountCount, long balancePerAccount, int logCap) {
        if (accountCount < 2 || balancePerAccount < 0 || logCap < 1) {
            throw new IllegalArgumentException("accounts " + accountCount + ", balance " + balancePerAccount + ", logCap " + logCap);
        }
        this.logCap = logCap;
        this.accounts = new String[accountCount];
        for (int i = 0; i < accountCount; i++) {
            this.accounts[i] = "acc-" + i;
            this.bank(i).put(this.accounts[i], balancePerAccount);
        }
        this.expectedBankTotal = (long) accountCount * balancePerAccount;
    }

    public int accountCount() {
        return this.accounts.length;
    }

    // ---------------------------------------------------------------- workload (NOT thread-safe on purpose)

    /** Counts one occurrence of {@code key}: HashMap counter, event log entry, and their atomic mirrors. */
    public void count(String key) {
        this.counters.merge(key, 1L, Long::sum);
        this.countMirror.incrementAndGet();
        if (this.log.size() >= this.logCap) {
            this.logMirror.addAndGet(-this.log.size());
            this.log.clear();
        }
        this.log.add(key);
        this.logMirror.incrementAndGet();
    }

    /** LOW priority: remembers an event that is being dispatched (nested dispatches stack up). */
    public void stashPush(Object event) {
        this.stash.addLast(event);
    }

    /**
     * MONITOR priority: the event must be the most recent stashed one. An event that is stashed but not on top
     * means another dispatch interleaved with this one (a race); an event that is not stashed at all was filtered
     * out at LOW and is ignored.
     */
    public void stashPop(Object event) {
        if (this.stash.peekLast() == event) {
            this.stash.removeLast();
            return;
        }
        for (final Object stashed : this.stash) {
            if (stashed == event) {
                this.stashErrors.incrementAndGet();
                this.stash.removeFirstOccurrence(event);
                return;
            }
        }
    }

    /**
     * Moves {@code amount} from account {@code from} to account {@code to} (indices modulo the account count)
     * with a plain read-modify-write on two maps. Returns false (and moves nothing) if the balance is too low.
     */
    public boolean transfer(int from, int to, long amount) {
        final int a = Math.floorMod(from, this.accounts.length);
        final int b = Math.floorMod(to, this.accounts.length);
        final Map<String, Long> src = this.bank(a);
        final long balance = src.get(this.accounts[a]);
        if (balance < amount || amount <= 0) {
            return false;
        }
        src.put(this.accounts[a], balance - amount);
        final Map<String, Long> dst = this.bank(b);
        dst.put(this.accounts[b], dst.get(this.accounts[b]) + amount);
        this.transfers.incrementAndGet();
        return true;
    }

    /** Walks the event log (throws {@link ConcurrentModificationException} if it is modified meanwhile). */
    public long scanLog() {
        long chars = 0;
        for (final String entry : this.log) {
            chars += entry.length();
        }
        return chars;
    }

    /** Balance of account {@code index} (modulo the account count). */
    public long balance(int index) {
        final int i = Math.floorMod(index, this.accounts.length);
        return this.bank(i).get(this.accounts[i]);
    }

    private Map<String, Long> bank(int index) {
        return (index & 1) == 0 ? this.bankA : this.bankB;
    }

    // ---------------------------------------------------------------- bookkeeping (thread-safe)

    /** Records an exception thrown by the workload (any thread). */
    public void exception(Throwable t) {
        this.exceptions.incrementAndGet();
        if (t instanceof ConcurrentModificationException) {
            this.concurrentModifications.incrementAndGet();
        }
        this.detail("exception: " + t);
    }

    private void detail(String text) {
        if (this.details.size() < MAX_DETAILS) {
            this.details.add(text);
        }
    }

    /** Outcome of one {@link #check()}: the violated invariants (empty = all hold). */
    public record Result(List<String> violated) {
        public boolean ok() {
            return this.violated.isEmpty();
        }
    }

    /**
     * Evaluates every invariant once. Must run where no workload call is in progress (a scheduler task on the
     * thread that owns the plugin's code, between event dispatches). Each violated invariant counts as one
     * violation; afterwards the expected values are re-synchronised with the observed ones, so every later
     * violation is a new one.
     */
    public Result check() {
        this.checks.incrementAndGet();
        final List<String> violated = new ArrayList<>(INVARIANTS);
        try {
            // 1. HashMap counters vs their AtomicLong mirror (lost updates in merge)
            long counted = 0;
            for (final long value : this.counters.values()) {
                counted += value;
            }
            final long mirror = this.countMirror.get();
            if (counted != mirror) {
                violated.add("counters: sum " + counted + " != mirror " + mirror);
                this.countMirror.set(counted);
            }
            // 2. ArrayList size vs its mirror (lost adds / clears)
            final int size = this.log.size();
            final long logMirror = this.logMirror.get();
            if (size != logMirror) {
                violated.add("log: size " + size + " != mirror " + logMirror);
                this.logMirror.set(size);
            }
            // 3. LOW -> MONITOR stash order
            final long stashErrors = this.stashErrors.get();
            if (stashErrors != this.stashErrorsSeen) {
                violated.add("stash: " + (stashErrors - this.stashErrorsSeen) + " events interleaved between LOW and MONITOR");
                this.stashErrorsSeen = stashErrors;
            }
            // 4. no dispatch may be in flight while the checker runs
            if (!this.stash.isEmpty()) {
                violated.add("stash: " + this.stash.size() + " events left between LOW and MONITOR");
                this.stash.clear();
            }
            // 5. bank: total conserved, no negative balance
            long total = 0;
            boolean negative = false;
            for (final String account : this.accounts) {
                final Long value = this.bankA.containsKey(account) ? this.bankA.get(account) : this.bankB.get(account);
                if (value == null) {
                    violated.add("bank: account " + account + " lost");
                    continue;
                }
                total += value;
                negative |= value < 0;
            }
            if (total != this.expectedBankTotal || negative) {
                violated.add("bank: total " + total + " != " + this.expectedBankTotal + (negative ? ", negative balance" : ""));
                this.expectedBankTotal = total;
            }
        } catch (final RuntimeException e) {
            // e.g. ConcurrentModificationException while summing: the state is being modified by another thread
            this.exception(e);
        }
        if (!violated.isEmpty()) {
            this.violations.addAndGet(violated.size());
            for (final String v : violated) {
                this.detail("violation: " + v);
            }
        }
        return new Result(violated);
    }

    public long checks() {
        return this.checks.get();
    }

    public long violations() {
        return this.violations.get();
    }

    public long exceptions() {
        return this.exceptions.get();
    }

    public long concurrentModifications() {
        return this.concurrentModifications.get();
    }

    /** Counted events (sum of all counters as seen by the atomic mirror). */
    public long counted() {
        return this.countMirror.get();
    }

    public long transfers() {
        return this.transfers.get();
    }

    /** First {@link #MAX_DETAILS} violation / exception descriptions. */
    public List<String> details() {
        return List.copyOf(this.details);
    }

    /** {@code checks, violations, exceptions, concurrentModifications, counted, transfers, details}. */
    public Map<String, Object> report() {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("checks", this.checks());
        out.put("invariants", INVARIANTS);
        out.put("violations", this.violations());
        out.put("exceptions", this.exceptions());
        out.put("concurrentModifications", this.concurrentModifications());
        out.put("counted", this.counted());
        out.put("transfers", this.transfers());
        out.put("details", this.details());
        return out;
    }
}
