package ink.hoka.amazake.cpu;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * The core layout: which logical CPUs each {@link Role} may run on. Immutable.
 *
 * <p>{@link #current()} is the layout that is actually applied to the server's threads right now,
 * or null when the layout is off (disabled in amazake.yml, not Linux, a single CPU, or taskset
 * missing or refused).</p>
 */
public final class CoreLayout {

    /** {@link #roleOf(int)} for a CPU that no role, or more than one role, uses. */
    public static final String SHARED = "shared";
    /** {@link #roleOf(int)} and {@link #roleIds(int)} of a reserve CPU (kept empty for the tick to move to). */
    public static final String RESERVE = "reserve";

    private static volatile CoreLayout current;

    private final List<Integer> allowed;
    private final EnumMap<Role, List<Integer>> cpus;
    /** Reserve CPUs: no role, no thread; the tick moves to one when its own CPU gets busy with foreign work. */
    private final List<Integer> reserve;
    /** SMT siblings by CPU, as {@link CpuTopology#siblings()}; not part of equality (a property of the machine). */
    private final Map<Integer, List<Integer>> siblings;

    /**
     * @param allowed every CPU the server process may use
     * @param cpus    each role's CPUs; every role must be present with at least one CPU
     */
    public CoreLayout(final List<Integer> allowed, final Map<Role, List<Integer>> cpus) {
        this(allowed, cpus, List.of());
    }

    /**
     * @param allowed every CPU the server process may use
     * @param cpus    each role's CPUs; every role must be present with at least one CPU
     * @param reserve CPUs kept empty (see {@link #reserve()}); none of them may belong to a role
     */
    public CoreLayout(final List<Integer> allowed, final Map<Role, List<Integer>> cpus, final List<Integer> reserve) {
        this(allowed, cpus, reserve, Map.of());
    }

    /**
     * @param allowed  every CPU the server process may use
     * @param cpus     each role's CPUs; every role must be present with at least one CPU
     * @param reserve  CPUs kept empty (see {@link #reserve()}); none of them may belong to a role
     * @param siblings SMT siblings by CPU (each list holds the CPU itself); empty when unknown or no SMT
     */
    public CoreLayout(final List<Integer> allowed, final Map<Role, List<Integer>> cpus, final List<Integer> reserve,
                      final Map<Integer, List<Integer>> siblings) {
        this.siblings = siblings == null ? Map.of() : Map.copyOf(siblings);
        this.allowed = List.copyOf(new TreeSet<>(allowed));
        this.cpus = new EnumMap<>(Role.class);
        for (final Role role : Role.values()) {
            final List<Integer> list = cpus.get(role);
            if (list == null || list.isEmpty()) {
                throw new IllegalArgumentException("role " + role + " has no CPUs");
            }
            this.cpus.put(role, List.copyOf(new TreeSet<>(list)));
        }
        this.reserve = List.copyOf(new TreeSet<>(reserve == null ? List.of() : reserve));
        for (final int cpu : this.reserve) {
            if (!this.allowed.contains(cpu)) {
                throw new IllegalArgumentException("reserve CPU " + cpu + " is not allowed");
            }
            for (final Role role : Role.values()) {
                if (this.cpus.get(role).contains(cpu)) {
                    throw new IllegalArgumentException("reserve CPU " + cpu + " belongs to role " + role);
                }
            }
        }
    }

    /** The layout applied to the threads right now; null when the core layout is off. */
    public static CoreLayout current() {
        return current;
    }

    static void setCurrent(final CoreLayout layout) {
        current = layout;
    }

    /** Sorted CPU ids of a role. */
    public List<Integer> cpus(final Role role) {
        return this.cpus.get(role);
    }

    /**
     * The CPUs a thread of {@code role} gets: its role's, every allowed CPU but the reserve for
     * stop-the-world GC workers, those without tick's for concurrent GC workers (tick's when nothing
     * else is left), or for plugin threads ({@link ThreadClassifier.Spread#SUPPORT}) {@link #supportCpus()}.
     * Reserve CPUs are never in the answer: they stay empty.
     */
    public List<Integer> cpus(final Role role, final ThreadClassifier.Spread spread) {
        return switch (spread) {
            case ROLE -> this.cpus.get(role);
            case ALL -> this.usable();
            case ALL_BUT_TICK -> {
                final List<Integer> out = new ArrayList<>(this.usable());
                out.removeAll(this.cpus.get(Role.TICK));
                yield out.isEmpty() ? this.usable() : List.copyOf(out);
            }
            case SUPPORT -> role == Role.PLUGINS ? this.supportCpus() : this.cpus.get(role);
        };
    }

    /**
     * Where plugin threads may run: the plugins CPUs plus the support (background) CPUs, without the
     * reserve, the tick CPUs and their SMT siblings (plugin work never shares the main thread's physical
     * core). The plugins CPUs alone when nothing else is left.
     */
    public List<Integer> supportCpus() {
        final TreeSet<Integer> out = new TreeSet<>(this.cpus.get(Role.PLUGINS));
        out.addAll(this.cpus.get(Role.BACKGROUND));
        out.removeAll(this.reserve);
        for (final int tick : this.cpus.get(Role.TICK)) {
            out.remove(tick);
            out.removeAll(this.siblingsOf(tick));
        }
        if (out.isEmpty()) {
            return this.cpus.get(Role.PLUGINS);
        }
        return List.copyOf(out);
    }

    /**
     * Support CPUs plugin threads spill onto: {@link #supportCpus()} without the plugins' own CPUs; empty
     * when plugins have no other CPUs (or share all of background's).
     */
    public List<Integer> spillCpus() {
        final List<Integer> out = new ArrayList<>(this.supportCpus());
        out.removeAll(this.cpus.get(Role.PLUGINS));
        return List.copyOf(out);
    }

    /** Allowed CPUs without the reserve. */
    private List<Integer> usable() {
        if (this.reserve.isEmpty()) {
            return this.allowed;
        }
        final List<Integer> out = new ArrayList<>(this.allowed);
        out.removeAll(this.reserve);
        return List.copyOf(out);
    }

    /** Reserve CPUs, sorted: no thread of the server runs there; empty when there is no reserve. */
    public List<Integer> reserve() {
        return this.reserve;
    }

    /** The other logical CPUs of {@code cpu}'s physical core (SMT siblings), without {@code cpu}; empty without SMT. */
    public List<Integer> siblingsOf(final int cpu) {
        final List<Integer> out = new ArrayList<>(this.siblings.getOrDefault(cpu, List.of()));
        out.remove(Integer.valueOf(cpu));
        return out;
    }

    /** Whether this CPU is a reserve CPU. */
    public boolean isReserve(final int cpu) {
        return this.reserve.contains(cpu);
    }

    /**
     * Whether the tick can move to a reserve CPU: there is a reserve, tick has exactly one CPU and no
     * other role uses that CPU (it becomes the new reserve and must be emptied).
     */
    public boolean canMoveTick() {
        if (this.reserve.isEmpty() || this.cpus.get(Role.TICK).size() != 1) {
            return false;
        }
        return this.rolesOf(this.cpus.get(Role.TICK).get(0)).size() == 1;
    }

    /**
     * This layout with the tick moved to reserve CPU {@code to}: tick gets [to], the old tick CPU takes
     * its place in the reserve, every other role keeps its CPUs. CPUs without a role (the idle SMT siblings
     * the planner keeps next to the tick CPU and next to each reserve CPU) stay idle: the old tick's core is
     * now a whole idle reserve core, the new tick's sibling was idle already.
     *
     * @throws IllegalArgumentException when {@link #canMoveTick()} is false or {@code to} is not a reserve CPU
     */
    public CoreLayout withTickMovedTo(final int to) {
        if (!this.canMoveTick()) {
            throw new IllegalArgumentException("the tick cannot move in " + this);
        }
        if (!this.reserve.contains(to)) {
            throw new IllegalArgumentException("CPU " + to + " is not a reserve CPU");
        }
        final int from = this.cpus.get(Role.TICK).get(0);
        final EnumMap<Role, List<Integer>> moved = new EnumMap<>(this.cpus);
        moved.put(Role.TICK, List.of(to));
        final List<Integer> reserve = new ArrayList<>(this.reserve);
        reserve.remove(Integer.valueOf(to));
        reserve.add(from);
        return new CoreLayout(this.allowed, moved, reserve, this.siblings);
    }

    /** The tick CPU when tick has exactly one, else -1. */
    public int tickCpu() {
        final List<Integer> tick = this.cpus.get(Role.TICK);
        return tick.size() == 1 ? tick.get(0) : -1;
    }

    /** Sorted CPU ids of a role by its config name ("tick", "chunks"...); empty for an unknown name. */
    public List<Integer> cpus(final String role) {
        final Role r = Role.byId(role);
        return r == null ? List.of() : this.cpus.get(r);
    }

    /** Every CPU the server process may use, sorted. */
    public List<Integer> allowed() {
        return this.allowed;
    }

    /** The roles that use this CPU, in role order; empty when none does. */
    public List<Role> rolesOf(final int cpu) {
        final List<Role> out = new ArrayList<>(2);
        for (final Role role : Role.values()) {
            if (this.cpus.get(role).contains(cpu)) {
                out.add(role);
            }
        }
        return out;
    }

    /**
     * The one role this CPU belongs to ("tick", "chunks", "network", "gc", "plugins", "background"),
     * {@value #RESERVE} for a reserve CPU, or {@value #SHARED} when it belongs to several roles (small
     * machines), to none (an idle SMT sibling) or is outside the CPUs the server may use. Never null.
     */
    public String roleOf(final int cpu) {
        if (this.reserve.contains(cpu)) {
            return RESERVE;
        }
        final List<Role> roles = this.rolesOf(cpu);
        return roles.size() == 1 ? roles.get(0).id() : SHARED;
    }

    /**
     * Every role of this CPU joined with "+" in role order ("chunks+gc" on small machines, "tick"),
     * {@value #RESERVE} for a reserve CPU, or {@value #SHARED} when no role uses it. This is what
     * reports carry in system.coreRoles.
     */
    public String roleIds(final int cpu) {
        if (this.reserve.contains(cpu)) {
            return RESERVE;
        }
        final List<Role> roles = this.rolesOf(cpu);
        if (roles.isEmpty()) {
            return SHARED;
        }
        final StringBuilder out = new StringBuilder();
        for (final Role role : roles) {
            if (out.length() > 0) {
                out.append('+');
            }
            out.append(role.id());
        }
        return out.toString();
    }

    /**
     * CPUs the server may use that no role was given and that are not reserve: on big SMT machines the
     * second thread of the tick core and of each reserve core, kept idle so the main thread has its physical
     * core to itself before and after a move.
     */
    public List<Integer> unused() {
        final List<Integer> out = new ArrayList<>();
        for (final int cpu : this.allowed) {
            if (this.rolesOf(cpu).isEmpty() && !this.reserve.contains(cpu)) {
                out.add(cpu);
            }
        }
        return out;
    }

    /** "tick=[0] chunks=[1] network=[4] gc=[2] plugins=[3] background=[4]", plus " reserve=[5]" with a reserve. */
    @Override
    public String toString() {
        final StringBuilder out = new StringBuilder();
        for (final Role role : Role.values()) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(role.id()).append("=[").append(CpuListParser.format(this.cpus.get(role))).append(']');
        }
        if (!this.reserve.isEmpty()) {
            out.append(" reserve=[").append(CpuListParser.format(this.reserve)).append(']');
        }
        return out.toString();
    }

    /** One line per allowed CPU: "  ядро 3 -> chunks", in Russian, for the console and /amazake cores. */
    public List<String> tableRu() {
        final List<String> out = new ArrayList<>();
        out.add("Раскладка ядер (серверу доступно логических ядер: " + this.allowed.size() + "):");
        for (final Role role : Role.values()) {
            out.add(String.format("  %-10s [%s] - %s", role.id(), CpuListParser.format(this.cpus.get(role)), role.descriptionRu()));
        }
        if (!this.reserve.isEmpty()) {
            out.add(String.format("  %-10s [%s] - %s", RESERVE, CpuListParser.format(this.reserve),
                "резерв: пустое ядро, сюда переезжает главный поток, если его ядро займёт кто-то чужой"));
        }
        final List<Integer> spill = this.spillCpus();
        if (!spill.isEmpty()) {
            out.add("  Потоки плагинов: [" + CpuListParser.format(this.supportCpus()) + "] - ядра plugins и ядро-подложка ["
                + CpuListParser.format(spill) + "], где у них приоритет над фоном (cpu.layout.support-nice).");
        }
        final List<Integer> unused = this.unused();
        if (!unused.isEmpty()) {
            out.add("  не заняты: [" + CpuListParser.format(unused) + "] (" + (this.reserve.isEmpty()
                ? "второй поток ядра tick, чтобы главному потоку ничто не мешало)"
                : "второй поток физических ядер tick и резерва: чтобы главному потоку ничто не мешало и после переезда)"));
        }
        out.add(this.reserve.isEmpty()
            ? "  Паузы GC (потоки GC Thread#) используют все ядра: в паузе главный поток всё равно стоит."
            : "  Паузы GC (потоки GC Thread#) используют все ядра, кроме резерва: в паузе главный поток всё равно стоит.");
        return out;
    }

    /** Short role label of a CPU for tables: "chunks", "chunks+gc", "—" when none. */
    public String label(final int cpu) {
        if (this.reserve.contains(cpu)) {
            return "резерв";
        }
        final List<Role> roles = this.rolesOf(cpu);
        if (roles.isEmpty()) {
            return this.allowed.contains(cpu) ? "свободно" : "не наше";
        }
        final StringBuilder out = new StringBuilder();
        for (final Role role : roles) {
            if (out.length() > 0) {
                out.append('+');
            }
            out.append(role.id());
        }
        return out.toString();
    }

    @Override
    public boolean equals(final Object o) {
        return o instanceof CoreLayout other && this.allowed.equals(other.allowed) && this.cpus.equals(other.cpus)
            && this.reserve.equals(other.reserve);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.allowed, this.cpus, this.reserve);
    }
}
