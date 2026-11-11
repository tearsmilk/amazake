package ink.hoka.amazake.stress.chunks;

import ink.hoka.amazake.stress.LoadModule;
import ink.hoka.amazake.stress.RegionAllocator;
import ink.hoka.amazake.stress.Stage;
import ink.hoka.amazake.stress.StressContext;
import ink.hoka.amazake.stress.StressPlugin;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Load module "chunks": deterministic chunk churn without players.
 *
 * <p>A square "ring" of chunks (radius {@code ring-radius}) around a moving center is loaded with
 * {@link World#getChunkAtAsync(int, int, boolean)} and held with plugin chunk tickets, nearest chunks first
 * (the load spreads outwards like around a player who just arrived). Every {@code move-interval-ticks} the
 * center moves {@code move-step-chunks} along +Z with a seeded sideways shift: chunks that left the ring are
 * released (unloaded and saved by the server), new chunks enter the queue (generation and lighting). This is
 * what players flying over new terrain cost a server. At most {@code max-concurrent} requests are in flight.
 *
 * <p>Everything stays inside this module's lane (lane 3, chunk X {@code [392, 519]}), so coordinates are
 * identical on every server. The path is computed from the module's seeded Random only.
 */
public final class ChunksModule implements LoadModule {

    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final StressPlugin plugin;
    private StressContext ctx;
    private World world;

    // settings
    private int radius;
    private int chunksPerTick;
    private int maxConcurrent;
    private int moveIntervalTicks;
    private int moveStep;
    private int maxShift;
    private boolean generate;
    private boolean urgent;
    private int releasePerTick;

    // lane geometry
    private int laneCenterX;
    private int minCenterZ;
    private int maxCenterZ;

    // state (main thread only)
    private int centerX;
    private int centerZ;
    private int direction = 1;
    private final Set<Long> wanted = new HashSet<>();
    private final Set<Long> held = new HashSet<>();
    private final Set<Long> inFlight = new HashSet<>();
    private final Deque<Long> queue = new ArrayDeque<>();
    private final Deque<Long> toRelease = new ArrayDeque<>();
    private long nextMoveTick;
    private double intensity = 1.0;
    private boolean stopped;

    // counters
    private long requested;
    private long loaded;
    private long released;
    private long failed;
    private int moves;

    public ChunksModule(StressPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "chunks";
    }

    @Override
    public void start(StressContext ctx) {
        this.ctx = ctx;
        this.world = ctx.world();
        final ConfigurationSection cfg = ctx.config();
        final int halfLane = RegionAllocator.LANE_SPACING_CHUNKS / 2;
        this.radius = clamp(cfg.getInt("ring-radius", 24), 1, halfLane - 2);
        this.chunksPerTick = Math.max(1, cfg.getInt("chunks-per-tick", 16));
        this.maxConcurrent = Math.max(1, cfg.getInt("max-concurrent", 96));
        this.moveIntervalTicks = Math.max(1, cfg.getInt("move-interval-ticks", 200));
        this.moveStep = Math.max(1, cfg.getInt("move-step-chunks", 8));
        this.maxShift = clamp(cfg.getInt("max-side-shift", 8), 0, Math.max(0, halfLane - 1 - this.radius));
        this.generate = cfg.getBoolean("generate", true);
        this.urgent = cfg.getBoolean("urgent", false);
        this.releasePerTick = Math.max(0, cfg.getInt("releases-per-tick", 64));

        final int laneMinChunkX = RegionAllocator.LANE_ORIGIN_CHUNK_X + ctx.regions().lane() * RegionAllocator.LANE_SPACING_CHUNKS;
        this.laneCenterX = laneMinChunkX + halfLane;
        this.minCenterZ = RegionAllocator.LANE_ORIGIN_CHUNK_Z + this.radius;
        this.maxCenterZ = RegionAllocator.LANE_ORIGIN_CHUNK_Z + RegionAllocator.LANE_LENGTH_CHUNKS - 1 - this.radius;

        this.centerX = this.laneCenterX;
        this.centerZ = this.minCenterZ;
        this.nextMoveTick = this.moveIntervalTicks;
        this.rebuildRing();
        ctx.logger().info("[chunks] ring radius " + this.radius + " (" + this.wanted.size() + " chunks) around chunk "
            + this.centerX + "," + this.centerZ + ", step " + this.moveStep + " every " + this.moveIntervalTicks + " ticks");
    }

    @Override
    public void onStageChange(Stage stage) {
        if (this.ctx != null) {
            this.intensity = this.ctx.intensity();
        }
    }

    @Override
    public void tick(long tick) {
        if (this.stopped || this.ctx.chunks().isReleased()) {
            return;
        }
        final long moduleTick = this.ctx.moduleTick();
        if (moduleTick >= this.nextMoveTick) {
            this.move();
            // MAX stage moves faster (intensity > 1 shortens the interval).
            this.nextMoveTick = moduleTick + Math.max(1L, Math.round(this.moveIntervalTicks / Math.max(0.01, this.intensity)));
        }
        // Release chunks that left the ring (spread over ticks so the unload work is not one spike).
        int releases = this.releasePerTick == 0 ? Integer.MAX_VALUE : this.releasePerTick;
        while (releases-- > 0 && !this.toRelease.isEmpty()) {
            final long key = this.toRelease.poll();
            if (!this.wanted.contains(key) && this.held.remove(key)) {
                this.ctx.chunks().release(chunkX(key), chunkZ(key));
                this.released++;
            }
        }
        // Issue new requests, nearest first.
        final int limit = Math.max(1, (int) Math.floor(this.chunksPerTick * this.intensity));
        final int concurrency = Math.max(1, (int) Math.floor(this.maxConcurrent * this.intensity));
        int issued = 0;
        while (issued < limit && this.inFlight.size() < concurrency && !this.queue.isEmpty()) {
            final long key = this.queue.poll();
            if (!this.wanted.contains(key) || this.held.contains(key) || this.inFlight.contains(key)) {
                continue;
            }
            this.request(key);
            issued++;
        }
    }

    private void request(long key) {
        final int cx = chunkX(key);
        final int cz = chunkZ(key);
        this.inFlight.add(key);
        this.requested++;
        // The future completes on the main thread (World#getChunkAtAsync contract).
        this.world.getChunkAtAsync(cx, cz, this.generate, this.urgent).whenComplete((chunk, error) -> {
            this.inFlight.remove(key);
            if (this.stopped || !this.ctx.isActive() || this.ctx.chunks().isReleased()) {
                return;
            }
            if (error != null || chunk == null) {
                this.failed++;
                return;
            }
            this.loaded++;
            if (this.wanted.contains(key) && !this.held.contains(key) && this.ctx.chunks().hold(cx, cz)) {
                this.held.add(key);
            }
        });
    }

    /** Moves the center one step along the fixed, seeded path and rebuilds the wanted set. */
    private void move() {
        int nextZ = this.centerZ + this.direction * this.moveStep;
        if (nextZ > this.maxCenterZ || nextZ < this.minCenterZ) {
            this.direction = -this.direction; // end of the lane: fly back over known terrain
            nextZ = this.centerZ + this.direction * this.moveStep;
            nextZ = clamp(nextZ, this.minCenterZ, this.maxCenterZ);
        }
        final int shift = this.maxShift == 0 ? 0 : this.ctx.random().nextInt(this.maxShift * 2 + 1) - this.maxShift;
        this.centerX = this.laneCenterX + shift;
        this.centerZ = nextZ;
        this.moves++;
        this.rebuildRing();
    }

    private void rebuildRing() {
        this.wanted.clear();
        final List<long[]> order = new ArrayList<>((2 * this.radius + 1) * (2 * this.radius + 1));
        for (int dx = -this.radius; dx <= this.radius; dx++) {
            for (int dz = -this.radius; dz <= this.radius; dz++) {
                final int cx = this.centerX + dx;
                final int cz = this.centerZ + dz;
                final long key = key(cx, cz);
                this.wanted.add(key);
                // distance², then x, then z: a fixed order independent of hashing
                order.add(new long[]{(long) dx * dx + (long) dz * dz, cx, cz});
            }
        }
        order.sort(Comparator.<long[]>comparingLong(a -> a[0]).thenComparingLong(a -> a[1]).thenComparingLong(a -> a[2]));
        this.queue.clear();
        for (final long[] entry : order) {
            final long key = key((int) entry[1], (int) entry[2]);
            if (!this.held.contains(key)) {
                this.queue.add(key);
            }
        }
        // Chunks held but no longer wanted, in a fixed order (x, z).
        final List<Long> leaving = new ArrayList<>();
        for (final long key : this.held) {
            if (!this.wanted.contains(key)) {
                leaving.add(key);
            }
        }
        leaving.sort(Comparator.<Long>comparingInt(k -> chunkX(k)).thenComparingInt(k -> chunkZ(k)));
        this.toRelease.addAll(leaving);
    }

    @Override
    public void stop() {
        this.stopped = true;
        if (this.ctx != null && !this.ctx.chunks().isReleased()) {
            for (final long key : this.held) {
                this.ctx.chunks().release(chunkX(key), chunkZ(key));
            }
        }
        this.held.clear();
        this.queue.clear();
        this.toRelease.clear();
        this.wanted.clear();
    }

    @Override
    public String status() {
        if (this.ctx == null) {
            return "не запущен";
        }
        return "центр " + this.centerX + "," + this.centerZ + " (сдвигов " + this.moves + "), держим " + this.held.size()
            + "/" + this.wanted.size() + ", в очереди " + this.queue.size() + ", в полёте " + this.inFlight.size()
            + ", загружено " + this.loaded + "/" + this.requested + ", выгружено " + this.released
            + (this.failed > 0 ? ", ошибок " + this.failed : "");
    }

    // ---------------------------------------------------------------- helpers

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX & 0xFFFFFFFFL) | (((long) chunkZ & 0xFFFFFFFFL) << 32);
    }

    private static int chunkX(long key) {
        return (int) key;
    }

    private static int chunkZ(long key) {
        return (int) (key >>> 32);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
