package ink.hoka.amazake.profiler;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Whole-machine and whole-process CPU: per-core load from Linux /proc/stat, totals from the JVM's
 * OperatingSystemMXBean. All values are fractions 0..1.
 */
final class CpuSampler {

    private static final Path PROC_STAT = Path.of("/proc/stat");

    record CpuSample(double[] cores, double systemCpu, double processCpu) {
    }

    private final com.sun.management.OperatingSystemMXBean os;
    private final boolean perCore;
    // Not Runtime.availableProcessors(): that answers from the calling thread's CPU set, and with the
    // core layout this is created on the main thread, pinned to the tick CPU.
    private final int logicalCores = ink.hoka.amazake.cpu.CpuTopology.availableProcessors();

    private long[][] lastCores;
    private int[] lastCoreIds;
    private long[] lastTotal;
    private long lastProcessCpuNs = -1L;

    CpuSampler() {
        final java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        this.os = bean instanceof com.sun.management.OperatingSystemMXBean ext ? ext : null;
        this.perCore = Files.isReadable(PROC_STAT) && readProcStat() != null;
    }

    boolean perCore() {
        return this.perCore;
    }

    /**
     * The logical CPU id (N of "cpuN" in /proc/stat) of each entry of {@link CpuSample#cores()}, in the
     * same order; null when per-core data is not available. Offline CPUs are missing from /proc/stat,
     * so an index is not always the CPU id.
     */
    int[] coreIds() {
        return this.lastCoreIds == null ? null : this.lastCoreIds.clone();
    }

    /** Takes the starting point the first sample is measured against. */
    void start() {
        this.readCounters();
        if (this.os != null) {
            this.os.getCpuLoad();        // the first call only primes the bean
            this.os.getProcessCpuLoad();
            this.lastProcessCpuNs = this.os.getProcessCpuTime();
        }
    }

    CpuSample sample(final long elapsedNs) {
        final long[][] prevCores = this.lastCores;
        final long[] prevTotal = this.lastTotal;
        this.readCounters();

        double[] cores = null;
        if (this.perCore && prevCores != null && this.lastCores != null) {
            final int count = Math.min(prevCores.length, this.lastCores.length);
            cores = new double[count];
            for (int i = 0; i < count; i++) {
                cores[i] = busyFraction(prevCores[i], this.lastCores[i]);
            }
        }

        double system = this.os == null ? -1.0 : this.os.getCpuLoad();
        if (system < 0.0 || Double.isNaN(system)) {
            system = prevTotal != null && this.lastTotal != null ? busyFraction(prevTotal, this.lastTotal) : 0.0;
        }

        double process = this.os == null ? -1.0 : this.os.getProcessCpuLoad();
        final long processNs = this.os == null ? -1L : this.os.getProcessCpuTime();
        if (process < 0.0 || Double.isNaN(process)) {
            process = 0.0;
            if (processNs >= 0L && this.lastProcessCpuNs >= 0L && elapsedNs > 0L) {
                process = (double) (processNs - this.lastProcessCpuNs) / ((double) elapsedNs * this.logicalCores);
            }
        }
        this.lastProcessCpuNs = processNs;
        return new CpuSample(cores, clamp(system), clamp(process));
    }

    private void readCounters() {
        if (!this.perCore) {
            return;
        }
        final ProcStat stat = readProcStat();
        if (stat == null) {
            this.lastCores = null;
            this.lastCoreIds = null;
            this.lastTotal = null;
            return;
        }
        this.lastTotal = stat.total();
        this.lastCores = stat.cores();
        this.lastCoreIds = stat.ids();
    }

    /** The "cpu" line's counters, then each "cpuN" line's counters with its N, in file order. */
    private record ProcStat(long[] total, long[][] cores, int[] ids) {
    }

    /** /proc/stat's CPU lines; null when unreadable or without the "cpu" total line. */
    private static ProcStat readProcStat() {
        final List<String> lines;
        try {
            lines = Files.readAllLines(PROC_STAT);
        } catch (final IOException | RuntimeException e) {
            return null;
        }
        final List<long[]> cores = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
        long[] total = null;
        for (final String line : lines) {
            if (!line.startsWith("cpu")) {
                continue;
            }
            final String[] parts = line.trim().split("\\s+");
            final long[] counters = new long[parts.length - 1];
            int id = -1;
            try {
                for (int i = 1; i < parts.length; i++) {
                    counters[i - 1] = Long.parseLong(parts[i]);
                }
                if (!"cpu".equals(parts[0])) {
                    id = Integer.parseInt(parts[0].substring(3));
                }
            } catch (final NumberFormatException e) {
                continue;
            }
            if ("cpu".equals(parts[0])) {
                total = counters;
            } else {
                cores.add(counters);
                ids.add(id);
            }
        }
        if (total == null) {
            return null;
        }
        final int[] idArray = new int[ids.size()];
        for (int i = 0; i < idArray.length; i++) {
            idArray[i] = ids.get(i);
        }
        return new ProcStat(total, cores.toArray(new long[0][]), idArray);
    }

    /**
     * Fields: user nice system idle iowait irq softirq steal [guest guest_nice]. Guest time is already
     * counted in user/nice, so only the first eight make up the total; busy = total - idle - iowait.
     */
    private static double busyFraction(final long[] before, final long[] after) {
        final int fields = Math.min(8, Math.min(before.length, after.length));
        if (fields < 4) {
            return 0.0;
        }
        long total = 0L;
        for (int i = 0; i < fields; i++) {
            total += after[i] - before[i];
        }
        long idle = after[3] - before[3];
        if (fields > 4) {
            idle += after[4] - before[4];
        }
        return total <= 0L ? 0.0 : clamp((double) (total - idle) / (double) total);
    }

    private static double clamp(final double value) {
        if (Double.isNaN(value) || value < 0.0) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }
}
