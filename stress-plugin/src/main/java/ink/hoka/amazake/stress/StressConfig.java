package ink.hoka.amazake.stress;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Difficulty;
import org.bukkit.WorldType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.FileConfiguration;

/** Immutable snapshot of config.yml, taken at every /stress start. */
public final class StressConfig {

    /** Value of {@code world.build-y} meaning "choose automatically". */
    public static final int BUILD_Y_AUTO = Integer.MIN_VALUE;

    private final FileConfiguration raw;
    private final long seed;
    private final WorldType worldType;
    private final String generatorSettings;
    private final int buildY;
    private final boolean recreateOnStart;
    private final Difficulty difficulty;
    private final long time;
    private final boolean autoSave;
    private final Map<String, Object> gamerules;
    private final int totalSeconds;
    private final int[] stageSeconds;
    private final int minMaxStageSeconds;
    private final double maxStageMultiplier;
    private final int logIntervalSeconds;
    private final boolean broadcastStageChanges;
    private final boolean saveResults;
    private final boolean autoProfile;

    public StressConfig(FileConfiguration raw) {
        this.raw = raw;
        this.seed = raw.getLong("world.seed", 20261111L);
        this.worldType = parseEnum(WorldType.class, raw.getString("world.type", "FLAT"), WorldType.FLAT);
        this.generatorSettings = raw.getString("world.generator-settings", "");
        final String buildY = String.valueOf(raw.get("world.build-y", "auto")).trim();
        int parsedY = BUILD_Y_AUTO;
        if (!buildY.equalsIgnoreCase("auto")) {
            try {
                parsedY = Integer.parseInt(buildY);
            } catch (final NumberFormatException ignored) {
                parsedY = BUILD_Y_AUTO;
            }
        }
        this.buildY = parsedY;
        this.recreateOnStart = raw.getBoolean("world.recreate-on-start", true);
        this.difficulty = parseEnum(Difficulty.class, raw.getString("world.difficulty", "NORMAL"), Difficulty.NORMAL);
        this.time = raw.getLong("world.time", 6000L);
        this.autoSave = raw.getBoolean("world.auto-save", false);
        final Map<String, Object> rules = new LinkedHashMap<>();
        final ConfigurationSection rulesSection = raw.getConfigurationSection("world.gamerules");
        if (rulesSection != null) {
            for (final String key : rulesSection.getKeys(false)) {
                rules.put(key, rulesSection.get(key));
            }
        }
        this.gamerules = Collections.unmodifiableMap(rules);

        this.totalSeconds = Math.max(10, raw.getInt("timeline.total-seconds", 300));
        this.stageSeconds = new int[6];
        final int[] defaults = {20, 30, 30, 30, 30, 30};
        for (int i = 0; i < 6; i++) {
            final Stage stage = Stage.TIMELINE[i];
            this.stageSeconds[i] = Math.max(0, raw.getInt("timeline.stages." + stage.key(), defaults[i]));
        }
        this.minMaxStageSeconds = Math.max(0, raw.getInt("timeline.min-max-stage-seconds", 30));
        this.maxStageMultiplier = Math.max(0.0, raw.getDouble("timeline.max-stage-multiplier", 1.5));

        this.logIntervalSeconds = Math.max(0, raw.getInt("status.log-interval-seconds", 10));
        this.broadcastStageChanges = raw.getBoolean("status.broadcast-stage-changes", true);
        this.saveResults = raw.getBoolean("status.save-results", true);
        this.autoProfile = raw.getBoolean("profiler.auto-start-with-run", false);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, E fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            return fallback;
        }
    }

    /** The raw file configuration (read-only use). */
    public FileConfiguration raw() {
        return this.raw;
    }

    /** {@code modules.<id>} section, or an empty section if missing. */
    public ConfigurationSection moduleSection(String id) {
        final ConfigurationSection section = this.raw.getConfigurationSection("modules." + id);
        return section != null ? section : new MemoryConfiguration();
    }

    public boolean moduleEnabled(String id) {
        return this.raw.getBoolean("modules." + id + ".enabled", true);
    }

    public long seed() {
        return this.seed;
    }

    public WorldType worldType() {
        return this.worldType;
    }

    public String generatorSettings() {
        return this.generatorSettings == null ? "" : this.generatorSettings;
    }

    /** Configured build Y or {@link #BUILD_Y_AUTO}. */
    public int buildY() {
        return this.buildY;
    }

    public boolean recreateOnStart() {
        return this.recreateOnStart;
    }

    public Difficulty difficulty() {
        return this.difficulty;
    }

    public long time() {
        return this.time;
    }

    public boolean autoSave() {
        return this.autoSave;
    }

    /** Gamerule name (1.21.11 snake_case, optionally namespaced) to configured value. */
    public Map<String, Object> gamerules() {
        return this.gamerules;
    }

    public int totalSeconds() {
        return this.totalSeconds;
    }

    /** Duration in seconds of timeline stage {@code index} (0..5). */
    public int stageSeconds(int index) {
        return this.stageSeconds[index];
    }

    public int minMaxStageSeconds() {
        return this.minMaxStageSeconds;
    }

    public double maxStageMultiplier() {
        return this.maxStageMultiplier;
    }

    public int logIntervalSeconds() {
        return this.logIntervalSeconds;
    }

    public boolean broadcastStageChanges() {
        return this.broadcastStageChanges;
    }

    public boolean saveResults() {
        return this.saveResults;
    }

    public boolean autoProfile() {
        return this.autoProfile;
    }
}
