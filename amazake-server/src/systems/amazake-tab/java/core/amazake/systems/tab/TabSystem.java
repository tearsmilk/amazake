package core.amazake.systems.tab;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * AmazakeTab: animated tab header/footer, tab list names with group prefixes and sorting, a sidebar and name tags
 * above heads. See {@link TabRuntime} for the threading model.
 */
public final class TabSystem implements AmazakeSystem {

    private volatile TabRuntime runtime;

    @Override
    public void enable(final SystemContext context) {
        final TabConfig config = this.load(context);
        final TabRuntime runtime = new TabRuntime(context, config);
        this.runtime = runtime;
        context.registerListener(new TabListener(runtime));
        for (final Player player : Bukkit.getOnlinePlayers()) {
            runtime.start(player);
        }
        context.logger().info("AmazakeTab включён: игроков " + Bukkit.getOnlinePlayers().size()
            + ", групп " + config.groups().size() + ".");
    }

    @Override
    public void disable(final SystemContext context) {
        final TabRuntime runtime = this.runtime;
        this.runtime = null;
        if (runtime != null) {
            runtime.close();
        }
    }

    @Override
    public void reload(final SystemContext context) throws Exception {
        final TabRuntime runtime = this.runtime;
        if (runtime == null) {
            this.enable(context);
            return;
        }
        // a broken file must not turn the system off (the core disables a system whose reload throws):
        // log it and keep the running config
        final YamlConfiguration yaml;
        try {
            yaml = context.loadConfig();
        } catch (final RuntimeException e) {
            context.logger().warning("config.yml не загружен: " + e.getMessage() + "; оставлен прежний конфиг");
            return;
        }
        runtime.apply(this.parse(context, yaml));
        context.logger().info("Конфиг AmazakeTab перезагружен.");
    }

    private TabConfig load(final SystemContext context) {
        return this.parse(context, context.loadConfig());
    }

    private TabConfig parse(final SystemContext context, final YamlConfiguration yaml) {
        final TabConfig config = TabConfig.parse(ConfigMaps.toMap(yaml));
        for (final String warning : config.warnings()) {
            context.logger().warning("config.yml: " + warning);
        }
        return config;
    }
}
