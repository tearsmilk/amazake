package ink.hoka.amazake.stress.redstone;

import ink.hoka.amazake.stress.LoadModule;
import ink.hoka.amazake.stress.Region;
import ink.hoka.amazake.stress.Stage;
import ink.hoka.amazake.stress.StageRunner;
import ink.hoka.amazake.stress.StressContext;
import ink.hoka.amazake.stress.StressPlugin;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.minecart.HopperMinecart;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Load module "redstone": self-running redstone contraptions built from typed {@code BlockData} in this
 * module's own regions of stress_world:
 * <ul>
 *   <li>clocks: observer clocks and torch/repeater ring clocks (alternating), each driving a long dust line
 *       on redstone lamps (dust power updates and lamp toggles are the expensive part);</li>
 *   <li>piston walls: a clock line powering two rows of sticky pistons that move slime/honey + stone;</li>
 *   <li>hopper chains: closed hopper loops (items never run out) with a dropper fired by an observer clock
 *       and a comparator reading a hopper into a dust line;</li>
 *   <li>minecart loops: rail squares with powered rails and hopper minecarts going round.</li>
 * </ul>
 * Everything is built over several ticks with a fixed block budget per tick, each contraption starts itself
 * with one "kick" block placed last with physics, and the layout, item types and counts depend only on the
 * config and the seed. During the MAX stage extra contraptions are added up to
 * {@code count * timeline.max-stage-multiplier} (their space is reserved at start, so coordinates are fixed).
 */
public final class RedstoneModule implements LoadModule {

    /** Contraption kinds, in build and allocation order. */
    enum Kind {
        CLOCK("clocks"), PISTON_WALL("pistons"), HOPPER_CHAIN("hoppers"), MINECART_LOOP("minecarts");

        final String region;

        Kind(String region) {
            this.region = region;
        }
    }

    private static final int CLEAR_HEIGHT = 5;          // blocks cleared above the build base (y0 .. y0+4)
    private static final int CART_CHECK_PERIOD = 100;   // ticks

    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final StressPlugin plugin;
    private StressContext ctx;
    private Settings settings;
    private Contraptions contraptions;
    private BuildQueue queue;

    private final Map<Kind, Layout> layouts = new EnumMap<>(Kind.class);
    private final Map<Kind, Integer> baseCount = new EnumMap<>(Kind.class);
    private final Map<Kind, Integer> maxCount = new EnumMap<>(Kind.class);
    private final Map<Kind, Integer> planned = new EnumMap<>(Kind.class);
    private final Map<Kind, Integer> running = new EnumMap<>(Kind.class);
    private final List<Plan.Placement> kills = new ArrayList<>();
    private final List<Region> cartRegions = new ArrayList<>();

    private boolean chunksReady;
    private boolean maxRequested;
    private boolean maxQueued;
    private boolean stopped;
    private int hoppers;
    private int cartsSpawned;
    private int cartsLost;

    public RedstoneModule(StressPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "redstone";
    }

    // ------------------------------------------------------------------ settings

