package core.amazake.cpu;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Decides when the main thread should move from its CPU to a reserve CPU. Pure logic, no Bukkit and no
 * file access: the caller hands it cumulative counters, so it is unit-tested on synthetic sequences.
 *
 * <p>Every check (each {@code cpu.reserve.check-seconds}) it compares two reads:</p>
 * <ul>
 *   <li>the tick CPU's line of /proc/stat: busy = (total - idle - iowait) / total, steal = steal / total,
 *       irq = (irq + softirq) / total;</li>
 *   <li>the main thread's own utime + stime (/proc/self/task/TID/stat, fields 14 and 15). Both files count
 *       in the same clock ticks (USER_HZ), so own = its delta / the CPU's total delta;</li>
 *   <li>the JVM's stop-the-world GC pause time (GarbageCollectorMXBean collection time of the pause
 *       collectors), as a fraction of the wall time between the checks.</li>
 * </ul>
 * <p>Foreign load = busy - own - GC pauses: other processes the scheduler put on that CPU, interrupts and
 * softirqs, time the hypervisor stole. The main thread's own work never counts, however heavy the tick is,
 * and neither do the server's own GC pauses: the {@code GC Thread#} workers may run on the tick CPU while the
 * main thread is stopped, which is the server's work too. When the foreign load is above the threshold on
 * {@value #CONSECUTIVE} checks in a row, and a reserve CPU (and its idle SMT siblings, see
 * {@link #check(long, int, List, Map, Map, int, long, long)}) was less than {@value #RESERVE_IDLE} busy over
 * the last check, the answer is {@link Kind#MIGRATE} to the idlest such CPU. Two checks in a row means two
 * check windows, each with foreign load above the threshold: a burst of a full core that straddles the
 * boundary between two windows is enough once it gives each window more than the threshold (about one
 * second of a full core at the defaults). After a migration nothing moves for the cooldown. When every
 * reserve CPU is busy too the answer is {@link Kind#NO_FREE_RESERVE}, at most once per
 * {@value #NO_FREE_REPORT_MS} ms (repeats in between are counted in {@link Result#suppressed()}).</p>
 *
 * <p>Not thread-safe: one detector per checking thread.</p>
 */
public final class ContentionDetector {

    /** Checks in a row with foreign load above the threshold before a move. */
    public static final int CONSECUTIVE = 2;
    /** A reserve CPU busier than this over the last check is not a place to move to. */
    public static final double RESERVE_IDLE = 0.10;
    /** "No free reserve" is reported at most this often (10 minutes); a move allows the next report at once. */
    public static final long NO_FREE_REPORT_MS = 600_000L;

    /** What a check concluded. */
    public enum Kind {
        /** No baseline yet (first read, a new tick CPU or main thread), or unreadable counters. */
        WARMUP,
        /** Measured; nothing to do. */
        OK,
        /** Foreign load is high, but this is the first check of a streak or the cooldown is running. */
        WAIT,
        /** Move the tick from {@link Result#from()} to {@link Result#to()}. */
        MIGRATE,
        /** Foreign load is high and no reserve CPU is idle; reported at most once per {@link #NO_FREE_REPORT_MS}. */
        NO_FREE_RESERVE
    }

    /**
     * One check.
     *
     * @param busy        busy fraction of the tick CPU over the check (0..1), NaN without a measurement
     * @param own         the main thread's share of that CPU
     * @param foreign     busy - own - gcPause, at least 0
     * @param steal       steal fraction of the tick CPU
     * @param irq         irq + softirq fraction of the tick CPU
     * @param gcPause     fraction of the window the JVM spent in stop-the-world GC pauses (0 when unknown)
     * @param reserveLoad busy fraction of each reserve CPU over the check (with its idle SMT siblings: the busiest)
     * @param from        the tick CPU
     * @param to          the reserve CPU to move to (MIGRATE), else -1
     * @param suppressed  NO_FREE_RESERVE: episodes not reported since the last report
     */
    public record Result(Kind kind, int from, int to, double busy, double own, double foreign, double steal, double irq,
                         double gcPause, Map<Integer, Double> reserveLoad, int suppressed) {
        public boolean measured() {
            return this.kind != Kind.WARMUP;
        }

        /** Foreign load not explained by interrupts or steal: other processes on the tick CPU. */
        public double processes() {
            return Math.max(0.0, this.foreign - this.irq - this.steal);
        }

        static Result warmup(final int tickCpu) {
            return new Result(Kind.WARMUP, tickCpu, -1, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Map.of(), 0);
        }
    }

    private final double threshold;
    private final long cooldownMs;

    private Map<Integer, long[]> previousStat;
    private long previousOwnTicks = -1L;
    private long previousPauseMs = -1L;
    private long previousNowMs;
    private int previousTickCpu = -1;
    private int previousTid = -1;
    private int streak;
    private long lastMigrationMs = Long.MIN_VALUE;
    /** In an episode of "contended, no free reserve" (ends with a check at or below the threshold, or a move). */
    private boolean noFreeEpisode;
    private boolean noFreeEpisodeReported;
    private long noFreeReportedMs = Long.MIN_VALUE;
    private int noFreeSuppressed;

    /**
     * @param threshold  foreign load, as a fraction of one CPU, above which the tick CPU counts as contended
     * @param cooldownMs no second move within this time after a move
     */
    public ContentionDetector(final double threshold, final long cooldownMs) {
        this.threshold = threshold;
        this.cooldownMs = Math.max(0L, cooldownMs);
    }

    public double threshold() {
        return this.threshold;
    }

    public long cooldownMs() {
        return this.cooldownMs;
    }

    /** Milliseconds (on the caller's clock) of the last MIGRATE answer, or Long.MIN_VALUE. */
    public long lastMigrationMs() {
        return this.lastMigrationMs;
    }

    /**
     * Takes over the move history of the detector this one replaces (the settings changed): the cooldown
     * keeps running from the last move, and the "no free reserve" report keeps its rate limit.
     */
    public void inheritFrom(final ContentionDetector old) {
        if (old != null) {
            this.lastMigrationMs = old.lastMigrationMs;
            this.noFreeReportedMs = old.noFreeReportedMs;
            this.noFreeSuppressed = old.noFreeSuppressed;
        }
    }

    /** Forgets the baseline and the streak (the layout changed under the detector); keeps the cooldown. */
    public void reset() {
        this.previousStat = null;
        this.previousOwnTicks = -1L;
        this.previousPauseMs = -1L;
        this.previousTickCpu = -1;
        this.previousTid = -1;
        this.streak = 0;
    }

    /** {@link #check(long, int, List, Map, Map, int, long, long)} without SMT siblings and GC pause time. */
    public Result check(final long nowMs, final int tickCpu, final List<Integer> reserve, final Map<Integer, long[]> stat,
                        final int tid, final long ownTicks) {
        return this.check(nowMs, tickCpu, reserve, Map.of(), stat, tid, ownTicks, -1L);
    }

    /**
     * One check.
     *
     * @param nowMs     a monotonic clock in milliseconds (for the cooldown and the GC pause fraction)
     * @param tickCpu   the CPU the main thread is pinned to
     * @param reserve   the reserve CPUs the tick may move to
     * @param quiet     for a reserve CPU, its SMT siblings that run no server thread: they must be idle too,
     *                  or the tick would share a physical core with someone else's work
     * @param stat      cumulative /proc/stat counters by CPU ({@link ProcStat#read()})
     * @param tid       the main thread's id
     * @param ownTicks  the main thread's cumulative utime + stime in clock ticks, or -1 when unknown
     * @param pauseMs   the JVM's cumulative stop-the-world GC pause time in ms, or -1 when unknown
     */
    public Result check(final long nowMs, final int tickCpu, final List<Integer> reserve, final Map<Integer, List<Integer>> quiet,
                        final Map<Integer, long[]> stat, final int tid, final long ownTicks, final long pauseMs) {
        final Map<Integer, long[]> before = this.previousStat;
        final long ownBefore = this.previousOwnTicks;
        final long pauseBefore = this.previousPauseMs;
        final long windowMs = nowMs - this.previousNowMs;
        final boolean sameBase = before != null && tickCpu == this.previousTickCpu && tid == this.previousTid
            && ownBefore >= 0L && ownTicks >= ownBefore;
        this.previousStat = stat;
        this.previousOwnTicks = ownTicks;
        this.previousPauseMs = pauseMs;
        this.previousNowMs = nowMs;
        this.previousTickCpu = tickCpu;
        this.previousTid = tid;
        final long[] cpuNow = stat == null ? null : stat.get(tickCpu);
        final long[] cpuBefore = before == null ? null : before.get(tickCpu);
        if (!sameBase || ownTicks < 0L || cpuNow == null || cpuBefore == null) {
            this.streak = 0;
            return Result.warmup(tickCpu);
        }
        final long total = total(cpuBefore, cpuNow);
        if (total <= 0L) {
            this.streak = 0;
            return Result.warmup(tickCpu);
        }
        final double busy = busy(cpuBefore, cpuNow);
        final double own = clamp((double) (ownTicks - ownBefore) / (double) total);
        final double gcPause = pauseMs >= 0L && pauseBefore >= 0L && windowMs > 0L
            ? clamp((double) Math.max(0L, pauseMs - pauseBefore) / (double) windowMs) : 0.0;
        final double foreign = Math.max(0.0, busy - own - gcPause);
        final double steal = field(cpuBefore, cpuNow, 7, total);
        final double irq = clamp(field(cpuBefore, cpuNow, 5, total) + field(cpuBefore, cpuNow, 6, total));

        final Map<Integer, Double> reserveLoad = new TreeMap<>();
        for (final int cpu : reserve) {
            final long[] a = before.get(cpu);
            final long[] b = stat.get(cpu);
            if (a == null || b == null || total(a, b) <= 0L) {
                continue;
            }
            double load = busy(a, b);
            for (final int sibling : quiet.getOrDefault(cpu, List.of())) {
                final long[] sa = before.get(sibling);
                final long[] sb = stat.get(sibling);
                if (sa != null && sb != null && total(sa, sb) > 0L) {
                    load = Math.max(load, busy(sa, sb));
                }
            }
            reserveLoad.put(cpu, load);
        }

        if (foreign <= this.threshold) {
            this.streak = 0;
            this.endNoFreeEpisode();
            return new Result(Kind.OK, tickCpu, -1, busy, own, foreign, steal, irq, gcPause, reserveLoad, 0);
        }
        this.streak++;
        if (this.streak < CONSECUTIVE || (this.lastMigrationMs != Long.MIN_VALUE && nowMs - this.lastMigrationMs < this.cooldownMs)) {
            return new Result(Kind.WAIT, tickCpu, -1, busy, own, foreign, steal, irq, gcPause, reserveLoad, 0);
        }
        int best = -1;
        double bestLoad = Double.MAX_VALUE;
        for (final Map.Entry<Integer, Double> entry : reserveLoad.entrySet()) {
            if (entry.getKey() != tickCpu && entry.getValue() < RESERVE_IDLE && entry.getValue() < bestLoad) {
                best = entry.getKey();
                bestLoad = entry.getValue();
            }
        }
        if (best < 0) {
            if (!this.noFreeEpisode) {
                this.noFreeEpisode = true;
                this.noFreeEpisodeReported = false;
            }
            if (!this.noFreeEpisodeReported
                && (this.noFreeReportedMs == Long.MIN_VALUE || nowMs - this.noFreeReportedMs >= NO_FREE_REPORT_MS)) {
                this.noFreeEpisodeReported = true;
                this.noFreeReportedMs = nowMs;
                final int suppressed = this.noFreeSuppressed;
                this.noFreeSuppressed = 0;
                return new Result(Kind.NO_FREE_RESERVE, tickCpu, -1, busy, own, foreign, steal, irq, gcPause, reserveLoad, suppressed);
            }
            return new Result(Kind.WAIT, tickCpu, -1, busy, own, foreign, steal, irq, gcPause, reserveLoad, 0);
        }
        this.lastMigrationMs = nowMs;
        this.streak = 0;
        this.endNoFreeEpisode();
        this.noFreeReportedMs = Long.MIN_VALUE; // a new situation: report it at once
        // The next check measures the new CPU from a fresh baseline.
        this.previousStat = null;
        return new Result(Kind.MIGRATE, tickCpu, best, busy, own, foreign, steal, irq, gcPause, reserveLoad, 0);
    }

    private void endNoFreeEpisode() {
        if (this.noFreeEpisode && !this.noFreeEpisodeReported) {
            this.noFreeSuppressed++;
        }
        this.noFreeEpisode = false;
    }

    /** One /proc/stat counter's delta as a fraction of the total, 0 when the line has no such field. */
    private static double field(final long[] before, final long[] after, final int index, final long total) {
        return before.length > index && after.length > index && total > 0L
            ? clamp((double) (after[index] - before[index]) / (double) total) : 0.0;
    }

    /** user nice system idle iowait irq softirq steal: the sum of the first 8 counters' deltas. */
    static long total(final long[] before, final long[] after) {
        final int fields = Math.min(8, Math.min(before.length, after.length));
        long total = 0L;
        for (int i = 0; i < fields; i++) {
            total += after[i] - before[i];
        }
        return total;
    }

    /** Busy fraction: (total - idle - iowait) / total, as {@link ProcStat#load} computes it. */
    static double busy(final long[] before, final long[] after) {
        final int fields = Math.min(8, Math.min(before.length, after.length));
        if (fields < 4) {
            return 0.0;
        }
        final long total = total(before, after);
        long idle = after[3] - before[3];
        if (fields > 4) {
            idle += after[4] - before[4];
        }
        return total <= 0L ? 0.0 : clamp((double) (total - idle) / (double) total);
    }

    private static double clamp(final double v) {
        return Double.isNaN(v) ? 0.0 : Math.max(0.0, Math.min(1.0, v));
    }

    /**
     * utime + stime (fields 14 and 15) of a /proc/PID/task/TID/stat line, or -1. The comm field (2) may
     * hold spaces and parentheses, so fields are counted after the last ')'.
     */
    public static long cpuTicks(final String statLine) {
        if (statLine == null) {
            return -1L;
        }
        final int close = statLine.lastIndexOf(')');
        if (close < 0) {
            return -1L;
        }
        final String[] rest = statLine.substring(close + 1).trim().split("\\s+");
        // rest[0] is field 3 (state): utime is field 14 -> rest[11], stime field 15 -> rest[12].
        if (rest.length < 13) {
            return -1L;
        }
        try {
            return Long.parseLong(rest[11]) + Long.parseLong(rest[12]);
        } catch (final NumberFormatException e) {
            return -1L;
        }
    }
}
