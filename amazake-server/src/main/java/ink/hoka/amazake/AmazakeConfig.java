package ink.hoka.amazake;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import ink.hoka.amazake.cpu.Cores;
import ink.hoka.amazake.cpu.Role;
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

    /** Core layout: pin thread groups to their own CPUs (Linux, at least 2 CPUs). */
    public static boolean cpuLayoutEnabled = true;
    /** cpu.layout.&lt;role&gt;: "auto" or a CPU list such as "1-3". */
    public static Map<Role, String> cpuLayoutRoles = Map.of();
    /** Size Paper's chunk worker pool to the chunks CPUs. */
    public static boolean cpuLayoutResizeChunkPool = true;
    /** Pin threads even without -XX:ActiveProcessorCount. */
    public static boolean cpuLayoutIgnoreProcessorCount = false;

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
        // Upper cap: Bukkit.getTickTimes() only keeps the last ~5 s of ticks (see profiler.TickSampler).
        profilerIntervalMs = Math.min(4000, Math.max(250, integer("profiler.interval-ms", 1000,
            "Интервал между замерами, в миллисекундах (от 250 до 4000).")));

        cpuLayoutEnabled = bool("cpu.layout.enabled", true,
            "Раскладка ядер: каждое ядро процессора получает свою работу.",
            "Главный поток (tick), чанки (chunks), сеть (network), GC и JIT (gc) и всё остальное (background)",
            "закрепляются каждый за своими ядрами, чтобы не мешать друг другу.",
            "Работает только на Linux, если серверу доступно хотя бы 2 ядра; нужна команда taskset (util-linux)",
            "и флаг запуска -XX:ActiveProcessorCount=<число ядер>. Тики сами по себе не ускоряются:",
            "раскладка только убирает помехи главному потоку, а остальным задачам достаётся меньше ядер.",
            "Таблицу раскладки и загрузку ядер показывает /amazake cores.");
        final Map<Role, String> roles = new EnumMap<>(Role.class);
        roles.put(Role.TICK, cpuList("cpu.layout.tick",
            "Ядра для главного потока (тики мира). auto или список: 0, 1-3, 1,2,5.",
            "В auto главный поток получает целое физическое ядро; его второй поток (SMT) остаётся свободным,",
            "если ядер 8 и больше."));
        roles.put(Role.CHUNKS, cpuList("cpu.layout.chunks",
            "Ядра для чанков: генерация, загрузка, свет, чтение и запись регионов. auto: около 40% остальных ядер."));
        roles.put(Role.NETWORK, cpuList("cpu.layout.network",
            "Ядра для сети (Netty: пакеты, сжатие, шифрование). auto: одно ядро."));
        roles.put(Role.GC, cpuList("cpu.layout.gc",
            "Ядра для сборщика мусора, JIT-компилятора и служебных потоков JVM. auto: около 15% остальных ядер."));
        roles.put(Role.BACKGROUND, cpuList("cpu.layout.background",
            "Ядра для всего остального: плагины, чат, фоновые задачи Paper, spark. auto: оставшиеся ядра."));
        cpuLayoutRoles = Map.copyOf(roles);
        cpuLayoutResizeChunkPool = bool("cpu.layout.resize-chunk-pool", true,
            "Сделать в пуле чанков Paper столько потоков, сколько ядер у chunks.",
            "Не действует, если в paper-global.yml задано chunk-system.worker-threads больше 0.");
        cpuLayoutIgnoreProcessorCount = bool("cpu.layout.ignore-processor-count", false,
            "Закреплять потоки, даже если нет флага -XX:ActiveProcessorCount. Без флага Java после закрепления",
            "считает ядра по потоку (у главного - 1), и пулы потоков плагинов получаются слишком маленькими.");

        try {
            yaml.save(file);
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING, "Could not write " + file, e);
        }
        LOGGER.info("Loaded " + file.getName());
        Cores.configure(cpuLayoutEnabled, cpuLayoutRoles, cpuLayoutResizeChunkPool, cpuLayoutIgnoreProcessorCount);
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

    /**
     * A CPU list setting: "auto", a number (YAML reads "tick: 0" as an int) or a list ("1-3", "1,2,5",
     * or a YAML list [1, 2]). Returned as text; LayoutPlanner checks it and warns when it is wrong.
     */
    private static String cpuList(final String path, final String... comment) {
        final Object value = yaml.get(path);
        final String text;
        if (value instanceof List<?> list && !list.isEmpty()) {
            final StringBuilder joined = new StringBuilder();
            for (final Object item : list) {
                if (joined.length() > 0) {
                    joined.append(',');
                }
                joined.append(item);
            }
            text = joined.toString();
        } else if (value instanceof Number || (value instanceof String string && !string.isBlank())) {
            text = value.toString().trim();
        } else {
            text = "auto";
            yaml.set(path, text);
        }
        yaml.setComments(path, List.of(comment));
        return text;
    }

    public static File file() {
        return file;
    }
}