    /** Snapshot of {@code modules.redstone}, clamped to sane ranges. */
    private record Settings(
        int clocks, int clockLength, int clockRingDelay, int lineRepeaterDelay,
        int pistonWalls, int pistonWallLength, int pistonClockDelay,
        int hopperChains, int hopperChainLength, int itemsPerChain, int hopperLineLength, boolean hopperDropperClock,
        int minecartLoops, int minecartLoopSize, int minecartsPerLoop, double minecartSpeed,
        int blocksPerTick, boolean clearBeforeBuild, boolean maxStageExtra) {

        static Settings read(ConfigurationSection c) {
            return new Settings(
                clamp(c.getInt("clocks", 40), 0, 2000),
                clamp(c.getInt("clock-length", 64), 1, 1000),
                clamp(c.getInt("clock-ring-delay", 1), 1, 4),
                clamp(c.getInt("line-repeater-delay", 1), 1, 4),
                clamp(c.getInt("piston-walls", 16), 0, 1000),
                clamp(c.getInt("piston-wall-length", 32), 4, 1000),
                clamp(c.getInt("piston-clock-delay", 2), 1, 4),
                clamp(c.getInt("hopper-chains", 64), 0, 2000),
                clamp(c.getInt("hopper-chain-length", 20), 3, 500),
                clamp(c.getInt("items-per-chain", 320), 0, 100_000),
                clamp(c.getInt("hopper-line-length", 24), 1, 1000),
                c.getBoolean("hopper-dropper-clock", true),
                clamp(c.getInt("minecart-loops", 8), 0, 500),
                clamp(c.getInt("minecart-loop-size", 24), 4, 200),
                clamp(c.getInt("minecarts-per-loop", 16), 0, 400),
                Math.max(0.0, Math.min(1.0, c.getDouble("minecart-speed", 0.4))),
                clamp(c.getInt("blocks-per-tick", 4000), 100, 1_000_000),
                c.getBoolean("clear-before-build", true),
                c.getBoolean("max-stage-extra", true));
        }

        private static int clamp(int v, int min, int max) {
            return Math.max(min, Math.min(max, v));
        }

        int count(Kind kind) {
            return switch (kind) {
                case CLOCK -> this.clocks;
                case PISTON_WALL -> this.pistonWalls;
                case HOPPER_CHAIN -> this.hopperChains;
                case MINECART_LOOP -> this.minecartLoops;
            };
        }

        int cellW(Kind kind) {
            return switch (kind) {
                case CLOCK -> 4 + this.clockLength + 2;
                case PISTON_WALL -> 4 + this.pistonWallLength + 2;
                case HOPPER_CHAIN -> Math.max(this.hopperChainLength, this.hopperChainLength / 2 + 1 + this.hopperLineLength) + 2;
                case MINECART_LOOP -> this.minecartLoopSize + 2;
            };
        }

        int cellD(Kind kind) {
            return switch (kind) {
                case CLOCK -> 5;
                case PISTON_WALL -> 8;
                case HOPPER_CHAIN -> 6;
                case MINECART_LOOP -> this.minecartLoopSize + 2;
            };
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start(StressContext ctx) {
        this.ctx = ctx;
        this.settings = Settings.read(ctx.config());
        this.contraptions = new Contraptions(new Parts(ctx.blocks()));
        this.queue = new BuildQueue(ctx.blocks(), this.settings.clearBeforeBuild(), this::onBuilt);

        final double multiplier = this.maxMultiplier();
        final List<CompletableFuture<Void>> loads = new ArrayList<>();
        for (final Kind kind : Kind.values()) {
            final int base = this.settings.count(kind);
            final int max = base == 0 ? 0 : Math.max(base, (int) Math.floor(base * multiplier));
            this.baseCount.put(kind, base);
            this.maxCount.put(kind, max);
            this.planned.put(kind, 0);
            this.running.put(kind, 0);
            if (max == 0) {
                continue;
            }
            final int cellW = this.settings.cellW(kind);
            final int cellD = this.settings.cellD(kind);
            final int chunksX = Layout.chunksX(max, cellW);
            final int chunksZ = Layout.chunksZ(max, cellW, cellD);
            final Region region = ctx.allocateRegion(kind.region, chunksX, chunksZ);
            this.layouts.put(kind, Layout.of(region, max, cellW, cellD));
            if (kind == Kind.MINECART_LOOP) {
                this.cartRegions.add(region);
            }
            loads.add(ctx.chunks().loadAndHold(region));
        }

        CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0])).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().warning("[redstone] chunk loading failed: " + error);
                return;
            }
            // Paper completes getChunkAtAsync futures on the main thread; runSync keeps it safe either way.
            ctx.runSync(() -> {
                if (this.stopped) {
                    return;
                }
                this.chunksReady = true;
                this.enqueue(false);
                if (this.maxRequested) {
                    this.enqueue(true);
                }
            });
        });

        ctx.runTimer(() -> {
            if (!this.stopped) {
                this.queue.step(this.settings.blocksPerTick());
            }
        }, 1L, 1L);
    }

    @Override
    public void onStageChange(Stage stage) {
        if (stage != Stage.MAX || this.maxRequested) {
            return;
        }
        this.maxRequested = true;
        if (this.chunksReady) {
            this.enqueue(true);
        }
    }

    @Override
    public void tick(long tick) {
        if (this.stopped || this.cartRegions.isEmpty() || this.ctx.moduleTick() % CART_CHECK_PERIOD != 0) {
            return;
        }
        // keep carts inside the loop regions (a derailed cart is removed)
        for (final Entity entity : this.ctx.entities().snapshot()) {
            if (!entity.isValid()) {
                continue;
            }
            final Location l = entity.getLocation();
            boolean inside = false;
            for (final Region region : this.cartRegions) {
                if (region.contains(l.getBlockX(), l.getBlockY(), l.getBlockZ())) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                this.ctx.entities().untrack(entity);
                entity.remove();
                this.cartsLost++;
            }
        }
    }

    @Override
    public void stop() {
        if (this.stopped) {
            return;
        }
        this.stopped = true;
        if (this.queue != null) {
            this.queue.clear();
        }
        if (this.ctx == null) {
            return;
        }
        // switch every started clock off (no physics, only in loaded chunks: never load a chunk for this)
        final World world = this.ctx.world();
        for (final Plan.Placement kill : this.kills) {
            if (world.isChunkLoaded(kill.x() >> 4, kill.z() >> 4)) {
                this.ctx.blocks().set(kill.x(), kill.y(), kill.z(), kill.data());
            }
        }
        this.kills.clear();
        // tracked minecarts are removed by the framework
    }

    @Override
    public String status() {
        if (this.ctx == null) {
            return "не запущен";
        }
        if (!this.chunksReady && !this.stopped) {
            return "загрузка чанков…";
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("часы ").append(this.counts(Kind.CLOCK))
            .append(", поршн. стены ").append(this.counts(Kind.PISTON_WALL))
            .append(", цепочки воронок ").append(this.counts(Kind.HOPPER_CHAIN))
            .append(" (воронок ").append(this.hoppers).append(')')
            .append(", петли вагонеток ").append(this.counts(Kind.MINECART_LOOP))
            .append(" (вагонеток ").append(this.ctx.entities().aliveCount()).append('/').append(this.cartsSpawned);
        if (this.cartsLost > 0) {
            sb.append(", сошло ").append(this.cartsLost);
        }
        sb.append(')');
        if (!this.queue.isIdle()) {
            sb.append(", строится: в очереди ").append(this.queue.pending());
        }
        sb.append(", блоков ").append(this.queue.placedBlocks());
        return sb.toString();
    }

    private String counts(Kind kind) {
        return this.running.get(kind) + "/" + this.planned.get(kind);
    }

    // ------------------------------------------------------------------ building

    private double maxMultiplier() {
        if (this.ctx.isSingleMode() || !this.settings.maxStageExtra()) {
            return 1.0;
        }
        final StageRunner run = this.ctx.plugin().currentRun();
        return run == null ? 1.0 : Math.max(1.0, run.config().maxStageMultiplier());
    }

    /** Queues the base contraptions, or the MAX-stage extras, in a fixed order. */
    private void enqueue(boolean extras) {
        if (extras) {
            if (this.maxQueued) {
                return;
            }
            this.maxQueued = true;
        }
        final Random random = this.ctx.random();
        for (final Kind kind : Kind.values()) {
            final Layout layout = this.layouts.get(kind);
            if (layout == null) {
                continue;
            }
            final int from = extras ? this.baseCount.get(kind) : 0;
            final int to = extras ? this.maxCount.get(kind) : this.baseCount.get(kind);
            for (int i = from; i < to; i++) {
                this.queue.add(this.plan(kind, i, layout, random));
                this.planned.merge(kind, 1, Integer::sum);
            }
        }
    }

    private Plan plan(Kind kind, int i, Layout layout, Random random) {
        final Settings s = this.settings;
        final Plan plan = new Plan(kind, i);
        final int cx = layout.cellX(i);
        final int cz = layout.cellZ(i);
        final int y0 = layout.region().minY();
        plan.clearBox(cx, y0, cz, cx + layout.cellW() - 1, Math.min(layout.region().maxY(), y0 + CLEAR_HEIGHT - 1),
            cz + layout.cellD() - 1);
        switch (kind) {
            case CLOCK -> {
                final int oz = cz + 1;
                if ((i & 1) == 0) {
                    this.contraptions.observerClock(plan, cx, y0, oz, s.clockLength(), s.lineRepeaterDelay());
                } else {
                    this.contraptions.repeaterClock(plan, cx, y0, oz, s.clockLength(), s.clockRingDelay(),
                        s.lineRepeaterDelay());
                }
            }
            // one block above the ground: slime would otherwise drag the ground blocks along
            case PISTON_WALL -> this.contraptions.pistonWall(plan, cx, y0 + 1, cz + 3, s.pistonWallLength(),
                s.pistonClockDelay(), s.lineRepeaterDelay());
            case HOPPER_CHAIN -> this.contraptions.hopperLoop(plan, cx, y0, cz + 3, s.hopperChainLength(),
                s.itemsPerChain(), s.hopperLineLength(), s.hopperDropperClock(), random, this::fill);
            case MINECART_LOOP -> {
                final List<Contraptions.CartSpawn> carts = this.contraptions.minecartLoop(plan, cx + 1, y0, cz + 1,
                    s.minecartLoopSize(), s.minecartsPerLoop(), s.minecartSpeed());
                for (final Contraptions.CartSpawn cart : carts) {
                    plan.action(20, () -> this.spawnCart(cart));
                }
            }
        }
        return plan;
    }

    private void onBuilt(Plan plan) {
        this.kills.addAll(plan.kills);
        this.running.merge(plan.kind, 1, Integer::sum);
        if (plan.kind == Kind.HOPPER_CHAIN) {
            this.hoppers += 2 * this.settings.hopperChainLength() - (this.settings.hopperDropperClock() ? 1 : 0);
        }
    }

    /** Replaces the live inventory of the container at (x, y, z) with {@code amount} items in stacks of 64. */
    private void fill(int x, int y, int z, Material item, int amount) {
        final Block block = this.ctx.blocks().block(x, y, z);
        final BlockState state = block.getState(false);
        if (!(state instanceof Container container)) {
            return;
        }
        final Inventory inventory = container.getInventory();
        final ItemStack[] contents = new ItemStack[inventory.getSize()];
        int left = amount;
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            final int stack = Math.min(64, left);
            contents[slot] = new ItemStack(item, stack);
            left -= stack;
        }
        inventory.setContents(contents);
    }

    private void spawnCart(Contraptions.CartSpawn cart) {
        final Location location = new Location(this.ctx.world(), cart.x() + 0.5, cart.y() + 0.0625, cart.z() + 0.5);
        final Vector velocity = new Vector(cart.vx(), 0.0, cart.vz());
        this.ctx.spawn(location, HopperMinecart.class, minecart -> minecart.setVelocity(velocity));
        this.cartsSpawned++;
    }
}
