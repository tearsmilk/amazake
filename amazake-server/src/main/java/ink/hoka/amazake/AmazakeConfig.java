package ink.hoka.amazake;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * amazake.yml: Amazake's own settings, next to server.properties.
 *
 * <p>Every multithreaded or behaviour-changing feature of Amazake gets a switch here, so a server
 * owner chooses between speed and vanilla behaviour feature by feature. A missing key is written
 * back with its default and its comment, so the file always lists every setting.</p>
 */
public final class AmazakeConfig {

    public static final String FILE_NAME = "amazake.yml";
    /** Bumped when a setting is renamed or moved, so an old file can be migrated. */
    public static final int CONFIG_VERSION = 1;

    private static final Logger LOGGER = Logger.getLogger("Amazake");
    private static final List<String> HEADER = List.of(
        "Amazake configuration.",
        "Amazake: a Paper fork for 1.21.11 - https://github.com/tearsmilk/amazake",
        "Reload it with /amazake reload (settings marked 'restart' need a restart)."
    );

    private static File file = new File(FILE_NAME);
    private static YamlConfiguration yaml = new YamlConfiguration();

    // --- Settings: each multithreaded feature adds its switch here, read in reload() ----------

    /** Profiler site to upload /amazake profile reports to; empty = save them to profiles/ only. */
    public static String profilerUploadUrl = "";
    /** Sent as X-Amazake-Token when the site requires a token; empty = no header. */
    public static String profilerUploadToken = "";
    /** Length of /amazake profile without an argument, seconds. */
    public static int profilerDefaultSeconds = 30;
    /** Longest measurement allowed, seconds. */
    public static int profilerMaxSeconds = 600;
    /** Time between two samples, milliseconds (at least 250). */
    public static int profilerIntervalMs = 1000;

    private AmazakeConfig() {
    }

    /** Reads amazake.yml from the server folder (creating it), at start and on /reload. */
    public static synchronized void load(final File serverFolder) {
        file = new File(serverFolder, FILE_NAME);
        reload();
    }

    /** Reads amazake.yml again. Returns false when it could not be read; the previous settings stay. */
    public static synchronized boolean reload() {
        final YamlConfiguration next = new YamlConfiguration();
        next.options().parseComments(true);
        final boolean firstStart = !file.exists();
        if (firstStart) {
            LOGGER.info("*************************************************************************************");
            LOGGER.info("This is the first time Amazake starts in this folder: it wrote " + FILE_NAME + ".");
            LOGGER.info("Builds, source and documentation: " + Amazake.REPOSITORY);
            LOGGER.info("*************************************************************************************");
        }
        if (file.isFile()) {
            try {
                next.load(file);
            } catch (final Exception e) {
                LOGGER.log(Level.SEVERE, "Could not read " + file + ", keeping the previous settings", e);
                return false;
            }
        }
        yaml = next;
        yaml.options().setHeader(HEADER);
        yaml.set("config-version", CONFIG_VERSION);

        // Settings are read here, e.g.:
        //   asyncEntityTracker = bool("async.entity-tracker", true, "Track entities off the main thread.");

        profilerUploadUrl = string("profiler.upload-url", "",
            "Адрес вашего сайта профайлера, например https://profiler.example.com",
            "/amazake profile отправляет туда отчёт и присылает ссылку на него.",
            "Пусто: отчёты только сохраняются в папку profiles/.").trim();
        profilerUploadToken = string("profiler.upload-token", "",
            "Токен, если сайт его требует (заголовок X-Amazake-Token). Пусто: без токена.").trim();
        profilerMaxSeconds = Math.max(1, integer("profiler.max-seconds", 600,
            "Самый долгий замер /amazake profile, в секундах."));
        profilerDefaultSeconds = Math.min(profilerMaxSeconds, Math.max(1, integer("profiler.default-seconds", 30,
            "Длина замера /amazake profile без аргумента, в секундах.")));
        profilerIntervalMs = Math.max(250, integer("profiler.interval-ms", 1000,
            "Интервал между замерами, в миллисекундах (не меньше 250)."));

        try {
            yaml.save(file);
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING, "Could not write " + file, e);
        }
        LOGGER.info("Loaded " + file.getName());
        return true;
    }

    private static boolean bool(final String path, final boolean def, final String... comment) {
        if (!yaml.isBoolean(path)) {
            yaml.set(path, def);
        }
        yaml.setComments(path, List.of(comment));
        return yaml.getBoolean(path, def);
    }

    private static int integer(final String path, final int def, final String... comment) {
        if (!yaml.isInt(path)) {
            yaml.set(path, def);
        }
        yaml.setComments(path, List.of(comment));
        return yaml.getInt(path, def);
    }

    private static String string(final String path, final String def, final String... comment) {
        if (!yaml.isString(path)) {
            yaml.set(path, def);
        }
        yaml.setComments(path, List.of(comment));
        return yaml.getString(path, def);
    }

    public static File file() {
        return file;
    }
}
