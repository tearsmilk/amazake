package core.amazake.cpu;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Plans the core layout from the CPUs the server may use. Pure logic, no Bukkit: unit-tested on its own.
 *
 * <p>Automatic layouts, by the number N of allowed logical CPUs c0 &lt; c1 &lt; ...:</p>
 * <ul>
 *   <li>N &lt;= 1: no layout (nothing to split).</li>
 *   <li>N = 2: tick [c0]; everything else [c1].</li>
 *   <li>N = 3: tick [c0]; chunks + gc [c1]; network + plugins + background [c2].</li>
 *   <li>N = 4: tick [c0]; chunks [c1]; network + gc + background [c2]; plugins [c3].</li>
 *   <li>N &gt;= 5: tick gets the first CPU of the first physical core. Its SMT sibling is left idle
 *       when N &gt;= 8 and there are at least 4 physical cores, so nothing competes with the main thread
 *       for that core; otherwise the sibling goes to the end of the pool. The other M CPUs are ordered
 *       physical core by physical core (both threads of a core next to each other) and cut into, in
 *       this order: chunks = round(40% of M), gc = round(15% of M), plugins = round(15% of M), network = 1,
 *       background = the rest; each at least 1 (chunks, then gc, then plugins give back a CPU while
 *       background would be empty). For M = 4 (N = 5 without SMT): chunks, gc, plugins one CPU each,
 *       network + background share the last. 6 CPUs without SMT: tick 0, chunks 1, gc 2, plugins 3,
 *       network + background 4, reserve 5.</li>
 * </ul>
 *
 * <p>Tick takes the lowest allowed CPU. The owner moves it with {@code cpu.layout.tick} when that CPU is
 * busy with interrupts on their machine.</p>
 *
 * <p>When a cgroup CPU quota allows less CPU time than the allowed CPUs (a Docker {@code --cpus} limit
 * on a big host), the automatic layout is off: the threads may use any host CPU, only for less time,
 * and pinning the main thread to one host CPU would just stop the scheduler from moving it off a busy
 * one. Manual lists still apply, with a warning.</p>
 *
 * <p>Manual overrides (amazake.yml {@code cpu.layout.<role>}) replace a role's CPUs. CPUs a manual
 * role takes are removed from the automatic roles; allowed CPUs nobody uses then go to background
 * (when background is automatic), and an automatic role left without CPUs shares background's.</p>
 *
 * <p>Reserve (amazake.yml {@code cpu.reserve.count}): CPUs kept empty, for the tick to move to when its
 * own CPU gets busy with foreign work (see {@link ContentionDetector}). "auto" is 1 when at least
 * {@value #AUTO_RESERVE_MIN_CPUS} CPUs are allowed, else 0. Reserve CPUs are taken out of background only,
 * never from tick or chunks, from the plugins' own core (a plugins CPU background does not share) nor from a
 * role set by hand, and never on the tick's own physical core (SMT
 * sibling): from the end of background's list, CPUs that no other role shares first. When the tick's SMT
 * sibling is kept idle (N &gt;= 8 with SMT), a reserve CPU's sibling is kept idle as well (taken out of
 * background, shown as unused), so the moved tick again has a physical core to itself; a CPU whose sibling
 * cannot be idled is skipped. On 6 CPUs with SMT (3 cores) the only background CPU is the tick's sibling,
 * so "auto" gives no reserve there. Roles that lose their last CPU to the reserve share another role's CPUs
 * (background, then network, gc, chunks), as on small machines; background itself takes network's, gc's or
 * chunks' and the plugins core only last, so plugins keep their own core whenever possible.</p>
 */
public final class LayoutPlanner {

    /**
     * The outcome: a layout, or why there is none.
     *
     * @param layout     null when disabled
     * @param disabledEn why there is no layout, in English (for the "Amazake cores:" log line)
     * @param disabledRu the same in Russian
     * @param warningsRu problems with manual overrides, in Russian; the plan still went ahead
     */
    public record Plan(CoreLayout layout, String disabledEn, String disabledRu, List<String> warningsRu) {
        public boolean enabled() {
            return this.layout != null;
        }

        static Plan disabled(final String en, final String ru, final List<String> warnings) {
            return new Plan(null, en, ru, List.copyOf(warnings));
        }
    }

