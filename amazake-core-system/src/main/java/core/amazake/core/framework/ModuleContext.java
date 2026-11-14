package core.amazake.core.framework;

import core.amazake.systems.api.ServerStats;
import core.amazake.systems.api.SystemContext;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.command.Command;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * What the core gives a {@link Module}: the underlying system context, the shared database, the service registry,
 * the module's own config section and a logger prefixed with the module id. Everything registered here is removed
 * when the module (or the whole AmazakeCore system) is disabled.
 */
public final class ModuleContext {

    private final String moduleId;
    private final SystemContext system;
    private final Database database;
    private final ModuleServices services;
    private final Logger logger;
    private volatile ConfigurationSection config;

    public ModuleContext(final String moduleId, final SystemContext system, final Database database,
                         final CoreServices services, final ConfigurationSection config) {
        this.moduleId = moduleId;
        this.system = system;
        this.database = database;
        this.services = new ModuleServices(services);
        this.config = config;
        this.logger = Logger.getLogger("AmazakeCore/" + moduleId);
        this.logger.setParent(system.logger());
        this.logger.setUseParentHandlers(true);
    }

    public String moduleId() {
        return this.moduleId;
    }

    /** The full system context (registerListener/registerCommand/timers/runForPlayer/stats…). */
    public SystemContext system() {
        return this.system;
    }

    public Database database() {
        return this.database;
    }

    public ModuleServices services() {
        return this.services;
    }

    /** Removes services this module published; called by the core when the module is disabled. */
    void cleanupServices() {
        this.services.removeAll();
    }

    /** The module's config section ({@code modules.<id>}); replaced on config reload before onConfigReload runs. */
    public ConfigurationSection config() {
        return this.config;
    }

    void setConfig(final ConfigurationSection config) {
        this.config = config;
    }

    public Logger logger() {
        return this.logger;
    }

    public Plugin plugin() {
        return this.system.plugin();
    }

    public void registerListener(final Listener listener) {
        this.system.registerListener(listener);
    }

    public void registerCommand(final Command command) {
        this.system.registerCommand(command);
    }

    public ScheduledTask runGlobalTimer(final Consumer<ScheduledTask> task, final Duration period) {
        return this.system.runGlobalTimer(task, period);
    }

    public ScheduledTask runAsyncTimer(final Consumer<ScheduledTask> task, final Duration period) {
        return this.system.runAsyncTimer(task, period);
    }

    public ScheduledTask runForPlayer(final Player player, final Consumer<ScheduledTask> task, final long periodTicks) {
        return this.system.runForPlayer(player, task, periodTicks);
    }

    public boolean runOnceForPlayer(final Player player, final Runnable task) {
        return this.system.runOnceForPlayer(player, task);
    }

    public ServerStats stats() {
        return this.system.stats();
    }
}
