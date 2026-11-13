package core.amazake.systems.api;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * What the core gives a running system. Everything registered through it is tracked and removed when the system
 * is disabled. All methods are thread-safe.
 */
public interface SystemContext {

    /** The system's description from its system.yml. */
    SystemInfo info();

    /** A logger whose messages are prefixed with the system's name. */
    Logger logger();

    /** {@code systems/<name>/}, created if missing. */
    Path dataFolder();

    /**
     * {@code systems/<name>/config.yml}, written from the {@code config.yml} inside the .azs file on first use
     * (existing files are never overwritten), then loaded. Each call reads the file again.
     */
    YamlConfiguration loadConfig();

    /** Copies a resource of the .azs file into the data folder unless the target exists. */
    void saveDefaultResource(String path);

    /**
     * The Bukkit-side owner of the system's registrations: a plugin object that is not in the plugin list (it shows
     * in /pl under AmazakeSystems) and is enabled exactly while the system is. Use it where Bukkit or Paper API asks
     * for a {@link Plugin}; registrations made with it directly are cleaned up too (listeners, Bukkit scheduler,
     * global/async/region schedulers), except entity-scheduler tasks: schedule those with {@link #runForPlayer}.
     */
    Plugin plugin();

    /** Registers a listener (all its {@code @EventHandler} methods), unregistered on disable. */
    void registerListener(Listener listener);

    /** Registers a command under the system's namespace ({@code /<name>:<label>} and {@code /<label>}), removed on disable. */
    void registerCommand(Command command);

    /** Runs {@code task} on the global tick thread every {@code period} (first run after {@code period}). */
    ScheduledTask runGlobalTimer(Consumer<ScheduledTask> task, Duration period);

    /** Runs {@code task} off the tick threads every {@code period}. */
    ScheduledTask runAsyncTimer(Consumer<ScheduledTask> task, Duration period);

    /**
     * Runs {@code task} on the thread that owns {@code player} every {@code periodTicks} ticks, until the player
     * leaves, the task cancels itself or the system is disabled. The place for per-player work (their tab list,
     * scoreboard, name tags). Returns null if the player is no longer valid.
     */
    ScheduledTask runForPlayer(Player player, Consumer<ScheduledTask> task, long periodTicks);

    /** Runs {@code task} once on the thread that owns {@code player}; false if the player is no longer valid. */
    boolean runOnceForPlayer(Player player, Runnable task);

    /** Server-wide numbers the core already measures, safe to call from any thread. */
    ServerStats stats();
}
