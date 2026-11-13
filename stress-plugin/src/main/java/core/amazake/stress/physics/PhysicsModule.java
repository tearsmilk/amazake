package core.amazake.stress.physics;

import core.amazake.stress.LoadModule;
import core.amazake.stress.Region;
import core.amazake.stress.StageRunner;
import core.amazake.stress.StressContext;
import core.amazake.stress.StressPlugin;
import core.amazake.stress.mobs.BuildQueue;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Dispenser;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Load module "physics": falling blocks, item entities, XP orbs, fluid flow, dispenser arrows and (optional)
 * TNT, each in its own build area at fixed coordinates and on a fixed cadence in ticks.
 *
 * <ul>
 *   <li><b>falling</b>: columns of sand/gravel/concrete powder stand on a one-block support above an air gap;
 *       every cycle the columns are re-placed (block budget per tick) and all supports are removed in one tick
 *       with physics, so every column collapses into falling-block entities.</li>
 *   <li><b>items</b>: bursts of item entities in clusters (they merge), removed after a fixed lifetime.</li>
 *   <li><b>xp</b>: bursts of experience orbs in clusters (they merge), removed after a fixed lifetime.</li>
 *   <li><b>fluids</b>: water and lava sources on pillars in walled cells, switched on and off on a cadence
 *       (flow spreading and receding).</li>
 *   <li><b>arrows</b>: a wall of dispensers full of arrows fired through the API on a cadence.</li>
 *   <li><b>tnt</b> (off by default): waves of primed TNT in an obsidian chamber; block damage is removed in
 *       {@link EntityExplodeEvent}.</li>
 * </ul>
 *
 * <p>All cadences are counted from the tick the builds finished, so they do not depend on how fast a server
 * loads chunks. In {@code MAX} the amounts become {@code scaled(base)}; builds are sized for that from the start.
 */
public final class PhysicsModule implements LoadModule {

    private static final int ARROW_ROW = 28;

    /** Items/orbs spawned at one tick, removed together after their lifetime. */
    private record Burst(long tick, List<Entity> entities) {
    }

    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final StressPlugin plugin;
    private StressContext ctx;

    // config
    private int buildBudget;
    private int columns, columnHeight, dropHeight, fallInterval;
    private final List<BlockData> fallMaterials = new ArrayList<>();
    private int itemsPerBurst, itemInterval, itemLifetime, itemsPerCluster;
    private final List<Material> itemMaterials = new ArrayList<>();
    private int orbsPerBurst, orbInterval, orbLifetime;
    private int sources, fluidOn, fluidOff;
    private double lavaShare;
    private int dispensers, arrowInterval, arrowRange;
    private boolean tntEnabled;
    private int tntPerWave, tntInterval, tntFuse;
    private float tntYield;

    // layout
    private final List<Region> regions = new ArrayList<>();
    private Region fallRegion, pitRegion, fluidRegion, arrowRegion, tntRegion;
    private int maxColumns, maxSources, maxDispensers;
    private final List<int[]> columnPos = new ArrayList<>();      // x, z
    private final List<BlockData> columnMaterial = new ArrayList<>();
    private final List<int[]> sourcePos = new ArrayList<>();      // x, y, z
    private final List<Boolean> sourceLava = new ArrayList<>();
    private final List<int[]> dispenserPos = new ArrayList<>();   // x, y, z
    private int[] tntInterior;                                    // x0, y0, z0, x1, y1, z1

    // state
    private BuildQueue build;
    private BuildQueue fallQueue;
    private boolean chunksReady, built, dropPending;
    private long readyTick;
    private int activeColumns;
    private boolean fluidsOn;
    private int fluidsActive;
    private Random itemRandom, orbRandom, tntRandom;
    private final Deque<Burst> itemBursts = new ArrayDeque<>();
    private final Deque<Burst> orbBursts = new ArrayDeque<>();
    private Listener listener;
    private long collapses, fallingBlocksPlaced, itemsDropped, orbsSpawned, fluidToggles, arrowsFired, tntSpawned,
        explosionsNeutralised;

