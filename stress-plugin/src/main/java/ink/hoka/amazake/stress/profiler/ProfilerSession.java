package ink.hoka.amazake.stress.profiler;

import ink.hoka.amazake.stress.StageRunner;
import ink.hoka.amazake.stress.StressPlugin;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

/**
 * One measurement: samples on its own thread every interval, then builds the report and delivers it.
 * Ported from Amazake's ProfilerSession; differences: tick data from {@link TickSampler} (ServerTickEndEvent),
 * main-thread reads through the Bukkit scheduler, and the extra {@code stress} block ({@link StressInfo}).
 */
final class ProfilerSession implements Runnable {

    /** Threads that get a per-interval series in the report. */
    private static final int SERIES_THREADS = 24;
    /** How long a sample waits for the main thread to count entities and chunks. */
    private static final long MAIN_THREAD_WAIT_MS = 1000L;
    /** How long the end of the measurement waits for the main thread (worlds + stress block). */
    private static final long FINAL_WAIT_MS = 5000L;

    /** World list and stress block, read on the main thread at the end. */
    record FinalData(List<Report.World> worlds, Map<String, Object> stress) {
    }

    final CommandSender sender;
    final int seconds;
    final int intervalMs;
    final ProfilerSettings settings;
    final long startMillis = System.currentTimeMillis();

    private final StressPlugin plugin;
    private final StressProfiler owner;
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final EnvironmentInfo.Start start;
    private final Instant createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final CpuSampler cpu = new CpuSampler();
    private final ThreadCpuSampler threadCpu = new ThreadCpuSampler();
    private final TickSampler ticks;
    private final WorldSnapshots worlds;
    private final List<Report.Sample> samples = new ArrayList<>();
    private final int profileStartTick;
    private long lastGcCount;
    private long lastGcTimeMs;

    // main thread writes, profiler thread only reads through FinalData
    private @Nullable StageRunner seenRun;
    private @Nullable Integer seenRunStartTick;
    private volatile @Nullable FinalData finalData;
    private volatile boolean localOnly;

    /** Main thread. */
    ProfilerSession(final StressPlugin plugin, final StressProfiler owner, final TickSampler ticks, final CommandSender sender,
                    final int seconds, final ProfilerSettings settings) {
        this.plugin = plugin;
        this.owner = owner;
        this.ticks = ticks;
        this.sender = sender;
        this.seconds = seconds;
        this.settings = settings;
        this.intervalMs = settings.intervalMs();
        this.worlds = new WorldSnapshots(plugin);
        this.start = EnvironmentInfo.Start.capture();
        this.profileStartTick = Bukkit.getCurrentTick();
        this.observeRun();
    }

    long endMillis() {
        return this.startMillis + this.seconds * 1000L;
    }

    /**
     * Main thread: ends the measurement early. The world list and the stress block are taken right now, on
     * the main thread, so they are complete even if the main thread is gone afterwards (plugin disable).
     */
    void requestStop(final boolean localOnly) {
        if (localOnly) {
            this.localOnly = true;
        }
        if (this.finalData == null) {
            try {
                this.finalData = this.captureFinal();
            } catch (final RuntimeException e) {
                this.plugin.getLogger().log(Level.WARNING, "Profiler could not read the final state", e);
            }
        }
        this.stopSignal.countDown();
    }

    /** Main thread: remembers the first stress run seen during the measurement and its start tick. */
    private void observeRun() {
        final StageRunner run = this.plugin.currentRun();
        if (run != null && this.seenRun == null) {
            this.seenRun = run;
            this.seenRunStartTick = Bukkit.getCurrentTick() - (int) run.tick();
        }
    }

    /** Main thread. */
    private FinalData captureFinal() {
        this.observeRun();
        StageRunner run = this.seenRun;
        if (run == null) {
            final StageRunner last = this.plugin.lastRun();
            if (last != null && last.startedAtMillis() >= this.startMillis) {
                run = last; // started and finished between two samples (cannot really happen, runs are >= 10 s)
            }
        }
        final WorldSnapshots.Snapshot snapshot = WorldSnapshots.read(true);
        final List<Report.World> out = new ArrayList<>(snapshot.worlds().size());
        for (final WorldSnapshots.WorldStats world : snapshot.worlds()) {
            out.add(new Report.World(world.name(), world.environment(), world.loadedChunks(), world.entities(),
                world.tileEntities(), world.players()));
        }
        return new FinalData(out, StressInfo.build(this.plugin, run, this.seenRunStartTick, this.profileStartTick, this.startMillis));
    }