    /** {@code cpu.reserve.count: auto} means 1 reserve CPU from this many allowed CPUs, 0 below. */
    public static final int AUTO_RESERVE_MIN_CPUS = 6;
    /** {@link #plan(CpuTopology, Map, int)}: the reserve count is "auto". */
    public static final int AUTO_RESERVE = -1;

    private LayoutPlanner() {
    }

    /** A plan without a reserve. */
    public static Plan plan(final CpuTopology topology, final Map<Role, String> overrides) {
        return plan(topology, overrides, 0);
    }

    /** How many reserve CPUs "auto" means for {@code n} allowed CPUs. */
    public static int autoReserve(final int n) {
        return n >= AUTO_RESERVE_MIN_CPUS ? 1 : 0;
    }

    /**
     * @param topology  allowed CPUs and SMT siblings
     * @param overrides config value per role ("auto" or a CPU list); missing roles are "auto"
     * @param reserve   reserve CPUs wanted, or {@link #AUTO_RESERVE}; 0 for none
     */
    public static Plan plan(final CpuTopology topology, final Map<Role, String> overrides, final int reserve) {
        final List<String> warnings = new ArrayList<>();
        final int n = topology.logical();
        if (n <= 1) {
            return Plan.disabled("only " + n + " CPU available to the server",
                "серверу доступно только " + n + " логическое ядро: делить нечего", warnings);
        }
        final Map<Role, List<Integer>> auto = auto(topology);

        final Map<Role, List<Integer>> manual = new EnumMap<>(Role.class);
        for (final Role role : Role.values()) {
            final String value = overrides == null ? null : overrides.get(role);
            if (CpuListParser.isAuto(value)) {
                continue;
            }
            final List<Integer> parsed;
            try {
                parsed = CpuListParser.parse(value);
            } catch (final IllegalArgumentException e) {
                warnings.add("cpu.layout." + role.id() + ": \"" + value.trim() + "\" - не список ядер (" + e.getMessage()
                    + "). Пишите auto или, например, 0, 1-3, 1,2,5. Для " + role.id() + " взято auto.");
                continue;
            }
            final List<Integer> usable = new ArrayList<>();
            final List<Integer> foreign = new ArrayList<>();
            for (final int cpu : parsed) {
                (topology.allowed().contains(cpu) ? usable : foreign).add(cpu);
            }
            if (!foreign.isEmpty()) {
                warnings.add("cpu.layout." + role.id() + ": ядра [" + CpuListParser.format(foreign)
                    + "] серверу недоступны (доступны [" + CpuListParser.format(topology.allowed()) + "]), они пропущены.");
            }
            if (usable.isEmpty()) {
                warnings.add("cpu.layout." + role.id() + ": не осталось ни одного доступного ядра, для " + role.id() + " взято auto.");
                continue;
            }
            manual.put(role, usable);
        }
        if (topology.quotaLimited()) {
            final String quota = formatQuota(topology.quota());
            if (manual.isEmpty()) {
                return Plan.disabled("CPU quota " + quota + " of " + n + " CPUs, not a cpuset",
                    "контейнер ограничен квотой CPU (" + quota + " " + coresWord(topology.quota()) + " из " + n
                        + "), а не набором ядер (cpuset): закрепление за ядрами хоста вредно. Ограничьте контейнер"
                        + " набором ядер (docker --cpuset-cpus) или задайте ядра ролей вручную в cpu.layout", warnings);
            }
            warnings.add("контейнер ограничен квотой CPU (" + quota + " " + coresWord(topology.quota()) + " из " + n
                + "), а не набором ядер: раскладка из ручных настроек cpu.layout применена, но система может"
                + " останавливать потоки по квоте на любых ядрах.");
        }
        if (manual.isEmpty()) {
            return new Plan(withReserve(topology, auto, manual.keySet(), reserve, warnings), null, null, List.copyOf(warnings));
        }

        final Set<Integer> claimed = new TreeSet<>();
        manual.values().forEach(claimed::addAll);
        final Map<Role, List<Integer>> result = new EnumMap<>(Role.class);
        for (final Role role : Role.values()) {
            if (manual.containsKey(role)) {
                result.put(role, manual.get(role));
            } else {
                final List<Integer> left = new ArrayList<>(auto.get(role));
                left.removeAll(claimed);
                result.put(role, left);
            }
        }
        // The automatic idle sibling of tick stays idle only while tick keeps its automatic core.
        final Set<Integer> keepIdle = new TreeSet<>();
        if (!manual.containsKey(Role.TICK)) {
            keepIdle.addAll(topology.allowed());
            auto.values().forEach(keepIdle::removeAll);
        }
        if (!manual.containsKey(Role.BACKGROUND)) {
            final Set<Integer> used = new TreeSet<>();
            result.values().forEach(used::addAll);
            final TreeSet<Integer> background = new TreeSet<>(result.get(Role.BACKGROUND));
            for (final int cpu : topology.allowed()) {
                if (!used.contains(cpu) && !keepIdle.contains(cpu)) {
                    background.add(cpu);
                }
            }
            result.put(Role.BACKGROUND, new ArrayList<>(background));
        }
        for (final Role role : Role.values()) {
            if (!result.get(role).isEmpty()) {
                continue;
            }
            List<Integer> fallback = result.get(Role.BACKGROUND);
            if (fallback.isEmpty()) {
                final List<Integer> all = new ArrayList<>(topology.allowed());
                all.removeAll(result.get(Role.TICK));
                fallback = all.isEmpty() ? topology.allowed() : all;
            }
            warnings.add("cpu.layout." + role.id() + " (auto): все его ядра заняты ручными настройками, "
                + role.id() + " делит ядра [" + CpuListParser.format(fallback) + "].");
            result.put(role, fallback);
        }
        return new Plan(withReserve(topology, result, manual.keySet(), reserve, warnings), null, null, List.copyOf(warnings));
    }

