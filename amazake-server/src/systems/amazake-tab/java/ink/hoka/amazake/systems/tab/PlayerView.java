package ink.hoka.amazake.systems.tab;

import io.papermc.paper.scoreboard.numbers.NumberFormat;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/**
 * One player's tab list, sidebar and private scoreboard. Every method except the {@link #task} field is used only
 * on the thread that owns the player (their repeating task, or the one-off cleanup task), so nothing here needs
 * synchronisation. Everything sent to the client is cached and only re-sent when it changed.
 */
final class PlayerView {

    static final String OBJECTIVE = "aztab";
    private static final String LINE_ENTRY = "#aztab-";

    final UUID id;
    /** The repeating task; replaced by start/reload from other threads. */
    volatile ScheduledTask task;
    int failures;

    private int generation = -1;

    private SharedEntry published;
    private String lastTagPrefix;
    private Component tagPrefix = Component.empty();
    private String lastTagSuffix;
    private Component tagSuffix = Component.empty();

    private String lastHeader;
    private String lastFooter;
    private boolean headerSent;

    private String lastTabKey;
    private boolean tabNameSet;

    private Scoreboard board;
    private final Map<UUID, SharedEntry> applied = new HashMap<>();
    private Objective sidebar;
    private String lastTitle;
    private Boolean lastHideNumbers;
    private int lineCount = -1;
    private final String[] lines = new String[TabConfig.MAX_SIDEBAR_LINES];

    PlayerView(final UUID id) {
        this.id = id;
    }

    void update(final TabRuntime runtime, final Player player) {
        final TabRuntime.Settings settings = runtime.settings();
        final TabConfig config = settings.config();
        if (this.generation != settings.generation()) {
            this.generation = settings.generation();
            this.forgetSentText();
        }
        final long tick = Bukkit.getCurrentTick();
        final String world = player.getWorld().getName();
        final GroupDef group = GroupResolver.resolve(config.groups(), player::hasPermission);
        final PlayerPlaceholders placeholders = new PlayerPlaceholders(player, runtime.context.stats(), group, settings);

        this.publish(runtime, player, config, group, placeholders);
        this.headerFooter(runtime, player, config.headerFooter(world), tick, placeholders);
        this.tabName(runtime, player, config, group, placeholders);
        this.scoreboard(runtime, player, config, world, tick, placeholders);
    }

    // ---------------------------------------------------------------- shared entry (what others see of us)

    private void publish(final TabRuntime runtime, final Player player, final TabConfig config, final GroupDef group,
                         final PlayerPlaceholders placeholders) {
        if (!config.usesTeams()) {
            if (this.published != null) {
                runtime.shared.remove(this.id, this.published);
                this.published = null;
            }
            return;
        }
        final TabConfig.Nametags tags = config.nametags();
        Component prefix = Component.empty();
        Component suffix = Component.empty();
        NamedTextColor color = null;
        if (tags.enabled()) {
            final String p = runtime.expand(player, group.tagPrefix(), placeholders);
            if (!p.equals(this.lastTagPrefix)) {
                this.lastTagPrefix = p;
                this.tagPrefix = TabRuntime.parse(p);
            }
            final String s = runtime.expand(player, group.tagSuffix(), placeholders);
            if (!s.equals(this.lastTagSuffix)) {
                this.lastTagSuffix = s;
                this.tagSuffix = TabRuntime.parse(s);
            }
            prefix = this.tagPrefix;
            suffix = this.tagSuffix;
            color = group.tagColor() == null ? null : NamedTextColor.NAMES.value(group.tagColor());
        }
        final boolean sort = config.tabNames().enabled() && config.tabNames().sort();
        final String team = SortKeys.teamName(sort ? group.priority() : SortKeys.MAX_PRIORITY, player.getName());
        final SharedEntry entry = new SharedEntry(this.id, player.getName(), team, prefix, suffix, color,
            SharedEntry.collision(tags.collision()), SharedEntry.visibility(tags.visibility()));
        if (!entry.equals(this.published)) {
            runtime.shared.put(this.id, entry);
            this.published = entry;
        }
    }

    // ---------------------------------------------------------------- tab list

