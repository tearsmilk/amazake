package core.amazake.cpu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Pins every thread of a process to the CPUs of its {@link Role}, with util-linux {@code taskset}
 * (no native code). Linux only.
 *
 * <p>Runs on its own daemon thread ({@value #THREAD_NAME}), never on the main thread: right after
 * {@link #setLayout} and then every {@value #SCAN_INTERVAL_MS} ms while a layout is applied (every
 * {@value #IDLE_SCAN_INTERVAL_MS} ms without one) it lists /proc/PID/task, reads each thread's comm,
 * classifies it with {@link ThreadClassifier} and runs {@code taskset -p -c <list> <tid>} for threads
 * that are new or whose CPUs changed. Scanning every second matters: Linux threads start with their
 * creator's CPUs, so a thread the main thread creates runs on the tick CPU until it is scanned. What it
 * applied is remembered per thread id, so a steady-state scan is a directory listing and one comm read
 * per thread; taskset only runs for new or changed threads. Once a second it also reads /proc/stat for
 * the per-core load {@code /amazake cores} shows.</p>
 *
 * <p>Before the first pin it checks {@code taskset --version} on this thread. If taskset is missing or
 * refused (EPERM) the manager says so once, in Russian, and stops pinning. On EINVAL (a CPU went
 * offline) it re-reads the online CPUs and asks for a new plan once before giving up.
 * {@code setLayout(null)} and {@link #shutdown} give every thread the full allowed CPU set back.</p>
 *
 * <p>Reserve and tick moves ({@link #setReserve}): with a layout that has reserve CPUs, every
 * {@code check-seconds} this thread also reads the main thread's CPU time (/proc/PID/task/TID/stat) and
 * hands it with /proc/stat to a {@link ContentionDetector}. When it says MIGRATE, the layout becomes
 * {@link CoreLayout#withTickMovedTo}: the next scan pins "Server thread" to the reserve CPU and takes the
 * old tick CPU out of every thread's set (it is the new, empty reserve). A later {@link #setLayout} with the
 * same tick CPU (a /amazake reload) keeps the moved tick while its CPU is still a reserve CPU of the new
 * layout, so a reload does not pin the main thread back onto the contended CPU.</p>
 *
 * <p>Plugin threads (for this server, pid "self"): each scan asks {@link PluginThreads} which comms are
 * threads plugins created themselves, so they join the plugins role. Plugin threads get
 * {@link ThreadClassifier.Spread#SUPPORT}: the plugins CPUs plus the support (background) CPUs. When the
 * layout lets them spill onto support CPUs, the support threads there that never run plugin code (spark,
 * Amazake, Paper's own housekeeping, unknown threads seen for a while) get {@code renice -n <support-nice>}
 * once, so plugin threads win the CPU about 3:1 at nice 5. Nice is only ever raised: without CAP_SYS_NICE
 * it cannot be lowered back, so turning the layout off leaves it as it is.</p>
 */
public final class AffinityManager {

    public static final String THREAD_NAME = "Amazake Cores";
    static final long SCAN_INTERVAL_MS = 1_000L;
    static final long IDLE_SCAN_INTERVAL_MS = 5_000L;
    static final long SAMPLE_INTERVAL_MS = 1_000L;
    static final long TASKSET_TIMEOUT_MS = 3_000L;

    private static final Logger LOGGER = Logger.getLogger("Amazake");
    /** Result of {@code taskset --version}: null until checked, "" when fine, else the problem in Russian. */
    private static volatile String tasksetVersionProblem;

    /** Most moves remembered for {@link #migrations()}. */
    static final int MIGRATIONS_KEPT = 200;

    /**
     * cpu.reserve settings.
     *
     * @param enabled    check for foreign load and move the tick
     * @param checkMs    time between two checks
     * @param threshold  foreign load (fraction of one CPU) that counts as contention
     * @param cooldownMs no second move within this time
     */
    public record ReserveSettings(boolean enabled, long checkMs, double threshold, long cooldownMs) {
        public static final ReserveSettings OFF = new ReserveSettings(false, 2_000L, 0.25, 30_000L);
    }

    /**
     * A move of the tick.
     *
     * @param wallMs      System.currentTimeMillis() of the move
     * @param from        the old tick CPU (now reserve)
     * @param to          the new tick CPU
     * @param foreignLoad foreign load on {@code from} over the last check (fraction of one CPU)
     * @param steal       steal fraction on {@code from} over the last check
     */
    public record Migration(long wallMs, int from, int to, double foreignLoad, double steal) {
    }

    /** The last reserve check, for /amazake cores. */
    public record ReserveCheck(long wallMs, ContentionDetector.Result result) {
    }

    /** A thread as the last scan saw it. {@code cpus} is its CPU list read back from the kernel. */
    public record PinnedThread(int tid, String comm, String group, Role role, String cpus) {
    }

    private enum Outcome { OK, VANISHED, REFUSED, INVALID, FAILED }

    /**
     * What a scan did to a thread.
     *
     * @param niced     whether its nice was raised to support-nice (or found at least that high)
     * @param firstSeen System.nanoTime() of the first scan that saw this thread with this comm
     */
    private record Applied(String comm, String cpus, boolean niced, long firstSeen) {
    }

    /** The process's nice when the manager started (its first thread's), Integer.MIN_VALUE until read. */
    private int baseNice = Integer.MIN_VALUE;
    /** cpu.layout.support-nice by default. */
    public static final int DEFAULT_SUPPORT_NICE = 5;

    private final Path tasks;
    private volatile String fullSet;
    private final boolean publishCurrent;
    /** Called on this manager's thread after EINVAL when CPUs went offline: Cores plans again. */
    private volatile Runnable onCpusLost;
    private final Object lock = new Object();

    // Guarded by lock.
    private CoreLayout target;
    /** The last layout passed to {@link #setLayout}, before any move of the tick. */
    private CoreLayout planned;
    private long targetVersion;
    private boolean exit;
    private Thread thread;

    // Owned by the worker thread.
    private final Map<Integer, Applied> applied = new HashMap<>();
    /** Finds plugin-created threads; null when this manager pins another process. */
    private final PluginThreads pluginThreads;
    /** Why renice cannot be used (logged once), or null. */
    private String niceProblem;
    private boolean everNiced;
    private boolean niceKeptLogged;
    private boolean pinned;
    private boolean otherFailureLogged;
    private boolean replanned;
    private ContentionDetector detector;
    private ReserveSettings detectorSettings;
    private long nextReserveCheck;
    private boolean reserveCheckScheduled;
    private boolean cannotMoveLogged;

    // Published to readers.
    private volatile List<PinnedThread> snapshot = List.of();
    private volatile Map<Integer, Double> load = Map.of();
    private volatile long loadAtNanos;
    private volatile CoreLayout active;
    private volatile String failure;
    private volatile int scans;
    private volatile ReserveSettings reserveSettings = ReserveSettings.OFF;
    private volatile int supportNice = DEFAULT_SUPPORT_NICE;
    private volatile int nicedThreads;
    private volatile String niceProblemRu;
    private volatile ReserveCheck reserveCheck;
    /** Why the reserve check cannot measure or move right now, in Russian; null when it can. */
    private volatile String reserveStatusRu;
    private final java.util.Deque<Migration> migrations = new java.util.ArrayDeque<>();

    /**
     * @param pid            the process whose threads to pin; "self" for this server
     * @param fullSet        every CPU the process may use: what threads get back when unpinned
     * @param publishCurrent whether to publish the applied layout as {@link CoreLayout#current()}
     */
    public AffinityManager(final String pid, final List<Integer> fullSet, final boolean publishCurrent) {
        this.tasks = Path.of("/proc", pid, "task");
        this.fullSet = CpuListParser.format(fullSet);
        this.publishCurrent = publishCurrent;
        this.pluginThreads = "self".equals(pid) ? new PluginThreads() : null;
    }

    /** cpu.layout.support-nice (0..19; 0: no priorities). Takes effect for threads not reniced yet. */
    public void setSupportNice(final int nice) {
        this.supportNice = Math.max(0, Math.min(19, nice));
    }

    public int supportNice() {
        return this.supportNice;
    }

    /** How many live threads run at support-nice now (reniced by us or found so). */
    public int nicedThreads() {
        return this.nicedThreads;
    }

    /** Why priorities are not set (renice missing or refused), in Russian; null when they work. */
    public String niceProblemRu() {
        return this.niceProblemRu;
    }

    /** Plugin-created threads the last scan found ({@link ThreadClassifier#PLUGINS_OWN}); 0 for another process. */
    public int pluginThreadCount() {
        return this.pluginThreads == null ? 0 : this.pluginThreads.count();
    }

    /** Java thread ids of plugin-created threads, for the profiler's thread groups. */
    public java.util.Set<Long> pluginThreadIds() {
        return this.pluginThreads == null ? java.util.Set.of() : this.pluginThreads.javaIds();
    }

    /**
     * Null when a taskset executable is on PATH (or this is not Linux, which Cores checks itself);
     * otherwise why pinning cannot work, in Russian. Only looks at files: safe on the main thread.
     */
    public static String tasksetMissing() {
        if (!Files.isDirectory(Path.of("/proc/self/task"))) {
            return "нет /proc/self/task";
        }
        final String path = System.getenv("PATH");
        final String dirs = path == null || path.isBlank() ? "/usr/bin:/bin:/usr/sbin:/sbin" : path;
        for (final String dir : dirs.split(java.io.File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            try {
                final Path candidate = Path.of(dir, "taskset");
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return null;
                }
            } catch (final RuntimeException e) {
                // a malformed PATH entry
            }
        }
        return "не найдена команда taskset (поставьте пакет util-linux)";
    }

    /** Runs {@code taskset --version} once per JVM; null when it works. Only on the manager's thread. */
    private static String checkTasksetVersion() {
        final String known = tasksetVersionProblem;
        if (known != null) {
            return known.isEmpty() ? null : known;
        }
        String problem = "";
        try {
            final Process process = command("taskset", "--version").start();
            process.getOutputStream().close();
            if (!process.waitFor(TASKSET_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                problem = "taskset --version не ответил за " + TASKSET_TIMEOUT_MS + " мс";
            } else {
                final String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (process.exitValue() != 0) {
                    problem = "taskset --version завершился с кодом " + process.exitValue() + ": " + out;
                } else if (!out.contains("util-linux")) {
                    LOGGER.warning("Раскладка ядер: taskset не из util-linux (" + out + "), пробую всё равно.");
                }
            }
        } catch (final IOException e) {
            problem = "не запускается команда taskset (поставьте пакет util-linux): " + e.getMessage();
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return "проверку taskset прервали";
        }
        tasksetVersionProblem = problem;
        return problem.isEmpty() ? null : problem;
    }

    private static ProcessBuilder command(final String... args) {
        final ProcessBuilder builder = new ProcessBuilder(args);
        builder.redirectErrorStream(true);
        builder.environment().put("LC_ALL", "C"); // English error texts, so they can be recognised
        return builder;
    }

    /**
     * Pins threads to {@code layout}, or with null gives every thread the full CPU set back. Returns at
     * once; the work happens on the manager's thread, which this starts when needed.
     *
     * <p>When the tick has moved to a reserve CPU since the last call, and {@code layout} has the same tick
     * CPU as that call's layout and still has the CPU the tick moved to in its reserve, the move is kept:
     * the answer is {@code layout} with the tick moved there. Nothing is re-applied when that equals what is
     * already wanted.</p>
     *
     * @return the layout that will be applied ({@code layout}, or {@code layout} with the earlier move kept)
     */
    public CoreLayout setLayout(final CoreLayout layout) {
        synchronized (this.lock) {
            final CoreLayout effective = keepMove(layout, this.planned, this.target);
            this.planned = layout;
            this.ensureThread();
            if (effective != null && effective.equals(this.target)) {
                return effective; // already wanted: keep the detector's baseline and the pins
            }
            this.target = effective;
            this.targetVersion++;
            this.failure = null;
            this.lock.notifyAll();
            return effective;
        }
    }

    /**
     * {@code layout}, or {@code layout} with the tick moved to where {@code current} has it, when the tick
     * moved away from {@code previous}'s tick CPU, {@code layout} keeps that tick CPU, and the CPU the tick
     * moved to is a reserve CPU of {@code layout}.
     */
    static CoreLayout keepMove(final CoreLayout layout, final CoreLayout previous, final CoreLayout current) {
        if (layout == null || previous == null || current == null || !layout.canMoveTick() || !current.canMoveTick()) {
            return layout;
        }
        final int movedTo = current.tickCpu();
        if (layout.tickCpu() != previous.tickCpu() || movedTo == layout.tickCpu() || !layout.isReserve(movedTo)) {
            return layout;
        }
        return layout.withTickMovedTo(movedTo);
    }

    /** The CPU set threads get back when unpinned (after a re-plan with fewer CPUs). */
    public void setFullSet(final List<Integer> cpus) {
        this.fullSet = CpuListParser.format(cpus);
    }

    /** What to run, on this manager's thread, when CPUs went offline and the layout needs a new plan. */
    public void onCpusLost(final Runnable action) {
        this.onCpusLost = action;
    }

    /** cpu.reserve settings; takes effect at the next check. */
    public void setReserve(final ReserveSettings settings) {
        this.reserveSettings = settings == null ? ReserveSettings.OFF : settings;
    }

    public ReserveSettings reserveSettings() {
        return this.reserveSettings;
    }

    /** The last reserve check with a measurement of the current layout, or null (no reserve, not measured yet). */
    public ReserveCheck reserveCheck() {
        return this.reserveCheck;
    }

    /**
     * Why the tick CPU is not being measured or the tick cannot move, in Russian ("главный поток не
     * найден"...), or null when the checks run normally.
     */
    public String reserveStatusRu() {
        return this.reserveStatusRu;
    }

    /** Moves of the tick so far (at most {@value #MIGRATIONS_KEPT}, oldest first). */
    public List<Migration> migrations() {
        synchronized (this.migrations) {
            return List.copyOf(this.migrations);
        }
    }

    /** Starts the manager's thread (load sampling) without pinning anything. */
    public void start() {
        synchronized (this.lock) {
            this.ensureThread();
        }
    }

    private void ensureThread() {
        if (this.exit || (this.thread != null && this.thread.isAlive())) {
            return;
        }
        this.thread = new Thread(this::run, THREAD_NAME);
        this.thread.setDaemon(true);
        this.thread.setPriority(Thread.NORM_PRIORITY + 1);
        this.thread.start();
    }

    /** Restores every thread's full CPU set and stops, waiting at most {@code timeoutMs}. */
    public void shutdown(final long timeoutMs) {
        final Thread worker;
        synchronized (this.lock) {
            this.exit = true;
            worker = this.thread;
            this.lock.notifyAll();
        }
        if (worker != null && worker != Thread.currentThread()) {
            try {
                worker.join(timeoutMs);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** The layout applied by the last scan; null when nothing is pinned. */
    public CoreLayout active() {
        return this.active;
    }

    /** Why the manager gave up pinning (taskset refused), in Russian; null otherwise. */
    public String failure() {
        return this.failure;
    }

    /** Threads as the last scan saw them. */
    public List<PinnedThread> snapshot() {
        return this.snapshot;
    }

    /** Number of scans done so far. */
    public int scans() {
        return this.scans;
    }

    /** Busy fraction of each CPU over the last second, or null when there is no fresh sample. */
    public Map<Integer, Double> recentLoad() {
        final Map<Integer, Double> current = this.load;
        if (current.isEmpty() || System.nanoTime() - this.loadAtNanos > TimeUnit.SECONDS.toNanos(3)) {
            return null;
        }
        return current;
    }

    private void run() {
        Map<Integer, long[]> previousStat = ProcStat.read();
        long seenVersion = -1L;
        long nextScan = 0L;
        try {
            while (true) {
                final CoreLayout want;
                final long version;
                final boolean quit;
                synchronized (this.lock) {
                    want = this.target;
                    version = this.targetVersion;
                    quit = this.exit;
                }
                if (quit) {
                    if (this.pinned) {
                        this.restoreAll();
                    }
                    return;
                }
                final long now = System.nanoTime();
                if (version != seenVersion || now - nextScan >= 0L) {
                    if (version != seenVersion) {
                        if (this.detector != null) {
                            this.detector.reset(); // a new layout: measure from scratch (the cooldown stays)
                        }
                        this.reserveCheck = null; // it measured the old layout's tick CPU
                    }
                    seenVersion = version;
                    this.cycle(want);
                    // Threads started by a pinned thread inherit its CPUs until they are scanned, so
                    // with a layout every second; without one only the snapshot for /amazake cores.
                    final long interval = this.pinned ? SCAN_INTERVAL_MS : IDLE_SCAN_INTERVAL_MS;
                    nextScan = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(interval);
                }
                final Map<Integer, long[]> stat = ProcStat.read();
                if (!stat.isEmpty() && !previousStat.isEmpty()) {
                    this.load = ProcStat.load(previousStat, stat);
                    this.loadAtNanos = System.nanoTime();
                }
                previousStat = stat;
                if (!stat.isEmpty()) {
                    this.reserveTick(stat, seenVersion);
                }
                synchronized (this.lock) {
                    if (!this.exit && this.targetVersion == seenVersion) {
                        this.lock.wait(SAMPLE_INTERVAL_MS);
                    }
                }
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (final RuntimeException e) {
            LOGGER.log(java.util.logging.Level.WARNING, "Раскладка ядер: поток " + THREAD_NAME + " упал", e);
            this.publish(null);
        }
    }

    /**
     * A reserve check when one is due: measures the tick CPU and moves the tick when the detector says so.
     * The move only changes the target layout; the scan right after it does the pinning.
     */
    private void reserveTick(final Map<Integer, long[]> stat, final long seenVersion) {
        final ReserveSettings settings = this.reserveSettings;
        final CoreLayout layout = this.active;
        if (!settings.enabled() || layout == null || layout.reserve().isEmpty()) {
            this.reserveCheck = null;
            this.reserveStatusRu = null;
            return;
        }
        final long nowMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
        if (this.reserveCheckScheduled && nowMs - this.nextReserveCheck < 0L) {
            return;
        }
        this.reserveCheckScheduled = true;
        this.nextReserveCheck = nowMs + Math.max(250L, settings.checkMs());
        if (!layout.canMoveTick()) {
            this.reserveStatusRu = "главный поток не может переехать в резерв: у tick не одно своё ядро"
                + " (cpu.layout.tick задан списком или ядро tick делит другая роль). Чужая нагрузка не проверяется.";
            if (!this.cannotMoveLogged) {
                this.cannotMoveLogged = true;
                LOGGER.info("Amazake cores: reserve " + layout.reserve() + " kept empty, but the tick cannot move (it does not own exactly one CPU)");
                LOGGER.info("Раскладка ядер: резерв [" + CpuListParser.format(layout.reserve()) + "] пустой, но главный поток"
                    + " не может туда переехать: у tick не одно своё ядро (cpu.layout.tick задан списком или делит ядро с другой ролью).");
            }
            return;
        }
        if (this.detector == null || !settings.equals(this.detectorSettings)) {
            final ContentionDetector fresh = new ContentionDetector(settings.threshold(), settings.cooldownMs());
            fresh.inheritFrom(this.detector); // new settings: the cooldown keeps running from the last move
            this.detector = fresh;
            this.detectorSettings = settings;
        }
        final int tickCpu = layout.tickCpu();
        // Reserve CPUs on the tick's own physical core are no place to go; a reserve CPU's siblings that run
        // no server thread must be idle too, or the tick would share its core with someone else.
        final List<Integer> tickSiblings = layout.siblingsOf(tickCpu);
        final List<Integer> targets = new ArrayList<>();
        final Map<Integer, List<Integer>> quiet = new HashMap<>();
        for (final int cpu : layout.reserve()) {
            if (tickSiblings.contains(cpu)) {
                continue;
            }
            targets.add(cpu);
            final List<Integer> idleSiblings = new ArrayList<>();
            for (final int sibling : layout.siblingsOf(cpu)) {
                if (layout.rolesOf(sibling).isEmpty()) {
                    idleSiblings.add(sibling);
                }
            }
            quiet.put(cpu, idleSiblings);
        }
        // Linux gives a thread its creator's name until it sets its own, so a native thread started from the
        // main thread is also "Server thread". The real one is the busiest of them.
        PinnedThread main = null;
        long mainTicks = -1L;
        for (final PinnedThread thread : this.snapshot) {
            if (thread.role() == Role.TICK) {
                final long ticks = ContentionDetector.cpuTicks(this.readStat(thread.tid()));
                if (main == null || ticks > mainTicks) {
                    main = thread;
                    mainTicks = ticks;
                }
            }
        }
        final long ownTicks;
        final int tid;
        if (main == null) {
            tid = -1;
            ownTicks = -1L;
            this.reserveStatusRu = "поток Server thread не найден, чужая нагрузка не измеряется.";
        } else if (!main.cpus().equals(Integer.toString(tickCpu))) {
            tid = -1;
            ownTicks = -1L; // not pinned yet: warm-up
            this.reserveStatusRu = "главный поток ещё не закреплён за ядром " + tickCpu + " (сейчас ядра [" + main.cpus()
                + "]), чужая нагрузка не измеряется.";
        } else if (targets.isEmpty()) {
            tid = main.tid();
            ownTicks = ContentionDetector.cpuTicks(this.readStat(tid));
            this.reserveStatusRu = "все резервные ядра [" + CpuListParser.format(layout.reserve()) + "] - второй поток"
                + " физического ядра tick, переезд туда не поможет.";
        } else {
            tid = main.tid();
            ownTicks = ContentionDetector.cpuTicks(this.readStat(tid));
            this.reserveStatusRu = null;
        }
        final ContentionDetector.Result result = this.detector.check(nowMs, tickCpu, targets, quiet, stat, tid, ownTicks, gcPauseMs());
        if (result.measured()) {
            this.reserveCheck = new ReserveCheck(System.currentTimeMillis(), result);
        }
        switch (result.kind()) {
            case MIGRATE -> this.moveTick(layout, result, seenVersion);
            case NO_FREE_RESERVE -> {
                final String again = result.suppressed() > 0
                    ? " (" + result.suppressed() + " more time(s) since the last report)" : "";
                LOGGER.info("Amazake cores: tick cpu" + tickCpu + " is contended (foreign load " + pct(result.foreign())
                    + ", steal " + pct(result.steal()) + "), but no reserve CPU is idle " + result.reserveLoad() + again);
                LOGGER.warning("Раскладка ядер: на ядре " + tickCpu + " главному потоку мешает чужая нагрузка ("
                    + causesRu(result) + "), но все резервные ядра [" + CpuListParser.format(layout.reserve())
                    + "] тоже заняты, переезжать некуда. Посмотрите в top/htop, что ещё работает на машине."
                    + (result.suppressed() > 0 ? " С прошлого такого сообщения это повторялось ещё " + result.suppressed()
                        + " раз; следующее - не раньше чем через "
                        + TimeUnit.MILLISECONDS.toMinutes(ContentionDetector.NO_FREE_REPORT_MS) + " мин." : ""));
            }
            default -> {
            }
        }
    }

    /** Pause collectors (not the "... Cycles" beans of ZGC and Shenandoah, which count concurrent work). */
    private static final List<java.lang.management.GarbageCollectorMXBean> PAUSE_COLLECTORS = pauseCollectors();

    private static List<java.lang.management.GarbageCollectorMXBean> pauseCollectors() {
        final List<java.lang.management.GarbageCollectorMXBean> out = new ArrayList<>();
        try {
            for (final java.lang.management.GarbageCollectorMXBean bean : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                if (!bean.getName().contains("Cycles")) {
                    out.add(bean);
                }
            }
        } catch (final RuntimeException | LinkageError e) {
            return List.of();
        }
        return List.copyOf(out);
    }

    /** The JVM's cumulative stop-the-world GC pause time in ms, or -1 when unknown. */
    static long gcPauseMs() {
        if (PAUSE_COLLECTORS.isEmpty()) {
            return -1L;
        }
        long total = 0L;
        for (final java.lang.management.GarbageCollectorMXBean bean : PAUSE_COLLECTORS) {
            final long time = bean.getCollectionTime();
            if (time < 0L) {
                return -1L;
            }
            total += time;
        }
        return total;
    }

    /** "97% ядра: другие процессы 90%, прерывания 5%, steal 2%" - the parts of the foreign load that are at least 1%. */
    static String causesRu(final ContentionDetector.Result result) {
        final String parts = causePartsRu(result);
        return pct(result.foreign()) + " ядра" + (parts.isEmpty() ? "" : ": " + parts);
    }

    /** "другие процессы 90%, прерывания 5%" - the parts of the foreign load that are at least 1%; may be empty. */
    static String causePartsRu(final ContentionDetector.Result result) {
        final List<String> parts = new ArrayList<>();
        final double foreign = result.foreign();
        if (!(foreign >= 0.01)) {
            return ""; // nothing foreign (or not measured): no noise like "steal 1%"
        }
        // irq and steal are measured apart from the GC correction: never show more than the foreign load.
        final double irq = Math.min(result.irq(), foreign);
        final double steal = Math.min(result.steal(), foreign - irq);
        final double processes = Math.max(0.0, foreign - irq - steal);
        if (processes >= 0.005) {
            parts.add("другие процессы " + pct(processes));
        }
        if (irq >= 0.005) {
            parts.add("прерывания " + pct(irq));
        }
        if (steal >= 0.005) {
            parts.add("гипервизор забирал (steal) " + pct(steal));
        }
        return String.join(", ", parts);
    }

    private void moveTick(final CoreLayout layout, final ContentionDetector.Result result, final long seenVersion) {
        final CoreLayout moved = layout.withTickMovedTo(result.to());
        synchronized (this.lock) {
            if (this.targetVersion != seenVersion || !layout.equals(this.target)) {
                return; // Cores set a new layout meanwhile: it wins
            }
            this.target = moved;
            this.targetVersion++;
        }
        final Migration migration = new Migration(System.currentTimeMillis(), result.from(), result.to(), result.foreign(), result.steal());
        synchronized (this.migrations) {
            this.migrations.addLast(migration);
            while (this.migrations.size() > MIGRATIONS_KEPT) {
                this.migrations.removeFirst();
            }
        }
        LOGGER.info("Amazake cores: tick moved cpu" + result.from() + " -> cpu" + result.to() + " (foreign load "
            + pct(result.foreign()) + ", steal " + pct(result.steal()) + ")");
        LOGGER.info("Раскладка ядер: на ядре " + result.from() + " главному потоку мешала чужая нагрузка ("
            + causesRu(result) + "), поэтому главный поток переехал на резервное ядро " + result.to() + ". Ядро " + result.from()
            + " стало резервом: потоки сервера с него убраны. Следующий переезд - не раньше чем через "
            + TimeUnit.MILLISECONDS.toSeconds(this.reserveSettings.cooldownMs()) + " с.");
    }

    private static String pct(final double fraction) {
        return Double.isNaN(fraction) ? "?" : Math.round(fraction * 100.0) + "%";
    }

    private String readStat(final int tid) {
        try {
            // Lenient decode: stat holds the comm, which may end inside a character (see readComm).
            return new String(Files.readAllBytes(this.tasks.resolve(Integer.toString(tid)).resolve("stat")), StandardCharsets.UTF_8);
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    private void cycle(final CoreLayout want) {
        if (want == null) {
            if (this.pinned) {
                this.restoreAll();
            } else {
                this.scan(null);
            }
            return;
        }
        final String problem = checkTasksetVersion();
        if (problem != null) {
            this.giveUp(problem, "taskset is not usable");
            return;
        }
        if (!this.scan(want)) {
            return;
        }
        this.publish(want);
    }

    /**
     * Reads every thread and, with a layout, pins the ones that need it. Returns false when taskset
     * was refused and the manager disabled itself.
     */
    private boolean scan(final CoreLayout layout) {
        if (layout != null) {
            this.pinned = true;
        }
        final List<PinnedThread> seen = new ArrayList<>();
        final Set<Integer> alive = new HashSet<>();
        final Set<String> pluginComms = this.pluginThreads == null || layout == null ? Set.of() : this.pluginThreads.refresh();
        // Priorities matter only where plugin threads share CPUs with support threads.
        final int nice = this.supportNice;
        final boolean prioritize = layout != null && nice > 0 && this.niceProblem == null && !layout.spillCpus().isEmpty();
        final long now = System.nanoTime();
        int niced = 0;
        for (final int tid : this.listTids()) {
            final String comm = this.readComm(tid);
            if (comm == null) {
                continue; // ended while listing
            }
            final String group = ThreadClassifier.classify(comm, pluginComms);
            final Role role = ThreadClassifier.roleOf(group);
            final String wanted = layout == null ? null : CpuListParser.format(layout.cpus(role, ThreadClassifier.spread(comm, group)));
            final Applied previous = this.applied.get(tid);
            final boolean sameThread = previous != null && previous.comm().equals(comm);
            final long firstSeen = sameThread ? previous.firstSeen() : now;
            boolean isNiced = sameThread && previous.niced();
            String current;
            if (wanted != null && sameThread && previous.cpus().equals(wanted)) {
                current = wanted;
            } else {
                current = this.readMask(tid);
                if (current == null) {
                    continue;
                }
                if (wanted != null && !wanted.equals(current)) {
                    final String[] message = new String[1];
                    final Outcome outcome = this.taskset(tid, wanted, message);
                    switch (outcome) {
                        case OK -> current = wanted;
                        case VANISHED -> {
                            continue;
                        }
                        case REFUSED -> {
                            this.giveUp("taskset отказал для потока " + tid + " (" + comm + "): " + message[0],
                                "taskset refused: " + message[0]);
                            return false;
                        }
                        case INVALID -> {
                            if (!this.cpusLost(wanted)) {
                                this.giveUp("taskset отказал для потока " + tid + " (" + comm + "): " + message[0],
                                    "taskset refused: " + message[0]);
                            }
                            return false;
                        }
                        case FAILED -> {
                            if (!this.otherFailureLogged) {
                                this.otherFailureLogged = true;
                                LOGGER.warning("Раскладка ядер: не удалось закрепить поток " + tid + " (" + comm + ") за ["
                                    + wanted + "]: " + message[0] + ". Попробую снова при следующей проверке.");
                            }
                        }
                    }
                }
                if (wanted != null && wanted.equals(current)) {
                    this.applied.put(tid, new Applied(comm, wanted, isNiced, firstSeen));
                }
            }
            if (prioritize && !isNiced && mustKeepPriority(comm, role)) {
                // Safety net: a demotion leaked through nice inheritance. Stop demoting for good.
                final int threadNice = niceOf(this.readStat(tid));
                if (threadNice != Integer.MIN_VALUE && threadNice > this.baseNice()) {
                    this.niceFailed("поток " + tid + " (" + comm + ") унаследовал пониженный приоритет nice " + threadNice
                        + " от фонового потока; понижение приоритетов отключено до перезапуска");
                }
            }
            if (prioritize && this.niceProblem == null && !isNiced && wanted != null && wanted.equals(current)
                && role == Role.BACKGROUND && demotable(comm, group)) {
                isNiced = this.raiseNice(tid, comm, nice);
                if (isNiced) {
                    this.applied.put(tid, new Applied(comm, wanted, true, firstSeen));
                }
            }
            if (isNiced) {
                niced++;
            }
            alive.add(tid);
            seen.add(new PinnedThread(tid, comm, group, role, current));
        }
        this.applied.keySet().retainAll(alive);
        this.nicedThreads = niced;
        this.snapshot = List.copyOf(seen);
        this.scans++;
        return true;
    }

    /**
     * EINVAL for {@code wanted}: when some of the layout's CPUs went offline, re-reads the topology and
     * lets Cores plan again (once per manager). Returns false when that is not the reason.
     */
    private boolean cpusLost(final String wanted) {
        final Runnable replan = this.onCpusLost;
        if (this.replanned || replan == null) {
            return false;
        }
        final List<Integer> before = CpuTopology.detect().allowed();
        final List<Integer> after = CpuTopology.refresh().allowed();
        if (after.size() >= before.size()) {
            return false;
        }
        this.replanned = true;
        final List<Integer> lost = new ArrayList<>(before);
        lost.removeAll(after);
        LOGGER.warning("Раскладка ядер: ядра [" + CpuListParser.format(lost) + "] больше недоступны (выключены),"
            + " taskset не смог закрепить поток за [" + wanted + "]. Раскладка пересчитана для ядер ["
            + CpuListParser.format(after) + "].");
        this.fullSet = CpuListParser.format(after);
        replan.run();
        return true;
    }

    private void giveUp(final String reason, final String english) {
        this.failure = reason;
        LOGGER.warning("Раскладка ядер выключена: " + reason
            + ". Обычно так бывает в контейнере без права менять привязку потоков (EPERM) или когда ядра заняты"
            + " ограничением cpuset (EINVAL). Сервер работает дальше без раскладки.");
        LOGGER.info("Amazake cores: disabled (" + english + ")");
        synchronized (this.lock) {
            this.target = null;
        }
        this.restoreAll();
    }

    /** Gives every thread the full allowed CPU set back. */
    private void restoreAll() {
        // A thread created during a pass by a not yet restored thread starts with that thread's CPUs
        // (the JVM adds GC workers at any time): list again until a pass finds nothing to restore.
        final String full = this.fullSet;
        passes:
        for (int pass = 0; pass < 5; pass++) {
            boolean changed = false;
            for (final int tid : this.listTids()) {
                final String current = this.readMask(tid);
                if (current == null || current.equals(full)) {
                    continue;
                }
                final Outcome outcome = this.taskset(tid, full, new String[1]);
                if (outcome == Outcome.REFUSED) {
                    break passes; // nothing more can be done
                }
                changed |= outcome == Outcome.OK;
            }
            if (!changed) {
                break;
            }
        }
        this.applied.clear();
        this.pinned = false;
        if (this.everNiced && !this.niceKeptLogged) {
            this.niceKeptLogged = true;
            LOGGER.info("Раскладка ядер: приоритеты фоновых потоков (nice) оставлены как есть - без права CAP_SYS_NICE"
                + " понизить nice обратно нельзя. Вернутся к 0 после перезапуска сервера.");
        }
        this.publish(null);
        this.scan(null);
    }

    /**
     * Whether a support thread may run at a lower priority. Only threads known to be server housekeeping that
     * never run plugin code are demoted: spark, Amazake's own threads and a few Paper/vanilla leaf threads.
     * Unknown ("other") threads are never demoted: an idle plugin pool (a database pool, a timer, an executor)
     * looks exactly like one, a demotion cannot be undone without CAP_SYS_NICE, and Linux copies nice to every
     * thread the demoted one creates (pool workers created by a submitting thread included). "Server console
     * handler" runs plugins' tab-complete listeners, the watchdogs must never be slowed down, and "Paper Async
     * Task Handler" may run plugin callbacks, so they keep their priority too.
     */
    static boolean demotable(final String comm, final String group) {
        return switch (group) {
            case ThreadClassifier.SPARK, ThreadClassifier.AMAZAKE -> true;
            case ThreadClassifier.PAPER_ASYNC -> comm.startsWith("Timer hack thre") || comm.startsWith("Chat-Filter-Wor")
                || comm.startsWith("Download-");
            default -> false;
        };
    }

    /**
     * Threads whose nice must stay at the process's original value: plugin threads and the JDK's shared pools
     * (CompletableFuture/parallel streams). If one of them is found reniced although Amazake did not demote it,
     * it inherited the nice from a demoted thread that created it.
     */
    static boolean mustKeepPriority(final String comm, final Role role) {
        return role == Role.PLUGINS || comm.startsWith("ForkJoinPool");
    }

    /** Nice of a thread: field 19 of /proc/PID/task/TID/stat; Integer.MIN_VALUE when unknown. */
    static int niceOf(final String stat) {
        if (stat == null) {
            return Integer.MIN_VALUE;
        }
        final int close = stat.lastIndexOf(')');
        if (close < 0) {
            return Integer.MIN_VALUE;
        }
        final String[] fields = stat.substring(close + 1).trim().split("\\s+");
        // fields[0] is field 3 (state), so field 19 (nice) is fields[16].
        if (fields.length < 17) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(fields[16]);
        } catch (final NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Raises the thread's nice to {@code nice} unless it is at least that already. Never lowers it.
     * Returns whether the thread now runs at {@code nice} or higher.
     */
    private boolean raiseNice(final int tid, final String comm, final int nice) {
        final int current = niceOf(this.readStat(tid));
        if (current == Integer.MIN_VALUE) {
            return false; // gone
        }
        if (current >= nice) {
            return true;
        }
        final Process process;
        try {
            process = command("renice", "-n", Integer.toString(nice), "-p", Integer.toString(tid)).start();
        } catch (final IOException e) {
            this.niceFailed("не запускается команда renice (пакет util-linux): " + e.getMessage());
            return false;
        }
        try (var stdout = process.getInputStream()) {
            process.getOutputStream().close();
            if (!process.waitFor(TASKSET_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                this.niceFailed("renice не ответил за " + TASKSET_TIMEOUT_MS + " мс");
                return false;
            }
            final String out = new String(stdout.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() == 0) {
                this.everNiced = true;
                return true;
            }
            if (out.toLowerCase(Locale.ROOT).contains("no such process") || !Files.isDirectory(this.tasks.resolve(Integer.toString(tid)))) {
                return false;
            }
            this.niceFailed("renice -n " + nice + " для потока " + tid + " (" + comm + "): " + (out.isEmpty() ? "код " + process.exitValue() : out));
            return false;
        } catch (final IOException e) {
            this.niceFailed("renice: " + e.getMessage());
            return false;
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The nice threads normally have in this process: the first thread's (the JVM's initial thread) at the first call. */
    private int baseNice() {
        if (this.baseNice == Integer.MIN_VALUE) {
            final int pidNice = niceOf(this.readStatFile(this.tasks.getParent().resolve("stat")));
            this.baseNice = pidNice == Integer.MIN_VALUE ? 0 : pidNice;
        }
        return this.baseNice;
    }

    private String readStatFile(final Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    private void niceFailed(final String why) {
        this.niceProblem = why;
        this.niceProblemRu = why;
        LOGGER.warning("Раскладка ядер: приоритеты потоков не выставляются (" + why + "). Потоки плагинов делят"
            + " ядро-подложку с фоном на равных; остальное работает как обычно.");
    }

    private void publish(final CoreLayout layout) {
        this.active = layout;
        if (this.publishCurrent) {
            CoreLayout.setCurrent(layout);
        }
    }

    private List<Integer> listTids() {
        final List<Integer> out = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(this.tasks)) {
            for (final Path task : stream) {
                try {
                    out.add(Integer.parseInt(task.getFileName().toString()));
                } catch (final NumberFormatException e) {
                    // not a thread
                }
            }
        } catch (final IOException | RuntimeException e) {
            // the process is gone
        }
        out.sort(null);
        return out;
    }

    private String readComm(final int tid) {
        try {
            // Bytes, not readString: the kernel cuts a non-ASCII name inside a character, which readString rejects.
            final byte[] bytes = Files.readAllBytes(this.tasks.resolve(Integer.toString(tid)).resolve("comm"));
            return PluginThreads.decodeComm(bytes, bytes.length);
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    /** The thread's CPU list in canonical form, or null when the thread is gone. */
    private String readMask(final int tid) {
        try {
            // Lenient decode: the Name: line holds the comm, which may end inside a character (see readComm).
            final String status = new String(Files.readAllBytes(this.tasks.resolve(Integer.toString(tid)).resolve("status")), StandardCharsets.UTF_8);
            for (final String line : status.split("\n")) {
                if (line.startsWith("Cpus_allowed_list:")) {
                    return CpuListParser.format(CpuListParser.parse(line.substring("Cpus_allowed_list:".length()).trim()));
                }
            }
        } catch (final IOException | RuntimeException e) {
            return null;
        }
        return null;
    }

    private Outcome taskset(final int tid, final String cpus, final String[] message) {
        final Process process;
        try {
            process = command("taskset", "-p", "-c", cpus, Integer.toString(tid)).start();
        } catch (final IOException e) {
            message[0] = "не запустился taskset: " + e.getMessage();
            return Outcome.REFUSED;
        }
        try {
            process.getOutputStream().close(); // taskset reads nothing
        } catch (final IOException e) {
            // already closed
        }
        try (var stdout = process.getInputStream()) {
            if (!process.waitFor(TASKSET_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                message[0] = "taskset не ответил за " + TASKSET_TIMEOUT_MS + " мс";
                return Outcome.FAILED;
            }
            // The output is a couple of lines: it fits the pipe, so reading after the exit is safe.
            final String out = new String(stdout.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (process.exitValue() == 0) {
                return Outcome.OK;
            }
            message[0] = out.isEmpty() ? "код " + process.exitValue() : out;
            final String lower = out.toLowerCase(Locale.ROOT);
            if (lower.contains("no such process") || !Files.isDirectory(this.tasks.resolve(Integer.toString(tid)))) {
                return Outcome.VANISHED;
            }
            if (lower.contains("operation not permitted")) {
                return Outcome.REFUSED;
            }
            if (lower.contains("invalid argument")) {
                return Outcome.INVALID;
            }
            return Outcome.FAILED;
        } catch (final IOException e) {
            message[0] = e.getMessage();
            return Outcome.FAILED;
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            message[0] = "прервано";
            return Outcome.FAILED;
        }
    }
}
