package core.amazake.systems.example;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Пример системы Amazake: приветствие при входе, actionbar с TPS/MSPT/онлайном/пингом и команда /example.
 *
 * <p>Правила потоков, которые здесь соблюдены (подробно — docs/systems.md, раздел «Потоки»):</p>
 * <ul>
 *   <li>настройки — неизменяемый {@link Settings} в {@link AtomicReference}: читаются из любых потоков, при reload
 *       подменяются целиком;</li>
 *   <li>всё, что трогает игрока (сообщения, actionbar, пинг), выполняется в задаче этого игрока
 *       ({@code runForPlayer}/{@code runOnceForPlayer});</li>
 *   <li>общая карта задач — {@link ConcurrentHashMap}.</li>
 * </ul>
 */
public final class ExampleSystem implements AmazakeSystem, Listener {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.DEFAULTS);
    /** Задача actionbar каждого игрока: её отменяют при выходе игрока и при перезагрузке настроек. */
    private final Map<UUID, ScheduledTask> actionbars = new ConcurrentHashMap<>();
    private volatile SystemContext context;

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        this.loadSettings(context);
        context.registerListener(this);
        context.registerCommand(new ExampleCommand(this));
        // Игроки, которые уже на сервере (система включена без рестарта).
        for (final Player player : Bukkit.getOnlinePlayers()) {
            this.startActionbar(player);
        }
        context.logger().info("Пример включён: " + Bukkit.getOnlinePlayers().size() + " игроков онлайн.");
    }

    @Override
    public void disable(final SystemContext context) {
        // Задачи и обработчики ядро снимет само; вернуть игрокам нужно то, что мы им показали.
        this.actionbars.clear();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            context.runOnceForPlayer(player, () -> player.sendActionBar(Component.empty()));
        }
    }

    @Override
    public void reload(final SystemContext context) {
        this.loadSettings(context);
        // Интервал мог поменяться: каждому игроку — новая задача. startActionbar заменяет и отменяет старую задачу
        // игрока сам (put + cancel), поэтому карту не чистим целиком: так не потеряется задача игрока, который
        // зашёл в этот момент в другом потоке.
        final boolean enabled = this.settings.get().actionbarEnabled();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (enabled) {
                this.startActionbar(player);
            } else {
                final ScheduledTask old = this.actionbars.remove(player.getUniqueId());
                if (old != null) {
                    old.cancel();
                }
            }
        }
    }

    private void loadSettings(final SystemContext context) {
        final Settings parsed = Settings.from(context.loadConfig(), warning -> context.logger().warning(warning));
        this.settings.set(parsed);
    }

    Settings settings() {
        return this.settings.get();
    }

    SystemContext context() {
        return this.context;
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final Settings now = this.settings.get();
        if (!now.welcome().isEmpty()) {
            player.sendMessage(MM.deserialize(now.welcome(), Placeholder.unparsed("player", player.getName())));
        }
        this.startActionbar(player);
    }

    @EventHandler
    public void onQuit(final PlayerQuitEvent event) {
        // Задача игрока закончится сама, когда он выйдет; убираем её из карты.
        this.actionbars.remove(event.getPlayer().getUniqueId());
    }

    private void startActionbar(final Player player) {
        final Settings now = this.settings.get();
        if (!now.actionbarEnabled()) {
            return;
        }
        final ScheduledTask task = this.context.runForPlayer(player, ignored -> this.showActionbar(player), now.actionbarIntervalTicks());
        if (task != null) {
            final ScheduledTask old = this.actionbars.put(player.getUniqueId(), task);
            if (old != null) {
                old.cancel();
            }
        }
    }

    /** Выполняется в потоке игрока. */
    private void showActionbar(final Player player) {
        final Settings now = this.settings.get();
        final var stats = this.context.stats();
        player.sendActionBar(MM.deserialize(now.actionbarFormat(),
            Placeholder.unparsed("tps", Settings.oneDecimal(stats.tps())),
            Placeholder.unparsed("mspt", Settings.oneDecimal(stats.mspt())),
            Placeholder.unparsed("online", Integer.toString(stats.onlinePlayers())),
            Placeholder.unparsed("max", Integer.toString(stats.maxPlayers())),
            Placeholder.unparsed("ping", Integer.toString(player.getPing()))));
    }
}
