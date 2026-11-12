package ink.hoka.amazake.cpu;

import ca.spottedleaf.moonrise.common.PlatformHooks;
import ca.spottedleaf.moonrise.common.util.MoonriseCommon;
import com.sun.management.HotSpotDiagnosticMXBean;
import io.papermc.paper.configuration.GlobalConfiguration;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The core layout of this server: plans it from amazake.yml ({@code cpu.layout}), hands it to the
 * {@link AffinityManager} and sizes Paper's chunk worker pool to the chunks CPUs. Called by
 * AmazakeConfig on every load and /amazake reload; returns at once, the pinning runs on its own thread.
 */
public final class Cores {

    private static final Logger LOGGER = Logger.getLogger("Amazake");

    /** What /amazake cores shows. */
    public record Status(CoreLayout planned, CoreLayout active, String disabledRu, List<String> warningsRu,
                         String chunkPoolRu, AffinityManager manager) {
    }

    private static AffinityManager manager;
    private static boolean shutdownHook;
    private static volatile Status status = new Status(null, null, "раскладка ещё не настроена", List.of(), null, null);
    /** Worker count we gave Paper's chunk pool; -1 while the pool has Paper's own size. */
    private static int chunkWorkers = -1;
    /** The last settings, for a re-plan after CPUs went offline. */
    private static Settings last;

    private record Settings(boolean enabled, Map<Role, String> overrides, boolean resizeChunkPool, boolean ignoreProcessorCount) {
    }

    private Cores() {
    }

    /**
     * Applies amazake.yml's cpu.layout. Called on the main thread: does no process work here (taskset
     * runs on the manager's own thread), only small reads of /proc and /sys.
     *
     * @param overrides            cpu.layout.&lt;role&gt; values ("auto" or CPU lists)
     * @param ignoreProcessorCount cpu.layout.ignore-processor-count: pin even without -XX:ActiveProcessorCount
     */
    public static synchronized void configure(final boolean enabled, final Map<Role, String> overrides, final boolean resizeChunkPool,
                                              final boolean ignoreProcessorCount) {
        last = new Settings(enabled, overrides == null ? Map.of() : Map.copyOf(overrides), resizeChunkPool, ignoreProcessorCount);
        apply(last, true);
    }

    /** Plans again with the last settings after the manager found CPUs gone offline. Runs on the manager's thread. */
    private static synchronized void replan() {
        if (last != null) {
            apply(last, false);
        }
    }

    private static void apply(final Settings settings, final boolean mainThread) {
        try {
            configure0(settings, mainThread);
        } catch (final RuntimeException | LinkageError e) {
            LOGGER.log(Level.WARNING, "Раскладка ядер: ошибка настройки, раскладка выключена", e);
            LOGGER.info("Amazake cores: disabled (error: " + e + ")");
            status = new Status(null, null, "ошибка: " + e, List.of(), null, manager);
            if (manager != null) {
                manager.setLayout(null);
            }
        }
    }

