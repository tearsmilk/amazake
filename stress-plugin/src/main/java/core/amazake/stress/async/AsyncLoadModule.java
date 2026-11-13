package core.amazake.stress.async;

import core.amazake.stress.LoadModule;
import core.amazake.stress.Region;
import core.amazake.stress.Stage;
import core.amazake.stress.StressContext;
import core.amazake.stress.StressPlugin;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Load module "async": what heavy (and some badly written) plugins cost a server.
 *
 * <ul>
 *   <li><b>Scheduler tasks</b>: {@code scheduler-tasks} repeating Bukkit async tasks
 *       ({@code runTaskTimerAsynchronously}, threads "Craft Scheduler Thread - N", group {@code plugins-async} in
 *       the profiler), each doing a fixed amount of CPU work per run: SHA-256, JSON (Gson) of fake player data,
 *       sorting. The first {@code file-writers} of them also write their JSON to {@code plugins/AmazakeStress/async-io/}.
 *       A run that is still busy when the next one is due is skipped (counted), never doubled.</li>
 *   <li><b>Own worker threads</b>: {@code threads} dedicated threads named "AmazakeStress Worker #N" running
 *       work batches with a short sleep between them (plugins with their own pools).</li>
 *   <li><b>Async → sync callbacks</b>: every tick an async task posts {@code sync-callbacks-per-tick} small
 *       tasks back to the main thread (Bukkit {@code runTask}).</li>
 *   <li><b>Chunk snapshots</b>: {@code chunk-snapshots-per-second} {@link ChunkSnapshot}s taken on the main
 *       thread (a fixed 8x8 chunk region, round robin) and scanned on the async pool.</li>
 *   <li><b>Busy main-thread tasks</b>: {@code sync-tasks} repeating tasks that spend
 *       {@code sync-ms-per-task} ms each per tick on the main thread (capped by {@code sync-max-ms-total}).</li>
 * </ul>
 * Every input comes from seeded Randoms ({@code ctx.newRandom("task-N")} ...), so the work per run is identical
 * on every server. In the MAX stage the work per run is multiplied by the stage intensity.
 */
public final class AsyncLoadModule implements LoadModule {

    public static final String WORKER_THREAD_PREFIX = "AmazakeStress Worker #";

    private final StressPlugin plugin;
    private StressContext ctx;

    // settings
    private int schedulerTasks;
    private int periodTicks;
    private int hashKib;
    private int jsonObjects;
    private int sortSize;
    private int fileWriters;
    private int fileIntervalTicks;
    private int workerThreads;
    private int workerSleepMs;
    private int syncCallbacksPerTick;
    private double snapshotsPerSecond;
    private int syncTasks;
    private double syncMsPerTask;
    private double syncMaxMsTotal;

    private volatile double intensity = 1.0;
    private volatile boolean stopped;
    private Path ioFolder;
    private Region snapshotRegion;
    private boolean snapshotsReady;
    private double snapshotCredit;
    private int snapshotCursor;
    private final List<Thread> workers = new ArrayList<>();

    // counters (thread-safe)
    private final AtomicLong asyncRuns = new AtomicLong();
    private final AtomicLong skippedRuns = new AtomicLong();
    private final AtomicLong workerBatches = new AtomicLong();
    private final AtomicLong filesWritten = new AtomicLong();
    private final AtomicLong callbacksRun = new AtomicLong();
    private final AtomicLong snapshotsDone = new AtomicLong();
    private final AtomicLong checksum = new AtomicLong();
    private double syncMsSpent;
    private long syncTicks;

    public AsyncLoadModule(StressPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "async";
    }

