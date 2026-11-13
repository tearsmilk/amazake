package core.amazake.cpu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** CoreLayout's role ids, the support spread of plugin threads and the tick move. */
class CoreLayoutTest {

    private static CoreLayout sixFlat() {
        final Map<Role, List<Integer>> roles = new EnumMap<>(Role.class);
        roles.put(Role.TICK, List.of(0));
        roles.put(Role.CHUNKS, List.of(1));
        roles.put(Role.GC, List.of(2));
        roles.put(Role.PLUGINS, List.of(3));
        roles.put(Role.NETWORK, List.of(4));
        roles.put(Role.BACKGROUND, List.of(4));
        return new CoreLayout(List.of(0, 1, 2, 3, 4, 5), roles, List.of(5));
    }

    @Test
    void roleIdsFollowRoleOrder() {
        final CoreLayout layout = sixFlat();
        assertEquals("tick", layout.roleIds(0));
        assertEquals("plugins", layout.roleIds(3));
        assertEquals("network+background", layout.roleIds(4));
        assertEquals("reserve", layout.roleIds(5));
        assertEquals("plugins", layout.roleOf(3));
        assertEquals("shared", layout.roleOf(4));
    }

    @Test
    void pluginThreadsUseThePluginsAndSupportCores() {
        final CoreLayout layout = sixFlat();
        assertEquals(List.of(3, 4), layout.cpus(Role.PLUGINS, ThreadClassifier.Spread.SUPPORT));
        assertEquals(List.of(4), layout.spillCpus());
        // SUPPORT only widens plugins.
        assertEquals(List.of(4), layout.cpus(Role.BACKGROUND, ThreadClassifier.Spread.SUPPORT));
        assertTrue(layout.tableRu().stream().anyMatch(line -> line.contains("ядро-подложка [4]")), () -> String.join("\n", layout.tableRu()));
        // After a tick move the old tick CPU is reserve, and plugin threads still stay off the tick.
        final CoreLayout moved = layout.withTickMovedTo(5);
        assertEquals(List.of(3, 4), moved.cpus(Role.PLUGINS, ThreadClassifier.Spread.SUPPORT));
    }

    @Test
    void supportSkipsTheTickSibling() {
        final Map<Role, List<Integer>> roles = new EnumMap<>(Role.class);
        roles.put(Role.TICK, List.of(0));
        roles.put(Role.CHUNKS, List.of(2));
        roles.put(Role.GC, List.of(3));
        roles.put(Role.PLUGINS, List.of(4));
        roles.put(Role.NETWORK, List.of(5));
        roles.put(Role.BACKGROUND, List.of(1, 5));
        final CoreLayout layout = new CoreLayout(List.of(0, 1, 2, 3, 4, 5), roles, List.of(),
            Topologies.smtAdjacent(6).siblings());
        assertEquals(List.of(4, 5), layout.cpus(Role.PLUGINS, ThreadClassifier.Spread.SUPPORT));
        assertEquals(List.of(5), layout.spillCpus());
    }
}