    /**
     * The layout of {@code roles} with {@code requested} reserve CPUs taken out of background.
     *
     * @param manualRoles roles set by hand: their CPUs never go to the reserve
     */
    static CoreLayout withReserve(final CpuTopology topology, final Map<Role, List<Integer>> roles, final Set<Role> manualRoles,
                                  final int requested, final List<String> warnings) {
        final int n = topology.logical();
        final int want = requested < 0 ? autoReserve(n) : requested;
        final Map<Integer, List<Integer>> siblings = topology.siblings();
        if (want <= 0) {
            return new CoreLayout(topology.allowed(), roles, List.of(), siblings);
        }
        final Map<Role, List<Integer>> result = new EnumMap<>(Role.class);
        roles.forEach((role, list) -> result.put(role, new ArrayList<>(list)));
        final Set<Integer> forbidden = new TreeSet<>(result.get(Role.TICK));
        forbidden.addAll(result.get(Role.CHUNKS));
        for (final Role role : manualRoles) {
            forbidden.addAll(result.get(role));
        }
        // The plugins core stays the plugins': only a CPU plugins share with background may become reserve.
        for (final int cpu : result.get(Role.PLUGINS)) {
            if (!result.get(Role.BACKGROUND).contains(cpu)) {
                forbidden.add(cpu);
            }
        }
        final Set<Integer> used = new TreeSet<>();
        result.values().forEach(used::addAll);
        // A reserve on the tick's own physical core is useless: a neighbour that takes the tick CPU would
        // share the core with the moved tick. And when the tick's SMT sibling is kept idle (big machines),
        // a reserve CPU's sibling is kept idle too, so after a move the tick has its core to itself again.
        final Set<Integer> tickSiblings = new TreeSet<>();
        boolean idleTickSibling = false;
        for (final int cpu : result.get(Role.TICK)) {
            for (final int sibling : siblings.getOrDefault(cpu, List.of(cpu))) {
                tickSiblings.add(sibling);
                idleTickSibling |= sibling != cpu && !used.contains(sibling);
            }
        }
        final List<Integer> background = result.get(Role.BACKGROUND);
        final List<Integer> candidates = new ArrayList<>();
        for (int i = background.size() - 1; i >= 0; i--) {
            final int cpu = background.get(i);
            if (!forbidden.contains(cpu) && !tickSiblings.contains(cpu)) {
                candidates.add(cpu);
            }
        }
        // Stable sort keeps "from the end of background" within each rank: CPUs no other role shares first.
        candidates.sort(java.util.Comparator.comparingInt((Integer cpu) -> {
            int users = 0;
            for (final List<Integer> list : result.values()) {
                users += list.contains(cpu) ? 1 : 0;
            }
            return users;
        }));
        final List<Integer> reserve = new ArrayList<>();
        final Set<Integer> idle = new TreeSet<>();
        // First pass: only siblings that are background candidates themselves may be idled. Second pass (big
        // SMT machines where background's last CPU shares its core with network or gc): an automatic
        // network/gc-only sibling may be idled too; those roles then share another role's CPUs. Never a
        // sibling of tick, chunks, the plugins core or a manual role.
        for (final boolean widen : new boolean[] {false, true}) {
            if (widen && !idleTickSibling) {
                break;
            }
            for (final int cpu : candidates) {
                if (reserve.size() >= want) {
                    break;
                }
                if (idle.contains(cpu) || reserve.contains(cpu)) {
                    continue;
                }
                final List<Integer> toIdle = new ArrayList<>();
                boolean fits = true;
                if (idleTickSibling) {
                    for (final int sibling : siblings.getOrDefault(cpu, List.of(cpu))) {
                        if (sibling == cpu || idle.contains(sibling) || !used.contains(sibling)) {
                            continue; // itself, or idle already
                        }
                        final boolean idleable = candidates.contains(sibling)
                            || (widen && !forbidden.contains(sibling) && !tickSiblings.contains(sibling)
                                && networkOrGcOnly(result, sibling));
                        if (!idleable || reserve.contains(sibling)) {
                            fits = false; // the sibling works for tick, chunks, plugins or a manual role: it cannot be idled
                            break;
                        }
                        toIdle.add(sibling);
                    }
                }
                if (fits) {
                    reserve.add(cpu);
                    idle.addAll(toIdle);
                }
            }
        }
        if (reserve.size() < want && requested >= 0) {
            warnings.add("cpu.reserve.count: " + requested + " - столько ядер в резерв не набралось, в резерве "
                + reserve.size() + ". Резерв берётся только из ядер background, которые не заняты tick, chunks, своим ядром plugins"
                + " и ролями, заданными вручную, и не делят физическое ядро (SMT) с tick.");
        }
        if (reserve.isEmpty()) {
            return new CoreLayout(topology.allowed(), roles, List.of(), siblings);
        }
        for (final Map.Entry<Role, List<Integer>> entry : result.entrySet()) {
            entry.getValue().removeAll(reserve);
            entry.getValue().removeAll(idle);
        }
        // Roles left without CPUs share another role's, the way the small automatic layouts combine them.
        // Background takes the plugins core only as the last resort, so plugins keep their own core.
        for (final Role role : List.of(Role.NETWORK, Role.GC, Role.PLUGINS, Role.BACKGROUND)) {
            if (!result.get(role).isEmpty()) {
                continue;
            }
            final List<Role> donors = role == Role.BACKGROUND
                ? List.of(Role.NETWORK, Role.GC, Role.CHUNKS, Role.PLUGINS)
                : List.of(Role.BACKGROUND, Role.NETWORK, Role.GC, Role.CHUNKS);
            for (final Role donor : donors) {
                if (donor != role && !result.get(donor).isEmpty()) {
                    result.put(role, new ArrayList<>(result.get(donor)));
                    break;
                }
            }
        }
        return new CoreLayout(topology.allowed(), result, reserve, siblings);
    }