    @Override
    public void start(StressContext ctx) {
        this.ctx = ctx;
        final ConfigurationSection cfg = ctx.config();
        this.schedulerTasks = clamp(cfg.getInt("scheduler-tasks", 12), 0, 256);
        this.periodTicks = clamp(cfg.getInt("scheduler-period-ticks", 1), 1, 1200);
        this.hashKib = Math.max(0, cfg.getInt("hash-kib-per-run", 64));
        this.jsonObjects = Math.max(0, cfg.getInt("json-objects-per-run", 40));
        this.sortSize = Math.max(0, cfg.getInt("sort-size-per-run", 20000));
        this.fileWriters = clamp(cfg.getInt("file-writers", 2), 0, this.schedulerTasks);
        this.fileIntervalTicks = Math.max(1, cfg.getInt("file-write-interval-ticks", 20));
        this.workerThreads = clamp(cfg.getInt("threads", 2), 0, 64);
        this.workerSleepMs = clamp(cfg.getInt("worker-sleep-ms", 5), 0, 1000);
        this.syncCallbacksPerTick = Math.max(0, cfg.getInt("sync-callbacks-per-tick", 100));
        this.snapshotsPerSecond = Math.max(0.0, cfg.getDouble("chunk-snapshots-per-second", 40.0));
        this.syncTasks = clamp(cfg.getInt("sync-tasks", 3), 0, 64);
        this.syncMsPerTask = Math.max(0.0, cfg.getDouble("sync-ms-per-task", 1.5));
        this.syncMaxMsTotal = Math.max(0.0, cfg.getDouble("sync-max-ms-total", 10.0));
        this.ioFolder = this.plugin.getDataFolder().toPath().resolve("async-io");

        // 1. Bukkit async scheduler tasks
        for (int i = 0; i < this.schedulerTasks; i++) {
            final int index = i;
            final Workload work = new Workload(ctx.newRandom("task-" + i));
            final AtomicBoolean busy = new AtomicBoolean();
            final AtomicLong runs = new AtomicLong();
            // Staggered start so the tasks do not all fire on the same tick.
            ctx.runAsyncTimer(() -> this.asyncRun(index, work, busy, runs), 1L + (i % this.periodTicks), this.periodTicks);
        }

        // 2. dedicated worker threads
        for (int i = 0; i < this.workerThreads; i++) {
            final Workload work = new Workload(ctx.newRandom("worker-" + i));
            final Thread thread = new Thread(() -> this.workerLoop(work), WORKER_THREAD_PREFIX + (i + 1));
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);
            this.workers.add(thread);
        }
        for (final Thread thread : this.workers) {
            thread.start();
        }
        ctx.onStop(this::stopWorkers);

        // 3. async -> sync callbacks
        if (this.syncCallbacksPerTick > 0) {
            final Workload callbackWork = new Workload(ctx.newRandom("callbacks"));
            ctx.runAsyncTimer(() -> this.postCallbacks(callbackWork), 1L, 1L);
        }

        // 4. chunk snapshots of a fixed held region
        if (this.snapshotsPerSecond > 0) {
            this.snapshotRegion = ctx.allocateRegion("snapshots", 8, 8);
            ctx.chunks().loadAndHold(this.snapshotRegion).thenRun(() -> this.snapshotsReady = true);
        }

        // 5. busy main-thread tasks ("badly written plugins")
        for (int i = 0; i < this.syncTasks; i++) {
            final Workload work = new Workload(ctx.newRandom("sync-" + i));
            ctx.runTimer(() -> this.busySync(work), 1L, 1L);
        }

