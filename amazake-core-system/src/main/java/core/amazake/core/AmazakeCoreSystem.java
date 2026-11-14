package core.amazake.core;

import core.amazake.core.framework.CoreServices;
import core.amazake.core.framework.Database;
import core.amazake.core.framework.Module;
import core.amazake.core.framework.ModuleManager;
import core.amazake.core.modules.chat.ChatModule;
import core.amazake.core.modules.essentials.EssentialsModule;
import core.amazake.core.modules.groups.GroupsModule;
import core.amazake.core.modules.holograms.HologramsModule;
import core.amazake.core.modules.npc.NpcModule;
import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * AmazakeCore: one Amazake system that bundles several feature modules (essentials, holograms, npc, groups, chat),
 * each switched on/off and configured under {@code modules.<id>} in config.yml, and reloadable without a server
 * restart. The entry point named in system.yml; its name must survive obfuscation (see proguard-rules.pro).
 */
public final class AmazakeCoreSystem implements AmazakeSystem {

    private SystemContext context;
    private Database database;
    private ModuleManager modules;
    private final CoreServices services = new CoreServices();

    /** The modules AmazakeCore ships, in enable order (chat last: it reads the groups service). */
    private List<Module> buildModules() {
        return List.of(
            new EssentialsModule(),
            new GroupsModule(),
            new HologramsModule(),
            new NpcModule(),
            new ChatModule());
    }

    @Override
    public void enable(final SystemContext context) throws Exception {
        this.context = context;
        final YamlConfiguration config = context.loadConfig();
        this.database = Database.open(context.dataFolder(), context.logger());
        this.modules = new ModuleManager(context, this.database, this.services);
        for (final Module module : this.buildModules()) {
            this.modules.register(module);
        }
        this.modules.enableConfigured(config);
        context.registerCommand(new CoreCommand(this));
        context.logger().info("AmazakeCore запущен. Модули: " + String.join(", ", this.modules.runningIds())
            + ". Настройки: " + context.dataFolder().resolve("config.yml"));
    }

    @Override
    public void disable(final SystemContext context) {
        if (this.modules != null) {
            this.modules.disableAll();
        }
        if (this.database != null) {
            this.database.close();
        }
    }

    @Override
    public void reload(final SystemContext context) {
        // Hot config reload: re-read config.yml and apply it to the modules (enable/disable/reload each in place).
        final YamlConfiguration config = context.loadConfig();
        this.modules.applyConfig(config);
        context.logger().info("AmazakeCore: настройки перечитаны. Модули: " + String.join(", ", this.modules.runningIds()));
    }

    // ---- used by CoreCommand ----

    SystemContext context() {
        return this.context;
    }

    ModuleManager modules() {
        return this.modules;
    }

    /** Re-reads config.yml and applies it; returns the running module ids. Runs on the global tick thread. */
    List<String> reloadConfig() {
        try {
            this.modules.applyConfig(this.context.loadConfig());
        } catch (final Throwable t) {
            this.context.logger().log(Level.SEVERE, "AmazakeCore: ошибка при перезагрузке конфига", t);
        }
        return this.modules.runningIds();
    }
}
