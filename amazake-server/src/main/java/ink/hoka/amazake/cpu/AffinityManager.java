package ink.hoka.amazake.cpu;

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

    /** A thread as the last scan saw it. {@code cpus} is its CPU list read back from the kernel. */
    public record PinnedThread(int tid, String comm, String group, Role role, String cpus) {
    }

    private enum Outcome { OK, VANISHED, REFUSED, INVALID, FAILED }

    private record Applied(String comm, String cpus) {
    }

    private final Path tasks;
    private volatile String fullSet;
    private final boolean publishCurrent;
    /** Called on this manager's thread after EINVAL when CPUs went offline: Cores plans again. */
    private volatile Runnable onCpusLost;
    private final Object lock = new Object();

    // Guarded by lock.
    private CoreLayout target;
    private long targetVersion;
    private boolean exit;
    private Thread thread;

    // Owned by the worker thread.
    private final Map<Integer, Applied> applied = new HashMap<>();
    private boolean pinned;
    private boolean otherFailureLogged;
    private boolean replanned;

    // Published to readers.
    private volatile List<PinnedThread> snapshot = List.of();
    private volatile Map<Integer, Double> load = Map.of();
    private volatile long loadAtNanos;
    private volatile CoreLayout active;
    private volatile String failure;
    private volatile int scans;

    /**
     * @param pid            the process whose threads to pin; "self" for this server
     * @param fullSet        every CPU the process may use: what threads get back when unpinned
     * @param publishCurrent whether to publish the applied layout as {@link CoreLayout#current()}
     */
    public AffinityManager(final String pid, final List<Integer> fullSet, final boolean publishCurrent) {
        this.tasks = Path.of("/proc", pid, "task");
        this.fullSet = CpuListParser.format(fullSet);
        this.publishCurrent = publishCurrent;
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
     */
    public void setLayout(final CoreLayout layout) {
        synchronized (this.lock) {
            this.target = layout;
            this.targetVersion++;
            this.failure = null;
            this.ensureThread();
            this.lock.notifyAll();
        }
    }

    /** The CPU set threads get back when unpinned (after a re-plan with fewer CPUs). */
    public void setFullSet(final List<Integer> cpus) {
        this.fullSet = CpuListParser.format(cpus);
    }

    /** What to run, on this manager's thread, when CPUs went offline and the layout needs a new plan. */
    public void onCpusLost(final Runnable action) {
        this.onCpusLost = action;
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
        for (final int tid : this.listTids()) {
            final String comm = this.readComm(tid);
            if (comm == null) {
                continue; // ended while listing
            }
            final String group = ThreadClassifier.classify(comm);
            final Role role = ThreadClassifier.roleOf(group);
            final String wanted = layout == null ? null : CpuListParser.format(layout.cpus(role, ThreadClassifier.spread(comm)));
            final Applied previous = this.applied.get(tid);
            String current;
            if (wanted != null && previous != null && previous.comm().equals(comm) && previous.cpus().equals(wanted)) {
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
                    this.applied.put(tid, new Applied(comm, wanted));
                }
            }
            alive.add(tid);
            seen.add(new PinnedThread(tid, comm, group, role, current));
        }
        this.applied.keySet().retainAll(alive);
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
        this.publish(null);
        this.scan(null);
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
            return Files.readString(this.tasks.resolve(Integer.toString(tid)).resolve("comm"), StandardCharsets.UTF_8).strip();
        } catch (final IOException | RuntimeException e) {
            return null;
        }
    }

    /** The thread's CPU list in canonical form, or null when the thread is gone. */
    private String readMask(final int tid) {
        try {
            for (final String line : Files.readAllLines(this.tasks.resolve(Integer.toString(tid)).resolve("status"), StandardCharsets.UTF_8)) {
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
