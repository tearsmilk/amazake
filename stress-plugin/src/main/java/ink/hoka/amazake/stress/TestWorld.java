package ink.hoka.amazake.stress;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Stream;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * The dedicated test world {@value #NAME}. The plugin only ever loads, modifies, unloads or deletes this
 * world, and only if its folder carries the marker file written when the plugin created it.
 */
public final class TestWorld {

    public static final String NAME = "stress_world";
    /** Written into the world folder when the plugin creates the world. */
    public static final String MARKER_FILE = "amazake-stress.marker";
    /** Written when a run starts; a world with this file is "used" and gets recreated before the next run. */
    public static final String USED_FILE = "amazake-stress.used";

    private final StressPlugin plugin;
    private boolean busy;

    public TestWorld(StressPlugin plugin) {
        this.plugin = plugin;
    }

    /** The loaded stress_world, or null. */
    public @Nullable World loaded() {
        return Bukkit.getWorld(NAME);
    }

    /** True while the world is being unloaded/deleted/created. */
    public boolean isBusy() {
        return this.busy;
    }

    public Path folder() {
        final World world = this.loaded();
        return world != null ? world.getWorldPath() : Bukkit.getWorldContainer().toPath().resolve(NAME);
    }

    public boolean hasMarker() {
        return Files.isRegularFile(this.folder().resolve(MARKER_FILE));
    }

    public boolean isUsed() {
        return Files.isRegularFile(this.folder().resolve(USED_FILE));
    }

    private boolean isMainWorld(String name) {
        final List<World> worlds = Bukkit.getWorlds();
        return !worlds.isEmpty() && worlds.get(0).getName().equals(name);
    }

    /** Null if it is safe to use/delete stress_world, otherwise a Russian error message. */
    public @Nullable String safetyProblem() {
        if (this.isMainWorld(NAME)) {
            return "мир " + NAME + " — основной мир сервера, плагин не будет его трогать";
        }
        if (Files.exists(this.folder()) && !this.hasMarker()) {
            return "папка " + NAME + " существует, но создана не AmazakeStress (нет файла " + MARKER_FILE
                + "). Плагин не будет её трогать — переименуйте или удалите её вручную";
        }
        return null;
    }

    /**
     * Makes stress_world ready for a run (main thread). Recreates it if it was used and
     * {@code world.recreate-on-start} is on. Exactly one callback is called later on the main thread.
     */
    public void prepare(StressConfig config, Consumer<World> onReady, Consumer<String> onError) {
        if (this.busy) {
            onError.accept("мир " + NAME + " сейчас пересоздаётся, подождите");
            return;
        }
        final String problem = this.safetyProblem();
        if (problem != null) {
            onError.accept(problem);
            return;
        }
        if (config.recreateOnStart() && Files.exists(this.folder()) && this.isUsed()) {
            this.plugin.getLogger().info("stress_world already used by a previous run, recreating it");
            this.delete(() -> this.createOrLoad(config, onReady, onError), onError);
            return;
        }
        this.createOrLoad(config, onReady, onError);
    }

    private void createOrLoad(StressConfig config, Consumer<World> onReady, Consumer<String> onError) {
        World world = this.loaded();
        if (world == null) {
            final boolean existed = Files.exists(this.folder());
            final WorldCreator creator = new WorldCreator(NAME)
                .seed(config.seed())
                .environment(World.Environment.NORMAL)
                .generateStructures(false)
                .type(config.worldType());
            if (config.worldType() == WorldType.FLAT && !config.generatorSettings().isBlank()) {
                creator.generatorSettings(config.generatorSettings());
            }
            this.busy = true;
            try {
                world = creator.createWorld();
            } catch (final Throwable t) {
                this.plugin.getLogger().log(Level.SEVERE, "Failed to create " + NAME, t);
                world = null;
            } finally {
                this.busy = false;
            }
            if (world == null) {
                onError.accept("не удалось создать мир " + NAME + " (подробности в консоли)");
                return;
            }
            if (!existed || !this.hasMarker()) {
                this.writeFile(world.getWorldPath().resolve(MARKER_FILE),
                    "Created by AmazakeStress " + Instant.now() + "\nseed=" + config.seed() + "\n");
            }
        }
        if (world.getSeed() != config.seed()) {
            onError.accept("сид мира " + NAME + " (" + world.getSeed() + ") не совпадает с конфигом (" + config.seed()
                + "). Выполните /stress cleanup confirm, чтобы пересоздать мир");
            return;
        }
        this.applySettings(world, config);
        onReady.accept(world);
    }

    /** Applies gamerules, time, weather, difficulty and autosave from the config. */
    @SuppressWarnings("unchecked")
    public void applySettings(World world, StressConfig config) {
        world.setAutoSave(config.autoSave());
        world.setDifficulty(config.difficulty());
        world.setTime(config.time());
        world.setStorm(false);
        world.setThundering(false);
        world.setClearWeatherDuration(Integer.MAX_VALUE / 2);
        for (final Map.Entry<String, Object> entry : config.gamerules().entrySet()) {
            final NamespacedKey key = NamespacedKey.fromString(entry.getKey().toLowerCase(java.util.Locale.ROOT));
            final GameRule<?> rule = key == null ? null : Registry.GAME_RULE.get(key);
            if (rule == null) {
                this.plugin.getLogger().warning("Unknown gamerule in config: " + entry.getKey());
                continue;
            }
            final String value = String.valueOf(entry.getValue()).trim();
            try {
                if (rule.getType() == Boolean.class) {
                    world.setGameRule((GameRule<Boolean>) rule, Boolean.parseBoolean(value));
                } else if (rule.getType() == Integer.class) {
                    world.setGameRule((GameRule<Integer>) rule, Integer.parseInt(value));
                } else {
                    this.plugin.getLogger().warning("Unsupported gamerule type for " + entry.getKey());
                }
            } catch (final RuntimeException e) {
                this.plugin.getLogger().warning("Bad value for gamerule " + entry.getKey() + ": " + value);
            }
        }
    }

    /** Y where module builds start. */
    public int baseY(World world, StressConfig config) {
        if (config.buildY() != StressConfig.BUILD_Y_AUTO) {
            return Math.max(world.getMinHeight(), Math.min(world.getMaxHeight() - 1, config.buildY()));
        }
        if (config.worldType() == WorldType.FLAT) {
            return world.getHighestBlockYAt(0, 0) + 1;
        }
        return Math.min(world.getMaxHeight() - 64, 120);
    }

    /** Marks the world as used by a run (it will be recreated before the next one). */
    public void markUsed(World world) {
        this.writeFile(world.getWorldPath().resolve(USED_FILE), "Used by a run started " + Instant.now() + "\n");
    }

    /**
     * Unloads (without saving) and deletes stress_world (main thread; deletion runs async).
     * Players inside are moved to the main world spawn first.
     */
    public void delete(Runnable onDone, Consumer<String> onError) {
        if (this.busy) {
            onError.accept("мир " + NAME + " уже пересоздаётся/удаляется");
            return;
        }
        final String problem = this.safetyProblem();
        if (problem != null) {
            onError.accept(problem);
            return;
        }
        final Path folder = this.folder();
        final World world = this.loaded();
        if (world != null) {
            final List<World> worlds = Bukkit.getWorlds();
            final Location fallback = worlds.get(0).getSpawnLocation();
            for (final Player player : world.getPlayers()) {
                player.teleport(fallback);
            }
            world.removePluginChunkTickets(this.plugin);
            if (!Bukkit.unloadWorld(world, false)) {
                onError.accept("сервер отказался выгружать мир " + NAME);
                return;
            }
        }
        if (!Files.exists(folder)) {
            onDone.run();
            return;
        }
        this.busy = true;
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            String error = null;
            try {
                deleteRecursively(folder);
            } catch (final IOException e) {
                this.plugin.getLogger().log(Level.SEVERE, "Failed to delete " + folder, e);
                error = "не удалось удалить папку " + folder + ": " + e.getMessage();
            }
            final String finalError = error;
            if (!this.plugin.isEnabled()) {
                this.busy = false;
                return;
            }
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                this.busy = false;
                if (finalError != null) {
                    onError.accept(finalError);
                } else {
                    onDone.run();
                }
            });
        });
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            final List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (final Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void writeFile(Path path, String content) {
        try {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (final IOException e) {
            this.plugin.getLogger().log(Level.WARNING, "Cannot write " + path, e);
        }
    }
}
