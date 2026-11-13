package core.amazake.systems.tab;

import java.util.Locale;

/**
 * One group of the config: who belongs to it (permission {@code amazake.tab.group.<name>}, or everyone for the
 * group named {@code default}) and how its members look. Strings are MiniMessage templates with {@code %placeholders%}.
 * Pure and immutable.
 *
 * @param name       group name as written in the config
 * @param priority   position in the tab list, 0..999, lower is higher in the list
 * @param tabPrefix  prefix in the tab list ({@code <prefix>} in the tab-names format)
 * @param tabSuffix  suffix in the tab list ({@code <suffix>})
 * @param tagPrefix  prefix above the head (team prefix)
 * @param tagSuffix  suffix above the head (team suffix)
 * @param tagColor   name colour above the head (a named colour such as "red"), or null for none
 */
public record GroupDef(String name, int priority, String tabPrefix, String tabSuffix, String tagPrefix, String tagSuffix,
                       String tagColor) {

    public static final String DEFAULT = "default";
    public static final String PERMISSION_PREFIX = "amazake.tab.group.";

    /** Used when no group of the config matches. */
    public static final GroupDef FALLBACK = new GroupDef(DEFAULT, SortKeys.MAX_PRIORITY, "", "", "", "", null);

    public GroupDef {
        name = name == null ? DEFAULT : name;
        priority = SortKeys.clampPriority(priority);
        tabPrefix = tabPrefix == null ? "" : tabPrefix;
        tabSuffix = tabSuffix == null ? "" : tabSuffix;
        tagPrefix = tagPrefix == null ? "" : tagPrefix;
        tagSuffix = tagSuffix == null ? "" : tagSuffix;
        tagColor = tagColor == null || tagColor.isBlank() ? null : tagColor.trim().toLowerCase(Locale.ROOT);
    }

    /** True for the catch-all group that needs no permission. */
    public boolean isDefault() {
        return DEFAULT.equalsIgnoreCase(this.name);
    }

    /** The permission that puts a player into this group; null for the default group. */
    public String permission() {
        return this.isDefault() ? null : PERMISSION_PREFIX + this.name.toLowerCase(Locale.ROOT);
    }
}