        ctx.logger().info("[async] " + this.schedulerTasks + " scheduler tasks, " + this.workerThreads + " worker threads, "
            + this.syncCallbacksPerTick + " sync callbacks/tick, " + this.snapshotsPerSecond + " snapshots/s, "
            + this.syncTasks + " busy sync tasks x " + this.syncMsPerTask + " ms");
    }

    @Override
    public void onStageChange(Stage stage) {
        if (this.ctx != null) {
            this.intensity = this.ctx.intensity();
        }
    }

    private int scaled(int base) {
        return Math.max(0, (int) Math.floor(base * this.intensity));
    }

    // ---------------------------------------------------------------- async parts

    private void asyncRun(int index, Workload work, AtomicBoolean busy, AtomicLong runs) {
        if (this.stopped || !busy.compareAndSet(false, true)) {
            this.skippedRuns.incrementAndGet();
            return;
        }
        try {
            work.hash(this.scaled(this.hashKib));
            final String json = work.json(this.scaled(this.jsonObjects));
            work.sort(this.scaled(this.sortSize));
            final long run = runs.incrementAndGet();
            if (index < this.fileWriters && run % Math.max(1, this.fileIntervalTicks / this.periodTicks) == 0 && !this.stopped) {
                this.writeFile(index, json);
            }
            this.asyncRuns.incrementAndGet();
            this.checksum.addAndGet(work.checksum());
        } finally {
            busy.set(false);
        }
    }

    private void writeFile(int index, String json) {
        try {
            Files.createDirectories(this.ioFolder);
            final Path file = this.ioFolder.resolve("task-" + index + ".json");
            final Path tmp = this.ioFolder.resolve("task-" + index + ".json.tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            this.filesWritten.incrementAndGet();
        } catch (final IOException e) {
            this.ctx.logger().log(Level.FINE, "[async] file write failed", e);
        }
    }

    private void workerLoop(Workload work) {
        while (!this.stopped && this.ctx.isActive()) {
            work.hash(this.scaled(this.hashKib));
            work.json(this.scaled(this.jsonObjects / 2));
            work.sort(this.scaled(this.sortSize));
            this.workerBatches.incrementAndGet();
            this.checksum.addAndGet(work.checksum());
            if (this.workerSleepMs > 0) {
                try {
                    Thread.sleep(this.workerSleepMs);
                } catch (final InterruptedException e) {
                    return;
                }
            }
        }
    }

    private void stopWorkers() {
        this.stopped = true;
        for (final Thread thread : this.workers) {
            thread.interrupt();
        }
    }

    private void postCallbacks(Workload work) {
        if (this.stopped) {
            return;
        }
        // prepared off-thread, applied on the main thread (the classic "compute async, apply sync" pattern)
        final int count = this.scaled(this.syncCallbacksPerTick);
        for (int i = 0; i < count; i++) {
            if (this.stopped || !this.plugin.isEnabled()) {
                return; // runTask would throw once the plugin is disabled
            }
            final String payload = "cb-" + i;
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (this.stopped || !this.ctx.isActive()) {
                    return;
                }
                work.hashText(payload); // main thread only from here on: Workload is used by callbacks only
                this.callbacksRun.incrementAndGet();
            });
        }
    }

    // ---------------------------------------------------------------- main thread parts

    @Override
    public void tick(long tick) {
        if (this.stopped || !this.snapshotsReady || this.snapshotRegion == null) {
            return;
        }
        this.snapshotCredit += this.snapshotsPerSecond * this.intensity / 20.0;
        final World world = this.ctx.world();
        final int total = this.snapshotRegion.chunkCount();
        while (this.snapshotCredit >= 1.0) {
            this.snapshotCredit -= 1.0;
            final int index = this.snapshotCursor++ % total;
            final int cx = this.snapshotRegion.minChunkX() + index % this.snapshotRegion.chunksX();
            final int cz = this.snapshotRegion.minChunkZ() + index / this.snapshotRegion.chunksX();
            if (!world.isChunkLoaded(cx, cz)) {
                continue;
            }
            final ChunkSnapshot snapshot = world.getChunkAt(cx, cz).getChunkSnapshot();
            final int minY = Math.max(world.getMinHeight(), this.ctx.baseY() - 8);
            final int maxY = Math.min(world.getMaxHeight() - 1, this.ctx.baseY() + 56);
            Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
                if (this.stopped) {
                    return;
                }
                long solid = 0;
                for (int y = minY; y <= maxY; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (snapshot.getBlockType(x, y, z) != Material.AIR) {
                                solid++;
                            }
                        }
                    }
                }
                this.checksum.addAndGet(solid);
                this.snapshotsDone.incrementAndGet();
            });
        }
    }

    /** Spends a fixed wall-time budget on the main thread, like a plugin doing too much per tick. */
    private void busySync(Workload work) {
        if (this.stopped) {
            return;
        }
        final double perTask = this.syncMsPerTask * this.intensity;
        final double cap = this.syncMaxMsTotal > 0 ? this.syncMaxMsTotal / Math.max(1, this.syncTasks) : perTask;
        final long budgetNs = (long) (Math.min(perTask, cap) * 1_000_000.0);
        if (budgetNs <= 0) {
            return;
        }
        final long start = System.nanoTime();
        int round = 0;
        while (System.nanoTime() - start < budgetNs) {
            work.hashText("sync-" + round++);
        }
        this.syncMsSpent += (System.nanoTime() - start) / 1_000_000.0;
        this.syncTicks++;
    }

    // ---------------------------------------------------------------- stop / status

    @Override
    public void stop() {
        this.stopWorkers();
        if (this.ioFolder != null && Files.isDirectory(this.ioFolder)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(this.ioFolder)) {
                for (final Path file : files) {
                    Files.deleteIfExists(file);
                }
                Files.deleteIfExists(this.ioFolder);
            } catch (final IOException e) {
                this.ctx.logger().log(Level.FINE, "[async] cleanup of " + this.ioFolder + " failed", e);
            }
        }
    }

    @Override
    public String status() {
        if (this.ctx == null) {
            return "не запущен";
        }
        final double syncAvg = this.syncTicks == 0 ? 0.0 : this.syncMsSpent / Math.max(1L, this.syncTicks / Math.max(1, this.syncTasks));
        return "асинх. запусков " + this.asyncRuns.get() + " (пропущено " + this.skippedRuns.get() + "), пачек в потоках "
            + this.workerBatches.get() + ", колбэков " + this.callbacksRun.get() + ", снимков чанков " + this.snapshotsDone.get()
            + ", файлов " + this.filesWritten.get() + ", синхр. ~" + String.format(java.util.Locale.ROOT, "%.1f", syncAvg) + " мс/тик";
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
