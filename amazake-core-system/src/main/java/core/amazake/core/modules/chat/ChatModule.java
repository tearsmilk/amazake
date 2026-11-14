package core.amazake.core.modules.chat;

import core.amazake.core.framework.GroupService;
import core.amazake.core.framework.Module;
import core.amazake.core.framework.ModuleContext;
import core.amazake.core.util.Text;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.Bukkit;

/**
 * Chat module: formats messages with group prefixes/suffixes, supports global and local channels, and throttles
 * spam with a per-player cooldown.
 *
 * <p>Formatting is applied through {@link AsyncChatEvent#renderer} so every viewer sees the same composed message.
 * Group prefix/suffix/name come from the {@link GroupService} published by the groups module when it is enabled;
 * otherwise they are empty and only {@code <player>}/{@code <message>} are filled. The player's own text is always
 * inserted as a {@link Component} (never deserialized as MiniMessage), so it cannot inject tags.</p>
 */
public final class ChatModule implements Module, Listener {

    /** Permission that lets a player skip the chat cooldown. */
    public static final String BYPASS_COOLDOWN = "amazakecore.chat.cooldown.bypass";

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private final AtomicReference<ChatSettings> settings = new AtomicReference<>();
    private final Cooldown cooldown = new Cooldown(System::currentTimeMillis);
    private final PositionTracker positions = new PositionTracker();

    private ModuleContext context;

    @Override
    public String id() {
        return "chat";
    }

    @Override
    public String description() {
        return "чат: глобал/локал, оформление, задержки, префиксы групп";
    }

    @Override
    public void onEnable(final ModuleContext context) throws Exception {
        this.context = context;
        this.settings.set(ChatSettings.from(context.config()));
        context.registerListener(this);
        for (final Player online : Bukkit.getOnlinePlayers()) {
            this.startTracking(online);
        }
    }

    @Override
    public void onConfigReload(final ConfigurationSection section) {
        this.settings.set(ChatSettings.from(section));
    }

    @Override
    public void onDisable() {
        this.cooldown.clearAll();
        this.positions.clear();
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        this.startTracking(event.getPlayer());
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        this.positions.remove(event.getPlayer().getUniqueId());
    }

    /** Starts a per-player task that refreshes the player's position snapshot on the player's own thread. */
    private void startTracking(final Player player) {
        this.context.runOnceForPlayer(player, () -> this.positions.update(player));
        this.context.system().runForPlayer(player, task -> this.positions.update(player), 10L);
    }

    // The event is async (its own thread). Per-player side effects go back through the player's thread.
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(final AsyncChatEvent event) {
        final ChatSettings current = this.settings.get();
        if (current == null) {
            return;
        }
        final Player sender = event.getPlayer();
        final UUID uuid = sender.getUniqueId();

        // 1) Cooldown: cancel and warn (on the player's thread) if throttled.
        if (current.cooldownMillis() > 0L && !sender.hasPermission(BYPASS_COOLDOWN)) {
            final long remaining = this.cooldown.tryConsume(uuid, current.cooldownMillis());
            if (remaining > 0L) {
                event.setCancelled(true);
                final long seconds = Math.max(1L, (remaining + 999L) / 1000L);
                this.context.runOnceForPlayer(sender, () ->
                    sender.sendMessage(Text.parse(current.cooldownMessage(), Text.var("seconds", Long.toString(seconds)))));
                return;
            }
        }

        // 2) Decide channel from the plain text, stripping any forcing prefix char.
        final String raw = PLAIN.serialize(event.message());
        final ChatRouting.Decision decision =
            ChatRouting.decide(raw, current.mode(), current.globalPrefix(), current.localPrefix());

        // The message component: the original (keeps any formatting) unless we stripped a prefix char.
        final Component messageComponent =
            decision.prefixStripped() ? Component.text(decision.message()) : event.message();

        // 3) Resolve group prefix/suffix/name (non-blocking; empty when the groups module is off).
        final Optional<GroupService> groups = this.context.services().get(GroupService.class);
        final String prefix = groups.map(g -> g.prefix(uuid)).orElse("");
        final String suffix = groups.map(g -> g.suffix(uuid)).orElse("");
        final String group = groups.map(g -> g.primaryGroup(uuid)).orElse("");
        final String world = worldName(sender);

        final String format = decision.channel() == ChatRouting.Channel.LOCAL
            ? current.formatLocal() : current.formatGlobal();

        // prefix/suffix are trusted MiniMessage from config -> parse them; player text & name stay literal.
        final Component rendered = Text.parse(format,
            Text.var("prefix", Text.parse(prefix)),
            Text.var("suffix", Text.parse(suffix)),
            Text.var("group", group),
            Text.var("player", sender.getName()),
            Text.var("world", world),
            Text.var("message", messageComponent));

        event.renderer((source, sourceDisplayName, message, viewer) -> rendered);

        // 4) Local channel: keep only the sender, the console and players within radius in the same world.
        if (decision.channel() == ChatRouting.Channel.LOCAL) {
            restrictToLocal(event, sender, current.radiusSquared());
        }
    }

    /** The sender's world from the position snapshot (never touches a live entity off its thread). */
    private String worldName(final Player sender) {
        final PositionTracker.Pos pos = this.positions.get(sender.getUniqueId());
        return pos == null ? "" : pos.world();
    }

    /**
     * Keeps only the sender, the console and players whose last position snapshot is within radius in the same world.
     * Uses {@link PositionTracker} snapshots so the async handler never reads a live entity's position off its thread.
     * If the sender has no snapshot yet (chatted within the first snapshot tick of joining), local is not restricted.
     */
    private void restrictToLocal(final AsyncChatEvent event, final Player sender, final double radiusSquared) {
        final PositionTracker.Pos origin = this.positions.get(sender.getUniqueId());
        if (origin == null) {
            return; // no snapshot yet: do not silently drop everyone
        }
        event.viewers().removeIf(audience -> {
            if (!(audience instanceof Player player)) {
                return false; // keep console and any non-player audiences (e.g. for logging)
            }
            final UUID id = player.getUniqueId();
            if (id.equals(sender.getUniqueId())) {
                return false; // always keep the sender
            }
            final PositionTracker.Pos pos = this.positions.get(id);
            if (pos == null || !pos.world().equals(origin.world())) {
                return true;
            }
            return pos.distanceSquared(origin) > radiusSquared;
        });
    }

    /** Exposed for the manager/tests to observe the live snapshot. */
    public ChatSettings settings() {
        return this.settings.get();
    }
}
