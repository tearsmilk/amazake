package ink.hoka.amazake.profiler;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

/** One measurement: samples on its own thread every interval, then builds the report. */
final class ProfilerSession implements Runnable {

    /** Threads that get a per-interval series in the report. */
    private static final int SERIES_THREADS = 24;
    /** How long a sample waits for the main thread to count entities and chunks. */
    private static final long MAIN_THREAD_WAIT_MS = 1000L;

    final CommandSender sender;
    final int seconds;
    final int intervalMs;
    final long startMillis = System.currentTimeMillis();

    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final EnvironmentInfo.Start start;
    private final Instant createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final CpuSampler cpu = new CpuSampler();
    private final ThreadCpuSampler threadCpu = new ThreadCpuSampler();
    private final TickSampler ticks = new TickSampler();
    private final WorldSnapshots worlds = new WorldSnapshots();
    private final List<Report.Sample> samples = new ArrayList<>();
    private long lastGcCount;
    private long lastGcTimeMs;
    /** System.currentTimeMillis() at t = 0 of the samples. */
    private long startWallMs;
    /** amazake.yml profiler.entity-breakdown at the start: time entity ticks (the report's "entities"). */
    private final boolean entityBreakdown = ink.hoka.amazake.AmazakeConfig.profilerEntityBreakdown;
    /** EntityTickStats snapshots: the session's first one and the last one a sample got. */
    private Map<String, Object> firstEntityTicks;
    private Map<String, Object> lastEntityTicks;

    ProfilerSession(final CommandSender sender, final int seconds, final int intervalMs) {
        this.sender = sender;
        this.seconds = seconds;
        this.intervalMs = intervalMs;
        this.start = EnvironmentInfo.Start.capture();
    }

    long endMillis() {
        return this.startMillis + this.seconds * 1000L;
    }

    void requestStop() {
        this.stopSignal.countDown();
    }

    @Override
    public void run() {
        final Report report;
        try {
            report = this.measure();
            this.summarizeEntities(report);
        } catch (final Throwable t) {
            Profiler.LOGGER.log(java.util.logging.Level.SEVERE, "Profiler failed", t);
            Profiler.finished(this);
            Messages.error(this.sender, "Замер прервался из-за ошибки: " + t + ". Подробности в консоли.");
            return;
        }
        Profiler.finished(this); // a new measurement may start while this report is sent
        // The profiler thread is a daemon: during shutdown the JVM may exit mid-upload, so save locally instead.
        ReportOutput.deliver(report, this.sender, Bukkit.isStopping());
    }

    private Report measure() throws InterruptedException {
        final long intervalNs = TimeUnit.MILLISECONDS.toNanos(this.intervalMs);
        this.cpu.start();
        this.threadCpu.start();
        this.ticks.start();
        this.readGc();
        final long startNs = System.nanoTime();
        this.startWallMs = System.currentTimeMillis();
        final long deadlineNs = startNs + TimeUnit.SECONDS.toNanos(this.seconds);
        long lastNs = startNs;
        if (this.entityBreakdown) {
            ink.hoka.amazake.tick.EntityTickStats.acquire();
        }
        try {
            if (this.entityBreakdown) {
                // The baseline the first sample and the report's "entities" are counted from.
                final WorldSnapshots.Snapshot baseline = this.worlds.take(false, true, MAIN_THREAD_WAIT_MS);
                this.firstEntityTicks = baseline == null ? null : baseline.entityTicks();
                this.lastEntityTicks = this.firstEntityTicks;
            }
            while (true) {
                final long nextNs = Math.min(lastNs + intervalNs, deadlineNs);
                final long waitNs = nextNs - System.nanoTime();
                final boolean stopped = waitNs > 0L ? this.stopSignal.await(waitNs, TimeUnit.NANOSECONDS) : this.stopSignal.getCount() == 0L;
                final long now = System.nanoTime();
                this.sample(Math.max(1L, now - lastNs), TimeUnit.NANOSECONDS.toMillis(now - startNs));
                lastNs = now;
                if (stopped || now >= deadlineNs || Bukkit.isStopping()) {
                    break;
                }
            }
        } finally {
            if (this.entityBreakdown) {
                ink.hoka.amazake.tick.EntityTickStats.release();
            }
        }
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
        final WorldSnapshots.Snapshot snapshot = this.worlds.take(false, this.entityBreakdown, MAIN_THREAD_WAIT_MS);
        Map<String, Object> entityTicks = null;
        if (snapshot != null && snapshot.entityTicks() != null) {
            if (this.firstEntityTicks == null) {
                this.firstEntityTicks = snapshot.entityTicks(); // no baseline: count from here
            } else {
                entityTicks = ink.hoka.amazake.tick.EntityTickStats.sample(this.lastEntityTicks, snapshot.entityTicks());
            }
            this.lastEntityTicks = snapshot.entityTicks();
        }

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
            snapshot == null ? null : snapshot.loadedChunks(),
            entityTicks
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

        return new Report(
            Report.FORMAT,
            Report.VERSION,
            this.createdAt.toString(),
            durationMs,
            this.intervalMs,
            EnvironmentInfo.server(this.start, this.finalWorlds()),
            EnvironmentInfo.system(this.cpu.perCore(), this.threadCpu.source(), this.reportedCoreIds()),
            List.copyOf(this.samples),
            threads,
            groupEntries,
            spikes,
            this.migrations(durationMs),
            this.entityBreakdown ? ink.hoka.amazake.tick.EntityTickStats.summarize(this.firstEntityTicks, this.lastEntityTicks, 40) : null
        );
    }

