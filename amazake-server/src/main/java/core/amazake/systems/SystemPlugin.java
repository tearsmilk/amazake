package core.amazake.systems;

import core.amazake.systems.api.SystemInfo;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.lifecycle.event.LifecycleEventManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginBase;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/**
 * The Bukkit-side owner of a system's registrations ({@link core.amazake.systems.api.SystemContext#plugin()}).
 * It is not in the plugin manager's list; it is enabled exactly while the system is, which is what Bukkit's and
 * Paper's schedulers and event registration check. One instance per enable.
 *
 * <p>Systems are thread-safe by contract, so {@link core.amazake.threading.PluginLock} never locks for them.</p>
 */
public final class SystemPlugin extends PluginBase {

    private final SystemInfo info;
    private final PluginDescriptionFile description;
    private final Path dataFolder;
    private final SystemClassLoader loader;
    private final Logger logger;
    private volatile boolean enabled;
    private boolean naggable = true;
    private FileConfiguration config;

    /**
     * The core's own owner for housekeeping tasks (never disabled, no class loader of its own): its no-op tasks push
     * a disabled system's last task out of the Bukkit schedulers' queue heads.
     */
    static final SystemPlugin CORE = core();

    private static SystemPlugin core() {
        final SystemPlugin plugin = new SystemPlugin(new SystemInfo("AmazakeSystems", "1", "core.amazake.systems.SystemManager",
            "Amazake systems (core)", List.of(), "2"), Path.of("systems").toAbsolutePath().normalize(), null);
        plugin.setEnabled(true);
        return plugin;
    }

    SystemPlugin(final SystemInfo info, final Path dataFolder, final SystemClassLoader loader) {
        this.info = info;
        this.dataFolder = dataFolder;
        this.loader = loader;
        this.description = describe(info);
        this.logger = new SystemLogger(info.name(), Bukkit.getServer() == null ? Logger.getLogger("") : Bukkit.getServer().getLogger());
    }

    /** A plugin description built from system.yml (Bukkit and Paper ask plugins for one). */
    static PluginDescriptionFile describe(final SystemInfo info) {
        final Map<String, Object> yaml = new LinkedHashMap<>();
        yaml.put("name", info.name());
        yaml.put("version", info.version());
        yaml.put("main", info.main());
        if (!info.description().isEmpty()) {
            yaml.put("description", info.description());
        }
        if (!info.authors().isEmpty()) {
            yaml.put("authors", List.copyOf(info.authors()));
        }
        yaml.put("api-version", "1.21");
        yaml.put("folia-supported", true);
        final DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        try {
            return new PluginDescriptionFile(new StringReader(new Yaml(options).dump(yaml)));
        } catch (final InvalidDescriptionException e) {
            throw new IllegalArgumentException("system.yml of " + info.name() + ": " + e.getMessage(), e);
        }
    }

    public SystemInfo info() {
        return this.info;
    }

    public SystemClassLoader classLoader() {
        return this.loader;
    }

    void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean isEnabled() {
        return this.enabled;
    }

    @Override
    public @NotNull File getDataFolder() {
        return this.dataFolder.toFile();
    }

    @Override
    public @NotNull PluginDescriptionFile getDescription() {
        return this.description;
    }

    @Override
    public @NotNull PluginMeta getPluginMeta() {
        return this.description;
    }

    @Override
    public @NotNull FileConfiguration getConfig() {
        if (this.config == null) {
            this.reloadConfig();
        }
        return this.config;
    }

    @Override
    public @Nullable InputStream getResource(final @NotNull String filename) {
        if (this.loader == null) {
            return null;
        }
        try {
            return this.loader.ownResource(filename);
        } catch (final IOException e) {
            return null;
        }
    }

    @Override
    public void saveConfig() {
        try {
            Files.createDirectories(this.dataFolder);
            this.getConfig().save(this.dataFolder.resolve("config.yml").toFile());
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Не удалось сохранить config.yml", e);
        }
    }

    @Override
    public void saveDefaultConfig() {
        if (!Files.exists(this.dataFolder.resolve("config.yml"))) {
            this.saveResource("config.yml", false);
        }
    }

    @Override
    public void saveResource(final @NotNull String resourcePath, final boolean replace) {
        final String path = resourcePath.replace('\\', '/');
        final Path target = this.dataFolder.resolve(path).normalize();
        if (!target.startsWith(this.dataFolder)) {
            throw new IllegalArgumentException("Путь за пределами папки системы: " + resourcePath);
        }
        try (InputStream in = this.getResource(path)) {
            if (in == null) {
                throw new IllegalArgumentException("В .azs-файле системы " + this.info.name() + " нет ресурса " + resourcePath);
            }
            if (Files.exists(target) && !replace) {
                return;
            }
            Files.createDirectories(target.getParent());
            BuiltinSystems.write(target, in);
        } catch (final IOException e) {
            this.logger.log(Level.SEVERE, "Не удалось сохранить " + resourcePath, e);
        }
    }

    @Override
    public void reloadConfig() {
        final YamlConfiguration loaded = YamlConfiguration.loadConfiguration(this.dataFolder.resolve("config.yml").toFile());
        try (InputStream in = this.getResource("config.yml")) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (final IOException ignored) {
            // no defaults
        }
        this.config = loaded;
    }

    @Deprecated
    @Override
    public @Nullable PluginLoader getPluginLoader() {
        return null; // systems are not loaded by a Bukkit plugin loader
    }

    @Override
    public @NotNull Server getServer() {
        return Bukkit.getServer();
    }

    @Override
    public void onDisable() {
    }

    @Override
    public void onLoad() {
    }

    @Override
    public void onEnable() {
    }

    @Override
    public boolean isNaggable() {
        return this.naggable;
    }

    @Override
    public void setNaggable(final boolean canNag) {
        this.naggable = canNag;
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(final @NotNull String worldName, final @Nullable String id) {
        return null;
    }

    @Override
    public @Nullable BiomeProvider getDefaultBiomeProvider(final @NotNull String worldName, final @Nullable String id) {
        return null;
    }

    @Override
    public @NotNull Logger getLogger() {
        return this.logger;
    }

    @Override
    public @NotNull LifecycleEventManager<org.bukkit.plugin.Plugin> getLifecycleManager() {
        throw new UnsupportedOperationException("Системы Amazake не используют lifecycle API Paper: команды регистрируйте через SystemContext.registerCommand");
    }

    @Override
    public boolean onCommand(final @NotNull CommandSender sender, final @NotNull Command command, final @NotNull String label, final @NotNull String[] args) {
        return false;
    }

    @Override
    public @Nullable List<String> onTabComplete(final @NotNull CommandSender sender, final @NotNull Command command, final @NotNull String alias, final @NotNull String[] args) {
        return null;
    }

    @Override
    public String toString() {
        return "AmazakeSystem[" + this.info.name() + " " + this.info.version() + "]";
    }
}
