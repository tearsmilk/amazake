package core.amazake.cpu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;

/**
 * Linux CPU lists as the kernel and taskset write them: "0", "1-3", "1,2,5", "0-3,8-11".
 * Pure logic, no Bukkit: unit-tested on its own.
 */
public final class CpuListParser {

    /** Highest CPU id accepted; Linux supports up to 8192 (CONFIG_NR_CPUS). */
    public static final int MAX_CPU = 8191;

    private CpuListParser() {
    }

    /** True for "auto" (any case, surrounding spaces ignored), null and "". */
    public static boolean isAuto(final String value) {
        return value == null || value.isBlank() || value.trim().equalsIgnoreCase("auto");
    }

    /**
     * Parses a CPU list into sorted, distinct ids. Spaces around items are allowed.
     *
     * @throws IllegalArgumentException with an English message for anything that is not a CPU list
     */
    public static List<Integer> parse(final String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("empty CPU list");
        }
        final TreeSet<Integer> out = new TreeSet<>();
        for (final String rawItem : text.split(",", -1)) {
            final String item = rawItem.trim();
            if (item.isEmpty()) {
                throw new IllegalArgumentException("empty item in CPU list \"" + text + "\"");
            }
            final int dash = item.indexOf('-');
            if (dash < 0) {
                out.add(number(item, text));
                continue;
            }
            final int from = number(item.substring(0, dash).trim(), text);
            final int to = number(item.substring(dash + 1).trim(), text);
            if (to < from) {
                throw new IllegalArgumentException("range " + item + " goes backwards in \"" + text + "\"");
            }
            for (int cpu = from; cpu <= to; cpu++) {
                out.add(cpu);
            }
        }
        return List.copyOf(out);
    }

    private static int number(final String digits, final String text) {
        if (digits.isEmpty() || digits.length() > 5) {
            throw new IllegalArgumentException("bad CPU number \"" + digits + "\" in \"" + text + "\"");
        }
        for (int i = 0; i < digits.length(); i++) {
            if (digits.charAt(i) < '0' || digits.charAt(i) > '9') {
                throw new IllegalArgumentException("bad CPU number \"" + digits + "\" in \"" + text + "\"");
            }
        }
        final int value = Integer.parseInt(digits);
        if (value > MAX_CPU) {
            throw new IllegalArgumentException("CPU " + value + " is above " + MAX_CPU + " in \"" + text + "\"");
        }
        return value;
    }

    /** The shortest list form: [0,1,2,3,8] -> "0-3,8"; an empty collection gives "". */
    public static String format(final Collection<Integer> cpus) {
        final List<Integer> sorted = new ArrayList<>(new TreeSet<>(cpus));
        final StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < sorted.size()) {
            final int start = sorted.get(i);
            int end = start;
            while (i + 1 < sorted.size() && sorted.get(i + 1) == end + 1) {
                end = sorted.get(++i);
            }
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(start);
            if (end != start) {
                out.append('-').append(end);
            }
            i++;
        }
        return out.toString();
    }
}
