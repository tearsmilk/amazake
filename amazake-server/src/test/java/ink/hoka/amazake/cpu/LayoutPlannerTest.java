package ink.hoka.amazake.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

/** The automatic core layout with the plugins role and the reserve, on fake topologies. */
class LayoutPlannerTest {

    private static CoreLayout plan(final CpuTopology topology, final int reserve) {
        final LayoutPlanner.Plan plan = LayoutPlanner.plan(topology, Map.of(), reserve);
        assertTrue(plan.enabled(), () -> "no layout: " + plan.disabledEn());
        return plan.layout();
    }

    @Test
    void sixCpusWithoutSmtGivesPluginsTheirOwnCore() {
        // The user's VPS: tick 0; chunks 1; gc 2; plugins 3; network+background 4; reserve 5.
        final CoreLayout layout = plan(Topologies.flat(6), LayoutPlanner.AUTO_RESERVE);
        assertEquals("tick=[0] chunks=[1] network=[4] gc=[2] plugins=[3] background=[4] reserve=[5]", layout.toString());
        assertEquals("plugins", layout.roleIds(3));
        assertEquals("network+background", layout.roleIds(4));
        assertEquals("reserve", layout.roleIds(5));
        assertEquals(List.of(3, 4), layout.cpus(Role.PLUGINS, ThreadClassifier.Spread.SUPPORT));
        assertEquals(List.of(4), layout.spillCpus());
    }

    @Test
    void restSplitsSmallPools() {
        assertEquals("{chunks=[1], network=[1], gc=[1], plugins=[1], background=[1]}", LayoutPlanner.rest(List.of(1)).toString());
        assertEquals("{chunks=[1], network=[2], gc=[1], plugins=[2], background=[2]}", LayoutPlanner.rest(List.of(1, 2)).toString());
        assertEquals("{chunks=[1], network=[2], gc=[2], plugins=[3], background=[2]}", LayoutPlanner.rest(List.of(1, 2, 3)).toString());
        assertEquals("{chunks=[1], network=[4], gc=[2], plugins=[3], background=[4]}", LayoutPlanner.rest(List.of(1, 2, 3, 4)).toString());
        assertEquals("{chunks=[1], network=[4], gc=[2], plugins=[3], background=[5]}", LayoutPlanner.rest(List.of(1, 2, 3, 4, 5)).toString());
    }