    /**
     * @param mainThread whether this runs on the main thread; the chunk pool is only resized there
     *                   (Paper resizes it from the main thread too), a re-plan keeps its size
     */
    private static void configure0(final Settings settings, final boolean mainThread) {
        if (!isLinux()) {
            disabled("not Linux", "сервер работает не на Linux (" + System.getProperty("os.name") + "), привязка потоков к ядрам есть только там",
                null, List.of(), null);
            return;
        }
        final CpuTopology topology = CpuTopology.detect();
        if (manager == null) {
            manager = new AffinityManager("self", topology.allowed(), true);
            manager.onCpusLost(Cores::replan);
            manager.start(); // per-core load for /amazake cores, even without a layout
        }
        manager.setFullSet(topology.allowed());
        if (!shutdownHook) {
            shutdownHook = true;
            final AffinityManager toStop = manager;
            Runtime.getRuntime().addShutdownHook(new Thread(() -> toStop.shutdown(3_000L), "Amazake Cores Shutdown"));
        }
        final String keepPool = status.chunkPoolRu();
        if (!settings.enabled()) {
            manager.setLayout(null);
            disabled("off in amazake.yml", "выключена в amazake.yml (cpu.layout.enabled: false)", null, List.of(), pool(mainThread, null, topology, keepPool));
            return;
        }
        final String missing = AffinityManager.tasksetMissing();
        if (missing != null) {
            manager.setLayout(null);
            disabled("taskset not found", missing, null, List.of(), pool(mainThread, null, topology, keepPool));
            return;
        }
        final LayoutPlanner.Plan plan = LayoutPlanner.plan(topology, settings.overrides());
        for (final String warning : plan.warningsRu()) {
            LOGGER.warning("Раскладка ядер: " + warning);
        }
        if (!plan.enabled()) {
            manager.setLayout(null);
            disabled(plan.disabledEn(), plan.disabledRu(), null, plan.warningsRu(), pool(mainThread, null, topology, keepPool));
            return;
        }
        final CoreLayout layout = plan.layout();
        final List<String> warnings = new ArrayList<>(plan.warningsRu());
        // The JVM answers availableProcessors() from the calling thread's CPU set: once pinned, the main
        // thread would see 1 CPU, and so would every pool sized from it later (plugins' onEnable runs on
        // the main thread, Netty caches its first answer). -XX:ActiveProcessorCount makes the answer
        // fixed; without it the layout is only planned, unless the owner says otherwise.
        ForkJoinPool.commonPool();
        if (!activeProcessorCountSet()) {
            final String flag = "-XX:ActiveProcessorCount=" + topology.processorCount();
            if (!settings.ignoreProcessorCount()) {
                manager.setLayout(null);
                final String why = "добавьте в флаги запуска " + flag + ". Без него Java после закрепления потоков считает,"
                    + " что ядер столько, сколько у потока (у главного - 1), и плагины делают пулы потоков слишком"
                    + " маленькими. Раскладка рассчитана, но не применена (или задайте cpu.layout.ignore-processor-count: true).";
                disabled("add " + flag, why, layout, warnings, pool(mainThread, null, topology, keepPool));
                for (final String line : layout.tableRu()) {
                    LOGGER.info(line);
                }
                return;
            }
            final String hint = "нет флага " + flag + " (cpu.layout.ignore-processor-count: true): после закрепления"
                + " Java считает ядра по потоку, и пулы потоков, созданные потом, будут меньше.";
            LOGGER.warning("Раскладка ядер: " + hint);
            warnings.add(hint);
        }
        manager.setLayout(layout);
        final String pool = settings.resizeChunkPool()
            ? pool(mainThread, layout, topology, keepPool)
            : pool(mainThread, null, topology, keepPool);
        LOGGER.info("Amazake cores: " + layout + " (" + topology.logical() + " CPUs" + (topology.smt() ? ", SMT" : "")
            + (topology.quota() > 0.0 ? ", quota " + LayoutPlanner.formatQuota(topology.quota()) : "") + ")");
        for (final String line : layout.tableRu()) {
            LOGGER.info(line);
        }
        if (pool != null) {
            LOGGER.info("  " + pool);
        }
        status = new Status(layout, null, null, List.copyOf(warnings), pool, manager);
    }

    /** Sizes the chunk pool for {@code layout} (null: Paper's own size); off the main thread keeps it as it is. */
    private static String pool(final boolean mainThread, final CoreLayout layout, final CpuTopology topology, final String keep) {
        if (!mainThread) {
            return keep;
        }
        if (layout == null) {
            return restoreChunkPool();
        }
        int cpus = layout.cpus(Role.CHUNKS).size();
        if (topology.quotaLimited()) {
            cpus = Math.min(cpus, topology.processorCount());
        }
        return resizeChunkPool(cpus);
    }

    private static void disabled(final String en, final String ru, final CoreLayout planned, final List<String> warnings, final String pool) {
        LOGGER.info("Amazake cores: disabled (" + en + ")");
        LOGGER.info("Раскладка ядер выключена: " + ru);
        if (pool != null) {
            LOGGER.info("  " + pool);
        }
        status = new Status(planned, null, ru, List.copyOf(warnings), pool, manager);
    }

    /** Whether the JVM was started with -XX:ActiveProcessorCount (then availableProcessors() ignores affinity). */
    private static boolean activeProcessorCountSet() {
        try {
            final HotSpotDiagnosticMXBean bean = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
            return bean != null && Integer.parseInt(bean.getVMOption("ActiveProcessorCount").getValue()) > 0;
        } catch (final RuntimeException e) {
            return true; // not HotSpot or no such flag: nothing to suggest
        }
    }

