package ink.hoka.amazake;

import java.io.File;
import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import ink.hoka.amazake.cpu.Cores;
import ink.hoka.amazake.cpu.LayoutPlanner;
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
    /** Time every entity tick by type and phase during a measurement (the report's "entities"). */
    public static boolean profilerEntityBreakdown = true;
    /** Time each plugin's main-thread work (tasks, sync listeners, commands) and async task CPU during a measurement (the report's "plugins"). */
    public static boolean profilerPluginBreakdown = true;

    /** Core layout: pin thread groups to their own CPUs (Linux, at least 2 CPUs). */
    public static boolean cpuLayoutEnabled = true;
    /** cpu.layout.&lt;role&gt;: "auto" or a CPU list such as "1-3". */
    public static Map<Role, String> cpuLayoutRoles = Map.of();
    /** Size Paper's chunk worker pool to the chunks CPUs. */
    public static boolean cpuLayoutResizeChunkPool = true;
    /** Pin threads even without -XX:ActiveProcessorCount. */
    public static boolean cpuLayoutIgnoreProcessorCount = false;
    /** cpu.reserve: empty CPUs the main thread moves to when its own CPU is busy with foreign work. */
    public static Cores.Reserve cpuReserve = Cores.Reserve.DEFAULT;
    /** cpu.layout.support-nice: nice of support threads on the CPUs plugin threads spill onto; 0: none. */
    public static int cpuLayoutSupportNice = 5;

    /** multithreading.mode as written: off, worlds or regions. */
    public static String multithreadingMode = "off";
    /** The mode in effect: only "off" is implemented yet, the others fall back to it with a warning. */
    public static String multithreadingEffectiveMode = "off";
    /** multithreading.thread-count: 0 = auto. Parsed, not used yet. */
    public static int multithreadingThreadCount = 0;
    /** multithreading.region-size in chunks (a power of two, at least 2). Parsed, not used yet. */
    public static int multithreadingRegionSize = 8;
    /** plugin-threading.mode. */
    public static ink.hoka.amazake.threading.PluginLock.Mode pluginThreadingMode = ink.hoka.amazake.threading.PluginLock.Mode.AUTO;
    /** plugin-threading.trust-folia-supported. */
    public static boolean pluginThreadingTrustFoliaSupported = true;
    /** plugin-threading.thread-safe: plugin names that run without the plugin lock. */
    public static List<String> pluginThreadingThreadSafe = List.of();
    /** plugin-threading.force-locked: plugin names always run under the plugin lock. */
    public static List<String> pluginThreadingForceLocked = List.of();

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
        profilerEntityBreakdown = bool("profiler.entity-breakdown", true,
            "Во время замера считать время тика сущностей: мобы, остальные сущности и игроки,",
            "по типам и по фазам (цели, мозг, поиск пути, движение...). В отчёте это вкладка «Сущности».",
            "Вне замера ничего не стоит; во время замера добавляет немного к тику при большом числе сущностей.");
        profilerPluginBreakdown = bool("profiler.plugin-breakdown", true,
            "Во время замера считать, сколько каждый плагин занимает в главном потоке (задачи планировщика,",
            "обработчики событий, команды) и сколько процессора съедают его асинхронные задачи.",
            "Код плагинов при этом работает как обычно, только измеряется. В отчёте это вкладка «Плагины».",
            "Вне замера ничего не стоит; во время замера два замера времени на каждый вызов плагина.");

        cpuLayoutEnabled = bool("cpu.layout.enabled", true,
            "Раскладка ядер: каждое ядро процессора получает свою работу.",
            "Главный поток (tick), чанки (chunks), сеть (network), GC и JIT (gc), плагины (plugins) и поддержка",
            "(background) закрепляются каждый за своими ядрами, чтобы не мешать друг другу.",
            "Код плагинов, который трогает мир (синхронные задачи, события, команды), как и в Bukkit, остаётся",
            "в главном потоке: гонок данных с тиком нет. На ядра plugins уходит только их асинхронная работа.",
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
        roles.put(Role.PLUGINS, cpuList("cpu.layout.plugins",
            "Ядра для асинхронной работы плагинов: асинхронные задачи планировщика, чат, асинхронные события входа",
            "и потоки, которые плагины создали сами. auto: своё ядро (около 15% остальных ядер), если ядер 4 и больше;",
            "на 2-3 ядрах плагины делят ядро с сетью и поддержкой. Кроме своих ядер потоки плагинов могут",
            "переливаться на ядра background (ядро-подложку), где у них приоритет над фоном (support-nice)."));
        roles.put(Role.BACKGROUND, cpuList("cpu.layout.background",
            "Ядра поддержки (ядро-подложка): фоновые задачи Paper, spark, Amazake. Сюда же с приоритетом",
            "переливаются потоки плагинов, когда им мало своих ядер. auto: оставшиеся ядра."));
        cpuLayoutRoles = Map.copyOf(roles);
        cpuLayoutResizeChunkPool = bool("cpu.layout.resize-chunk-pool", true,
            "Сделать в пуле чанков Paper столько потоков, сколько ядер у chunks.",
            "Не действует, если в paper-global.yml задано chunk-system.worker-threads больше 0.");
        cpuLayoutIgnoreProcessorCount = bool("cpu.layout.ignore-processor-count", false,
            "Закреплять потоки, даже если нет флага -XX:ActiveProcessorCount. Без флага Java после закрепления",
            "считает ядра по потоку (у главного - 1), и пулы потоков плагинов получаются слишком маленькими.");
        cpuLayoutSupportNice = Math.min(19, Math.max(0, integer("cpu.layout.support-nice", 5,
            "Приоритет фоновых потоков на ядре-подложке, где с ними работают потоки плагинов: nice от 0 до 19.",
            "5 - потоки плагинов получают процессор примерно 3:1. 0 - не менять приоритеты. Понижаются только",
            "потоки, о которых точно известно, что в них нет кода плагинов: spark, Amazake и несколько служебных",
            "потоков Paper. Неизвестные потоки не трогаются (это может быть простаивающий пул плагина), как и",
            "главный поток, сеть, чанки, GC, потоки плагинов и общий пул ForkJoinPool. Если пониженный приоритет",
            "всё же унаследует поток плагина, понижение выключается до перезапуска (сообщение в логе).",
            "Без права CAP_SYS_NICE nice можно только поднять: после выключения раскладки он остаётся",
            "до перезапуска сервера.")));

        final boolean reserveEnabled = bool("cpu.reserve.enabled", true,
            "Резерв и переезд главного потока. Резервные ядра остаются пустыми: Amazake не ставит туда ни одного",
            "потока. Каждые check-seconds секунд он смотрит, сколько ядро tick было занято не главным потоком",
            "(другие процессы машины, бот, база данных, прерывания, steal - время, которое забрал гипервизор VPS;",
            "паузы GC самого сервера не считаются). Если две проверки подряд это больше foreign-load-threshold,",
            "главный поток переезжает на свободное резервное ядро, а старое ядро tick становится резервом. Хватает",
            "и всплеска чуть дольше секунды на стыке двух проверок. Тяжёлый тик это легче не делает:",
            "помогает только против чужой нагрузки на ядре. Текущее состояние показывает /amazake cores.");
        final int reserveCount = reserveCount("cpu.reserve.count",
            "Сколько ядер держать в резерве. auto: 1, если серверу доступно 6 ядер и больше, иначе 0.",
            "На 3 ядрах auto даёт 0: лишнего ядра нет, резерв отнял бы единственное ядро у фона и сети.",
            "Можно задать 1 вручную: тогда чанки, GC, сеть, плагины и фон делят одно ядро. Ядра резерва берутся",
            "только у background (никогда у tick, chunks, своего ядра plugins и не со второго потока SMT ядра tick;",
            "поэтому на 6 ядрах с SMT auto даёт 0).");
        final int reserveCheck = Math.min(60, Math.max(1, integer("cpu.reserve.check-seconds", 2,
            "Как часто проверять ядро tick, в секундах (от 1 до 60).")));
        final double reserveThreshold = Math.min(1.0, Math.max(0.05, decimal("cpu.reserve.foreign-load-threshold", 0.25,
            "Какая чужая нагрузка на ядре tick считается помехой, в долях ядра: 0.25 - четверть ядра.")));
        final int reserveCooldown = Math.max(0, integer("cpu.reserve.cooldown-seconds", 30,
            "После переезда следующий - не раньше чем через столько секунд."));
        cpuReserve = new Cores.Reserve(reserveCount, reserveEnabled, reserveCheck, reserveThreshold, reserveCooldown);

        readMultithreading();

        try {
            yaml.save(file);
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING, "Could not write " + file, e);
        }
        LOGGER.info("Loaded " + file.getName());
        Cores.configure(cpuLayoutEnabled, cpuLayoutRoles, cpuLayoutResizeChunkPool, cpuLayoutIgnoreProcessorCount, cpuReserve,
            cpuLayoutSupportNice);
        ink.hoka.amazake.threading.PluginLock.configure(pluginThreadingMode, !"off".equals(multithreadingEffectiveMode),
            pluginThreadingTrustFoliaSupported, pluginThreadingThreadSafe, pluginThreadingForceLocked);
        if (ink.hoka.amazake.threading.PluginLock.active()) {
            LOGGER.info("Plugin lock: on (plugin-threading.mode: " + pluginThreadingMode.key + ")");
        }
        return true;
    }

    /** The multithreading and plugin-threading sections (phase 1: parsed; only the plugin lock acts). */
    private static void readMultithreading() {
        final String mode = choice("multithreading.mode", "off", List.of("off", "worlds", "regions"),
            "off - один поток тика, как в Paper. worlds - каждый мир в своём потоке. regions - регионы мира",
            "в нескольких потоках. worlds и regions ещё не готовы: сервер пишет предупреждение и работает как off.");
        // The section comment after its first key: setComments ignores a path that does not exist yet.
        yaml.setComments("multithreading", List.of(
            "Многопоточный тик (в разработке). Мир делится на регионы, как в Folia, и регионы тикают",
            "в нескольких потоках. Сейчас готова только подготовка: работает только mode: off."));
        multithreadingMode = mode;
        if (!"off".equals(mode)) {
            LOGGER.warning("multithreading.mode: " + mode + " ещё не готово, работаю как off.");
        }
        multithreadingEffectiveMode = "off";
        multithreadingThreadCount = autoOrNumber("multithreading.thread-count",
            "Сколько потоков тикают регионы. auto: по раскладке ядер. Пока не используется.");
        int regionSize = integer("multithreading.region-size", 8,
            "Размер региона в чанках (сторона квадрата): степень двойки, не меньше 2. Пока не используется.");
        if (regionSize < 2 || Integer.bitCount(regionSize) != 1) {
            LOGGER.warning("multithreading.region-size: " + regionSize + " - не степень двойки от 2, взято 8.");
            regionSize = 8;
        }
        multithreadingRegionSize = regionSize;

        final String lockMode = choice("plugin-threading.mode", "auto", List.of("auto", "global-lock", "off"),
            "auto - блокировка включается, только когда многопоточность включена (multithreading.mode не off).",
            "global-lock - блокировка всегда (с одним потоком она ничего не меняет, только проверка).",
            "off - без блокировки: все плагины считаются потокобезопасными (опасно).");
        yaml.setComments("plugin-threading", List.of(
            "Как защищать плагины, когда тик идёт в нескольких потоках. Плагины Bukkit рассчитаны на один",
            "главный поток, поэтому код плагинов, не готовых к многопоточности, выполняется под одной общей",
            "блокировкой: в каждый момент его выполняет только один поток, и гонок данных у плагина нет."));
        pluginThreadingMode = ink.hoka.amazake.threading.PluginLock.Mode.parse(lockMode);
        pluginThreadingTrustFoliaSupported = bool("plugin-threading.trust-folia-supported", true,
            "Плагины с folia-supported: true в plugin.yml работают без блокировки: они уже рассчитаны на Folia.");
        pluginThreadingThreadSafe = names("plugin-threading.thread-safe",
            "Имена плагинов, которые работают без блокировки, например [LuckPerms].");
        pluginThreadingForceLocked = names("plugin-threading.force-locked",
            "Имена плагинов, которые всегда работают под блокировкой (важнее двух настроек выше).");
    }

    /** A text setting with fixed values; an unknown value is warned about and replaced by the default. */
    private static String choice(final String path, final String def, final List<String> values, final String... comment) {
        Object value = yaml.get(path);
        if (Boolean.FALSE.equals(value) && values.contains("off")) {
            value = "off"; // YAML 1.1 reads an unquoted off as false
            yaml.set(path, value);
        }
        String out = def;
        if (value != null && values.contains(String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT))) {
            out = String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        } else {
            if (value != null) {
                LOGGER.warning(path + ": \"" + value + "\" - нет такого значения (" + String.join(", ", values) + "), взято " + def + ".");
            }
            yaml.set(path, def);
        }
        yaml.setComments(path, List.of(comment));
        return out;
    }

    /** "auto" (0) or a number of at least 1. */
    private static int autoOrNumber(final String path, final String... comment) {
        final Object value = yaml.get(path);
        int out = 0;
        if (value instanceof Number number) {
            out = Math.max(1, number.intValue());
        } else if (value instanceof String text && !text.trim().equalsIgnoreCase("auto")) {
            try {
                out = Math.max(1, Integer.parseInt(text.trim()));
            } catch (final NumberFormatException e) {
                LOGGER.warning(path + ": \"" + text.trim() + "\" - не число и не auto, взято auto.");
            }
        } else if (!(value instanceof String)) {
            yaml.set(path, "auto");
        }
        yaml.setComments(path, List.of(comment));
        return out;
    }

    /** A list of plugin names (a YAML list; a single name is accepted too). */
    private static List<String> names(final String path, final String... comment) {
        final Object value = yaml.get(path);
        final List<String> out = new java.util.ArrayList<>();
        if (value instanceof List<?> list) {
            for (final Object item : list) {
                if (item != null && !String.valueOf(item).isBlank()) {
                    out.add(String.valueOf(item).trim());
                }
            }
        } else if (value instanceof String text && !text.isBlank()) {
            out.add(text.trim());
        } else {
            yaml.set(path, List.of());
        }
        yaml.setComments(path, List.of(comment));
        return List.copyOf(out);
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

    /** A number setting; an int in the file (YAML "1") is read as a number too. */
    private static double decimal(final String path, final double def, final String... comment) {
        final Object value = yaml.get(path);
        double out = def;
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            out = number.doubleValue();
        } else {
            yaml.set(path, def);
        }
        yaml.setComments(path, List.of(comment));
        return out;
    }

    /** cpu.reserve.count: "auto" ({@link LayoutPlanner#AUTO_RESERVE}) or a number of CPUs, at least 0. */
    private static int reserveCount(final String path, final String... comment) {
        final Object value = yaml.get(path);
        int out = LayoutPlanner.AUTO_RESERVE;
        if (value instanceof Number number) {
            out = Math.max(0, number.intValue());
        } else if (value instanceof String text && !text.isBlank() && !text.trim().equalsIgnoreCase("auto")) {
            try {
                out = Math.max(0, Integer.parseInt(text.trim()));
            } catch (final NumberFormatException e) {
                LOGGER.warning(path + ": \"" + text.trim() + "\" - не число и не auto, взято auto.");
            }
        } else if (!(value instanceof String text && text.trim().equalsIgnoreCase("auto"))) {
            yaml.set(path, "auto");
        }
        yaml.setComments(path, List.of(comment));
        return out;
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
