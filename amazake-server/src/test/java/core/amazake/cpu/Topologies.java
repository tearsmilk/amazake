package core.amazake.cpu;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Fake CPU topologies for the layout tests. */
final class Topologies {

    private Topologies() {
    }

    /** CPUs 0..n-1, no SMT. */
    static CpuTopology flat(final int n) {
        final List<Integer> cpus = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cpus.add(i);
        }
        return CpuTopology.flat(cpus);
    }

    /** n CPUs, SMT pairs of neighbours: (0,1), (2,3)... */
    static CpuTopology smtAdjacent(final int n) {
        final Map<Integer, List<Integer>> siblings = new HashMap<>();
        final List<Integer> cpus = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            cpus.add(i);
            final int base = i - (i % 2);
            siblings.put(i, List.of(base, base + 1));
        }
        return new CpuTopology(cpus, siblings, "test");
    }

    /** n CPUs, SMT pairs (i, i + n/2), the usual Intel/AMD numbering. */
    static CpuTopology smtSplit(final int n) {
        final Map<Integer, List<Integer>> siblings = new HashMap<>();
        final List<Integer> cpus = new ArrayList<>();
        final int half = n / 2;
        for (int i = 0; i < n; i++) {
            cpus.add(i);
            final int core = i % half;
            siblings.put(i, List.of(core, core + half));
        }
        return new CpuTopology(cpus, siblings, "test");
    }
}
