package core.amazake.profiler;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CPU time of every thread. Java threads come from ThreadMXBean (full names); on Linux the JVM's own
 * GC and JIT threads, which Java cannot see, come from /proc/self/task. Each thread is read from
 * exactly one source, so nothing is counted twice. A thread that dies keeps the CPU it used.
 */
final class ThreadCpuSampler {

    private static final Path TASKS = Path.of("/proc/self/task");
    private static final Path AUXV = Path.of("/proc/self/auxv");
    private static final long AT_CLKTCK = 17L;

    /** One thread over the whole measurement. */
    static final class ThreadStat {
        final String name;
        final String group;
        final Series series = new Series();
        private long firstNs;
        private long lastNs;

        ThreadStat(final String name, final String group) {
            this.name = name;
            this.group = group;
        }

        long cpuNs() {
            return Math.max(0L, this.lastNs - this.firstNs);
        }
    }

    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final boolean jmxCpu;
    private final boolean proc;
    private final long clockTicksPerSecond;
    private final Map<String, ThreadStat> stats = new LinkedHashMap<>();

    ThreadCpuSampler() {
        boolean cpu = false;
        try {
            if (this.threads.isThreadCpuTimeSupported()) {
                if (!this.threads.isThreadCpuTimeEnabled()) {
                    this.threads.setThreadCpuTimeEnabled(true);
                }
                cpu = this.threads.isThreadCpuTimeEnabled();
            }
        } catch (final UnsupportedOperationException | SecurityException e) {
            cpu = false;
        }
        this.jmxCpu = cpu;
        this.proc = Files.isDirectory(TASKS) && Files.isReadable(TASKS);
        this.clockTicksPerSecond = this.proc ? readClockTicks() : 100L;
    }

    /** "proc" when JVM-internal threads are read from /proc, else "jmx". */
    String source() {
        return this.proc ? "proc" : "jmx";
    }

    Collection<ThreadStat> stats() {
        return this.stats.values();
    }

    /** Remembers every thread's CPU time so far: only what comes after counts. */
    void start() {
        this.read(-1, 0L);
    }

    /** Records the CPU each thread used since the previous call, as cores, at sample {@code index}. */
    void sample(final int index, final long elapsedNs) {
        this.read(index, elapsedNs);
    }

    private void read(final int index, final long elapsedNs) {
        if (this.proc) {
            this.readProc(index, elapsedNs);
        }
        if (this.jmxCpu) {
            this.readJmx(index, elapsedNs);
        }
    }

    private void record(final String key, final String name, final long cpuNs, final int index, final long elapsedNs) {
        this.record(key, name, -1L, cpuNs, index, elapsedNs);
    }

    /** @param javaId the Java thread id, or -1 for a JVM-internal thread read from /proc */
    private void record(final String key, final String name, final long javaId, final long cpuNs, final int index, final long elapsedNs) {
        ThreadStat stat = this.stats.get(key);
        if (stat == null) {
            stat = new ThreadStat(name, ThreadGroups.classify(name, javaId));
            // Seen at the start: only later CPU counts. Born during the measurement: all of it counts.
            stat.firstNs = index < 0 ? cpuNs : 0L;
            stat.lastNs = stat.firstNs;
            this.stats.put(key, stat);
        }
        final long delta = Math.max(0L, cpuNs - stat.lastNs);
        stat.lastNs = Math.max(stat.lastNs, cpuNs);
        if (index >= 0 && elapsedNs > 0L) {
            // One thread uses at most one core; the clocks are read a moment apart, so cap the jitter.
            stat.series.set(index, Math.min(1.0, (double) delta / (double) elapsedNs));
        }
    }

    private void readJmx(final int index, final long elapsedNs) {
        final long[] ids = this.threads.getAllThreadIds();
        final ThreadInfo[] infos = this.threads.getThreadInfo(ids);
        final long[] times;
        if (this.threads instanceof com.sun.management.ThreadMXBean ext) {
            times = ext.getThreadCpuTime(ids);
        } else {
            times = new long[ids.length];
            for (int i = 0; i < ids.length; i++) {
                times[i] = this.threads.getThreadCpuTime(ids[i]);
            }
        }
        for (int i = 0; i < ids.length; i++) {
            final ThreadInfo info = infos[i];
            if (info == null || times[i] < 0L) {
                continue; // died in between, or no CPU time for it
            }
            final String name = info.getThreadName();
            if (this.proc && ThreadGroups.isJvmInternal(ThreadGroups.classify(name))) {
                continue; // already taken from /proc
            }
            this.record("jmx:" + ids[i], name, ids[i], times[i], index, elapsedNs);
        }
    }

    private void readProc(final int index, final long elapsedNs) {
        try (DirectoryStream<Path> tasks = Files.newDirectoryStream(TASKS)) {
            for (final Path task : tasks) {
                final String stat;
                try {
                    stat = Files.readString(task.resolve("stat"));
                } catch (final IOException | RuntimeException e) {
                    continue; // the thread ended while listing
                }
                // "<tid> (<comm>) <state> ...": comm may hold spaces and parentheses, so cut at the last ')'.
                final int open = stat.indexOf('(');
                final int close = stat.lastIndexOf(')');
                if (open < 0 || close < open) {
                    continue;
                }
                final String name = stat.substring(open + 1, close);
                if (!ThreadGroups.isJvmInternal(ThreadGroups.classify(name))) {
                    continue; // Java threads are read through ThreadMXBean with their full names
                }
                final String[] fields = stat.substring(close + 1).trim().split("\\s+");
                // fields[0] is field 3 (state); utime and stime are fields 14 and 15.
                if (fields.length < 13) {
                    continue;
                }
                final long ticks;
                try {
                    ticks = Long.parseLong(fields[11]) + Long.parseLong(fields[12]);
                } catch (final NumberFormatException e) {
                    continue;
                }
                final long cpuNs = ticks * 1_000_000_000L / this.clockTicksPerSecond;
                this.record("proc:" + task.getFileName() + ":" + name, name, cpuNs, index, elapsedNs);
            }
        } catch (final IOException | RuntimeException e) {
            // /proc went away: keep what was measured so far
        }
    }

    /** USER_HZ from the ELF auxiliary vector (AT_CLKTCK); 100 when it cannot be read. */
    private static long readClockTicks() {
        try {
            final byte[] bytes = Files.readAllBytes(AUXV);
            if (bytes.length % 16 != 0) {
                return 100L;
            }
            final ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
            while (buffer.remaining() >= 16) {
                final long key = buffer.getLong();
                final long value = buffer.getLong();
                if (key == AT_CLKTCK) {
                    return value > 0L && value <= 10_000L ? value : 100L;
                }
                if (key == 0L) {
                    break;
                }
            }
        } catch (final IOException | RuntimeException e) {
            // fall through
        }
        return 100L;
    }
}
