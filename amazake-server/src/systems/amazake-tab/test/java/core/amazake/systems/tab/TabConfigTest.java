package core.amazake.systems.tab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class TabConfigTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(final String text) {
        return (Map<String, Object>) new Yaml().load(text);
    }

    /** The config.yml shipped in the .azs: from the test classpath, or from the source tree. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> shippedConfig() throws IOException {
        try (InputStream in = TabConfigTest.class.getResourceAsStream("/config.yml")) {
            if (in != null) {
                final String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (text.contains("AmazakeTab")) {
                    return (Map<String, Object>) new Yaml().load(text);
                }
            }
        }
        for (final String candidate : List.of("src/systems/amazake-tab/resources/config.yml",
            "amazake-server/src/systems/amazake-tab/resources/config.yml")) {
            final Path path = Path.of(candidate);
            if (Files.exists(path)) {
                return yaml(Files.readString(path));
            }
        }
        Assumptions.abort("config.yml of AmazakeTab not found");
        return Map.of();
    }

    @Test
    void shippedConfigParsesWithoutWarnings() throws IOException {
        final TabConfig config = TabConfig.parse(shippedConfig());
        assertEquals(List.of(), config.warnings());
        assertEquals(5, config.updatePeriodTicks());
        assertTrue(config.respectOtherScoreboards());
        assertEquals(List.of("admin", "moderator", "vip", "default"), config.groups().stream().map(GroupDef::name).toList());
        assertEquals(0, config.groups().get(0).priority());
        assertEquals(999, config.groups().get(3).priority());
        assertEquals("red", config.groups().get(0).tagColor());
        assertTrue(config.headerFooter().header().frames().get(0).contains("\n"), "header frames are multi-line");
        assertEquals(2, config.headerFooter().header().frames().size());
        final TabConfig.Sidebar sidebar = config.sidebar("world");
        assertTrue(sidebar.enabled());
        assertTrue(sidebar.lines().size() <= TabConfig.MAX_SIDEBAR_LINES);
        final Frames animatedLine = sidebar.lines().get(sidebar.lines().size() - 1);
        assertEquals(2, animatedLine.frames().size());
        assertEquals(10, animatedLine.intervalTicks());
        assertTrue(config.usesTeams());
        assertTrue(config.needsScoreboard());
        assertEquals("always", config.nametags().collision());
    }

    @Test
    void emptyConfigUsesDefaults() {
        final TabConfig config = TabConfig.parse(Map.of());
        assertEquals(List.of(), config.warnings());
        assertEquals(5, config.updatePeriodTicks());
        assertEquals("<prefix><name><suffix>", config.tabNames().format());
        assertTrue(config.headerFooter().header().isEmpty());
        assertEquals(List.of(), config.groups());
        final TabConfig nullConfig = TabConfig.parse(null);
        assertEquals(config, nullConfig);
    }

    @Test
    void worldOverridesInheritMissingKeys() {
        final TabConfig config = TabConfig.parse(yaml("""
            header-footer:
              interval-ticks: 10
              header: ["A", "B"]
              footer: "F"
              worlds:
                world_nether:
                  header: ["N"]
                world_the_end:
                  enabled: false
            sidebar:
              title: "T"
              lines: ["one", ["x", "y"]]
              worlds:
                world_nether:
                  enabled: false
                arena:
                  lines: ["arena"]
                  hide-numbers: false
            """));
        assertEquals(List.of(), config.warnings());
        assertEquals(List.of("A", "B"), config.headerFooter("world").header().frames());
        assertEquals(List.of("N"), config.headerFooter("world_nether").header().frames());
        assertEquals(List.of("F"), config.headerFooter("world_nether").footer().frames(), "footer inherited");
        assertEquals(10, config.headerFooter("world_nether").header().intervalTicks(), "interval inherited");
        assertFalse(config.headerFooter("world_the_end").enabled());
        assertSame(config.headerFooter(), config.headerFooter(null));

        assertFalse(config.sidebar("world_nether").enabled());
        assertEquals(List.of("T"), config.sidebar("arena").title().frames(), "title inherited");
        assertEquals(1, config.sidebar("arena").lines().size());
        assertFalse(config.sidebar("arena").hideNumbers());
        assertTrue(config.sidebar("world").hideNumbers());
        assertEquals(List.of("x", "y"), config.sidebar("world").lines().get(1).frames());
    }

    @Test
    void multiLineFramesFromListsOfLines() {
        final TabConfig config = TabConfig.parse(yaml("""
            header-footer:
              header:
                - ["line 1", "line 2"]
                - "single"
            """));
        assertEquals(List.of("line 1\nline 2", "single"), config.headerFooter().header().frames());
    }

    @Test
    void tooManySidebarLinesAreCutWithAWarning() {
        final List<String> lines = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            lines.add("line " + i);
        }
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("sidebar", Map.of("lines", lines));
        final TabConfig config = TabConfig.parse(root);
        assertEquals(TabConfig.MAX_SIDEBAR_LINES, config.sidebar().lines().size());
        assertEquals(1, config.warnings().size());
        assertTrue(config.warnings().get(0).contains("15"));
    }

    @Test
    void badValuesFallBackWithWarnings() {
        final TabConfig config = TabConfig.parse(yaml("""
            update-period-ticks: 0
            groups:
              admin:
                color: pink
                priority: abc
              broken: 5
            nametags:
              collision: sometimes
              visibility: HIDE_FOR_OTHER_TEAMS
            """));
        assertEquals(1, config.updatePeriodTicks());
        assertEquals(1, config.groups().size());
        assertNull(config.groups().get(0).tagColor());
        assertEquals(0, config.groups().get(0).priority());
        assertEquals("always", config.nametags().collision());
        assertEquals("hide-for-other-teams", config.nametags().visibility());
        assertEquals(5, config.warnings().size(), config.warnings().toString());
    }

    @Test
    void teamsAndScoreboardNeeds() {
        final TabConfig nothing = TabConfig.parse(yaml("""
            tab-names: {sort: false}
            nametags: {enabled: false}
            sidebar: {enabled: false}
            """));
        assertFalse(nothing.usesTeams());
        assertFalse(nothing.needsScoreboard());
        final TabConfig sidebarInOneWorld = TabConfig.parse(yaml("""
            tab-names: {sort: false}
            nametags: {enabled: false}
            sidebar:
              enabled: false
              worlds: {arena: {enabled: true}}
            """));
        assertTrue(sidebarInOneWorld.needsScoreboard());
        assertFalse(sidebarInOneWorld.usesTeams());
    }

    @Test
    void defaultGroupSortsLastUnlessSet() {
        final TabConfig config = TabConfig.parse(yaml("""
            groups:
              default: {}
              admin: {}
              helper: {priority: 5}
            """));
        assertEquals(999, config.groups().get(0).priority());
        assertEquals(1, config.groups().get(1).priority(), "position in the list");
        assertEquals(5, config.groups().get(2).priority());
    }
}
