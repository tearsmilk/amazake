package ink.hoka.amazake.cpu;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The CPUs this server may use and which of them are SMT siblings (two hardware threads of one
 * physical core). Read from Linux /proc and /sys; elsewhere 0..availableProcessors-1 without SMT.
 *
 * <p>The allowed set is the process's affinity (/proc/self/status Cpus_allowed_list) without CPUs that
 * are offline (/sys/devices/system/cpu/online). A cgroup CPU quota (Docker {@code --cpus}, Pterodactyl's
 * CPU limit: cgroup v2 cpu.max, v1 cpu.cfs_quota_us) is read as well: it limits CPU time, not which
 * CPUs, so a container may see 64 host CPUs and be allowed to use 2 CPUs' worth of time.</p>
 *
 * @param allowed  sorted logical CPU ids the process may run on
 * @param siblings for each allowed CPU, its SMT siblings among the allowed CPUs (itself included)
 * @param source   where {@code allowed} came from, for the log
 * @param quota    the cgroup CPU quota in CPUs (quota / period, e.g. 2.0), or 0 when there is none
 */
public record CpuTopology(List<Integer> allowed, Map<Integer, List<Integer>> siblings, String source, double quota) {

    private static final Path STATUS = Path.of("/proc/self/status");
    private static final Path SYS_CPU = Path.of("/sys/devices/system/cpu");
    private static final Path CGROUP_ROOT = Path.of("/sys/fs/cgroup");
    private static final Path PROC_CGROUP = Path.of("/proc/self/cgroup");

    /** Taken once, before any thread is pinned: later reads of /proc/self/status see our own masks. */
    private static volatile CpuTopology detected;

    public CpuTopology(final List<Integer> allowed, final Map<Integer, List<Integer>> siblings, final String source) {
        this(allowed, siblings, source, 0.0);
    }

    public CpuTopology {
        if (!(quota > 0.0) || Double.isInfinite(quota)) {
            quota = 0.0;
        }
        allowed = List.copyOf(new TreeSet<>(allowed));
        final Map<Integer, List<Integer>> clean = new TreeMap<>();
        for (final int cpu : allowed) {
            final TreeSet<Integer> set = new TreeSet<>();
            set.add(cpu);
            final List<Integer> listed = siblings == null ? null : siblings.get(cpu);
            if (listed != null) {
                for (final int sibling : listed) {
                    if (allowed.contains(sibling)) {
                        set.add(sibling);
                    }
                }
            }
            clean.put(cpu, List.copyOf(set));
        }
        siblings = java.util.Collections.unmodifiableMap(clean);
    }

    /** A topology without SMT: every CPU is its own physical core. */
    public static CpuTopology flat(final Collection<Integer> allowed) {
        return new CpuTopology(new ArrayList<>(allowed), Map.of(), "test");
    }

    /**
     * The process's topology, read on the first call and remembered. The first call must come before
     * any thread is pinned (Cores does it at startup): /proc/self/status shows the affinity of the
     * process's first thread, which the layout pins too.
     */
    public static CpuTopology detect() {
        CpuTopology topology = detected;
        if (topology == null) {
            synchronized (CpuTopology.class) {
                topology = detected;
                if (topology == null) {
                    topology = read();
                    detected = topology;
                }
            }
        }
        return topology;
    }

    /**
     * Re-checks the remembered topology after taskset said "invalid argument": drops CPUs that went
     * offline since (CPU hotplug, a VM losing vCPUs). Never widens it: once threads are pinned,
     * /proc/self/status no longer shows the process's own set. Returns the new topology.
     */
    public static synchronized CpuTopology refresh() {
        final CpuTopology old = detect();
        final List<Integer> online = readList(SYS_CPU.resolve("online"));
        if (online == null) {
            return old;
        }
        final List<Integer> kept = new ArrayList<>(old.allowed);
        kept.retainAll(online);
        if (kept.isEmpty() || kept.size() == old.allowed.size()) {
            return old;
        }
        final CpuTopology fresh = new CpuTopology(kept, old.siblings, old.source + " & online", old.quota);
        detected = fresh;
        return fresh;
    }