    private void headerFooter(final TabRuntime runtime, final Player player, final TabConfig.HeaderFooter hf, final long tick,
                              final PlayerPlaceholders placeholders) {
        if (!hf.enabled() || (hf.header().isEmpty() && hf.footer().isEmpty())) {
            if (this.headerSent) {
                player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
                this.headerSent = false;
            }
            this.lastHeader = null;
            this.lastFooter = null;
            return;
        }
        final String header = runtime.expand(player, hf.header().at(tick), placeholders);
        final String footer = runtime.expand(player, hf.footer().at(tick), placeholders);
        if (!header.equals(this.lastHeader) || !footer.equals(this.lastFooter)) {
            player.sendPlayerListHeaderAndFooter(TabRuntime.parse(header), TabRuntime.parse(footer));
            this.lastHeader = header;
            this.lastFooter = footer;
            this.headerSent = true;
        }
    }

    private void tabName(final TabRuntime runtime, final Player player, final TabConfig config, final GroupDef group,
                         final PlayerPlaceholders placeholders) {
        final TabConfig.TabNames names = config.tabNames();
        if (!names.enabled()) {
            if (this.tabNameSet) {
                player.playerListName(null);
                this.tabNameSet = false;
            }
            this.lastTabKey = null;
            return;
        }
        String prefix = "";
        String suffix = "";
        if (names.vault()) {
            prefix = LegacyColors.toMiniMessage(runtime.hooks.vaultPrefix(player), true);
            suffix = LegacyColors.toMiniMessage(runtime.hooks.vaultSuffix(player), true);
        }
        if (prefix.isEmpty()) {
            prefix = runtime.expand(player, group.tabPrefix(), placeholders);
        }
        if (suffix.isEmpty()) {
            suffix = runtime.expand(player, group.tabSuffix(), placeholders);
        }
        final String format = runtime.expand(player, names.format(), placeholders);
        final String key = format + '\u0000' + prefix + '\u0000' + suffix + '\u0000' + player.getName();
        if (!key.equals(this.lastTabKey)) {
            // prefix/suffix are inserted as MiniMessage text, so a colour left open in the prefix carries over to
            // the name (like legacy prefixes); <name> is inserted as plain text
            player.playerListName(TabRuntime.parse(format,
                Placeholder.parsed("prefix", prefix), Placeholder.parsed("suffix", suffix),
                Placeholder.unparsed("name", player.getName())));
            this.lastTabKey = key;
            this.tabNameSet = true;
        }
    }

    // ---------------------------------------------------------------- scoreboard: teams and sidebar

    private void scoreboard(final TabRuntime runtime, final Player player, final TabConfig config, final String world,
                            final long tick, final PlayerPlaceholders placeholders) {
        final Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        if (!config.needsScoreboard()) {
            if (this.board != null) {
                this.releaseBoard(player, main);
            }
            return;
        }
        final Scoreboard current = player.getScoreboard();
        if (this.board != null && current != this.board && current != main && config.respectOtherScoreboards()) {
            // another plugin took the player over: let it, forget our board (we come back when it gives them main)
            this.dropBoard();
            return;
        }
        if (this.board == null) {
            if (current != main && config.respectOtherScoreboards()) {
                return;
            }
            this.board = Bukkit.getScoreboardManager().getNewScoreboard();
        }
        if (current != this.board) {
            player.setScoreboard(this.board);
        }
        this.syncTeams(runtime, config.usesTeams());
        this.sidebar(runtime, player, config.sidebar(world), tick, placeholders);
    }

    private void syncTeams(final TabRuntime runtime, final boolean enabled) {
        final Map<UUID, SharedEntry> shared = runtime.shared;
        // removals first, so a team name that is reused (same name, new entry) is not removed after being re-created
        for (final Iterator<Map.Entry<UUID, SharedEntry>> it = this.applied.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, SharedEntry> e = it.next();
            final SharedEntry now = enabled ? shared.get(e.getKey()) : null;
            if (now == null || !now.team().equals(e.getValue().team())) {
                this.unregisterTeam(e.getValue().team());
                it.remove();
            }
        }
        if (!enabled) {
            return;
        }
        for (final SharedEntry entry : shared.values()) {
            SharedEntry old = this.applied.get(entry.id());
            if (entry.equals(old)) {
                continue;
            }
            Team team = this.board.getTeam(entry.team());
            if (team == null) {
                team = this.board.registerNewTeam(entry.team());
                old = null;
            }
            // every team setter sends a team packet even when the value is unchanged: call only what changed
            if (old == null || !entry.prefix().equals(old.prefix())) {
                team.prefix(entry.prefix());
            }
            if (old == null || !entry.suffix().equals(old.suffix())) {
                team.suffix(entry.suffix());
            }
            if (old == null || !java.util.Objects.equals(entry.color(), old.color())) {
                team.color(entry.color());
            }
            if (old == null || entry.collision() != old.collision()) {
                team.setOption(Team.Option.COLLISION_RULE, entry.collision());
            }
            if (old == null || entry.visibility() != old.visibility()) {
                team.setOption(Team.Option.NAME_TAG_VISIBILITY, entry.visibility());
            }
            if (!team.hasEntry(entry.name())) {
                team.addEntry(entry.name());
            }
            this.applied.put(entry.id(), entry);
        }
    }