    /** Whether {@code cpu} is used by network and/or gc and by no other role. */
    private static boolean networkOrGcOnly(final Map<Role, List<Integer>> roles, final int cpu) {
        boolean any = false;
        for (final Map.Entry<Role, List<Integer>> entry : roles.entrySet()) {
            if (entry.getValue().contains(cpu)) {
                if (entry.getKey() != Role.NETWORK && entry.getKey() != Role.GC) {
                    return false;
                }
                any = true;
            }
        }
        return any;
    }

    /** "2" or "1.5". */
    static String formatQuota(final double quota) {
        final double rounded = Math.round(quota * 100.0) / 100.0;
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Double.toString(rounded);
    }

    private static String coresWord(final double quota) {
        if (quota != Math.rint(quota)) {
            return "ядра";
        }
        final long n = (long) quota;
        final long mod100 = n % 100;
        final long mod10 = n % 10;
        if (mod10 == 1 && mod100 != 11) {
            return "ядро";
        }
        return mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14) ? "ядра" : "ядер";
    }

    /** The automatic layout for at least 2 CPUs. */
    static Map<Role, List<Integer>> auto(final CpuTopology topology) {
        final List<Integer> cpus = topology.allowed();
        final int n = cpus.size();
        final Map<Role, List<Integer>> out = new EnumMap<>(Role.class);
        if (n < 5) {
            out.put(Role.TICK, List.of(cpus.get(0)));
            out.putAll(rest(cpus.subList(1, n)));
            return out;
        }
        final List<List<Integer>> cores = topology.physicalCores();
        final List<Integer> tickCore = cores.get(0);
        out.put(Role.TICK, List.of(tickCore.get(0)));
        final boolean idleSibling = n >= 8 && cores.size() >= 4;
        final LinkedHashSet<Integer> pool = new LinkedHashSet<>();
        for (int i = 1; i < cores.size(); i++) {
            pool.addAll(cores.get(i));
        }
        if (!idleSibling) {
            // Sharing a core with the main thread: the weakest CPUs, so they go last (to background).
            pool.addAll(tickCore.subList(1, tickCore.size()));
        }
        out.putAll(rest(new ArrayList<>(pool)));
        return out;
    }

    /** Splits ordered CPUs among the roles other than tick. */
    static Map<Role, List<Integer>> rest(final List<Integer> pool) {
        final int m = pool.size();
        final Map<Role, List<Integer>> out = new EnumMap<>(Role.class);
        switch (m) {
            case 0 -> throw new IllegalArgumentException("no CPUs left for the other roles");
            case 1 -> {
                for (final Role role : List.of(Role.CHUNKS, Role.NETWORK, Role.GC, Role.PLUGINS, Role.BACKGROUND)) {
                    out.put(role, List.of(pool.get(0)));
                }
            }
            case 2 -> {
                out.put(Role.CHUNKS, List.of(pool.get(0)));
                out.put(Role.GC, List.of(pool.get(0)));
                out.put(Role.NETWORK, List.of(pool.get(1)));
                out.put(Role.PLUGINS, List.of(pool.get(1)));
                out.put(Role.BACKGROUND, List.of(pool.get(1)));
            }
            case 3 -> {
                out.put(Role.CHUNKS, List.of(pool.get(0)));
                out.put(Role.NETWORK, List.of(pool.get(1)));
                out.put(Role.GC, List.of(pool.get(1)));
                out.put(Role.BACKGROUND, List.of(pool.get(1)));
                out.put(Role.PLUGINS, List.of(pool.get(2)));
            }
            case 4 -> {
                out.put(Role.CHUNKS, List.of(pool.get(0)));
                out.put(Role.GC, List.of(pool.get(1)));
                out.put(Role.PLUGINS, List.of(pool.get(2)));
                out.put(Role.NETWORK, List.of(pool.get(3)));
                out.put(Role.BACKGROUND, List.of(pool.get(3)));
            }
            default -> {
                int chunks = Math.max(1, (4 * m + 5) / 10);   // round(0.40 * m)
                int gc = Math.max(1, (15 * m + 50) / 100);    // round(0.15 * m)
                int plugins = Math.max(1, (15 * m + 50) / 100); // round(0.15 * m)
                final int network = 1;
                while (m - chunks - gc - plugins - network < 1) {
                    if (chunks > 1) {
                        chunks--;
                    } else if (gc > 1) {
                        gc--;
                    } else {
                        plugins--;
                    }
                }
                int at = 0;
                out.put(Role.CHUNKS, List.copyOf(pool.subList(at, at += chunks)));
                out.put(Role.GC, List.copyOf(pool.subList(at, at += gc)));
                out.put(Role.PLUGINS, List.copyOf(pool.subList(at, at += plugins)));
                out.put(Role.NETWORK, List.copyOf(pool.subList(at, at += network)));
                out.put(Role.BACKGROUND, List.copyOf(pool.subList(at, m)));
            }
        }
        return out;
    }
}