    /** For tests: makes {@link #detect()} return this topology. */
    static synchronized void setDetected(final CpuTopology topology) {
        detected = topology;
    }

    private static List<Integer> readList(final Path file) {
        try {
            return CpuListParser.parse(Files.readString(file).trim());
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    private static CpuTopology read() {
        List<Integer> allowed = null;
        String source = "/proc/self/status";
        try {
            for (final String line : Files.readAllLines(STATUS)) {
                if (line.startsWith("Cpus_allowed_list:")) {
                    allowed = CpuListParser.parse(line.substring("Cpus_allowed_list:".length()).trim());
                    break;
                }
            }
        } catch (final IOException | RuntimeException e) {
            allowed = null;
        }
        if (allowed == null || allowed.isEmpty()) {
            source = "availableProcessors";
            final List<Integer> all = new ArrayList<>();
            for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
                all.add(i);
            }
            allowed = all;
        } else {
            // The mask may name CPUs that are offline (possible > online in VMs, hotplug): skip them,
            // pinning a thread only to offline CPUs fails with EINVAL.
            final List<Integer> online = readList(SYS_CPU.resolve("online"));
            if (online != null) {
                final List<Integer> kept = new ArrayList<>(allowed);
                kept.retainAll(online);
                if (!kept.isEmpty() && kept.size() < allowed.size()) {
                    allowed = kept;
                    source += " & online";
                }
            }
        }
        final Map<Integer, List<Integer>> siblings = new LinkedHashMap<>();
        for (final int cpu : allowed) {
            final Path topology = SYS_CPU.resolve("cpu" + cpu).resolve("topology");
            for (final String file : new String[] {"thread_siblings_list", "core_cpus_list"}) {
                try {
                    siblings.put(cpu, CpuListParser.parse(Files.readString(topology.resolve(file)).trim()));
                    break;
                } catch (final IOException | RuntimeException e) {
                    // try the next name, or no SMT information for this CPU
                }
            }
        }
        List<String> cgroups;
        try {
            cgroups = Files.readAllLines(PROC_CGROUP);
        } catch (final IOException | RuntimeException e) {
            cgroups = List.of();
        }
        return new CpuTopology(allowed, siblings, source, cgroupQuota(CGROUP_ROOT, cgroups));
    }

    /**
     * The smallest CPU quota, in CPUs, of the process's cgroup and its ancestors, or 0 when none is set.
     *
     * @param root    where cgroup file systems are mounted (/sys/fs/cgroup)
     * @param cgroups the lines of /proc/self/cgroup ("0::/path" for v2, "4:cpu,cpuacct:/path" for v1)
     */
    static double cgroupQuota(final Path root, final List<String> cgroups) {
        double best = 0.0;
        for (final String line : cgroups) {
            final String[] parts = line.split(":", 3);
            if (parts.length < 3) {
                continue;
            }
            final String path = parts[2].trim();
            if (parts[0].equals("0") && parts[1].isEmpty()) {
                for (final Path dir : withAncestors(root, path)) {
                    best = smaller(best, cpuMax(dir.resolve("cpu.max")));
                }
                continue;
            }
            if (!List.of(parts[1].split(",")).contains("cpu")) {
                continue;
            }
            for (final String mount : new String[] {"cpu", "cpu,cpuacct", "cpuacct,cpu"}) {
                final Path base = root.resolve(mount);
                if (!Files.isDirectory(base)) {
                    continue;
                }
                for (final Path dir : withAncestors(base, path)) {
                    best = smaller(best, cfsQuota(dir));
                }
            }
        }
        return best;
    }

    /** base/path, then each of its parents up to base itself (in a container the path may not exist). */
    private static List<Path> withAncestors(final Path base, final String path) {
        final List<Path> out = new ArrayList<>();
        String rest = path.startsWith("/") ? path.substring(1) : path;
        while (true) {
            if (rest.contains("..")) {
                break;
            }
            out.add(rest.isEmpty() ? base : base.resolve(rest));
            if (rest.isEmpty()) {
                break;
            }
            final int slash = rest.lastIndexOf('/');
            rest = slash < 0 ? "" : rest.substring(0, slash);
        }
        if (out.isEmpty() || !out.get(out.size() - 1).equals(base)) {
            out.add(base);
        }
        return out;
    }

    private static double smaller(final double best, final double found) {
        if (!(found > 0.0)) {
            return best;
        }
        return best > 0.0 ? Math.min(best, found) : found;
    }

    /** cgroup v2 cpu.max: "max 100000" (no limit) or "200000 100000" (2 CPUs). */
    private static double cpuMax(final Path file) {
        try {
            final String[] parts = Files.readString(file).trim().split("\\s+");
            if (parts.length < 2 || parts[0].equals("max")) {
                return 0.0;
            }
            final double period = Double.parseDouble(parts[1]);
            return period > 0.0 ? Double.parseDouble(parts[0]) / period : 0.0;
        } catch (final IOException | RuntimeException e) {
            return 0.0;
        }
    }

    /** cgroup v1 cpu.cfs_quota_us / cpu.cfs_period_us; quota -1 means no limit. */
    private static double cfsQuota(final Path dir) {
        try {
            final double quota = Double.parseDouble(Files.readString(dir.resolve("cpu.cfs_quota_us")).trim());
            final double period = Double.parseDouble(Files.readString(dir.resolve("cpu.cfs_period_us")).trim());
            return quota > 0.0 && period > 0.0 ? quota / period : 0.0;
        } catch (final IOException | RuntimeException e) {
            return 0.0;
        }
    }

    /** Whether a cgroup CPU quota gives the server less CPU time than its allowed CPUs (ceil(quota) &lt; N). */
    public boolean quotaLimited() {
        return this.quota > 0.0 && Math.ceil(this.quota - 1e-6) < this.allowed.size();
    }

    /**
     * How many CPUs the server can use at once: the allowed CPUs, capped by the cgroup quota (rounded
     * up). This is what Runtime.availableProcessors() answers before any thread is pinned, and unlike
     * it does not depend on the calling thread.
     */
    public int processorCount() {
        final int n = Math.max(1, this.allowed.size());
        return this.quotaLimited() ? Math.max(1, (int) Math.ceil(this.quota - 1e-6)) : n;
    }

    /**
     * {@link #processorCount()} of this process, for code that would otherwise call
     * Runtime.availableProcessors() after the layout may have pinned its thread (the profiler).
     * Before the topology was read (not Linux: nothing is ever pinned) it is the JVM's answer.
     */
    public static int availableProcessors() {
        final CpuTopology topology = detected;
        if (topology == null) {
            return Runtime.getRuntime().availableProcessors();
        }
        return topology.processorCount();
    }

    /** Number of allowed logical CPUs. */
    public int logical() {
        return this.allowed.size();
    }

    /** The allowed CPUs grouped by physical core, each group sorted, groups ordered by their lowest CPU. */
    public List<List<Integer>> physicalCores() {
        // /sys can be inconsistent (a CPU listing a sibling that does not list it back): every CPU
        // goes into exactly one group, the first one that claims it.
        final List<List<Integer>> out = new ArrayList<>();
        final TreeSet<Integer> taken = new TreeSet<>();
        for (final int cpu : this.allowed) {
            if (taken.contains(cpu)) {
                continue;
            }
            final List<Integer> group = new ArrayList<>();
            for (final int sibling : this.siblings.get(cpu)) {
                if (taken.add(sibling)) {
                    group.add(sibling);
                }
            }
            out.add(List.copyOf(group));
        }
        return List.copyOf(out);
    }

    /** Whether any allowed CPU shares its physical core with another allowed CPU. */
    public boolean smt() {
        for (final List<Integer> group : this.siblings.values()) {
            if (group.size() > 1) {
                return true;
            }
        }
        return false;
    }
}