    public PhysicsModule(StressPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "physics";
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start(StressContext ctx) {
        this.ctx = ctx;
        this.readConfig(ctx.config());
        this.build = new BuildQueue(ctx.blocks());
        this.fallQueue = new BuildQueue(ctx.blocks());
        this.itemRandom = ctx.newRandom("items");
        this.orbRandom = ctx.newRandom("xp");
        this.tntRandom = ctx.newRandom("tnt");

        final double mult = this.maxMultiplier();
        this.maxColumns = maxOf(this.columns, mult);
        this.maxSources = maxOf(this.sources, mult);
        this.maxDispensers = maxOf(this.dispensers, mult);

        // Fixed allocation order: falling, pit, fluids, arrows, tnt (last, so toggling it moves nothing).
        this.layoutFalling();
        this.layoutPit();
        this.layoutFluids();
        this.layoutArrows();
        if (this.tntEnabled) {
            this.layoutTnt();
        }

        this.listener = new PhysicsListener();
        ctx.plugin().getServer().getPluginManager().registerEvents(this.listener, ctx.plugin());
        ctx.onStop(() -> HandlerList.unregisterAll(this.listener));

        final List<CompletableFuture<Void>> loads = new ArrayList<>();
        for (final Region region : this.regions) {
            loads.add(ctx.chunks().loadAndHold(region));
        }
        CompletableFuture.allOf(loads.toArray(new CompletableFuture<?>[0])).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().log(Level.WARNING, "[physics] chunk loading failed", error);
            }
            this.chunksReady = true;
        });
        ctx.logger().info("[physics] regions " + this.regions + ", build " + this.build.queued() + " blocks");
    }

    @Override
    public void tick(long tick) {
        if (!this.chunksReady || !this.ctx.isActive()) {
            return;
        }
        if (!this.built) {
            this.built = this.build.step(this.buildBudget);
            this.readyTick = this.ctx.moduleTick() + 1;
            return;
        }
        final long t = this.ctx.moduleTick() - this.readyTick;

        // falling blocks: re-place the columns (budgeted), then drop them all in one tick
        if (this.maxColumns > 0 && t % this.fallInterval == 0 && !this.dropPending) {
            this.queueColumns();
        }
        if (this.dropPending && this.fallQueue.step(this.buildBudget)) {
            this.dropColumns();
        }
        // items and XP orbs
        if (this.itemsPerBurst > 0 && t % this.itemInterval == 0) {
            this.itemBurst(t);
        }
        if (this.orbsPerBurst > 0 && t % this.orbInterval == 0) {
            this.orbBurst(t);
        }
        expire(this.itemBursts, t, this.itemLifetime);
        expire(this.orbBursts, t, this.orbLifetime);
        // fluids
        if (this.maxSources > 0) {
            final long phase = t % (this.fluidOn + this.fluidOff);
            if (phase == 0) {
                this.setFluids(true);
            } else if (phase == this.fluidOn) {
                this.setFluids(false);
            }
        }
        // arrows
        if (this.maxDispensers > 0 && t % this.arrowInterval == 0) {
            this.fireArrows();
        }
        // tnt
        if (this.tntRegion != null && t % this.tntInterval == 0) {
            this.tntWave();
        }
        if (t % 200 == 0) {
            this.ctx.entities().aliveCount();
        }
    }

    @Override
    public void stop() {
        if (this.build != null) {
            this.build.clear();
            this.fallQueue.clear();
        }
        this.dropPending = false;
        // remove the fluid sources so nothing keeps flowing after the run
        if (this.ctx != null && this.built) {
            final BlockData air = this.ctx.blocks().data("minecraft:air");
            for (final int[] p : this.sourcePos) {
                this.ctx.blocks().set(p[0], p[1], p[2], air);
            }
        }
        this.fluidsOn = false;
        this.itemBursts.clear();
        this.orbBursts.clear();
    }

    @Override
    public String status() {
        if (this.ctx == null) {
            return "не запущен";
        }
        if (!this.chunksReady) {
            return "загрузка чанков";
        }
        if (!this.built) {
            return "стройка " + this.build.percent() + "%";
        }
        return "колонн " + this.activeColumns + " (обрушений " + this.collapses + ", блоков " + this.fallingBlocksPlaced
            + "), предметов " + liveCount(this.itemBursts) + "/" + this.itemsDropped + ", сфер опыта " + liveCount(this.orbBursts)
            + "/" + this.orbsSpawned + ", жидкость " + (this.fluidsOn ? this.fluidsActive + " вкл" : "выкл") + " (переключений "
            + this.fluidToggles + "), стрел " + this.arrowsFired + ", TNT "
            + (this.tntRegion == null ? "выкл" : this.tntSpawned + " (взрывов без разрушений " + this.explosionsNeutralised + ")");
    }

    // ------------------------------------------------------------------ config

    private void readConfig(ConfigurationSection c) {
        this.buildBudget = Math.max(64, c.getInt("build-blocks-per-tick", 2000));

        final ConfigurationSection f = section(c, "falling");
        this.columns = Math.max(0, f.getInt("columns", 256));
        this.columnHeight = clamp(f.getInt("column-height", 24), 1, 48);
        this.dropHeight = clamp(f.getInt("drop-height", 6), 1, 12);
        this.fallInterval = Math.max(20, f.getInt("interval-ticks", 120));
        for (final String name : listOr(f.getStringList("materials"),
            List.of("SAND", "RED_SAND", "GRAVEL", "WHITE_CONCRETE_POWDER", "LIME_CONCRETE_POWDER", "BLUE_CONCRETE_POWDER"))) {
            final Material m = Material.matchMaterial(name);
            if (m != null && m.isBlock() && m.hasGravity()) {
                this.fallMaterials.add(this.ctx.blocks().data(m));
            } else {
                this.ctx.logger().warning("[physics] not a falling block, ignored: " + name);
            }
        }
        if (this.fallMaterials.isEmpty()) {
            this.fallMaterials.add(this.ctx.blocks().data(Material.SAND));
        }

        final ConfigurationSection i = section(c, "items");
        this.itemsPerBurst = Math.max(0, i.getInt("per-burst", 600));
        this.itemInterval = Math.max(1, i.getInt("interval-ticks", 100));
        this.itemLifetime = Math.max(1, i.getInt("lifetime-ticks", 400));
        this.itemsPerCluster = Math.max(1, i.getInt("per-cluster", 12));
        for (final String name : listOr(i.getStringList("materials"),
            List.of("COBBLESTONE", "DIRT", "OAK_LOG", "WHEAT_SEEDS", "BONE", "ROTTEN_FLESH", "IRON_INGOT", "STICK"))) {
            final Material m = Material.matchMaterial(name);
            if (m != null && m.isItem() && !m.isAir()) {
                this.itemMaterials.add(m);
            } else {
                this.ctx.logger().warning("[physics] not an item, ignored: " + name);
            }
        }
        if (this.itemMaterials.isEmpty()) {
            this.itemMaterials.add(Material.COBBLESTONE);
        }

        final ConfigurationSection x = section(c, "xp");
        this.orbsPerBurst = Math.max(0, x.getInt("orbs-per-burst", 200));
        this.orbInterval = Math.max(1, x.getInt("interval-ticks", 60));
        this.orbLifetime = Math.max(1, x.getInt("lifetime-ticks", 300));

        final ConfigurationSection w = section(c, "fluids");
        this.sources = Math.max(0, w.getInt("sources", 64));
        this.lavaShare = Math.max(0.0, Math.min(1.0, w.getDouble("lava-share", 0.25)));
        this.fluidOn = Math.max(1, w.getInt("on-ticks", 100));
        this.fluidOff = Math.max(1, w.getInt("off-ticks", 60));

        final ConfigurationSection a = section(c, "arrows");
        this.dispensers = Math.max(0, a.getInt("dispensers", 32));
        this.arrowInterval = Math.max(1, a.getInt("interval-ticks", 20));
        this.arrowRange = clamp(a.getInt("range", 16), 4, 40);

        final ConfigurationSection t = section(c, "tnt");
        this.tntEnabled = t.getBoolean("enabled", false);
        this.tntPerWave = Math.max(0, t.getInt("per-wave", 40));
        this.tntInterval = Math.max(20, t.getInt("interval-ticks", 100));
        this.tntFuse = clamp(t.getInt("fuse-ticks", 40), 1, 200);
        this.tntYield = (float) Math.max(0.0, Math.min(8.0, t.getDouble("yield", 2.0)));
        this.tntEnabled &= this.tntPerWave > 0;
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        final ConfigurationSection s = parent.getConfigurationSection(key);
        return s != null ? s : new MemoryConfiguration();
    }

    private static List<String> listOr(List<String> list, List<String> fallback) {
        return list == null || list.isEmpty() ? fallback : list;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int maxOf(int base, double mult) {
        return Math.max(base, (int) Math.floor(base * mult));
    }

    private double maxMultiplier() {
        if (this.ctx.isSingleMode()) {
            return 1.0;
        }
        final StageRunner run = this.ctx.plugin().currentRun();
        return run == null ? 1.0 : Math.max(1.0, run.config().maxStageMultiplier());
    }

    private Region allocate(String name, int chunksX, int chunksZ) {
        final Region region = this.ctx.allocateRegion(name, chunksX, chunksZ);
        this.regions.add(region);
        return region;
    }

    // ------------------------------------------------------------------ layout + build (queued, budgeted)

    private void layoutFalling() {
        if (this.maxColumns == 0) {
            return;
        }
        final int side = (int) Math.ceil(Math.sqrt(this.maxColumns));
        final int chunks = Math.max(1, (side * 2 + 2 + 15) / 16);
        this.fallRegion = this.allocate("falling", chunks, chunks);
        final int y0 = this.fallRegion.minY();
        final Random random = this.ctx.random();
        for (int i = 0; i < this.maxColumns; i++) {
            this.columnPos.add(new int[] {this.fallRegion.minX() + 1 + (i % side) * 2, this.fallRegion.minZ() + 1 + (i / side) * 2});
            this.columnMaterial.add(this.fallMaterials.get(random.nextInt(this.fallMaterials.size())));
        }
        final int x1 = this.fallRegion.minX() + side * 2;
        final int z1 = this.fallRegion.minZ() + side * 2;
        this.build.box(this.fallRegion.minX(), y0, this.fallRegion.minZ(), x1, y0 + this.dropHeight + this.columnHeight + 1, z1,
            this.ctx.blocks().data("minecraft:air"));
        this.build.box(this.fallRegion.minX(), y0 - 1, this.fallRegion.minZ(), x1, y0 - 1, z1,
            this.ctx.blocks().data("minecraft:smooth_stone"));
    }

    /** Queues the re-placement of the active columns: air gap, support block, column (no physics). */
    private void queueColumns() {
        this.activeColumns = Math.min(this.maxColumns, this.ctx.scaled(this.columns));
        if (this.activeColumns == 0) {
            return;
        }
        final int y0 = this.fallRegion.minY();
        final int support = y0 + this.dropHeight;
        final BlockData air = this.ctx.blocks().data("minecraft:air");
        final BlockData stone = this.ctx.blocks().data("minecraft:stone");
        for (int i = 0; i < this.activeColumns; i++) {
            final int[] p = this.columnPos.get(i);
            this.fallQueue.box(p[0], y0, p[1], p[0], support - 1, p[1], air);
            this.fallQueue.set(p[0], support, p[1], stone);
            this.fallQueue.box(p[0], support + 1, p[1], p[0], support + this.columnHeight, p[1], this.columnMaterial.get(i));
        }
        this.dropPending = true;
    }

    /** Removes every support with physics: the column above starts falling block by block. */
    private void dropColumns() {
        this.dropPending = false;
        final int support = this.fallRegion.minY() + this.dropHeight;
        final BlockData air = this.ctx.blocks().data("minecraft:air");
        for (int i = 0; i < this.activeColumns; i++) {
            final int[] p = this.columnPos.get(i);
            this.ctx.blocks().setWithPhysics(p[0], support, p[1], air);
        }
        this.collapses++;
        this.fallingBlocksPlaced += (long) this.activeColumns * this.columnHeight;
    }

    /** A walled 4x4-chunk pit: items in the west half, XP orbs in the east half. */
    private void layoutPit() {
        if (this.itemsPerBurst == 0 && this.orbsPerBurst == 0) {
            return;
        }
        this.pitRegion = this.allocate("pit", 4, 4);
        final Region r = this.pitRegion;
        final int y0 = r.minY();
        this.build.box(r.minX(), y0, r.minZ(), r.maxX(), y0 + 3, r.maxZ(), this.ctx.blocks().data("minecraft:air"));
        this.build.box(r.minX(), y0 - 1, r.minZ(), r.maxX(), y0 - 1, r.maxZ(), this.ctx.blocks().data("minecraft:smooth_stone"));
        final BlockData wall = this.ctx.blocks().data("minecraft:stone_bricks");
        this.build.walls(r.minX(), y0, r.minZ(), r.maxX(), y0 + 1, r.maxZ(), wall);
        this.build.box(r.centerX(), y0, r.minZ() + 1, r.centerX(), y0 + 1, r.maxZ() - 1, wall);
    }

    private void itemBurst(long t) {
        final int total = this.ctx.scaled(this.itemsPerBurst);
        if (total == 0) {
            return;
        }
        final Random random = this.itemRandom;
        final Region r = this.pitRegion;
        final World world = this.ctx.world();
        final List<Entity> spawned = new ArrayList<>(total);
        final int halfX = r.centerX() - r.minX() - 4;
        for (int n = 0; n < total; ) {
            final double cx = r.minX() + 2 + random.nextInt(halfX) + 0.5;
            final double cz = r.minZ() + 2 + random.nextInt(r.sizeZ() - 4) + 0.5;
            final Material material = this.itemMaterials.get(random.nextInt(this.itemMaterials.size()));
            for (int k = 0; k < this.itemsPerCluster && n < total; k++, n++) {
                final int amount = 1 + random.nextInt(8);
                final Vector velocity = new Vector((random.nextDouble() - 0.5) * 0.2, 0.15 + random.nextDouble() * 0.15,
                    (random.nextDouble() - 0.5) * 0.2);
                final Location loc = new Location(world, cx, r.minY() + 0.5, cz);
                final Item item = world.dropItem(loc, ItemStack.of(material, amount), it -> it.setVelocity(velocity));
                spawned.add(this.ctx.entities().track(item));
            }
        }
        this.itemsDropped += total;
        this.itemBursts.addLast(new Burst(t, spawned));
    }

    private void orbBurst(long t) {
        final int total = this.ctx.scaled(this.orbsPerBurst);
        if (total == 0) {
            return;
        }
        final Random random = this.orbRandom;
        final Region r = this.pitRegion;
        final World world = this.ctx.world();
        final List<Entity> spawned = new ArrayList<>(total);
        final int halfX = r.maxX() - r.centerX() - 4;
        final int perCluster = 10;
        for (int n = 0; n < total; ) {
            final double cx = r.centerX() + 2 + random.nextInt(halfX) + 0.5;
            final double cz = r.minZ() + 2 + random.nextInt(r.sizeZ() - 4) + 0.5;
            for (int k = 0; k < perCluster && n < total; k++, n++) {
                final int value = 1 + random.nextInt(10);
                final Vector velocity = new Vector((random.nextDouble() - 0.5) * 0.2, 0.2, (random.nextDouble() - 0.5) * 0.2);
                final Location loc = new Location(world, cx, r.minY() + 0.5, cz);
                spawned.add(this.ctx.spawn(loc, ExperienceOrb.class, orb -> {
                    orb.setExperience(value);
                    orb.setVelocity(velocity);
                }));
            }
        }
        this.orbsSpawned += total;
        this.orbBursts.addLast(new Burst(t, spawned));
    }

    /** Removes the entities of bursts older than {@code lifetime} ticks (despawn on a fixed cadence). */
    private static void expire(Deque<Burst> bursts, long now, int lifetime) {
        while (!bursts.isEmpty() && now - bursts.peekFirst().tick() >= lifetime) {
            for (final Entity e : bursts.pollFirst().entities()) {
                if (e.isValid()) {
                    e.remove();
                }
            }
        }
    }

    private static int liveCount(Deque<Burst> bursts) {
        int n = 0;
        for (final Burst burst : bursts) {
            for (final Entity e : burst.entities()) {
                if (e.isValid()) {
                    n++;
                }
            }
        }
        return n;
    }

    /** One 16x16 cell (= one chunk) per source: rim, floor, a 3-high pillar with the source on top. */
    private void layoutFluids() {
        if (this.maxSources == 0) {
            return;
        }
        final int side = (int) Math.ceil(Math.sqrt(this.maxSources));
        this.fluidRegion = this.allocate("fluids", side, side);
        final Region r = this.fluidRegion;
        final int y0 = r.minY();
        final BlockData stone = this.ctx.blocks().data("minecraft:stone");
        this.build.box(r.minX(), y0, r.minZ(), r.maxX(), y0 + 4, r.maxZ(), this.ctx.blocks().data("minecraft:air"));
        this.build.box(r.minX(), y0 - 1, r.minZ(), r.maxX(), y0 - 1, r.maxZ(), stone);
        final Random random = this.ctx.random();
        for (int i = 0; i < this.maxSources; i++) {
            final int cx = r.minX() + (i % side) * 16;
            final int cz = r.minZ() + (i / side) * 16;
            this.build.walls(cx, y0, cz, cx + 15, y0, cz + 15, stone);
            this.build.box(cx + 8, y0, cz + 8, cx + 8, y0 + 2, cz + 8, stone);
            this.sourcePos.add(new int[] {cx + 8, y0 + 3, cz + 8});
            this.sourceLava.add(random.nextDouble() < this.lavaShare);
        }
    }

    private void setFluids(boolean on) {
        final BlockData water = this.ctx.blocks().data("minecraft:water");
        final BlockData lava = this.ctx.blocks().data("minecraft:lava");
        final BlockData air = this.ctx.blocks().data("minecraft:air");
        if (on) {
            this.fluidsActive = Math.min(this.maxSources, this.ctx.scaled(this.sources));
        }
        for (int i = 0; i < this.fluidsActive; i++) {
            final int[] p = this.sourcePos.get(i);
            this.ctx.blocks().setWithPhysics(p[0], p[1], p[2], on ? (this.sourceLava.get(i) ? lava : water) : air);
        }
        this.fluidsOn = on;
        this.fluidToggles++;
    }

    /** Rows of dispensers facing south at a stone-brick wall {@code range} blocks away. */
    private void layoutArrows() {
        if (this.maxDispensers == 0) {
            return;
        }
        final int layers = (this.maxDispensers + ARROW_ROW - 1) / ARROW_ROW;
        final int chunksZ = Math.max(1, (this.arrowRange + 6 + 15) / 16);
        this.arrowRegion = this.allocate("arrows", 2, chunksZ);
        final Region r = this.arrowRegion;
        final int y0 = r.minY();
        final int zDisp = r.minZ() + 2;
        final int zWall = zDisp + this.arrowRange + 1;
        final int top = y0 + layers + 2;
        this.build.box(r.minX(), y0, r.minZ(), r.maxX(), top, zWall, this.ctx.blocks().data("minecraft:air"));
        this.build.box(r.minX(), y0 - 1, r.minZ(), r.maxX(), y0 - 1, zWall, this.ctx.blocks().data("minecraft:smooth_stone"));
        this.build.box(r.minX() + 1, y0, zWall, r.minX() + ARROW_ROW + 2, top, zWall, this.ctx.blocks().data("minecraft:stone_bricks"));
        final BlockData dispenser = this.ctx.blocks().data("minecraft:dispenser[facing=south]");
        for (int i = 0; i < this.maxDispensers; i++) {
            final int x = r.minX() + 2 + (i % ARROW_ROW);
            final int y = y0 + i / ARROW_ROW;
            this.build.set(x, y, zDisp, dispenser);
            this.dispenserPos.add(new int[] {x, y, zDisp});
            this.build.action(() -> this.refill(x, y, zDisp));
        }
    }

    private Dispenser dispenserAt(int x, int y, int z) {
        final BlockState state = this.ctx.blocks().block(x, y, z).getState(false);
        return state instanceof Dispenser d ? d : null;
    }

    private void refill(int x, int y, int z) {
        final Dispenser dispenser = this.dispenserAt(x, y, z);
        if (dispenser != null) {
            final Inventory inv = dispenser.getInventory();
            for (int slot = 0; slot < inv.getSize(); slot++) {
                inv.setItem(slot, ItemStack.of(Material.ARROW, 64));
            }
        }
    }

    private void fireArrows() {
        final int active = Math.min(this.maxDispensers, this.ctx.scaled(this.dispensers));
        for (int i = 0; i < active; i++) {
            final int[] p = this.dispenserPos.get(i);
            final Dispenser dispenser = this.dispenserAt(p[0], p[1], p[2]);
            if (dispenser == null) {
                continue;
            }
            if (!dispenser.getInventory().contains(Material.ARROW)) {
                this.refill(p[0], p[1], p[2]);
            }
            if (dispenser.dispense()) {
                this.arrowsFired++;
            }
        }
    }

    /** Hollow obsidian chamber in a 2x2-chunk region. */
    private void layoutTnt() {
        this.tntRegion = this.allocate("tnt", 2, 2);
        final Region r = this.tntRegion;
        final int y0 = r.minY();
        final int x0 = r.minX() + 2, z0 = r.minZ() + 2, x1 = r.maxX() - 2, z1 = r.maxZ() - 2, y1 = y0 + 11;
        final BlockData obsidian = this.ctx.blocks().data("minecraft:obsidian");
        this.build.box(x0, y0 - 1, z0, x1, y1, z1, this.ctx.blocks().data("minecraft:air"));
        this.build.box(x0, y0 - 1, z0, x1, y0 - 1, z1, obsidian);
        this.build.box(x0, y1, z0, x1, y1, z1, obsidian);
        this.build.walls(x0, y0, z0, x1, y1 - 1, z1, obsidian);
        this.tntInterior = new int[] {x0 + 1, y0, z0 + 1, x1 - 1, y1 - 1, z1 - 1};
    }

    private void tntWave() {
        final int count = this.ctx.scaled(this.tntPerWave);
        final int[] b = this.tntInterior;
        final Random random = this.tntRandom;
        for (int i = 0; i < count; i++) {
            final double x = b[0] + 1 + random.nextInt(b[3] - b[0] - 1) + 0.5;
            final double y = b[1] + random.nextInt(3);
            final double z = b[2] + 1 + random.nextInt(b[5] - b[2] - 1) + 0.5;
            final int fuse = this.tntFuse + (i % 10);
            this.ctx.spawn(new Location(this.ctx.world(), x, y, z), TNTPrimed.class, tnt -> {
                tnt.setFuseTicks(fuse);
                tnt.setYield(this.tntYield);
            });
        }
        this.tntSpawned += count;
    }

    // ------------------------------------------------------------------ events

    private final class PhysicsListener implements Listener {

        /** Explosions in the physics area never break blocks (the chamber stays intact, the load stays equal). */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onExplode(EntityExplodeEvent event) {
            final PhysicsModule m = PhysicsModule.this;
            final Location loc = event.getLocation();
            if (!m.ctx.isActive() || loc.getWorld() == null || !loc.getWorld().equals(m.ctx.world())) {
                return;
            }
            for (final Region region : m.regions) {
                if (region.containsChunk(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                    event.blockList().clear();
                    m.explosionsNeutralised++;
                    return;
                }
            }
        }
    }
}
