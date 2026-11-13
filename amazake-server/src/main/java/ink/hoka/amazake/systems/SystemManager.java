package ink.hoka.amazake.systems;

import ink.hoka.amazake.systems.api.AmazakeSystem;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;

/**
 * Amazake systems: the {@code .azs} modules in the server's {@code systems/} folder, enabled and disabled at runtime.
 *
 * <p>The server calls {@link #startup()} once plugins are enabled (POSTWORLD, also after /reload) and
 * {@link #shutdown()} before plugins are disabled (server stop, and in CraftServer.reload() before the plugins and
 * commands are cleared). Everything that changes state runs on the global tick thread;
 * {@link #overview()} may be called from any thread.</p>
 */
public final class SystemManager {

    public static final String PERMISSION = "amazake.command.systems";
    static final Logger LOGGER = Logger.getLogger("Amazake");
    /** How long a disabled system's one-shot per-player clean-up tasks may take before its class loader is closed. */
    private static final int DRAIN_TICKS = 100;

    private static final Runnable NOOP = () -> {
    };
    /** By system name (as in system.yml). */
    private static final Map<String, Entry> SYSTEMS = new ConcurrentHashMap<>();
    /** Files already reported as invalid or duplicate (path + modification time), so a rescan does not repeat it. */
    private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();
    /**
     * What systems.yml lists: the owner's wish, kept apart from what runs. Changed only by enable/disable commands
     * (and read at startup); a failed enable or reload leaves it as it is.
     */
    private static final WantedSystems WANTED = new WantedSystems();
    private static volatile Path directory = Path.of("systems").toAbsolutePath().normalize();
    private static volatile boolean started;
    private static volatile boolean stopping;

    private SystemManager() {
    }

    /** One system file known to the core. */
    static final class Entry {
        final String name;
        volatile Path file;
        volatile SystemDescriptor descriptor;
        volatile Running running;
        /** A disabled run whose one-shot clean-up tasks have not finished yet. */
        volatile Running draining;
        volatile String lastError;

        Entry(final String name, final Path file, final SystemDescriptor descriptor) {
            this.name = name;
            this.file = file;
            this.descriptor = descriptor;
        }
    }

    /** One enable: a new class loader, plugin object, context and instance. */
    record Running(SystemClassLoader loader, SystemPlugin plugin, SystemContextImpl context, AmazakeSystem instance) {
    }

    /** What /pl and /amazake systems show. */
    public record Overview(String name, String version, String description, List<String> authors, boolean enabled,
                           String file, String lastError) {
    }

    /** The outcome of a command, already worded for the sender (Russian). */
    public record Result(boolean ok, List<String> lines) {
        static Result ok(final String... lines) {
            return new Result(true, List.of(lines));
        }

        static Result fail(final String... lines) {
            return new Result(false, List.of(lines));
        }
    }

    public static Path directory() {
        return directory;
    }

    static boolean stopping() {
        return stopping;
    }