    @Override
    public void run() {
        Report report;
        try {
            report = this.measure();
        } catch (final Throwable t) {
            this.plugin.getLogger().log(Level.SEVERE, "Profiler failed", t);
            this.owner.finished(this, null);
            this.owner.messages().error(this.sender, "Замер прервался из-за ошибки: " + t + ". Подробности в консоли.");
            return;
        }
        this.owner.finished(this, null); // a new measurement may start while this report is sent
        String result = null;
        try {
            result = this.owner.output().deliver(report, this.settings, this.sender, this.localOnly || Bukkit.isStopping());
        } catch (final Throwable t) {
            this.plugin.getLogger().log(Level.SEVERE, "Profiler could not deliver the report", t);
        }
        this.owner.delivered(result);
    }

    private Report measure() throws InterruptedException {
        final long intervalNs = TimeUnit.MILLISECONDS.toNanos(this.intervalMs);
        this.cpu.start();
        this.threadCpu.start();
        this.readGc();
        final long startNs = System.nanoTime();
        final long deadlineNs = startNs + TimeUnit.SECONDS.toNanos(this.seconds);
        long lastNs = startNs;
        while (true) {
            final long nextNs = Math.min(lastNs + intervalNs, deadlineNs);
            final long waitNs = nextNs - System.nanoTime();
            final boolean stopped = waitNs > 0L ? this.stopSignal.await(waitNs, TimeUnit.NANOSECONDS) : this.stopSignal.getCount() == 0L;
            final long now = System.nanoTime();
            this.sample(Math.max(1L, now - lastNs), TimeUnit.NANOSECONDS.toMillis(now - startNs));
            lastNs = now;
            if (stopped || now >= deadlineNs || Bukkit.isStopping() || this.localOnly) {
                break;
            }
        }
        this.ticks.stop();
        // At least 1 ms: a stop right after the start must not produce durationMs 0, which the site rejects (400).
        return this.build(Math.max(1L, TimeUnit.NANOSECONDS.toMillis(lastNs - startNs)));
    }

    private void sample(final long elapsedNs, final long tMs) {
        final int index = this.samples.size();
        final CpuSampler.CpuSample cpuSample = this.cpu.sample(elapsedNs);
        this.threadCpu.sample(index, elapsedNs);
        final TickSampler.TickSample tick = this.ticks.sample(elapsedNs, tMs);
        final long gcCountBefore = this.lastGcCount;
        final long gcTimeBefore = this.lastGcTimeMs;
        this.readGc();
        final long heapUsed = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
        final int players = Bukkit.getOnlinePlayers().size();
        final WorldSnapshots.Snapshot snapshot = this.localOnly ? null : this.worlds.take(false, MAIN_THREAD_WAIT_MS, this::observeRun);

        this.samples.add(new Report.Sample(
            tMs,
            cpuSample.cores() == null ? null : round(cpuSample.cores()),
            round(cpuSample.systemCpu()),
            round(cpuSample.processCpu()),
            round(tick.tps()),
            round(tick.msptAvg()),
            round(tick.msptMax()),
            heapUsed,
            (int) Math.max(0L, this.lastGcCount - gcCountBefore),
            (int) Math.max(0L, this.lastGcTimeMs - gcTimeBefore),
            players,
            snapshot == null ? null : snapshot.entities(),
            snapshot == null ? null : snapshot.loadedChunks()
        ));
    }

