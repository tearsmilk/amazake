package ink.hoka.amazake.stress;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitTask;
import org.jspecify.annotations.Nullable;

/**
 * Everything a {@link LoadModule} may use. One context per module per run.
 *
 * <p>Threading: all methods are main-thread only, except {@link #isActive()}, {@link #seed()},
 * {@link #moduleSeed()}, {@link #newRandom(String)}, {@link #config()} reads, {@link #logger()} and the
 * {@code run*} scheduling methods, which are safe from any thread.
 */
public final class StressContext {

    private final StressPlugin plugin;
    private final StageRunner runner;
    private final String moduleId;
    private final World world;
    private final ConfigurationSection config;
    private final long seed;
    private final long moduleSeed;
    private final Random random;
    private final RegionAllocator regions;
    private final EntityRegistry entities = new EntityRegistry();
    private final ChunkTickets chunks;
    private final BlockWriter blocks;
    private final List<BukkitTask> tasks = new ArrayList<>();
    private final Deque<AutoCloseable> closers = new ArrayDeque<>();
    private volatile boolean active = true;
    private long startTick = -1;

    StressContext(StressPlugin plugin, StageRunner runner, String moduleId, World world, ConfigurationSection config,
                  long seed, RegionAllocator regions, ChunkTickets chunks, BlockWriter blocks) {
        this.plugin = plugin;
        this.runner = runner;
        this.moduleId = moduleId;
        this.world = world;
        this.config = config;
        this.seed = seed;
        this.moduleSeed = deriveSeed(seed, moduleId);
        this.random = new Random(this.moduleSeed);
        this.regions = regions;
        this.chunks = chunks;
        this.blocks = blocks;
    }

    /** Deterministic seed derivation (FNV-1a over UTF-8 of the salt, mixed with the base seed). */
    public static long deriveSeed(long base, String salt) {
        long h = 0xcbf29ce484222325L;
        for (final byte b : salt.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 0x100000001b3L;
        }
        long z = base ^ h;
        z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L;
        z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL;
        return z ^ (z >>> 31);
    }

    // ---------------------------------------------------------------- basics

    public StressPlugin plugin() {
        return this.plugin;
    }

    public Logger logger() {
        return this.plugin.getLogger();
    }

    /** The test world (always stress_world). */
    public World world() {
        return this.world;
    }

    public String moduleId() {
        return this.moduleId;
    }

    /** This module's config section ({@code modules.<id>} of config.yml); empty section if missing. Read-only. */
    public ConfigurationSection config() {
        return this.config;
    }

    /** Global seed from config ({@code world.seed}). */
    public long seed() {
        return this.seed;
    }

    /** Seed of this module: {@code deriveSeed(seed, moduleId)}. */
    public long moduleSeed() {
        return this.moduleSeed;
    }

    /**
     * This module's seeded Random ({@code new Random(moduleSeed())}). Use it for EVERY random choice made on
     * the main thread, in a fixed order, so the load is identical on both servers.
     */
    public Random random() {
        return this.random;
    }

    /** A new independent Random seeded with {@code deriveSeed(moduleSeed(), salt)}; use one per async worker. */
    public Random newRandom(String salt) {
        return new Random(deriveSeed(this.moduleSeed, salt));
    }

    // ---------------------------------------------------------------- world helpers

    /** Allocator for this module's lane (fixed coordinates per module id). */
    public RegionAllocator regions() {
        return this.regions;
    }

    /** Shortcut for {@code regions().allocate(name, chunksX, chunksZ)}. */
    public Region allocateRegion(String name, int chunksX, int chunksZ) {
        return this.regions.allocate(name, chunksX, chunksZ);
    }

    /** Y where builds start (top of the flat ground + 1 for FLAT worlds). */
    public int baseY() {
        return this.regions.baseY();
    }

    /** Entities of this module; removed automatically on stop. */
    public EntityRegistry entities() {
        return this.entities;
    }

    /** Plugin chunk tickets shared by the run (released automatically on stop). */
    public ChunkTickets chunks() {
        return this.chunks;
    }

    /** Fast block setter for stress_world. */
    public BlockWriter blocks() {
        return this.blocks;
    }

    /**
     * Spawns an entity in stress_world and tracks it. {@code randomizeData=false}: no random equipment or
     * variants, so spawns are deterministic. The location must be in stress_world.
     */
    public <T extends Entity> T spawn(Location location, Class<T> type, @Nullable Consumer<? super T> init) {
        if (location.getWorld() == null || !location.getWorld().equals(this.world)) {
            throw new IllegalArgumentException("AmazakeStress only spawns in " + TestWorld.NAME);
        }
        return this.entities.track(this.world.spawn(location, type, false, init));
    }