    /** One line about the entity tick time: "Сущности: мобы 120.2 мс/тик, остальные 80.1, игроки 0.2 (из 210.4 мс на цикл сущностей)". */
    @SuppressWarnings("unchecked")
    private void summarizeEntities(final Report report) {
        final Map<String, Object> entities = report.entities();
        if (entities == null) {
            return;
        }
        try {
            final Map<String, Object> loop = (Map<String, Object>) entities.get("msPerTick");
            final Map<String, Double> byClass = new LinkedHashMap<>();
            for (final Map<String, Object> entry : (List<Map<String, Object>>) entities.get("classes")) {
                byClass.put(String.valueOf(entry.get("class")), ((Number) entry.get("msPerTick")).doubleValue());
            }
            Messages.result(this.sender, "Сущности: мобы " + ms(byClass.get("mob")) + " мс/тик, остальные "
                + ms(byClass.get("other")) + ", игроки " + ms(byClass.get("player")) + " (из "
                + ms(((Number) loop.get("loop")).doubleValue()) + " мс на цикл сущностей)");
        } catch (final RuntimeException e) {
            Profiler.LOGGER.warning("Profiler could not summarize entity ticks: " + e);
        }
    }

    private static String ms(final Double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value == null ? 0.0 : value);
    }

    /** Moves of the tick to a reserve CPU during the measurement; null (left out) when there were none. */
    private List<Report.Migration> migrations(final long durationMs) {
        final List<Report.Migration> out = new ArrayList<>();
        try {
            for (final ink.hoka.amazake.cpu.AffinityManager.Migration m : ink.hoka.amazake.cpu.Cores.migrations()) {
                final long t = m.wallMs() - this.startWallMs;
                if (t >= 0L && t <= durationMs + this.intervalMs) {
                    out.add(new Report.Migration(Math.min(t, durationMs), m.from(), m.to(), round(m.foreignLoad())));
                }
            }
        } catch (final RuntimeException | LinkageError e) {
            Profiler.LOGGER.warning("Profiler could not read the tick moves: " + e);
        }
        return out.isEmpty() ? null : out;
    }

    /**
     * The CPU id of each entry of samples[].cores: the ids of the last /proc/stat read, cut to the
     * last sample's core count; null without per-core samples or when they do not line up.
     */
    private int[] reportedCoreIds() {
        int count = -1;
        for (int i = this.samples.size() - 1; i >= 0; i--) {
            final double[] cores = this.samples.get(i).cores();
            if (cores != null) {
                count = cores.length;
                break;
            }
        }
        final int[] ids = this.cpu.coreIds();
        if (count <= 0 || ids == null || ids.length < count) {
            return null;
        }
        return java.util.Arrays.copyOf(ids, count);
    }

    /**
     * Worlds at the end, with block entities; names only if the main thread does not answer
     * (or the server is stopping), and an empty list if even the names cannot be read.
     */
    private List<Report.World> finalWorlds() {
        final WorldSnapshots.Snapshot snapshot = Bukkit.isStopping() ? null : this.worlds.take(true, 2 * MAIN_THREAD_WAIT_MS);
        List<WorldSnapshots.WorldStats> stats;
        if (snapshot != null) {
            stats = snapshot.worlds();
        } else {
            try {
                stats = WorldSnapshots.namesOnly();
            } catch (final RuntimeException e) { // worlds may be unloading during shutdown
                Profiler.LOGGER.warning("Profiler could not list worlds: " + e);
                stats = List.of();
            }
        }
        final List<Report.World> out = new ArrayList<>(stats.size());
        for (final WorldSnapshots.WorldStats world : stats) {
            out.add(new Report.World(world.name(), world.environment(), world.loadedChunks(), world.entities(),
                world.tileEntities(), world.players()));
        }
        return out;
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
