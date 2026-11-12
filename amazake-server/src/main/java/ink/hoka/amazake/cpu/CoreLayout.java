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

    private static volatile CoreLayout current;

    private final List<Integer> allowed;
    private final EnumMap<Role, List<Integer>> cpus;

    /**
     * @param allowed every CPU the server process may use
     * @param cpus    each role's CPUs; every role must be present with at least one CPU
     */
    public CoreLayout(final List<Integer> allowed, final Map<Role, List<Integer>> cpus) {
        this.allowed = List.copyOf(new TreeSet<>(allowed));
        this.cpus = new EnumMap<>(Role.class);
        for (final Role role : Role.values()) {
            final List<Integer> list = cpus.get(role);
            if (list == null || list.isEmpty()) {
                throw new IllegalArgumentException("role " + role + " has no CPUs");
            }
            this.cpus.put(role, List.copyOf(new TreeSet<>(list)));
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
     * The CPUs a thread of {@code role} gets: its role's, every allowed CPU for stop-the-world GC
     * workers, or every allowed CPU but tick's for concurrent GC workers (tick's when nothing else is left).
     */
    public List<Integer> cpus(final Role role, final ThreadClassifier.Spread spread) {
        return switch (spread) {
            case ROLE -> this.cpus.get(role);
            case ALL -> this.allowed;
            case ALL_BUT_TICK -> {
                final List<Integer> out = new ArrayList<>(this.allowed);
                out.removeAll(this.cpus.get(Role.TICK));
                yield out.isEmpty() ? this.allowed : List.copyOf(out);
            }
        };
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
     * The one role this CPU belongs to ("tick", "chunks", "network", "gc", "background"), or
     * {@value #SHARED} when it belongs to several roles (small machines), to none (an idle SMT
     * sibling) or is outside the CPUs the server may use. Never null.
     */
    public String roleOf(final int cpu) {
        final List<Role> roles = this.rolesOf(cpu);
        return roles.size() == 1 ? roles.get(0).id() : SHARED;
    }

    /**
     * Every role of this CPU joined with "+" in role order ("chunks+gc" on small machines, "tick"),
     * or {@value #SHARED} when no role uses it. This is what reports carry in system.coreRoles.
     */
    public String roleIds(final int cpu) {
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

    /** CPUs the server may use that no role was given (the tick core's SMT sibling on big machines). */
    public List<Integer> unused() {
        final List<Integer> out = new ArrayList<>();
        for (final int cpu : this.allowed) {
            if (this.rolesOf(cpu).isEmpty()) {
                out.add(cpu);
            }
        }
        return out;
    }

    /** "tick=[0] chunks=[1-3] network=[4] gc=[5] background=[6-7]". */
    @Override
    public String toString() {
        final StringBuilder out = new StringBuilder();
        for (final Role role : Role.values()) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(role.id()).append("=[").append(CpuListParser.format(this.cpus.get(role))).append(']');
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
        final List<Integer> unused = this.unused();
        if (!unused.isEmpty()) {
            out.add("  не заняты: [" + CpuListParser.format(unused) + "] (второй поток ядра tick, чтобы главному потоку ничто не мешало)");
        }
        out.add("  Паузы GC (потоки GC Thread#) используют все ядра: в паузе главный поток всё равно стоит.");
        return out;
    }

    /** Short role label of a CPU for tables: "chunks", "chunks+gc", "—" when none. */
    public String label(final int cpu) {
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
        return o instanceof CoreLayout other && this.allowed.equals(other.allowed) && this.cpus.equals(other.cpus);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.allowed, this.cpus);
    }
}
