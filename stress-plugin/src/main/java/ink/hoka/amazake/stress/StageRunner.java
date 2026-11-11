package ink.hoka.amazake.stress;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * One run of the benchmark: a single repeating task (period 1 tick) that advances the stage timeline,
 * starts the modules of each stage and calls {@link LoadModule#tick(long)} on every started module.
 * The timeline is counted in server ticks, so it is identical on every server regardless of lag.
 */
public final class StageRunner {

    /** A started module. */
    public static final class Handle {
        private final ModuleRegistry.Entry entry;
        private final LoadModule module;
        private final StressContext context;
        private @Nullable String failure;

        Handle(ModuleRegistry.Entry entry, LoadModule module, StressContext context) {
            this.entry = entry;
            this.module = module;
            this.context = context;
        }

        public String id() {
            return this.entry.id();
        }

        public LoadModule module() {
            return this.module;
        }

        public StressContext context() {
            return this.context;
        }

        public boolean failed() {
            return this.failure != null;
        }

        public @Nullable String failure() {
            return this.failure;
        }
    }

    /** Tick statistics of one stage. */
    public static final class StageStats {
        private final Stage stage;
        private final long startNanos;
        private long endNanos;
        private double[] durations = new double[1024];
        private int ticks;
        private double sum;
        private double max;
        private int over50;

        StageStats(Stage stage) {
            this.stage = stage;
            this.startNanos = System.nanoTime();
        }

        void record(double ms) {
            if (this.ticks == this.durations.length) {
                this.durations = Arrays.copyOf(this.durations, this.ticks * 2);
            }
            this.durations[this.ticks++] = ms;
            this.sum += ms;
            this.max = Math.max(this.max, ms);
            if (ms > 50.0) {
                this.over50++;
            }
        }

        void end() {
            if (this.endNanos == 0) {
                this.endNanos = System.nanoTime();
            }
        }

        public Stage stage() {
            return this.stage;
        }

        public int ticks() {
            return this.ticks;
        }

        public double seconds() {
            final long end = this.endNanos == 0 ? System.nanoTime() : this.endNanos;
            return (end - this.startNanos) / 1_000_000_000.0;
        }

        /** Real TPS of the stage (ticks / wall seconds). */
        public double tps() {
            final double s = this.seconds();
            return s <= 0 ? 0.0 : this.ticks / s;
        }

        public double avgMspt() {
            return this.ticks == 0 ? 0.0 : this.sum / this.ticks;
        }

        public double maxMspt() {
            return this.max;
        }

        /** Ticks that took longer than 50 ms. */
        public int lagTicks() {
            return this.over50;
        }

        /** Percentile of tick duration, {@code p} in [0, 100]. */
        public double percentile(double p) {
            if (this.ticks == 0) {
                return 0.0;
            }
            final double[] sorted = Arrays.copyOf(this.durations, this.ticks);
            Arrays.sort(sorted);
            final int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
            return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
        }
    }

    private final StressPlugin plugin;
    private final StressConfig config;
    private final World world;
    private final String mode;
    private final boolean single;
    private final CommandSender initiator;
    private final long totalTicks;
    private final long[] stageEnds = new long[Stage.TIMELINE.length];
    private final ChunkTickets tickets;
    private final BlockWriter blocks;
    private final int baseY;
    private final List<Handle> handles = new ArrayList<>();
    private final List<StageStats> stats = new ArrayList<>();
    private final long startedAtMillis = System.currentTimeMillis();
    private @Nullable StageStats currentStats;
    private Stage stage = Stage.BASELINE;
    private boolean stageEntered;
    private long tick;
    private @Nullable BukkitTask task;
    private boolean finished;
    private final boolean profileRequested;
    private boolean profilerAutoStarted;
    private @Nullable String endReason;

    /**
     * @param mode {@code "all"} or a module id (single-module mode)
     * @param profile run the built-in profiler from the first to the last tick of the run
     */
    StageRunner(StressPlugin plugin, StressConfig config, World world, String mode, int totalSeconds, CommandSender initiator,
                boolean profile) {
        this.plugin = plugin;
        this.config = config;
        this.world = world;
        this.mode = mode;
        this.single = !mode.equals("all");
        this.initiator = initiator;
        this.profileRequested = profile;
        this.totalTicks = totalSeconds * 20L;
        this.tickets = new ChunkTickets(plugin, world);
        this.blocks = new BlockWriter(world);
        this.baseY = plugin.testWorld().baseY(world, config);
        computeTimeline(config, this.totalTicks, this.stageEnds);
    }

    /**
     * Stage i (0..5) lasts its configured seconds; MAX takes the rest. If that would leave MAX shorter than
     * {@code min-max-stage-seconds}, stages 0..5 are scaled down proportionally (integer tick math).
     */
    static void computeTimeline(StressConfig config, long totalTicks, long[] ends) {
        final long[] lengths = new long[6];
        long pre = 0;
        for (int i = 0; i < 6; i++) {
            lengths[i] = config.stageSeconds(i) * 20L;
            pre += lengths[i];
        }
        final long minMax = Math.min(totalTicks, config.minMaxStageSeconds() * 20L);
        final long available = Math.max(0, totalTicks - minMax);
        if (pre > available && pre > 0) {
            for (int i = 0; i < 6; i++) {
                lengths[i] = lengths[i] * available / pre;
            }
        }
        long cursor = 0;
        for (int i = 0; i < 6; i++) {
            cursor += lengths[i];
            ends[i] = cursor;
        }
        ends[6] = totalTicks;
    }

    // ---------------------------------------------------------------- lifecycle

    void begin() {
        this.plugin.testWorld().markUsed(this.world);
        this.plugin.tickMonitor().setSink(this);
        if (this.profileRequested) {
            final ProfilerBridge profiler = this.plugin.profiler();
            if (!profiler.isAvailable()) {
                Msg.warn(this.initiator, "Профайлер недоступен, прогон идёт без замера");
            } else if (profiler.isRunning()) {
                Msg.warn(this.initiator, "Профайлер уже запущен вручную (" + profiler.status() + "), прогон его не трогает");
            } else {
                // Wall-time limit far beyond the run; the run stops the profiler exactly at its end.
                profiler.start(this.initiator, (int) Math.min(Integer.MAX_VALUE, this.totalTicks / 20L * 10L + 60L));
                this.profilerAutoStarted = profiler.isRunning();
            }
        }
        this.task = Bukkit.getScheduler().runTaskTimer(this.plugin, this::onTick, 1L, 1L);
    }

    private void onTick() {
        if (this.finished) {
            return;
        }
        final long now = this.tick;
        final Stage target = this.single ? Stage.SINGLE : this.stageAt(now);
        if (!this.stageEntered || target != this.stage) {
            this.enterStage(target, now);
        }
        for (final Handle handle : this.handles) {
            if (handle.failed()) {
                continue;
            }
            try {
                handle.module.tick(now);
            } catch (final Throwable t) {
                this.fail(handle, "tick", t);
            }
        }
        final int logEvery = this.config.logIntervalSeconds() * 20;
        if (logEvery > 0 && now > 0 && now % logEvery == 0) {
            this.plugin.getLogger().info(StatusPrinter.shortLine(this.plugin, this));
        }
        this.tick++;
        if (this.tick >= this.totalTicks) {
            this.stop("время прогона истекло", true);
        }
    }

    private Stage stageAt(long t) {
        for (int i = 0; i < this.stageEnds.length; i++) {
            if (t < this.stageEnds[i]) {
                return Stage.TIMELINE[i];
            }
        }
        return Stage.MAX;
    }

    private void enterStage(Stage target, long now) {
        if (this.currentStats != null) {
            this.currentStats.end();
        }
        this.stage = target;
        this.stageEntered = true;
        this.currentStats = new StageStats(target);
        this.stats.add(this.currentStats);
        if (this.config.broadcastStageChanges()) {
            Msg.broadcast("Этап " + target.display() + " — t=" + (now / 20) + " с", NamedTextColor.AQUA, null);
        }
        final List<ModuleRegistry.Entry> toStart = new ArrayList<>();
        if (this.single) {
            final ModuleRegistry.Entry entry = this.plugin.modules().get(this.mode);
            if (entry != null) {
                toStart.add(entry);
            }
        } else {
            for (final ModuleRegistry.Entry entry : this.plugin.modules().forStage(target)) {
                if (this.config.moduleEnabled(entry.id())) {
                    toStart.add(entry);
                }
            }
        }
        for (final ModuleRegistry.Entry entry : toStart) {
            this.startModule(entry, now);
        }
        for (final Handle handle : this.handles) {
            if (handle.failed()) {
                continue;
            }
            try {
                handle.module.onStageChange(target);
            } catch (final Throwable t) {
                this.fail(handle, "onStageChange", t);
            }
        }
    }

    private void startModule(ModuleRegistry.Entry entry, long now) {
        final LoadModule module;
        try {
            module = entry.factory().apply(this.plugin);
        } catch (final Throwable t) {
            this.plugin.getLogger().log(Level.SEVERE, "Cannot create module " + entry.id(), t);
            Msg.broadcast("Модуль " + entry.id() + " не создан: " + t, NamedTextColor.RED, null);
            return;
        }
        if (!entry.id().equals(module.id())) {
            this.plugin.getLogger().warning("Module registered as '" + entry.id() + "' reports id '" + module.id() + "'");
        }
        final RegionAllocator regions = new RegionAllocator(entry.id(), entry.lane(), this.baseY, this.world.getMaxHeight());
        final StressContext context = new StressContext(this.plugin, this, entry.id(), this.world,
            this.config.moduleSection(entry.id()), this.config.seed(), regions, this.tickets, this.blocks);
        context.markStarted(now);
        final Handle handle = new Handle(entry, module, context);
        this.handles.add(handle);
        try {
            module.start(context);
        } catch (final Throwable t) {
            this.fail(handle, "start", t);
        }
    }

    private void fail(Handle handle, String phase, Throwable t) {
        handle.failure = phase + ": " + t;
        this.plugin.getLogger().log(Level.SEVERE, "Module " + handle.id() + " failed in " + phase + ", disabling it", t);
        Msg.broadcast("Модуль " + handle.id() + " упал (" + phase + "): " + t + " — модуль отключён, прогон продолжается",
            NamedTextColor.RED, null);
        try {
            handle.module.stop();
        } catch (final Throwable ignored) {
            // already failing
        }
        handle.context.close();
    }

    void recordTick(double ms) {
        final StageStats stats = this.currentStats;
        if (stats != null && !this.finished) {
            stats.record(ms);
        }
    }

    /**
     * Stops the run: stops modules (reverse start order), cancels their tasks, removes tracked entities,
     * sweeps every remaining non-player entity in stress_world, releases chunk tickets, prints the summary.
     *
     * @param completed true if the timeline finished normally
     */
    public void stop(String reason, boolean completed) {
        if (this.finished) {
            return;
        }
        this.finished = true;
        this.endReason = reason;
        if (this.task != null) {
            this.task.cancel();
        }
        if (this.currentStats != null) {
            this.currentStats.end();
        }
        this.plugin.tickMonitor().setSink(null);
        int removed = 0;
        final List<Handle> reversed = new ArrayList<>(this.handles);
        Collections.reverse(reversed);
        for (final Handle handle : reversed) {
            if (!handle.failed()) {
                try {
                    handle.module.stop();
                } catch (final Throwable t) {
                    this.plugin.getLogger().log(Level.WARNING, "Module " + handle.id() + " failed to stop", t);
                }
            }
            removed += handle.context.close();
        }
        for (final Entity entity : this.world.getEntities()) {
            if (!(entity instanceof Player) && entity.isValid()) {
                entity.remove();
                removed++;
            }
        }
        this.tickets.releaseAll();
        if (this.profilerAutoStarted && this.plugin.profiler().isRunning()) {
            final CommandSender to = this.initiator instanceof Player p && !p.isOnline() ? Bukkit.getConsoleSender() : this.initiator;
            this.plugin.profiler().stop(to);
        }
        // English and stable: CI (scripts/smoke-test.sh) looks for "Run completed (".
        this.plugin.getLogger().info("Run " + (completed ? "completed" : "stopped early") + " (" + reason + ") at tick "
            + this.tick + "/" + this.totalTicks + ", removed " + removed + " entities");
        this.plugin.onRunFinished(this, completed);
    }

    // ---------------------------------------------------------------- read-only state

    public StressConfig config() {
        return this.config;
    }

    public World world() {
        return this.world;
    }

    /** {@code "all"} or the module id. */
    public String mode() {
        return this.mode;
    }

    public boolean isSingleMode() {
        return this.single;
    }

    /** True if this run asked for the built-in profiler ({@code /stress run} or {@code profiler.auto-start-with-run}). */
    public boolean isProfiled() {
        return this.profileRequested;
    }

    public CommandSender initiator() {
        return this.initiator;
    }

    public Stage stage() {
        return this.stage;
    }

    /** Ticks since the run started. */
    public long tick() {
        return this.tick;
    }

    public long totalTicks() {
        return this.totalTicks;
    }

    /** End tick (exclusive) of timeline stage {@code index} 0..6. */
    public long stageEnd(int index) {
        return this.stageEnds[index];
    }

    public boolean isFinished() {
        return this.finished;
    }

    public @Nullable String endReason() {
        return this.endReason;
    }

    public long startedAtMillis() {
        return this.startedAtMillis;
    }

    public int baseY() {
        return this.baseY;
    }

    public ChunkTickets tickets() {
        return this.tickets;
    }

    public BlockWriter blocks() {
        return this.blocks;
    }

    public List<Handle> handles() {
        return Collections.unmodifiableList(this.handles);
    }

    public List<StageStats> stats() {
        return Collections.unmodifiableList(this.stats);
    }
}