    /** All known systems, sorted by name; any thread. */
    public static List<Overview> overview() {
        final List<Overview> out = new ArrayList<>();
        for (final Entry entry : SYSTEMS.values()) {
            final SystemDescriptor d = entry.descriptor;
            out.add(new Overview(entry.name, d.version(), d.info().description(), d.info().authors(), entry.running != null,
                entry.file.getFileName().toString(), entry.lastError));
        }
        out.sort(Comparator.comparing(Overview::name, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    /** The system of this name (any case), or null. */
    static Entry find(final String name) {
        final Entry exact = SYSTEMS.get(name);
        if (exact != null) {
            return exact;
        }
        for (final Entry entry : SYSTEMS.values()) {
            if (entry.name.equalsIgnoreCase(name)) {
                return entry;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // Server start and stop
    // ------------------------------------------------------------------------------------------

    /**
     * Creates {@code systems/}, extracts new built-in systems, finds the .azs files and enables the systems listed in
     * systems.yml. Called on the server thread once plugins are enabled (POSTWORLD), also after /reload.
     */
    public static synchronized void startup() {
        stopping = false;
        final Path dir = Path.of("systems").toAbsolutePath().normalize();
        directory = dir;
        try {
            Files.createDirectories(dir);
        } catch (final IOException e) {
            LOGGER.log(Level.SEVERE, "Системы: не удалось создать папку " + dir + ", системы не загружены", e);
            return;
        }
        try {
            final BuiltinSystems.Outcome outcome = BuiltinSystems.install(dir, BuiltinSystems.NAMES, BuiltinSystems.Source.JAR);
            for (final String name : outcome.extracted()) {
                LOGGER.info("Системы: встроенная система " + name + " распакована в systems/" + BuiltinSystems.fileName(name)
                    + ". Она выключена; включить без перезапуска: /amazake system enable " + name);
            }
            for (final String name : outcome.keptExisting()) {
                LOGGER.info("Системы: файл systems/" + BuiltinSystems.fileName(name) + " уже есть, встроенная система "
                    + name + " не распаковывалась поверх него.");
            }
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING, "Системы: не удалось распаковать встроенные системы", e);
        }
        scan();
        reportNewerBuiltins();
        // Start Paper's async scheduler timer thread from core code, before any system code runs (see SystemClassLoader).
        Bukkit.getAsyncScheduler().runDelayed(SystemPlugin.CORE, task -> {
        }, 1L, java.util.concurrent.TimeUnit.MILLISECONDS);

        Set<String> wanted;
        try {
            wanted = SystemsState.readEnabled(dir);
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING, "Системы: не удалось прочитать systems/" + SystemsState.STATE_FILE
                + ", при запуске ни одна система не включается", e);
            wanted = Set.of();
        }
        final List<String> canonical = new ArrayList<>();
        for (final String name : wanted) {
            final Entry entry = find(name);
            canonical.add(entry != null ? entry.name : name);
        }
        WANTED.replace(canonical);
        if (!Files.exists(dir.resolve(SystemsState.STATE_FILE))) {
            writeState();
        }
        for (final String name : wanted) {
            final Entry entry = find(name);
            if (entry == null) {
                LOGGER.warning("Системы: в systems/" + SystemsState.STATE_FILE + " включена система " + name
                    + ", но её .azs-файла в папке systems/ нет.");
                continue;
            }
            if (entry.running == null) {
                final Result result = enable(entry, false);
                if (!result.ok()) {
                    // The reason is logged with its stack by enable(); WANTED keeps the system, so systems.yml still
                    // lists it after other systems are toggled, for the next start.
                    continue;
                }
            }
        }
        started = true;
        final List<String> on = SYSTEMS.values().stream().filter(e -> e.running != null).map(e -> e.name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
        LOGGER.info("Системы: найдено " + SYSTEMS.size() + ", включены: " + (on.isEmpty() ? "нет" : String.join(", ", on))
            + " (папка systems/, команды /amazake systems и /amazake system)");
    }

    /** Disables every system (server stop or /reload), before plugins are disabled. */
    public static synchronized void shutdown() {
        stopping = true;
        for (final Entry entry : List.copyOf(SYSTEMS.values())) {
            if (entry.running != null) {
                disable(entry, false);
            }
            finishDrain(entry);
        }
        started = false;
    }

    // ------------------------------------------------------------------------------------------
    // The folder
    // ------------------------------------------------------------------------------------------

    /** Reads the .azs files in the folder: new files appear, removed ones of disabled systems disappear. */
    static synchronized void scan() {
        final Path dir = directory;
        final Map<String, Path> files = new LinkedHashMap<>();
        final Map<String, SystemDescriptor> descriptors = new LinkedHashMap<>();
        final List<Path> paths = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.azs")) {
            stream.forEach(paths::add);
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING, "Системы: не удалось прочитать папку " + dir, e);
            return;
        }
        paths.sort(Comparator.comparing(p -> p.getFileName().toString()));
        for (final Path path : paths) {
            if (!Files.isRegularFile(path)) {
                continue;
            }
            final String key = reportKey(path);
            final SystemDescriptor descriptor;
            try {
                descriptor = SystemDescriptor.read(path);
            } catch (final SystemDescriptor.InvalidException e) {
                if (REPORTED.add(key)) {
                    LOGGER.warning("Системы: файл systems/" + path.getFileName() + " пропущен: " + e.getMessage());
                }
                continue;
            }
            final Path first = files.get(descriptor.name());
            final Entry running = SYSTEMS.get(descriptor.name());
            if (first != null) {
                if (REPORTED.add(key)) {
                    LOGGER.warning("Системы: в systems/" + path.getFileName() + " система " + descriptor.name()
                        + ", как и в systems/" + first.getFileName() + "; используется первый файл, этот пропущен.");
                }
                continue;
            }
            if (running != null && running.running != null && !running.file.equals(path)) {
                continue; // a second file of a running system: the running one stays
            }
            files.put(descriptor.name(), path);
            descriptors.put(descriptor.name(), descriptor);
        }
        // Keep running systems as they are; refresh or drop the others.
        for (final Entry entry : List.copyOf(SYSTEMS.values())) {
            if (entry.running != null) {
                continue;
            }
            final Path path = files.get(entry.name);
            if (path == null) {
                SYSTEMS.remove(entry.name);
            } else {
                entry.file = path;
                entry.descriptor = descriptors.get(entry.name);
            }
        }
        for (final Map.Entry<String, Path> file : files.entrySet()) {
            SYSTEMS.computeIfAbsent(file.getKey(), name -> new Entry(name, file.getValue(), descriptors.get(name)));
        }
    }

    private static String reportKey(final Path path) {
        try {
            return path + "@" + Files.getLastModifiedTime(path).toMillis();
        } catch (final IOException e) {
            return path.toString();
        }
    }

    private static void reportNewerBuiltins() {
        for (final String name : BuiltinSystems.NAMES) {
            final Entry entry = SYSTEMS.get(name);
            if (entry == null) {
                continue;
            }
            try {
                final SystemDescriptor inJar = BuiltinSystems.describe(name, BuiltinSystems.Source.JAR);
                if (BuiltinSystems.newer(inJar, entry.descriptor) && REPORTED.add("newer:" + name + ":" + inJar.version())) {
                    LOGGER.info("Системы: в ядре есть более новая версия " + name + " (" + inJar.version()
                        + ", в папке " + entry.descriptor.version() + "). Ваш файл не тронут; обновить его из ядра: /amazake system update " + name);
                }
            } catch (final Exception e) {
                LOGGER.log(Level.FINE, "built-in " + name, e);
            }
        }
    }

    /** Writes systems.yml from {@link #WANTED}, so systems that are wanted but not running stay listed. */
    static void writeState() {
        try {
            SystemsState.writeEnabled(directory, WANTED.sorted());
        } catch (final IOException e) {
            LOGGER.log(Level.WARNING, "Системы: не удалось записать systems/" + SystemsState.STATE_FILE, e);
        }
    }

    // ------------------------------------------------------------------------------------------
    // Commands (global tick thread)
    // ------------------------------------------------------------------------------------------

    public static synchronized List<Overview> rescanAndList() {
        scan();
        return overview();
    }

    public static synchronized Result enable(final String name) {
        scan();
        final Entry entry = find(name);
        if (entry == null) {
            return Result.fail("Системы «" + name + "» нет в папке systems/. Список: /amazake systems");
        }
        if (entry.running != null) {
            return Result.fail("Система " + entry.name + " уже включена.");
        }
        return enable(entry, true);
    }

    public static synchronized Result disable(final String name) {
        final Entry entry = find(name);
        final String known = entry != null ? entry.name : name;
        if (entry == null || entry.running == null) {
            // Wanted but not running (its enable failed, or its .azs file is gone): drop it from systems.yml.
            if (WANTED.remove(known)) {
                writeState();
                LOGGER.info("Системы: система " + known + " убрана из systems/" + SystemsState.STATE_FILE + ".");
                return Result.ok("Система " + known + " не была запущена; она убрана из systems/" + SystemsState.STATE_FILE
                    + " и при запуске больше не включается.");
            }
            return entry == null
                ? Result.fail("Системы «" + name + "» нет. Список: /amazake systems")
                : Result.fail("Система " + entry.name + " уже выключена.");
        }
        return disable(entry, true);
    }

    public static synchronized Result reload(final String name) {
        final Entry entry = find(name);
        if (entry == null) {
            return Result.fail("Системы «" + name + "» нет. Список: /amazake systems");
        }
        final Running running = entry.running;
        if (running == null) {
            return Result.fail("Система " + entry.name + " выключена; включить: /amazake system enable " + entry.name);
        }
        try {
            running.instance().reload(running.context());
        } catch (final Throwable e) {
            rethrowFatal(e);
            LOGGER.log(Level.SEVERE, "Системы: ошибка при перезагрузке системы " + entry.name + "; система выключена, чтобы не остаться наполовину настроенной", e);
            disable(entry, false);
            entry.lastError = describe(e);
            // WANTED (systems.yml) keeps it: the owner fixes the config and runs enable (or restarts).
            return Result.fail("Ошибка при перезагрузке " + entry.name + ": " + describe(e),
                "Система выключена. Исправьте ошибку (подробности в консоли) и включите её: /amazake system enable " + entry.name);
        }
        if (running.context().hasCommands()) {
            syncCommands();
        }
        entry.lastError = null;
        LOGGER.info("Системы: система " + entry.name + " перезагружена.");
        return Result.ok("Система " + entry.name + " перезагружена.");
    }

    /** Replaces a built-in system's file with the one in the server jar, re-enabling it if it was enabled. */
    public static synchronized Result update(final String name) {
        scan();
        String builtin = null;
        for (final String candidate : BuiltinSystems.NAMES) {
            if (candidate.equalsIgnoreCase(name)) {
                builtin = candidate;
            }
        }
        if (builtin == null) {
            return Result.fail("«" + name + "» не встроенная система; обновите её файл в папке systems/ сами. Встроенные: "
                + String.join(", ", BuiltinSystems.NAMES));
        }
        final SystemDescriptor inJar;
        try {
            inJar = BuiltinSystems.describe(builtin, BuiltinSystems.Source.JAR);
        } catch (final Exception e) {
            return Result.fail("Не удалось прочитать встроенную " + builtin + ": " + describe(e));
        }
        if (inJar == null) {
            return Result.fail("В этой сборке ядра нет встроенной " + builtin + ".");
        }
        final Entry entry = SYSTEMS.get(builtin);
        final boolean wasEnabled = entry != null && entry.running != null;
        if (wasEnabled) {
            disable(entry, false);
        }
        if (entry != null) {
            finishDrain(entry);
        }
        final Path target = entry != null ? entry.file : directory.resolve(BuiltinSystems.fileName(builtin));
        final String oldVersion = entry != null ? entry.descriptor.version() : null;
        try (var in = BuiltinSystems.Source.JAR.open(builtin)) {
            BuiltinSystems.write(target, in);
            final Set<String> installed = SystemsState.readInstalled(directory);
            if (installed.add(builtin)) {
                SystemsState.writeInstalled(directory, installed);
            }
        } catch (final IOException e) {
            LOGGER.log(Level.SEVERE, "Системы: не удалось записать " + target, e);
            return Result.fail("Не удалось записать " + target.getFileName() + ": " + e.getMessage());
        }
        scan();
        final List<String> lines = new ArrayList<>();
        lines.add("Файл systems/" + target.getFileName() + " заменён встроенной " + builtin + " " + inJar.version()
            + (oldVersion == null ? "" : " (была " + oldVersion + ")") + ". Папка systems/" + builtin + "/ с настройками не тронута.");
        LOGGER.info("Системы: " + lines.get(0));
        if (wasEnabled) {
            final Entry fresh = SYSTEMS.get(builtin);
            final Result result = fresh == null ? Result.fail("После замены система не найдена") : enable(fresh, false);
            lines.addAll(result.lines());
            return new Result(result.ok(), lines);
        }
        return new Result(true, lines);
    }

    /** /amazake system info. */
    public static synchronized Result info(final String name) {
        scan();
        final Entry entry = find(name);
        if (entry == null) {
            return Result.fail("Системы «" + name + "» нет. Список: /amazake systems");
        }
        final SystemDescriptor d = entry.descriptor;
        final List<String> lines = new ArrayList<>();
        lines.add(entry.name + " " + d.version() + " — " + (entry.running != null ? "включена" : "выключена"));
        if (!d.info().description().isEmpty()) {
            lines.add("Описание: " + d.info().description());
        }
        if (!d.info().authors().isEmpty()) {
            lines.add("Авторы: " + String.join(", ", d.info().authors()));
        }
        lines.add("Файл: systems/" + entry.file.getFileName() + ", настройки: systems/" + entry.name + "/");
        lines.add("Главный класс: " + d.info().main() + ", api-version: " + d.info().apiVersion());
        if (BuiltinSystems.NAMES.contains(entry.name)) {
            lines.add("Встроенная система ядра (обновить файл из ядра: /amazake system update " + entry.name + ")");
        }
        for (final String conflict : d.conflicts()) {
            if (Bukkit.getPluginManager().getPlugin(conflict) != null) {
                lines.add("Внимание: установлен плагин " + conflict + ", он делает то же самое.");
            }
        }
        if (entry.running == null && WANTED.contains(entry.name)) {
            lines.add("Указана в systems/" + SystemsState.STATE_FILE + ", но не запущена: исправьте ошибку и включите "
                + "(/amazake system enable " + entry.name + ") или уберите из списка (/amazake system disable " + entry.name + ")");
        }
        if (entry.lastError != null) {
            lines.add("Последняя ошибка: " + entry.lastError);
        }
        return Result.ok(lines.toArray(String[]::new));
    }

    /** Names for tab completion: enabled (true), disabled (false) or all (null). */
    public static List<String> names(final Boolean enabled) {
        return SYSTEMS.values().stream()
            .filter(e -> enabled == null || (e.running != null) == enabled)
            .map(e -> e.name).sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    // ------------------------------------------------------------------------------------------
    // Enable / disable
    // ------------------------------------------------------------------------------------------

    private static Result enable(final Entry entry, final boolean persist) {
        finishDrain(entry);
        final SystemDescriptor descriptor;
        try {
            descriptor = SystemDescriptor.read(entry.file); // the file as it is now
        } catch (final SystemDescriptor.InvalidException e) {
            entry.lastError = e.getMessage();
            LOGGER.warning("Системы: не удалось включить " + entry.name + ": " + e.getMessage());
            return Result.fail("Не удалось включить " + entry.name + ": " + e.getMessage());
        }
        if (!descriptor.name().equals(entry.name)) {
            scan();
            return Result.fail("Файл systems/" + entry.file.getFileName() + " теперь содержит систему " + descriptor.name()
                + "; повторите команду.");
        }
        entry.descriptor = descriptor;
        final String name = entry.name;
        if (Bukkit.getPluginManager().getPlugin(name) != null) {
            final String reason = "есть плагин с таким же именем (" + name + "); переименуйте систему или уберите плагин";
            entry.lastError = reason;
            LOGGER.warning("Системы: не удалось включить " + name + ": " + reason);
            return Result.fail("Не удалось включить " + name + ": " + reason);
        }
        final List<String> lines = new ArrayList<>();
        for (final String conflict : descriptor.conflicts()) {
            if (Bukkit.getPluginManager().getPlugin(conflict) != null) {
                final String warning = "Внимание: установлен плагин " + conflict + ". Он и " + name
                    + " будут спорить за одно и то же (таб, скорборд). Оставьте что-то одно.";
                LOGGER.warning("Системы: " + warning);
                lines.add(warning);
            }
        }
        final Path dataFolder = directory.resolve(name);
        SystemClassLoader loader = null;
        SystemPlugin plugin = null;
        SystemContextImpl context = null;
        try {
            Files.createDirectories(dataFolder);
            loader = new SystemClassLoader(name, entry.file, SystemManager.class.getClassLoader());
            plugin = new SystemPlugin(descriptor.info(), dataFolder, loader);
            context = new SystemContextImpl(descriptor.info(), plugin, dataFolder);
            plugin.setEnabled(true);
            final Class<?> main = Class.forName(descriptor.info().main(), true, loader);
            if (main.getClassLoader() != loader) {
                throw new IllegalStateException("класс " + descriptor.info().main()
                    + " найден не в .azs-файле, а в ядре или его библиотеках: такая система не выгрузится");
            }
            if (!AmazakeSystem.class.isAssignableFrom(main)) {
                throw new IllegalStateException("класс " + descriptor.info().main() + " не реализует " + AmazakeSystem.class.getName());
            }
            final AmazakeSystem instance = (AmazakeSystem) main.getConstructor().newInstance();
            CoreServerStats.INSTANCE.refresh();
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> CoreServerStats.INSTANCE.refresh(), 20L, 20L);
            instance.enable(context);
            entry.running = new Running(loader, plugin, context, instance);
        } catch (final Throwable e) {
            rethrowFatal(e);
            final Throwable cause = e instanceof java.lang.reflect.InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
            LOGGER.log(Level.SEVERE, "Системы: не удалось включить систему " + name + " " + descriptor.version(), cause);
            if (context != null) {
                context.startClosing();
                context.cleanUp();
            }
            finish(loader, plugin, context);
            entry.lastError = describe(cause);
            lines.add(0, "Не удалось включить " + name + ": " + describe(cause) + " (подробности в консоли)");
            return new Result(false, lines);
        }
        entry.lastError = null;
        if (persist) {
            WANTED.add(name);
            writeState();
        }
        if (started && context.hasCommands()) {
            syncCommands();
        }
        LOGGER.info("Системы: система " + name + " " + descriptor.version() + " включена.");
        lines.add(0, "Система " + name + " " + descriptor.version() + " включена. Настройки: systems/" + name + "/");
        return new Result(true, lines);
    }

    private static Result disable(final Entry entry, final boolean persist) {
        final Running running = entry.running;
        if (running == null) {
            return Result.fail("Система " + entry.name + " уже выключена.");
        }
        final SystemContextImpl context = running.context();
        context.startClosing();
        String error = null;
        try {
            running.instance().disable(context);
        } catch (final Throwable e) {
            rethrowFatal(e);
            error = describe(e);
            LOGGER.log(Level.SEVERE, "Системы: ошибка при выключении системы " + entry.name + " (она всё равно выключена и убрана)", e);
        }
        entry.running = null;
        final boolean commands = context.cleanUp();
        if (commands && !stopping) {
            refreshHelp();
            syncCommands();
        }
        if (stopping || context.pendingOnce() == 0) {
            finish(running.loader(), running.plugin(), context);
        } else {
            drain(entry, running);
        }
        if (persist) {
            WANTED.remove(entry.name);
            writeState();
        }
        LOGGER.info("Системы: система " + entry.name + " выключена.");
        return error == null
            ? Result.ok("Система " + entry.name + " выключена.")
            : Result.ok("Система " + entry.name + " выключена, но при выключении была ошибка: " + error + " (подробности в консоли)");
    }

    /** Waits (up to DRAIN_TICKS) for the one-shot per-player clean-up tasks the system scheduled in disable(). */
    private static void drain(final Entry entry, final Running running) {
        entry.draining = running;
        final int[] ticks = {0};
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(running.plugin(), task -> {
            if (running.context().pendingOnce() <= 0 || ++ticks[0] >= DRAIN_TICKS || stopping) {
                task.cancel();
                synchronized (SystemManager.class) {
                    if (entry.draining == running) {
                        entry.draining = null;
                        finish(running.loader(), running.plugin(), running.context());
                    }
                }
            }
        }, 1L, 1L);
    }

    private static void finishDrain(final Entry entry) {
        final Running draining = entry.draining;
        if (draining != null) {
            entry.draining = null;
            finish(draining.loader(), draining.plugin(), draining.context());
        }
    }

    /** The plugin object goes disabled, its leftover tasks are cancelled, the class loader is closed. */
    private static void finish(final SystemClassLoader loader, final SystemPlugin plugin, final SystemContextImpl context) {
        if (plugin != null) {
            plugin.setEnabled(false);
            cancelBukkitTasks(plugin);
            Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
            Bukkit.getAsyncScheduler().cancelTasks(plugin);
            HandlerList.unregisterAll(plugin);
        }
        if (context != null) {
            context.close();
        }
        if (plugin != null && !stopping) {
            // The Bukkit schedulers keep the last task they parsed as their queue head until another one comes: on a
            // quiet server that could be this system's cancelled task, keeping its class loader alive.
            Bukkit.getScheduler().runTask(SystemPlugin.CORE, NOOP);
            Bukkit.getScheduler().runTaskAsynchronously(SystemPlugin.CORE, NOOP);
        }
        if (loader != null) {
            try {
                loader.close();
            } catch (final IOException e) {
                LOGGER.log(Level.WARNING, "Системы: не удалось закрыть " + loader, e);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Cancels the plugin's Bukkit scheduler tasks one by one. {@code BukkitScheduler.cancelTasks(plugin)} queues a
     * task that holds the plugin and stays the scheduler queue's head until another task is scheduled, which would
     * keep a disabled system's class loader alive on a quiet server; cancelling by id holds only the id.
     */
    static void cancelBukkitTasks(final SystemPlugin plugin) {
        for (final org.bukkit.scheduler.BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
            if (task.getOwner() == plugin) {
                task.cancel();
            }
        }
    }

    private static void syncCommands() {
        if (Bukkit.getServer() instanceof org.bukkit.craftbukkit.CraftServer server) {
            server.syncCommands();
        }
    }

    /** Help topics hold Command objects: rebuild them once a system's commands are gone. */
    private static void refreshHelp() {
        if (Bukkit.getHelpMap() instanceof org.bukkit.craftbukkit.help.SimpleHelpMap help) {
            help.clear();
            help.initializeGeneralTopics();
            help.initializeCommands();
        }
    }

    private static String describe(final Throwable e) {
        final String message = e.getMessage();
        return e.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static void rethrowFatal(final Throwable e) {
        if (e instanceof VirtualMachineError error && !(e instanceof StackOverflowError)) {
            throw error;
        }
    }
}
