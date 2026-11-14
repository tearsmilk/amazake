package core.amazake.core.framework;

import core.amazake.systems.api.SystemContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Owns the module instances and their lifecycle. Enabling/disabling and config reloads all run on the global tick
 * thread (the system's enable/disable/reload). Keeps the registration order so disable happens in reverse.
 */
public final class ModuleManager {

    private final SystemContext system;
    private final Database database;
    private final CoreServices services;
    /** All known modules by id, in registration order. */
    private final Map<String, Module> modules = new LinkedHashMap<>();
    /** Currently running modules and their contexts. */
    private final Map<String, ModuleContext> running = new LinkedHashMap<>();

    public ModuleManager(final SystemContext system, final Database database, final CoreServices services) {
        this.system = system;
        this.database = database;
        this.services = services;
    }

    public void register(final Module module) {
        this.modules.put(module.id(), module);
    }

    public Map<String, Module> all() {
        return this.modules;
    }

    public boolean isRunning(final String id) {
        return this.running.containsKey(id);
    }

    /** Enables every module marked {@code modules.<id>.enabled: true} (default true when the section is missing). */
    public void enableConfigured(final ConfigurationSection root) {
        for (final Module module : this.modules.values()) {
            if (enabledInConfig(root, module.id())) {
                this.enable(module.id(), root);
            } else {
                this.system.logger().info("AmazakeCore: модуль " + module.id() + " выключен в конфиге.");
            }
        }
    }

    /** Applies a reloaded config: enable newly-on modules, disable newly-off ones, reload the rest in place. */
    public void applyConfig(final ConfigurationSection root) {
        for (final Module module : this.modules.values()) {
            final String id = module.id();
            final boolean want = enabledInConfig(root, id);
            final boolean have = this.running.containsKey(id);
            if (want && !have) {
                this.enable(id, root);
            } else if (!want && have) {
                this.disable(id);
            } else if (want) {
                final ModuleContext ctx = this.running.get(id);
                ctx.setConfig(section(root, id));
                try {
                    module.onConfigReload(ctx.config());
                } catch (final Throwable t) {
                    this.system.logger().log(Level.WARNING,
                        "AmazakeCore: ошибка при перезагрузке настроек модуля " + id + "; модуль остаётся как был", t);
                }
            }
        }
    }

    public boolean enable(final String id, final ConfigurationSection root) {
        final Module module = this.modules.get(id);
        if (module == null || this.running.containsKey(id)) {
            return false;
        }
        final ModuleContext ctx = new ModuleContext(id, this.system, this.database, this.services, section(root, id));
        try {
            module.onEnable(ctx);
            this.running.put(id, ctx);
            this.system.logger().info("AmazakeCore: модуль " + id + " включён.");
            return true;
        } catch (final Throwable t) {
            this.system.logger().log(Level.SEVERE, "AmazakeCore: не удалось включить модуль " + id, t);
            try {
                module.onDisable();
            } catch (final Throwable ignored) {
                // best effort cleanup of a half-enabled module
            }
            return false;
        }
    }

    public boolean disable(final String id) {
        final Module module = this.modules.get(id);
        if (module == null || !this.running.containsKey(id)) {
            return false;
        }
        final ModuleContext ctx = this.running.get(id);
        try {
            module.onDisable();
        } catch (final Throwable t) {
            this.system.logger().log(Level.WARNING, "AmazakeCore: ошибка при выключении модуля " + id, t);
        }
        if (ctx != null) {
            ctx.cleanupServices();
        }
        this.running.remove(id);
        this.system.logger().info("AmazakeCore: модуль " + id + " выключен.");
        return true;
    }

    /** Disables all running modules in reverse order (system shutdown). */
    public void disableAll() {
        final List<String> ids = new ArrayList<>(this.running.keySet());
        for (int i = ids.size() - 1; i >= 0; i--) {
            this.disable(ids.get(i));
        }
    }

    public List<String> runningIds() {
        return new ArrayList<>(this.running.keySet());
    }

    private static boolean enabledInConfig(final ConfigurationSection root, final String id) {
        final ConfigurationSection section = root.getConfigurationSection("modules." + id);
        return section == null || section.getBoolean("enabled", true);
    }

    private static ConfigurationSection section(final ConfigurationSection root, final String id) {
        final ConfigurationSection existing = root.getConfigurationSection("modules." + id);
        return existing != null ? existing : root.createSection("modules." + id);
    }
}