    private static boolean isLinux() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("linux");
    }

    /** The layout state right now. */
    public static Status status() {
        final Status planned = status;
        final AffinityManager current = planned.manager();
        if (current == null) {
            return planned;
        }
        String disabled = planned.disabledRu();
        if (disabled == null && current.failure() != null) {
            disabled = current.failure();
        }
        return new Status(planned.planned(), current.active(), disabled, planned.warningsRu(), planned.chunkPoolRu(), current);
    }

    /** Gives Paper's chunk worker pool one thread per chunks CPU, unless the owner set its size. Returns a note. */
    private static String resizeChunkPool(final int cpus) {
        final GlobalConfiguration config = GlobalConfiguration.get();
        if (config == null || config.chunkSystem == null) {
            return null;
        }
        if (config.chunkSystem.workerThreads > 0) {
            return "Пул чанков: в paper-global.yml задано chunk-system.worker-threads: " + config.chunkSystem.workerThreads + ", размер не меняю.";
        }
        final String property = PlatformHooks.get().getBrand() + ".WorkerThreadCount";
        if (System.getProperty(property) != null) {
            return "Пул чанков: задан -D" + property + "=" + System.getProperty(property) + ", размер не меняю.";
        }
        final int workers = Math.max(1, cpus);
        // Every time, not only when the size changes: /paper reload and Bukkit's /reload give the pool
        // Paper's size back behind our back, and this is cheap (Paper calls it on each of its reloads).
        MoonriseCommon.adjustWorkerThreads(workers, config.chunkSystem.ioThreads);
        chunkWorkers = workers;
        return "Пул чанков: " + workers + " " + threadsWord(workers) + " (по числу ядер chunks; cpu.layout.resize-chunk-pool).";
    }

    /** Gives the chunk pool its size from paper-global.yml back, if we changed it. */
    private static String restoreChunkPool() {
        if (chunkWorkers < 0) {
            return null;
        }
        chunkWorkers = -1;
        final GlobalConfiguration config = GlobalConfiguration.get();
        if (config == null || config.chunkSystem == null) {
            return null;
        }
        MoonriseCommon.adjustWorkerThreads(config.chunkSystem.workerThreads, config.chunkSystem.ioThreads);
        return "Пул чанков: размер снова из paper-global.yml.";
    }

    static String threadsWord(final int n) {
        final int mod100 = n % 100;
        final int mod10 = n % 10;
        if (mod10 == 1 && mod100 != 11) {
            return "поток";
        }
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
            return "потока";
        }
        return "потоков";
    }

    /** Lines for /amazake cores: load per CPU, roles, and where each thread group runs. */
    public static List<String> describe(final Status current, final Map<Integer, Double> load) {
        final List<String> out = new ArrayList<>();
        final CoreLayout layout = current.active() != null ? current.active() : current.planned();
        if (current.disabledRu() != null) {
            out.add("Раскладка ядер выключена: " + current.disabledRu());
            if (current.planned() != null) {
                out.add("Рассчитанная раскладка (не применена): " + current.planned());
            }
        } else if (current.active() == null) {
            out.add("Раскладка ядер: потоки ещё закрепляются (через пару секунд будет готово).");
        } else {
            out.add("Раскладка ядер включена: " + current.active());
        }
        final List<Integer> cpus = new ArrayList<>();
        if (load != null) {
            cpus.addAll(load.keySet());
        } else if (layout != null) {
            cpus.addAll(layout.allowed());
        }
        if (!cpus.isEmpty()) {
            out.add(load == null ? "Ядро  роль" : "Ядро  роль                     нагрузка за секунду");
            for (final int cpu : cpus) {
                final String role = layout == null ? "-" : layout.label(cpu);
                if (load == null) {
                    out.add(String.format("%4d  %s", cpu, role));
                } else {
                    final double busy = load.getOrDefault(cpu, 0.0);
                    out.add(String.format("%4d  %-24s %3d%% %s", cpu, role, Math.round(busy * 100.0), bar(busy)));
                }
            }
        }
        final AffinityManager mgr = current.manager();
        if (mgr != null && !mgr.snapshot().isEmpty()) {
            out.add("Потоки по группам (группа: сколько потоков -> на каких ядрах):");
            final Map<String, Map<String, Integer>> byGroup = new java.util.LinkedHashMap<>();
            for (final String group : ThreadClassifier.GROUPS) {
                byGroup.put(group, new java.util.TreeMap<>());
            }
            for (final AffinityManager.PinnedThread thread : mgr.snapshot()) {
                byGroup.computeIfAbsent(thread.group(), g -> new java.util.TreeMap<>()).merge(thread.cpus(), 1, Integer::sum);
            }
            for (final Map.Entry<String, Map<String, Integer>> entry : byGroup.entrySet()) {
                if (entry.getValue().isEmpty()) {
                    continue;
                }
                final StringBuilder line = new StringBuilder("  ").append(entry.getKey())
                    .append(" (").append(ThreadClassifier.roleOf(entry.getKey()).id()).append("): ");
                boolean first = true;
                for (final Map.Entry<String, Integer> where : entry.getValue().entrySet()) {
                    if (!first) {
                        line.append(", ");
                    }
                    first = false;
                    line.append(where.getValue()).append(" -> [").append(where.getKey()).append(']');
                }
                out.add(line.toString());
            }
        }
        if (current.chunkPoolRu() != null) {
            out.add(current.chunkPoolRu());
        }
        for (final String warning : current.warningsRu()) {
            out.add("Внимание: " + warning);
        }
        return out;
    }

    private static String bar(final double busy) {
        final int filled = (int) Math.round(Math.max(0.0, Math.min(1.0, busy)) * 10.0);
        return "|".repeat(filled) + ".".repeat(10 - filled);
    }
}
