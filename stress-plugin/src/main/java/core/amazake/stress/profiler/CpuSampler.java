package core.amazake.stress.profiler;

// Ported unchanged from Amazake (amazake-server/src/main/java/core/amazake/profiler/CpuSampler.java),
// so the stress plugin measures CPU and threads exactly like the built-in /amazake profile.

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
    private final int logicalCores = Cpus.logical();

    private long[][] lastCores;
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
        final List<long[]> lines = readProcStat();
        if (lines == null || lines.isEmpty()) {
            this.lastCores = null;
            this.lastTotal = null;
            return;
        }
        this.lastTotal = lines.get(0);
        this.lastCores = lines.subList(1, lines.size()).toArray(new long[0][]);
    }

    /** The "cpu" line first, then each "cpuN" line, as their counters; null when unreadable. */
    private static List<long[]> readProcStat() {
        final List<String> lines;
        try {
            lines = Files.readAllLines(PROC_STAT);
        } catch (final IOException | RuntimeException e) {
            return null;
        }
        final List<long[]> out = new ArrayList<>();
        long[] total = null;
        for (final String line : lines) {
            if (!line.startsWith("cpu")) {
                continue;
            }
            final String[] parts = line.trim().split("\\s+");
            final long[] counters = new long[parts.length - 1];
            try {
                for (int i = 1; i < parts.length; i++) {
                    counters[i - 1] = Long.parseLong(parts[i]);
                }
            } catch (final NumberFormatException e) {
                continue;
            }
            if ("cpu".equals(parts[0])) {
                total = counters;
            } else {
                out.add(counters);
            }
        }
        if (total == null) {
            return null;
        }
        out.add(0, total);
        return out;
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
