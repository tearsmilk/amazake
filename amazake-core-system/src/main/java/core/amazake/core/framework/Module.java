package core.amazake.core.framework;

import org.bukkit.configuration.ConfigurationSection;

/**
 * One feature module of AmazakeCore (essentials, holograms, npc, groups, chat). A module is turned on and off in
 * config.yml under {@code modules.<id>} and can be reloaded without a server restart.
 *
 * <p>Lifecycle, all on the global tick thread:</p>
 * <ul>
 *   <li>{@link #onEnable(ModuleContext)} — register commands, listeners and timers through the context, create the
 *       module's database tables, publish services. Everything registered through {@link ModuleContext} is removed
 *       by the core when the module (or the whole system) is disabled.</li>
 *   <li>{@link #onConfigReload(ConfigurationSection)} — the config changed and the module stays enabled: apply the
 *       new settings in place, without a full disable/enable, so players feel nothing.</li>
 *   <li>{@link #onDisable()} — undo what the core cannot (entities shown to players, caches), close resources the
 *       module opened itself.</li>
 * </ul>
 *
 * <p><b>Threads.</b> Same rules as any Amazake system (docs/systems.md): never under the plugin lock, per-player
 * work on the player's thread via {@link ModuleContext}, shared state thread-safe, blocking I/O (the database)
 * off the tick thread. Modules must be written for regionized ticking.</p>
 */
public interface Module {

    /** Stable id, lowercase, used as the config key {@code modules.<id>} and in commands. */
    String id();

    /** A short human description (Russian) for {@code /amazakecore modules}. */
    String description();

    /** Starts the module. Its config section is {@code context.config()}. */
    void onEnable(ModuleContext context) throws Exception;

    /** Stops the module. The core then removes everything registered through the context. */
    void onDisable() throws Exception;

    /**
     * The config was reloaded and the module is still enabled: apply {@code section} in place. The default does
     * nothing (the module reads its config lazily); override to re-apply settings to live state.
     */
    default void onConfigReload(final ConfigurationSection section) throws Exception {
    }
}
