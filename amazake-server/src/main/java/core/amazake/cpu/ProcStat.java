package core.amazake.cpu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Per-core busy counters from Linux /proc/stat, and the load between two reads. */
public final class ProcStat {

    private static final Path PROC_STAT = Path.of("/proc/stat");

    private ProcStat() {
    }

    /** Counters of each "cpuN" line by N; empty when /proc/stat cannot be read. */
    public static Map<Integer, long[]> read() {
        try {
            return parse(Files.readAllLines(PROC_STAT));
        } catch (final IOException | RuntimeException e) {
            return Map.of();
        }
    }

    static Map<Integer, long[]> parse(final List<String> lines) {
        final Map<Integer, long[]> out = new TreeMap<>();
        for (final String line : lines) {
            if (!line.startsWith("cpu") || line.startsWith("cpu ")) {
                continue;
            }
            final String[] parts = line.trim().split("\\s+");
            try {
                final int id = Integer.parseInt(parts[0].substring(3));
                final long[] counters = new long[parts.length - 1];
                for (int i = 1; i < parts.length; i++) {
                    counters[i - 1] = Long.parseLong(parts[i]);
                }
                out.put(id, counters);
            } catch (final NumberFormatException e) {
                // not a CPU line
            }
        }
        return out;
    }

    /** Busy fraction 0..1 of each CPU present in both reads. */
    public static Map<Integer, Double> load(final Map<Integer, long[]> before, final Map<Integer, long[]> after) {
        final Map<Integer, Double> out = new TreeMap<>();
        for (final Map.Entry<Integer, long[]> entry : after.entrySet()) {
            final long[] prev = before.get(entry.getKey());
            if (prev != null) {
                out.put(entry.getKey(), busy(prev, entry.getValue()));
            }
        }
        return out;
    }

    /** user nice system idle iowait irq softirq steal: busy = total - idle - iowait (guest is inside user). */
    private static double busy(final long[] before, final long[] after) {
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
        if (total <= 0L) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) (total - idle) / (double) total));
    }
}