    private void unregisterTeam(final String name) {
        final Team team = this.board.getTeam(name);
        if (team != null) {
            team.unregister();
        }
    }

    private void sidebar(final TabRuntime runtime, final Player player, final TabConfig.Sidebar sb, final long tick,
                         final PlayerPlaceholders placeholders) {
        if (!sb.enabled() || (sb.lines().isEmpty() && sb.title().isEmpty())) {
            this.removeSidebar();
            return;
        }
        if (this.sidebar == null) {
            Objective objective = this.board.getObjective(OBJECTIVE);
            if (objective == null) {
                objective = this.board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, Component.empty());
            }
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            this.sidebar = objective;
            this.forgetSidebarText();
        }
        if (this.lastHideNumbers == null || this.lastHideNumbers != sb.hideNumbers()) {
            this.sidebar.numberFormat(sb.hideNumbers() ? NumberFormat.blank() : null);
            this.lastHideNumbers = sb.hideNumbers();
        }
        final String title = runtime.expand(player, sb.title().at(tick), placeholders);
        if (!title.equals(this.lastTitle)) {
            this.sidebar.displayName(TabRuntime.parse(title));
            this.lastTitle = title;
        }
        final int count = sb.lines().size();
        if (count != this.lineCount) {
            for (int i = count; i < Math.max(0, this.lineCount); i++) {
                this.board.resetScores(lineEntry(i));
            }
            for (int i = 0; i < count; i++) {
                this.sidebar.getScore(lineEntry(i)).setScore(count - i);
                this.lines[i] = null;
            }
            this.lineCount = count;
        }
        for (int i = 0; i < count; i++) {
            final String text = runtime.expand(player, sb.lines().get(i).at(tick), placeholders);
            if (!text.equals(this.lines[i])) {
                this.sidebar.getScore(lineEntry(i)).customName(TabRuntime.parse(text));
                this.lines[i] = text;
            }
        }
    }

    static String lineEntry(final int index) {
        return LINE_ENTRY + (index < 10 ? "0" : "") + index;
    }

    private void removeSidebar() {
        if (this.sidebar != null) {
            try {
                this.sidebar.unregister();
            } catch (final IllegalStateException ignored) {
                // already unregistered
            }
            this.sidebar = null;
        }
        this.forgetSidebarText();
    }

    // ---------------------------------------------------------------- state resets

    /** After a reload: re-render and re-send everything on the next run. */
    private void forgetSentText() {
        this.published = null;
        this.lastTagPrefix = null;
        this.lastTagSuffix = null;
        this.lastHeader = null;
        this.lastFooter = null;
        this.lastTabKey = null;
        this.forgetSidebarText();
    }

    private void forgetSidebarText() {
        this.lastTitle = null;
        this.lastHideNumbers = null;
        this.lineCount = -1;
        java.util.Arrays.fill(this.lines, null);
    }

    /** Our board is no longer shown (someone else replaced it): forget it without touching anything. */
    private void dropBoard() {
        this.board = null;
        this.sidebar = null;
        this.applied.clear();
        this.forgetSidebarText();
    }

    /** Give the main scoreboard back and empty ours. */
    private void releaseBoard(final Player player, final Scoreboard main) {
        if (player.getScoreboard() == this.board) {
            player.setScoreboard(main);
        }
        this.removeSidebar();
        for (final SharedEntry entry : this.applied.values()) {
            this.unregisterTeam(entry.team());
        }
        this.dropBoard();
    }

    /** Disable: leave the player as if AmazakeTab had never run. Owner thread. */
    void cleanup(final Player player) {
        if (this.board != null) {
            this.releaseBoard(player, Bukkit.getScoreboardManager().getMainScoreboard());
        }
        if (this.headerSent) {
            player.sendPlayerListHeaderAndFooter(Component.empty(), Component.empty());
            this.headerSent = false;
        }
        if (this.tabNameSet) {
            player.playerListName(null);
            this.tabNameSet = false;
        }
    }
}