    @Test
    void restSplitsBigPoolsByShare() {
        final List<Integer> pool = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            pool.add(i);
        }
        // chunks round(6.0)=6, gc round(2.25)=2, plugins 2, network 1, background 4.
        assertEquals("{chunks=[1, 2, 3, 4, 5, 6], network=[11], gc=[7, 8], plugins=[9, 10], background=[12, 13, 14, 15]}",
            LayoutPlanner.rest(pool).toString());
    }

    @Test
    void knownLayouts() {
        assertEquals("tick=[0] chunks=[1] network=[1] gc=[1] plugins=[1] background=[1]", plan(Topologies.flat(2), -1).toString());
        assertEquals("tick=[0] chunks=[1] network=[2] gc=[1] plugins=[2] background=[2]", plan(Topologies.flat(3), -1).toString());
        assertEquals("tick=[0] chunks=[1] network=[2] gc=[2] plugins=[3] background=[2]", plan(Topologies.flat(4), -1).toString());
        assertEquals("tick=[0] chunks=[1-3] network=[6] gc=[4] plugins=[5] background=[6] reserve=[7]", plan(Topologies.flat(8), -1).toString());
        assertEquals("tick=[0] chunks=[1-6] network=[11] gc=[7-8] plugins=[9-10] background=[12-14] reserve=[15]",
            plan(Topologies.flat(16), -1).toString());
        // 8 with SMT: the reserve 7 needs its sibling 6 idle, so network shares gc's CPU; plugins keep CPU 5.
        assertEquals("tick=[0] chunks=[2-3] network=[4] gc=[4] plugins=[5] background=[4] reserve=[7]",
            plan(Topologies.smtAdjacent(8), -1).toString());
        assertEquals(List.of(1, 6), plan(Topologies.smtAdjacent(8), -1).unused());
        // 16 with SMT: the tick's sibling 1 is idle, plugins get a whole physical core, reserve 15 with 14 idle.
        assertEquals("tick=[0] chunks=[2-7] network=[12] gc=[8-9] plugins=[10-11] background=[13] reserve=[15]",
            plan(Topologies.smtAdjacent(16), -1).toString());
    }

    @Test
    void manualReserveOnThreeCpusSharesOneCore() {
        final CoreLayout layout = plan(Topologies.flat(3), 1);
        assertEquals(List.of(2), layout.reserve());
        for (final Role role : List.of(Role.CHUNKS, Role.NETWORK, Role.GC, Role.PLUGINS, Role.BACKGROUND)) {
            assertEquals(List.of(1), layout.cpus(role), role.id());
        }
    }

    @Test
    void reserveNeverTakesThePluginsOwnCore() {
        // Plugins on a CPU of its own, background on another: asking for more reserve than background has
        // must not empty the plugins core.
        final Map<Role, List<Integer>> roles = new EnumMap<>(Role.class);
        roles.put(Role.TICK, List.of(0));
        roles.put(Role.CHUNKS, List.of(1));
        roles.put(Role.NETWORK, List.of(2));
        roles.put(Role.GC, List.of(2));
        roles.put(Role.PLUGINS, List.of(3));
        roles.put(Role.BACKGROUND, List.of(4));
        final List<String> warnings = new ArrayList<>();
        final CoreLayout layout = LayoutPlanner.withReserve(Topologies.flat(5), roles, java.util.Set.of(), 2, warnings);
        assertEquals(List.of(4), layout.reserve());
        assertEquals(List.of(3), layout.cpus(Role.PLUGINS));
        // Background shares network's CPU, not the plugins core.
        assertEquals(List.of(2), layout.cpus(Role.BACKGROUND));
        assertEquals(1, warnings.size());
    }

    @Test
    void invariantsOnManyMachines() {
        final List<IntFunction<CpuTopology>> kinds = List.of(Topologies::flat, Topologies::smtAdjacent, Topologies::smtSplit);
        for (final int n : new int[] {2, 3, 4, 6, 8, 12, 16, 32}) {
            for (final IntFunction<CpuTopology> kind : kinds) {
                final CpuTopology topology = kind.apply(n);
                final String name = n + " CPUs" + (topology.smt() ? " SMT " + topology.siblings().get(0) : "");
                final CoreLayout bare = plan(topology, 0);
                final CoreLayout layout = plan(topology, LayoutPlanner.AUTO_RESERVE);
                for (final CoreLayout l : List.of(bare, layout)) {
                    for (final Role role : Role.values()) {
                        assertFalse(l.cpus(role).isEmpty(), name + ": " + role + " empty");
                    }
                    assertEquals(1, l.cpus(Role.TICK).size(), name);
                    final int tick = l.tickCpu();
                    assertEquals(List.of(Role.TICK), l.rolesOf(tick), name + ": tick shares its CPU in " + l);
                    final List<Integer> support = l.cpus(Role.PLUGINS, ThreadClassifier.Spread.SUPPORT);
                    assertFalse(support.contains(tick), name);
                    for (final int sibling : l.siblingsOf(tick)) {
                        if (!l.cpus(Role.PLUGINS).contains(sibling)) {
                            assertFalse(support.contains(sibling), name + ": plugin threads on the tick's sibling");
                        }
                    }
                    for (final int cpu : l.reserve()) {
                        assertFalse(support.contains(cpu), name);
                    }
                    if (n >= 4) {
                        for (final int cpu : l.cpus(Role.PLUGINS)) {
                            assertEquals(List.of(Role.PLUGINS), l.rolesOf(cpu), name + ": plugins not exclusive in " + l);
                        }
                    }
                }
                // auto keeps one reserve CPU from 6 CPUs; with SMT from 8 (on 6 the only candidate is the tick's sibling).
                final boolean reserveExpected = topology.smt() ? n >= 8 : n >= LayoutPlanner.AUTO_RESERVE_MIN_CPUS;
                assertEquals(reserveExpected ? 1 : 0, layout.reserve().size(), name + ": " + layout);
                for (final int cpu : layout.reserve()) {
                    final List<Role> before = bare.rolesOf(cpu);
                    assertFalse(before.contains(Role.TICK) || before.contains(Role.CHUNKS), name + ": reserve " + cpu + " was " + before);
                    assertFalse(before.contains(Role.PLUGINS) && !before.contains(Role.BACKGROUND), name + ": reserve on the plugins core");
                    assertFalse(bare.siblingsOf(bare.tickCpu()).contains(cpu), name + ": reserve on the tick's sibling");
                }
            }
        }
    }

    @Test
    void sixCpusWithSmtHasNoAutoReserve() {
        final CoreLayout layout = plan(Topologies.smtAdjacent(6), LayoutPlanner.AUTO_RESERVE);
        assertTrue(layout.reserve().isEmpty(), layout::toString);
        assertEquals(List.of(1), layout.cpus(Role.BACKGROUND)); // the tick's sibling
        // Plugin threads do not spill onto the tick's physical core.
        assertEquals(layout.cpus(Role.PLUGINS), layout.cpus(Role.PLUGINS, ThreadClassifier.Spread.SUPPORT));
    }

    @Test
    void manualPluginsWin() {
        final CoreLayout layout = plan(Topologies.flat(6), 0);
        final LayoutPlanner.Plan manual = LayoutPlanner.plan(Topologies.flat(6), Map.of(Role.PLUGINS, "4-5"), 0);
        assertTrue(manual.enabled());
        assertEquals(List.of(4, 5), manual.layout().cpus(Role.PLUGINS));
        assertFalse(layout.cpus(Role.PLUGINS).isEmpty());
        for (final Role role : Role.values()) {
            assertFalse(manual.layout().cpus(role).isEmpty(), role.id());
        }
    }
}