    private void readGc() {
        long count = 0L;
        long time = 0L;
        for (final GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0L, gc.getCollectionCount());
            time += Math.max(0L, gc.getCollectionTime());
        }
        this.lastGcCount = count;
        this.lastGcTimeMs = time;
    }

    /** The final state: given by {@link #requestStop}, else asked from the main thread, else names only. */
    private FinalData finalData() {
        FinalData data = this.finalData;
        if (data != null) {
            return data;
        }
        if (!this.localOnly && !Bukkit.isStopping() && this.plugin.isEnabled()) {
            final CompletableFuture<FinalData> future = new CompletableFuture<>();
            try {
                Bukkit.getScheduler().runTask(this.plugin, () -> {
                    try {
                        future.complete(this.captureFinal());
                    } catch (final Throwable t) {
                        future.completeExceptionally(t);
                    }
                });
                data = future.get(FINAL_WAIT_MS, TimeUnit.MILLISECONDS);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (final Exception e) {
                this.plugin.getLogger().warning("Profiler: the main thread did not answer at the end (" + e + ")");
            }
        }
        if (data == null) {
            data = this.finalData; // requestStop may have filled it meanwhile
        }
        if (data != null) {
            return data;
        }
        List<Report.World> names = new ArrayList<>();
        try {
            for (final WorldSnapshots.WorldStats world : WorldSnapshots.namesOnly()) {
                names.add(new Report.World(world.name(), world.environment(), 0, 0, 0, 0));
            }
        } catch (final RuntimeException e) { // worlds may be unloading during shutdown
            names = List.of();
        }
        final Map<String, Object> stress = new LinkedHashMap<>();
        stress.put("plugin", "AmazakeStress " + this.plugin.getPluginMeta().getVersion());
        stress.put("stageTimeline", List.of());
        stress.put("profileStartedAtTick", this.profileStartTick);
        stress.put("note", "main thread did not answer at the end of the measurement");
        return new FinalData(names, stress);
    }

    private Report build(final long durationMs) {
        final int sampleCount = this.samples.size();
        final double duration = Math.max(1L, durationMs);

        final List<ThreadCpuSampler.ThreadStat> stats = new ArrayList<>(this.threadCpu.stats());
        stats.sort(Comparator.comparingLong(ThreadCpuSampler.ThreadStat::cpuNs).reversed());

        final List<Report.ThreadEntry> threads = new ArrayList<>(stats.size());
        final Map<String, GroupTotal> groups = new LinkedHashMap<>();
        for (int i = 0; i < stats.size(); i++) {
            final ThreadCpuSampler.ThreadStat stat = stats.get(i);
            final long cpuMs = TimeUnit.NANOSECONDS.toMillis(stat.cpuNs());
            final double[] series = stat.series.toArray(sampleCount);
            threads.add(new Report.ThreadEntry(stat.name, stat.group, cpuMs, round(cpuMs / duration),
                i < SERIES_THREADS ? round(series) : null));
            groups.computeIfAbsent(stat.group, g -> new GroupTotal(sampleCount)).add(stat.cpuNs(), series);
        }

        final List<Report.GroupEntry> groupEntries = new ArrayList<>(groups.size());
        groups.forEach((group, total) -> {
            final long cpuMs = TimeUnit.NANOSECONDS.toMillis(total.cpuNs);
            double peak = 0.0;
            for (final double value : total.series) {
                peak = Math.max(peak, value);
            }
            groupEntries.add(new Report.GroupEntry(group, total.threads, cpuMs, round(cpuMs / duration), round(peak), round(total.series)));
        });
        groupEntries.sort(Comparator.comparingLong(Report.GroupEntry::cpuMs).reversed());

        final List<Report.LagSpike> spikes = new ArrayList<>();
        for (final TickSampler.Spike spike : this.ticks.spikes()) {
            spikes.add(new Report.LagSpike(spike.t(), round(spike.ms())));
        }

        final FinalData end = this.finalData();
        return new Report(
            Report.FORMAT,
            Report.VERSION,
            this.createdAt.toString(),
            durationMs,
            this.intervalMs,
            EnvironmentInfo.server(this.start, end.worlds()),
            EnvironmentInfo.system(this.cpu.perCore(), this.threadCpu.source()),
            List.copyOf(this.samples),
            threads,
            groupEntries,
            spikes,
            end.stress()
        );
    }

    private static final class GroupTotal {
        final double[] series;
        long cpuNs;
        int threads;

        GroupTotal(final int samples) {
            this.series = new double[samples];
        }

        void add(final long cpuNs, final double[] threadSeries) {
            this.cpuNs += cpuNs;
            this.threads++;
            for (int i = 0; i < this.series.length; i++) {
                this.series[i] += threadSeries[i];
            }
        }
    }

    /** Four decimals are plenty and keep the report small. */
    static double round(final double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.0;
        }
        return Math.round(value * 10_000.0) / 10_000.0;
    }

    static double[] round(final double[] values) {
        final double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = round(values[i]);
        }
        return out;
    }
}
