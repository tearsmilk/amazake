package ink.hoka.amazake.systems;

import ink.hoka.amazake.systems.api.ServerStats;
import ink.hoka.amazake.systems.api.SystemContext;
import ink.hoka.amazake.systems.api.SystemInfo;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * The {@link SystemContext} of one enable of one system: tracks what the system registers and removes it all in
 * {@link #cleanUp}. Thread-safe.
 */
final class SystemContextImpl implements SystemContext {

    /** Failures of a system's repeating task are logged at most this often (with the stack the first time). */
    private static final long FAILURE_LOG_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    private final SystemInfo info;
    private final SystemPlugin plugin;
    private final Path dataFolder;
    private final Set<Command> commands = ConcurrentHashMap.newKeySet();
    /** Repeating per-player tasks (runForPlayer), cancelled on disable; removed once they end. */
    private final Set<ScheduledTask> playerTasks = ConcurrentHashMap.newKeySet();
    /** One-shot per-player tasks scheduled but not yet run (they may run while the system is being disabled). */
    private final AtomicInteger pendingOnce = new AtomicInteger();
    private final AtomicLong nextFailureLog = new AtomicLong(Long.MIN_VALUE);
    private final AtomicInteger suppressedFailures = new AtomicInteger();
    /** Set when disable starts: from then on, per-player one-shots run at once when this thread owns the player. */
    private volatile boolean closing;
    /** Set when everything is removed: nothing can be registered any more. */
    private volatile boolean closed;

    SystemContextImpl(final SystemInfo info, final SystemPlugin plugin, final Path dataFolder) {
        this.info = info;
        this.plugin = plugin;
        this.dataFolder = dataFolder;
    }

    @Override
    public SystemInfo info() {
        return this.info;
    }

    @Override
    public Logger logger() {
        return this.plugin.getLogger();
    }

    @Override
    public Path dataFolder() {
        try {
            Files.createDirectories(this.dataFolder);
        } catch (final IOException e) {
            this.logger().log(Level.WARNING, "Не удалось создать папку " + this.dataFolder, e);
        }
        return this.dataFolder;
    }

    @Override
    public YamlConfiguration loadConfig() {
        this.saveDefaultResource("config.yml");
        final YamlConfiguration config = new YamlConfiguration();
        final Path file = this.dataFolder.resolve("config.yml");
        if (Files.isRegularFile(file)) {
            try {
                config.load(file.toFile());
            } catch (final Exception e) {
                throw new IllegalStateException("Ошибка в " + file + ": " + e.getMessage(), e);
            }
        }
        return config;
    }

    @Override
    public void saveDefaultResource(final String path) {
        final Path target = this.dataFolder().resolve(path).normalize();
        if (!target.startsWith(this.dataFolder) || Files.exists(target)) {
            return;
        }
        try (InputStream in = this.plugin.classLoader().ownResource(path)) {
            if (in == null) {
                return;
            }
            Files.createDirectories(target.getParent());
            BuiltinSystems.write(target, in);
        } catch (final IOException e) {
            this.logger().log(Level.WARNING, "Не удалось записать " + target, e);
        }
    }

    @Override
    public Plugin plugin() {
        return this.plugin;
    }

    private void checkOpen() {
        if (this.closed || !this.plugin.isEnabled()) {
            throw new IllegalStateException("Система " + this.info.name() + " выключена");
        }
    }

    @Override
    public void registerListener(final Listener listener) {
        this.checkOpen();
        Bukkit.getPluginManager().registerEvents(listener, this.plugin);
    }

    @Override
    public void registerCommand(final Command command) {
        this.checkOpen();
        Bukkit.getCommandMap().register(command.getLabel(), this.info.name().toLowerCase(Locale.ROOT), command);
        this.commands.add(command);
    }

    boolean hasCommands() {
        return !this.commands.isEmpty();
    }

    @Override
    public ScheduledTask runGlobalTimer(final Consumer<ScheduledTask> task, final Duration period) {
        this.checkOpen();
        final long ticks = Math.max(1L, period.toMillis() / 50L);
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(this.plugin, this.guarded(task), ticks, ticks);
    }

    @Override
    public ScheduledTask runAsyncTimer(final Consumer<ScheduledTask> task, final Duration period) {
        this.checkOpen();
        final long millis = Math.max(1L, period.toMillis());
        return Bukkit.getAsyncScheduler().runAtFixedRate(this.plugin, this.guarded(task), millis, millis, TimeUnit.MILLISECONDS);
    }

    @Override
    public ScheduledTask runForPlayer(final Player player, final Consumer<ScheduledTask> task, final long periodTicks) {
        this.checkOpen();
        final AtomicReference<ScheduledTask> self = new AtomicReference<>();
        final Consumer<ScheduledTask> guarded = this.guarded(task);
        final ScheduledTask scheduled = player.getScheduler().runAtFixedRate(this.plugin, t -> {
            try {
                guarded.accept(t);
            } finally {
                if (t.isCancelled()) {
                    this.playerTasks.remove(t);
                }
            }
        }, () -> {
            final ScheduledTask t = self.get();
            if (t != null) {
                this.playerTasks.remove(t);
            }
        }, 1L, Math.max(1L, periodTicks));
        if (scheduled != null) {
            self.set(scheduled);
            this.playerTasks.add(scheduled);
            if (scheduled.isCancelled()) {
                this.playerTasks.remove(scheduled);
            }
        }
        return scheduled;
    }

    @Override
    public boolean runOnceForPlayer(final Player player, final Runnable task) {
        if (this.closed || !this.plugin.isEnabled()) {
            return false;
        }
        // While the system is being disabled (also at shutdown), run at once when this thread owns the player:
        // the clean-up then happens before the system's classes go away.
        if (this.closing && (SystemManager.stopping() || Bukkit.isOwnedByCurrentRegion(player))) {
            if (!player.isValid() && !player.isOnline()) {
                return false;
            }
            this.runGuarded(task);
            return true;
        }
        this.pendingOnce.incrementAndGet();
        final boolean scheduled = player.getScheduler().execute(this.plugin, () -> {
            try {
                this.runGuarded(task);
            } finally {
                this.pendingOnce.decrementAndGet();
            }
        }, this.pendingOnce::decrementAndGet, 1L);
        if (!scheduled) {
            this.pendingOnce.decrementAndGet();
        }
        return scheduled;
    }

    @Override
    public ServerStats stats() {
        return CoreServerStats.INSTANCE;
    }

    // ------------------------------------------------------------------------------------------
    // Failures of the system's own code inside tasks
    // ------------------------------------------------------------------------------------------

    private Consumer<ScheduledTask> guarded(final Consumer<ScheduledTask> task) {
        return t -> {
            try {
                task.accept(t);
            } catch (final Throwable e) {
                this.taskFailed(e);
            }
        };
    }

    private void runGuarded(final Runnable task) {
        try {
            task.run();
        } catch (final Throwable e) {
            this.taskFailed(e);
        }
    }

    private void taskFailed(final Throwable e) {
        if (e instanceof VirtualMachineError error && !(e instanceof StackOverflowError)) {
            throw error;
        }
        final long now = System.nanoTime();
        final long next = this.nextFailureLog.get();
        if (next != Long.MIN_VALUE && now - next < 0) {
            this.suppressedFailures.incrementAndGet();
            return;
        }
        if (!this.nextFailureLog.compareAndSet(next, now + FAILURE_LOG_INTERVAL_NANOS)) {
            this.suppressedFailures.incrementAndGet();
            return;
        }
        final int skipped = this.suppressedFailures.getAndSet(0);
        this.logger().log(Level.SEVERE, "Ошибка в задаче системы" + (skipped > 0 ? " (ещё " + skipped + " ошибок за минуту не показаны)" : ""), e);
    }

    // ------------------------------------------------------------------------------------------
    // Clean-up (SystemManager, on the global tick thread)
    // ------------------------------------------------------------------------------------------

    void startClosing() {
        this.closing = true;
    }

    int pendingOnce() {
        return this.pendingOnce.get();
    }

    /**
     * Removes everything registered by the system, except pending one-shot per-player tasks (they still run while
     * the plugin is enabled). Returns whether the command tree changed.
     */
    boolean cleanUp() {
        this.closing = true;
        HandlerList.unregisterAll(this.plugin);
        SystemManager.cancelBukkitTasks(this.plugin);
        Bukkit.getGlobalRegionScheduler().cancelTasks(this.plugin);
        Bukkit.getAsyncScheduler().cancelTasks(this.plugin);
        for (final ScheduledTask task : List.copyOf(this.playerTasks)) {
            task.cancel();
        }
        this.playerTasks.clear();
        Bukkit.getServicesManager().unregisterAll(this.plugin);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this.plugin);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this.plugin);
        return this.unregisterCommands();
    }

    /** Final step: nothing can be registered or scheduled any more. */
    void close() {
        this.closed = true;
        this.playerTasks.clear();
    }

    private boolean unregisterCommands() {
        if (this.commands.isEmpty()) {
            return false;
        }
        final CommandMap map = Bukkit.getCommandMap();
        final Map<String, Command> known = map.getKnownCommands();
        // The keys a command can be registered under (Paper's keySet() lists command names, not namespaced keys).
        final String prefix = this.info.name().toLowerCase(Locale.ROOT) + ":";
        for (final Command command : this.commands) {
            final Set<String> labels = new java.util.HashSet<>();
            labels.add(command.getName());
            labels.add(command.getLabel());
            labels.addAll(command.getAliases());
            for (final String label : labels) {
                final String lower = label.toLowerCase(Locale.ROOT).trim();
                for (final String key : List.of(lower, prefix + lower)) {
                    if (known.get(key) == command) {
                        known.remove(key);
                    }
                }
            }
            command.unregister(map);
        }
        this.commands.clear();
        return true;
    }
}
