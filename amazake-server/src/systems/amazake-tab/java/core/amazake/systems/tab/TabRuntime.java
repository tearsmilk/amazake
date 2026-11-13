package core.amazake.systems.tab;

import core.amazake.systems.api.SystemContext;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * The running state of AmazakeTab.
 *
 * <p><b>Threads.</b> Each online player has a {@link PlayerView} and a repeating task on the thread that owns that
 * player ({@link SystemContext#runForPlayer}). Only that task touches the player's view, scoreboard and tab list.
 * What other players need from a player (team name/sort key, name tag) is published by the player's own task as an
 * immutable {@link SharedEntry} in {@link #shared}; every task copies everyone's entries into its own scoreboard.
 * The config is an immutable {@link Settings} snapshot behind a volatile field, swapped by reload on the global
 * thread. Join/quit only add and remove map entries and start/cancel tasks, which is thread-safe.</p>
 */
final class TabRuntime {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Immutable config snapshot plus what is derived from it. {@code generation} changes on every reload. */
    record Settings(TabConfig config, ZoneId zone, int generation) {
    }

    final SystemContext context;
    final Logger logger;
    final Hooks hooks;
    final ConcurrentHashMap<UUID, SharedEntry> shared = new ConcurrentHashMap<>();
    final ConcurrentHashMap<UUID, PlayerView> views = new ConcurrentHashMap<>();
    private volatile Settings settings;
    private volatile boolean closed;

    TabRuntime(final SystemContext context, final TabConfig config) {
        this.context = context;
        this.logger = context.logger();
        this.hooks = new Hooks(this.logger);
        this.settings = this.settingsFor(config, 1);
        this.hooks.refresh(config.placeholderApi(), config.tabNames().vault());
    }

    Settings settings() {
        return this.settings;
    }

    boolean closed() {
        return this.closed;
    }

    /** Publishes a new config (global thread). Players' tasks pick it up on their next run. */
    void apply(final TabConfig config) {
        final Settings old = this.settings;
        this.settings = this.settingsFor(config, old.generation() + 1);
        this.hooks.refresh(config.placeholderApi(), config.tabNames().vault());
        if (old.config().updatePeriodTicks() != config.updatePeriodTicks()) {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                this.start(player);
            }
        }
    }

    /** Re-resolves PlaceholderAPI/Vault (a service was registered or a plugin enabled). */
    void refreshHooks() {
        final TabConfig config = this.settings.config();
        this.hooks.refresh(config.placeholderApi(), config.tabNames().vault());
    }

    /** Starts (or restarts) the player's task. Any thread (join handler, reload on the global thread). */
    void start(final Player player) {
        if (this.closed || !player.isOnline()) {
            return;
        }
        final PlayerView view = this.views.computeIfAbsent(player.getUniqueId(), PlayerView::new);
        // short lock on the view only to swap its task: a join and a reload must not leave two tasks running
        synchronized (view) {
            final ScheduledTask old = view.task;
            if (old != null) {
                old.cancel();
            }
            final int period = this.settings.config().updatePeriodTicks();
            view.task = this.context.runForPlayer(player, t -> this.run(player, view, t), period);
        }
        if (view.task == null || !player.isOnline()) {
            // the player left meanwhile
            this.views.remove(player.getUniqueId(), view);
            final ScheduledTask task = view.task;
            if (task != null) {
                task.cancel();
            }
        }
    }

    /** The player left: forget them. Their task ends with them; other viewers drop their team on the next run. */
    void stop(final Player player) {
        final UUID id = player.getUniqueId();
        this.shared.remove(id);
        final PlayerView view = this.views.remove(id);
        if (view != null && view.task != null) {
            view.task.cancel();
        }
    }

    private void run(final Player player, final PlayerView view, final ScheduledTask task) {
        if (this.closed || this.views.get(player.getUniqueId()) != view) {
            if (task != null) {
                task.cancel();
            }
            return;
        }
        if (!player.isOnline()) {
            return;
        }
        try {
            view.update(this, player);
            view.failures = 0;
        } catch (final Throwable t) {
            if (view.failures++ == 0) {
                this.logger.log(Level.WARNING, "Ошибка обновления таба игрока " + player.getName()
                    + " (повторы этой ошибки не логируются, пока обновление снова не пройдёт)", t);
            }
        }
    }

    /**
     * Disables: stops all tasks and gives every player their tab list and the main scoreboard back. Called on the
     * global thread. A player owned by this thread is cleaned right here; others through their own thread.
     */
    void close() {
        this.closed = true;
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final PlayerView view = this.views.remove(player.getUniqueId());
            if (view == null) {
                continue;
            }
            if (view.task != null) {
                view.task.cancel();
            }
            final Runnable cleanup = new Cleanup(player, view, this.logger);
            boolean done = false;
            if (Bukkit.isStopping() || Bukkit.isOwnedByCurrentRegion(player)) {
                cleanup.run();
                done = true;
            }
            if (!done && !this.context.runOnceForPlayer(player, cleanup)) {
                // could not schedule (player gone or scheduler unavailable): best effort right here
                cleanup.run();
            }
        }
        this.views.clear();
        this.shared.clear();
    }

    /** A named class (not a lambda) so it is loaded while the system's class loader is still open. */
    private record Cleanup(Player player, PlayerView view, Logger logger) implements Runnable {
        @Override
        public void run() {
            try {
                this.view.cleanup(this.player);
            } catch (final Throwable t) {
                this.logger.log(Level.WARNING, "Не удалось вернуть таб игрока " + this.player.getName() + " в исходное состояние", t);
            }
        }
    }

    // ---------------------------------------------------------------- text

    /**
     * PlaceholderAPI on the raw template first (its legacy colours turned into MiniMessage), then the built-in
     * placeholders. This order keeps player-controlled built-in values (%displayname%, %world% ...) away from PAPI,
     * so they cannot pull in other PAPI placeholders. Built-in names have no '_', so PAPI leaves them alone.
     */
    String expand(final Player player, final String template, final Function<String, String> placeholders) {
        String text = template;
        if (this.settings.config().placeholderApi() && this.hooks.hasPapi()) {
            text = LegacyColors.toMiniMessage(this.hooks.papi(player, text), false);
        }
        return Placeholders.apply(text, placeholders);
    }

    static Component parse(final String miniMessage, final TagResolver... resolvers) {
        if (miniMessage.isEmpty()) {
            return Component.empty();
        }
        return MINI.deserialize(miniMessage, resolvers);
    }

    private Settings settingsFor(final TabConfig config, final int generation) {
        ZoneId zone = ZoneId.systemDefault();
        if (!config.timeZone().isBlank()) {
            try {
                zone = ZoneId.of(config.timeZone().trim());
            } catch (final DateTimeException e) {
                this.logger.warning("time-zone: неизвестная зона '" + config.timeZone() + "', используется зона сервера " + zone);
            }
        }
        return new Settings(config, zone, generation);
    }
}