    // ---------------------------------------------------------------- timeline

    /** Current stage ({@link Stage#SINGLE} in single-module mode). */
    public Stage stage() {
        return this.runner.stage();
    }

    public boolean isMaxStage() {
        return this.runner.stage() == Stage.MAX;
    }

    /** 1.0 normally, {@code timeline.max-stage-multiplier} during {@link Stage#MAX}. */
    public double intensity() {
        return this.isMaxStage() ? this.runner.config().maxStageMultiplier() : 1.0;
    }

    /** {@code intensity()} applied to a base amount, rounded down, never below 0. */
    public int scaled(int base) {
        return Math.max(0, (int) Math.floor(base * this.intensity()));
    }

    /** True when the run was started as {@code /stress start <module>}. */
    public boolean isSingleMode() {
        return this.runner.isSingleMode();
    }

    /** Ticks since the run started. */
    public long runTick() {
        return this.runner.tick();
    }

    /** Ticks since this module was started. */
    public long moduleTick() {
        return this.startTick < 0 ? 0 : this.runner.tick() - this.startTick;
    }

    /** Total length of the run in ticks. */
    public long totalTicks() {
        return this.runner.totalTicks();
    }

    // ---------------------------------------------------------------- scheduling (cancelled on stop)

    /** False once the module is stopped. Async loops must check it and exit. Thread-safe. */
    public boolean isActive() {
        return this.active;
    }

    /** Repeating main-thread task, cancelled automatically on stop. */
    public synchronized BukkitTask runTimer(Runnable task, long delay, long period) {
        return this.track(Bukkit.getScheduler().runTaskTimer(this.plugin, this.guard(task), delay, period));
    }

    /** Delayed main-thread task, cancelled automatically on stop. */
    public synchronized BukkitTask runLater(Runnable task, long delay) {
        return this.track(Bukkit.getScheduler().runTaskLater(this.plugin, this.guard(task), delay));
    }

    /** Main-thread task on the next tick (safe to call from async threads). */
    public synchronized BukkitTask runSync(Runnable task) {
        return this.track(Bukkit.getScheduler().runTask(this.plugin, this.guard(task)));
    }

    /** Async task on the Bukkit scheduler pool. */
    public synchronized BukkitTask runAsync(Runnable task) {
        return this.track(Bukkit.getScheduler().runTaskAsynchronously(this.plugin, this.guard(task)));
    }

    /** Repeating async task on the Bukkit scheduler pool. */
    public synchronized BukkitTask runAsyncTimer(Runnable task, long delay, long period) {
        return this.track(Bukkit.getScheduler().runTaskTimerAsynchronously(this.plugin, this.guard(task), delay, period));
    }

    /** Registers a task created elsewhere so it is cancelled on stop. */
    public synchronized BukkitTask track(BukkitTask task) {
        if (!this.active) {
            task.cancel();
        } else {
            this.tasks.removeIf(BukkitTask::isCancelled);
            this.tasks.add(task);
        }
        return task;
    }

    /** Registers a resource (thread pool, ...) closed on stop, in reverse registration order. */
    public synchronized <T extends AutoCloseable> T closeOnStop(T closeable) {
        this.closers.push(closeable);
        return closeable;
    }

    /** Registers an action run on stop (main thread), in reverse registration order. */
    public void onStop(Runnable action) {
        this.closeOnStop((AutoCloseable) action::run);
    }

    private Runnable guard(Runnable task) {
        return () -> {
            if (!this.active) {
                return;
            }
            try {
                task.run();
            } catch (final Throwable t) {
                this.logger().log(Level.WARNING, "[" + this.moduleId + "] task failed", t);
            }
        };
    }

    // ---------------------------------------------------------------- framework internals

    void markStarted(long tick) {
        this.startTick = tick;
    }

    /** Cancels tasks, runs closers, removes tracked entities. Main thread. Returns removed entity count. */
    int close() {
        final List<BukkitTask> toCancel;
        final List<AutoCloseable> toClose;
        synchronized (this) {
            this.active = false;
            toCancel = new ArrayList<>(this.tasks);
            this.tasks.clear();
            toClose = new ArrayList<>(this.closers);
            this.closers.clear();
        }
        for (final BukkitTask task : toCancel) {
            task.cancel();
        }
        for (final AutoCloseable closeable : toClose) {
            try {
                closeable.close();
            } catch (final Exception e) {
                this.logger().log(Level.WARNING, "[" + this.moduleId + "] close failed", e);
            }
        }
        return this.entities.removeAll();
    }
}
