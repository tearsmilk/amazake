package ink.hoka.amazake.systems.tab;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The parsed {@code config.yml} of AmazakeTab. Pure and immutable: built from plain nested maps and lists (what a
 * YAML parser returns), so it is tested without a server and shared between threads as is. A reload builds a new
 * instance and publishes it through a volatile field.
 *
 * <p>Problems in the file never fail the parse: bad values fall back to defaults and are reported in
 * {@link #warnings()} (in Russian, they go to the server log).</p>
 */
public record TabConfig(
    int updatePeriodTicks,
    boolean respectOtherScoreboards,
    boolean placeholderApi,
    String timeZone,
    HeaderFooter headerFooter,
    Map<String, HeaderFooter> headerFooterWorlds,
    List<GroupDef> groups,
    TabNames tabNames,
    Sidebar sidebar,
    Map<String, Sidebar> sidebarWorlds,
    Nametags nametags,
    List<String> warnings
) {

    /** Sidebar lines the client shows at most. */
    public static final int MAX_SIDEBAR_LINES = 15;

    public static final Set<String> COLLISION_VALUES = Set.of("always", "never", "push-other-teams", "push-own-team");
    public static final Set<String> VISIBILITY_VALUES = Set.of("always", "never", "hide-for-other-teams", "hide-for-own-team");
    public static final Set<String> COLOR_VALUES = Set.of(
        "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray",
        "blue", "green", "aqua", "red", "light_purple", "yellow", "white");

    public record HeaderFooter(boolean enabled, Frames header, Frames footer) {
        public static final HeaderFooter DISABLED = new HeaderFooter(false, Frames.EMPTY, Frames.EMPTY);
    }

    public record TabNames(boolean enabled, String format, boolean vault, boolean sort) {
    }

    public record Sidebar(boolean enabled, Frames title, List<Frames> lines, boolean hideNumbers) {
        public static final Sidebar DISABLED = new Sidebar(false, Frames.EMPTY, List.of(), true);

        public Sidebar {
            lines = List.copyOf(lines);
        }
    }

    public record Nametags(boolean enabled, String collision, String visibility) {
    }

    public TabConfig {
        headerFooterWorlds = Map.copyOf(headerFooterWorlds);
        groups = List.copyOf(groups);
        sidebarWorlds = Map.copyOf(sidebarWorlds);
        warnings = List.copyOf(warnings);
    }

    /** Header/footer for a world: its override if there is one, the defaults otherwise. */
    public HeaderFooter headerFooter(final String world) {
        final HeaderFooter override = world == null ? null : this.headerFooterWorlds.get(world);
        return override != null ? override : this.headerFooter;
    }

    /** Sidebar for a world: its override if there is one, the defaults otherwise. */
    public Sidebar sidebar(final String world) {
        final Sidebar override = world == null ? null : this.sidebarWorlds.get(world);
        return override != null ? override : this.sidebar;
    }

    /** True if any feature needs the player's own scoreboard (teams or the sidebar). */
    public boolean needsScoreboard() {
        if (this.nametags.enabled() || (this.tabNames.enabled() && this.tabNames.sort())) {
            return true;
        }
        if (this.sidebar.enabled()) {
            return true;
        }
        for (final Sidebar s : this.sidebarWorlds.values()) {
            if (s.enabled()) {
                return true;
            }
        }
        return false;
    }

    /** True if players get one team each (for sorting and/or name tags). */
    public boolean usesTeams() {
        return this.nametags.enabled() || (this.tabNames.enabled() && this.tabNames.sort());
    }

    // ---------------------------------------------------------------- parsing

    public static TabConfig parse(final Map<String, ?> root) {
        final List<String> warnings = new ArrayList<>();
        final Map<String, ?> r = root == null ? Map.of() : root;

        final int period = clampInt(r, "update-period-ticks", 5, 1, 1200, warnings);
        final boolean respect = bool(r, "respect-other-scoreboards", true);
        final boolean papi = bool(r, "placeholderapi", true);
        final String timeZone = string(r, "time-zone", "");

        final Map<String, ?> hfSection = section(r, "header-footer");
        final HeaderFooter hf = parseHeaderFooter(hfSection, warnings);
        final Map<String, HeaderFooter> hfWorlds = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, ?>> world : worlds(hfSection).entrySet()) {
            hfWorlds.put(world.getKey(), parseHeaderFooter(overlay(hfSection, world.getValue()), warnings));
        }

        final List<GroupDef> groups = parseGroups(section(r, "groups"), warnings);

        final Map<String, ?> tn = section(r, "tab-names");
        final TabNames tabNames = new TabNames(
            bool(tn, "enabled", true), string(tn, "format", "<prefix><name><suffix>"), bool(tn, "vault", true),
            bool(tn, "sort", true));

        final Map<String, ?> sbSection = section(r, "sidebar");
        final Sidebar sidebar = parseSidebar(sbSection, "sidebar", warnings);
        final Map<String, Sidebar> sbWorlds = new LinkedHashMap<>();
        for (final Map.Entry<String, Map<String, ?>> world : worlds(sbSection).entrySet()) {
            sbWorlds.put(world.getKey(), parseSidebar(overlay(sbSection, world.getValue()), "sidebar.worlds." + world.getKey(), warnings));
        }

        final Map<String, ?> nt = section(r, "nametags");
        final Nametags nametags = new Nametags(
            bool(nt, "enabled", true),
            oneOf(nt, "collision", "always", COLLISION_VALUES, "nametags.collision", warnings),
            oneOf(nt, "visibility", "always", VISIBILITY_VALUES, "nametags.visibility", warnings));

        return new TabConfig(period, respect, papi, timeZone, hf, hfWorlds, groups, tabNames, sidebar, sbWorlds, nametags, warnings);
    }

    private static HeaderFooter parseHeaderFooter(final Map<String, ?> s, final List<String> warnings) {
        final int interval = clampInt(s, "interval-ticks", 20, 1, 72000, warnings);
        return new HeaderFooter(bool(s, "enabled", true),
            new Frames(multilineFrames(s.get("header")), interval),
            new Frames(multilineFrames(s.get("footer")), interval));
    }

    private static Sidebar parseSidebar(final Map<String, ?> s, final String path, final List<String> warnings) {
        final int interval = clampInt(s, "interval-ticks", 20, 1, 72000, warnings);
        final Frames title = new Frames(simpleFrames(s.get("title")), interval);
        final List<Frames> lines = new ArrayList<>();
        final Object raw = s.get("lines");
        if (raw instanceof List<?> list) {
            for (final Object line : list) {
                lines.add(lineFrames(line, interval, warnings));
            }
        } else if (raw != null) {
            warnings.add(path + ".lines: ожидался список строк, секция пропущена");
        }
        if (lines.size() > MAX_SIDEBAR_LINES) {
            warnings.add(path + ".lines: строк " + lines.size() + ", клиент показывает не больше "
                + MAX_SIDEBAR_LINES + "; лишние отброшены");
            lines.subList(MAX_SIDEBAR_LINES, lines.size()).clear();
        }
        return new Sidebar(bool(s, "enabled", true), title, lines, bool(s, "hide-numbers", true));
    }

    /** A sidebar line: a string (static), a list (frames with the sidebar interval) or {frames, interval-ticks}. */
    private static Frames lineFrames(final Object line, final int interval, final List<String> warnings) {
        if (line instanceof Map<?, ?> map) {
            final Map<String, ?> m = stringKeys(map);
            return new Frames(simpleFrames(m.get("frames")), clampInt(m, "interval-ticks", interval, 1, 72000, warnings));
        }
        if (line instanceof List<?> list) {
            return new Frames(simpleFrames(list), interval);
        }
        return new Frames(List.of(line == null ? "" : String.valueOf(line)), interval);
    }

    private static List<GroupDef> parseGroups(final Map<String, ?> s, final List<String> warnings) {
        final List<GroupDef> groups = new ArrayList<>();
        int index = 0;
        for (final Map.Entry<String, ?> e : s.entrySet()) {
            final String name = e.getKey();
            if (!(e.getValue() instanceof Map<?, ?> raw)) {
                warnings.add("groups." + name + ": ожидалась секция, группа пропущена");
                continue;
            }
            final Map<String, ?> g = stringKeys(raw);
            String color = string(g, "color", "");
            if (!color.isBlank() && !COLOR_VALUES.contains(color.trim().toLowerCase(Locale.ROOT))) {
                warnings.add("groups." + name + ".color: неизвестный цвет '" + color + "', допустимо: " + sorted(COLOR_VALUES));
                color = "";
            }
            final int defaultPriority = GroupDef.DEFAULT.equalsIgnoreCase(name) ? SortKeys.MAX_PRIORITY : index;
            groups.add(new GroupDef(name, clampInt(g, "priority", defaultPriority, 0, SortKeys.MAX_PRIORITY, warnings),
                string(g, "tab-prefix", ""), string(g, "tab-suffix", ""),
                string(g, "tag-prefix", ""), string(g, "tag-suffix", ""), color));
            index++;
        }
        return groups;
    }

    // ---------------------------------------------------------------- value helpers

    /** Frames whose items may be multi-line: a string, or a list of lines joined with '\n'. */
    static List<String> multilineFrames(final Object raw) {
        final List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (final Object item : list) {
                if (item instanceof List<?> lines) {
                    final List<String> parts = new ArrayList<>(lines.size());
                    for (final Object l : lines) {
                        parts.add(l == null ? "" : String.valueOf(l));
                    }
                    out.add(String.join("\n", parts));
                } else if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else if (raw != null) {
            out.add(String.valueOf(raw));
        }
        return out;
    }

    /** Frames of one-line items: a string or a list of strings. */
    static List<String> simpleFrames(final Object raw) {
        final List<String> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (final Object item : list) {
                out.add(item == null ? "" : String.valueOf(item));
            }
        } else if (raw != null) {
            out.add(String.valueOf(raw));
        }
        return out;
    }

    /** A world override: the parent section's values with the world's values on top ("worlds" itself dropped). */
    static Map<String, ?> overlay(final Map<String, ?> parent, final Map<String, ?> world) {
        final Map<String, Object> merged = new LinkedHashMap<>(parent);
        merged.remove("worlds");
        merged.putAll(world);
        return merged;
    }

    private static Map<String, Map<String, ?>> worlds(final Map<String, ?> section) {
        final Map<String, Map<String, ?>> out = new LinkedHashMap<>();
        if (section.get("worlds") instanceof Map<?, ?> worlds) {
            for (final Map.Entry<?, ?> e : worlds.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> m) {
                    out.put(String.valueOf(e.getKey()), stringKeys(m));
                } else if (e.getValue() == null) {
                    out.put(String.valueOf(e.getKey()), Map.of());
                }
            }
        }
        return out;
    }

    static Map<String, ?> section(final Map<String, ?> map, final String key) {
        return map.get(key) instanceof Map<?, ?> m ? stringKeys(m) : Map.of();
    }

    private static Map<String, ?> stringKeys(final Map<?, ?> map) {
        final Map<String, Object> out = new LinkedHashMap<>();
        for (final Map.Entry<?, ?> e : map.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    static boolean bool(final Map<String, ?> map, final String key, final boolean def) {
        final Object v = map.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("yes") || s.equalsIgnoreCase("on")) {
                return true;
            }
            if (s.equalsIgnoreCase("false") || s.equalsIgnoreCase("no") || s.equalsIgnoreCase("off")) {
                return false;
            }
        }
        return def;
    }

    static String string(final Map<String, ?> map, final String key, final String def) {
        final Object v = map.get(key);
        return v == null ? def : String.valueOf(v);
    }

    static int clampInt(final Map<String, ?> map, final String key, final int def, final int min, final int max,
                        final List<String> warnings) {
        final Object v = map.get(key);
        if (v == null) {
            return def;
        }
        final long value;
        if (v instanceof Number n) {
            value = n.longValue();
        } else {
            try {
                value = Long.parseLong(String.valueOf(v).trim());
            } catch (final NumberFormatException e) {
                warnings.add(key + ": '" + v + "' не число, используется " + def);
                return def;
            }
        }
        if (value < min || value > max) {
            final int clamped = (int) Math.max(min, Math.min(max, value));
            warnings.add(key + ": " + value + " вне диапазона " + min + ".." + max + ", используется " + clamped);
            return clamped;
        }
        return (int) value;
    }

    private static String oneOf(final Map<String, ?> map, final String key, final String def, final Set<String> allowed,
                                final String path, final List<String> warnings) {
        final String v = string(map, key, def).trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (allowed.contains(v)) {
            return v;
        }
        warnings.add(path + ": неизвестное значение '" + map.get(key) + "', допустимо: " + sorted(allowed)
            + "; используется " + def);
        return def;
    }

    private static String sorted(final Set<String> values) {
        final List<String> list = new ArrayList<>(values);
        Collections.sort(list);
        return String.join(", ", list);
    }
}
