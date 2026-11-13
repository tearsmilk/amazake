package core.amazake.stress.mobs;

import core.amazake.stress.LoadModule;
import core.amazake.stress.Region;
import core.amazake.stress.Stage;
import core.amazake.stress.StageRunner;
import core.amazake.stress.StressContext;
import core.amazake.stress.StressPlugin;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Bee;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.PiglinAbstract;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Load module "mobs": closed pens at fixed coordinates with a deterministic mix of mobs that exercise AI,
 * pathfinding, POI/brain logic, breeding, bee hives and entity collisions/cramming.
 *
 * <p>Timeline: pens are built over several ticks (block budget per tick), then mobs are spawned at
 * {@code spawn-per-tick} until the target count is reached. During {@link Stage#MAX} the target becomes
 * {@code scaled(count)} and the extra mobs are spawned into the same pens. Every random choice comes from
 * {@link StressContext#random()} in a fixed order.
 */
public final class MobsModule implements LoadModule {

    /** Kind of pen; each pen is a 2x2-chunk cell of the module's region. */
    enum PenKind { ZOMBIE, SKELETON, VILLAGE, FARM, BEE, PIGLIN, CRAMMED }

    /** Kind of mob in the mix; {@code key} is the config key under {@code mix}. */
    enum MobKind {
        ZOMBIE("zombie", PenKind.ZOMBIE, 18),
        SKELETON("skeleton", PenKind.SKELETON, 12),
        VILLAGER("villager", PenKind.VILLAGE, 10),
        IRON_GOLEM("iron-golem", PenKind.VILLAGE, 2),
        FARM("farm", PenKind.FARM, 26),
        BEE("bee", PenKind.BEE, 6),
        PIGLIN("piglin", PenKind.PIGLIN, 8),
        CRAMMED("crammed", PenKind.CRAMMED, 18);

        final String key;
        final PenKind pen;
        final int defaultWeight;

        MobKind(String key, PenKind pen, int defaultWeight) {
            this.key = key;
            this.pen = pen;
            this.defaultWeight = defaultWeight;
        }
    }

    private static final int CELL = 32;
    private static final int GRID_COLUMNS = 4;
    private static final String[] WORKSTATIONS = {
        "minecraft:composter", "minecraft:lectern[facing=south]", "minecraft:blast_furnace[facing=south]",
        "minecraft:smoker[facing=south]", "minecraft:cartography_table", "minecraft:brewing_stand",
        "minecraft:fletching_table", "minecraft:grindstone[face=floor,facing=south]", "minecraft:loom[facing=south]",
        "minecraft:smithing_table", "minecraft:stonecutter[facing=south]", "minecraft:barrel[facing=up]",
        "minecraft:cauldron",
    };
    private static final String[] FLOWERS = {"minecraft:poppy", "minecraft:dandelion", "minecraft:cornflower",
        "minecraft:oxeye_daisy", "minecraft:allium"};
    private static final Set<EntityDamageEvent.DamageCause> ENVIRONMENT_DAMAGE = EnumSet.of(
        EntityDamageEvent.DamageCause.SUFFOCATION, EntityDamageEvent.DamageCause.FALL,
        EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK,
        EntityDamageEvent.DamageCause.LAVA, EntityDamageEvent.DamageCause.DROWNING,
        EntityDamageEvent.DamageCause.HOT_FLOOR, EntityDamageEvent.DamageCause.CRAMMING,
        EntityDamageEvent.DamageCause.FREEZE, EntityDamageEvent.DamageCause.CONTACT,
        EntityDamageEvent.DamageCause.FALLING_BLOCK, EntityDamageEvent.DamageCause.DRYOUT);

    /** One pen: interior bounds are inclusive; mobs stand on y0 (floor is y0 - 1). */
    static final class Pen {
        final int index;
        final PenKind kind;
        final int x0, z0, x1, z1, y0, wallHeight;
        final List<Location> hives = new ArrayList<>();
        final List<Location> flowers = new ArrayList<>();

        Pen(int index, PenKind kind, int x0, int z0, int x1, int z1, int y0, int wallHeight) {
            this.index = index;
            this.kind = kind;
            this.x0 = x0;
            this.z0 = z0;
            this.x1 = x1;
            this.z1 = z1;
            this.y0 = y0;
            this.wallHeight = wallHeight;
        }

        int sizeX() {
            return this.x1 - this.x0 + 1;
        }

        int sizeZ() {
            return this.z1 - this.z0 + 1;
        }
    }

    private record SpawnOrder(MobKind kind, Pen pen, int ordinal) {
    }

    /** A mob that gets a new pathfinding target every retarget interval; its own Random keeps targets stable. */
    private record Walker(Mob mob, Pen pen, Random random) {
    }

    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final StressPlugin plugin;
    private StressContext ctx;

    // config
    private int count;
    private int spawnPerTick;
    private int buildBudget;
    private int penSize;
    private int wallHeight;
    private int crammedSize;
    private int retargetInterval;
    private double retargetShare;
    private double retargetSpeed;
    private int breedInterval;
    private int maxBabies;
    private final Map<MobKind, Integer> weights = new EnumMap<>(MobKind.class);
    private final Map<PenKind, Integer> penCounts = new EnumMap<>(PenKind.class);
    private final List<EntityType> farmTypes = new ArrayList<>();
    private final List<EntityType> crammedTypes = new ArrayList<>();

    // state
    private Region region;
    private final List<Pen> pens = new ArrayList<>();
    private final Map<PenKind, List<Pen>> pensByKind = new EnumMap<>(PenKind.class);
    private final Map<MobKind, Integer> planned = new EnumMap<>(MobKind.class);
    private final Deque<SpawnOrder> spawnQueue = new ArrayDeque<>();
    private final List<Walker> walkers = new ArrayList<>();
    private final List<Animals> breeders = new ArrayList<>();
    private final List<Entity> babies = new ArrayList<>();
    private BuildQueue build;
    private Listener listener;
    private boolean chunksReady;
    private boolean built;
    private long builtTick;
    private Random spawnRandom;
    private int target;
    private long spawned;
    private long retargets;
    private long breedingsStarted;
    private long babiesBorn;
    private long babiesRefused;

    public MobsModule(StressPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String id() {
        return "mobs";
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start(StressContext ctx) {
        this.ctx = ctx;
        this.readConfig(ctx.config());
        this.build = new BuildQueue(ctx.blocks());
        this.spawnRandom = ctx.newRandom("spawn");

        // Build for the largest population of the run (MAX stage), so pens never change size mid-run.
        final int maxTotal = Math.max(this.count, (int) Math.floor(this.count * this.maxMultiplier()));
        final Map<MobKind, Integer> maxPlan = this.plan(maxTotal);

        this.layoutPens();
        this.target = this.count;
        this.enqueueSpawns(this.count);

        this.listener = new MobsListener();
        ctx.plugin().getServer().getPluginManager().registerEvents(this.listener, ctx.plugin());
        ctx.onStop(() -> HandlerList.unregisterAll(this.listener));

        // ctx.random() is used only for the build layout (flower positions); spawns and walkers have own streams.
        this.queueBuild(maxPlan);
        ctx.chunks().loadAndHold(this.region).whenComplete((ignored, error) -> {
            if (error != null) {
                ctx.logger().log(Level.WARNING, "[mobs] chunk loading failed", error);
            }
            this.chunksReady = true;
        });
        ctx.logger().info("[mobs] pens: " + this.pens.size() + " in " + this.region + ", target " + this.count
            + " mobs, build " + this.build.queued() + " blocks");
    }

    @Override
    public void tick(long tick) {
        if (!this.chunksReady || !this.ctx.isActive()) {
            return;
        }
        if (!this.built) {
            this.built = this.build.step(this.buildBudget);
            this.builtTick = this.ctx.moduleTick() + 1;
            return;
        }
        // 1. gradual spawning
        for (int i = 0; i < this.spawnPerTick && !this.spawnQueue.isEmpty(); i++) {
            this.spawn(this.spawnQueue.pollFirst());
        }
        // Everything below is timed relative to the end of the build, so it does not depend on how fast
        // the server loaded the chunks.
        final long now = this.ctx.moduleTick() - this.builtTick;
        // 2. pathfinding targets: a fixed slice of the walkers every tick, each walker every retargetInterval ticks
        if (!this.walkers.isEmpty()) {
            final int slot = (int) (now % this.retargetInterval);
            for (int i = slot; i < this.walkers.size(); i += this.retargetInterval) {
                final Walker walker = this.walkers.get(i);
                final Pen pen = walker.pen;
                final Random random = walker.random;
                // Always draw, even for dead mobs, so the sequence does not depend on deaths.
                final double x = pen.x0 + 1 + random.nextInt(Math.max(1, pen.sizeX() - 2)) + 0.5;
                final double z = pen.z0 + 1 + random.nextInt(Math.max(1, pen.sizeZ() - 2)) + 0.5;
                if (walker.mob.isValid()) {
                    walker.mob.getPathfinder().moveTo(new Location(this.ctx.world(), x, pen.y0, z), this.retargetSpeed);
                    this.retargets++;
                }
            }
        }
        // 3. breeding: feed adult pairs through the API (setLoveModeTicks(600) == feeding wheat/seeds)
        if (this.breedInterval > 0 && now % this.breedInterval == 0 && !this.breeders.isEmpty()) {
            this.babies.removeIf(e -> !e.isValid());
            if (this.babies.size() < this.maxBabies) {
                for (final Animals animal : this.breeders) {
                    if (animal.isValid() && animal.isAdult() && animal.canBreed() && !animal.isLoveMode()) {
                        animal.setLoveModeTicks(600);
                        this.breedingsStarted++;
                    }
                }
            }
        }
        // 4. keep the registry small
        if (now % 200 == 0) {
            this.ctx.entities().aliveCount();
        }
    }

    @Override
    public void onStageChange(Stage stage) {
        if (stage == Stage.MAX && this.ctx != null && !this.ctx.isSingleMode()) {
            final int scaled = this.ctx.scaled(this.count);
            if (scaled > this.target) {
                this.target = scaled;
                this.enqueueSpawns(scaled);
            }
        }
    }

    @Override
    public void stop() {
        this.spawnQueue.clear();
        if (this.build != null) {
            this.build.clear();
        }
        // Bees hidden inside hives are not entities: removing the hives removes them too.
        if (this.ctx != null && this.built) {
            final BlockData air = this.ctx.blocks().data("minecraft:air");
            for (final Pen pen : this.pens) {
                for (final Location hive : pen.hives) {
                    this.ctx.blocks().set(hive.getBlockX(), hive.getBlockY(), hive.getBlockZ(), air);
                }
            }
        }
        this.walkers.clear();
        this.breeders.clear();
        this.babies.clear();
    }

    @Override
    public String status() {
        if (this.ctx == null) {
            return "не запущен";
        }
        if (!this.chunksReady) {
            return "загрузка чанков (" + this.region.chunkCount() + ")";
        }
        if (!this.built) {
            return "стройка загонов " + this.build.percent() + "%";
        }
        final int alive = this.ctx.entities().aliveCount();
        return "мобов " + alive + " (заспавнено " + this.spawned + "/" + this.target + "), загонов " + this.pens.size()
            + ", маршрутов " + this.retargets + ", спариваний " + this.breedingsStarted + ", детёнышей "
            + this.babies.size() + "/" + this.maxBabies;
    }

    // ------------------------------------------------------------------ config

    private void readConfig(ConfigurationSection c) {
        this.count = Math.max(0, c.getInt("count", 800));
        this.spawnPerTick = Math.max(1, c.getInt("spawn-per-tick", 20));
        this.buildBudget = Math.max(64, c.getInt("build-blocks-per-tick", 2000));
        this.penSize = clamp(c.getInt("pen-size", 28), 6, 28);
        this.wallHeight = clamp(c.getInt("wall-height", 4), 3, 12);
        this.crammedSize = clamp(c.getInt("crammed-pen-size", 3), 1, 12);
        this.retargetInterval = Math.max(1, c.getInt("retarget-interval-ticks", 40));
        this.retargetShare = Math.max(0.0, Math.min(1.0, c.getDouble("retarget-share", 0.5)));
        this.retargetSpeed = Math.max(0.1, c.getDouble("retarget-speed", 1.0));
        this.breedInterval = Math.max(0, c.getInt("breed-interval-ticks", 300));
        this.maxBabies = Math.max(0, c.getInt("max-babies", 120));

        final ConfigurationSection mix = c.getConfigurationSection("mix");
        for (final MobKind kind : MobKind.values()) {
            this.weights.put(kind, Math.max(0, mix == null ? kind.defaultWeight : mix.getInt(kind.key, kind.defaultWeight)));
        }
        final ConfigurationSection pensSection = c.getConfigurationSection("pens");
        final int[] defaults = {2, 2, 2, 3, 1, 1, 4};
        for (final PenKind kind : PenKind.values()) {
            final String key = kind.name().toLowerCase(Locale.ROOT);
            final int def = defaults[kind.ordinal()];
            this.penCounts.put(kind, clamp(pensSection == null ? def : pensSection.getInt(key, def), 0, 32));
        }
        // A kind without pens gets no mobs.
        for (final MobKind kind : MobKind.values()) {
            if (this.penCounts.get(kind.pen) == 0) {
                this.weights.put(kind, 0);
            }
        }
        parseTypes(c.getStringList("farm-animals"), List.of(EntityType.COW, EntityType.SHEEP, EntityType.CHICKEN,
            EntityType.PIG), this.farmTypes, true);
        parseTypes(c.getStringList("crammed-animals"), List.of(EntityType.COW, EntityType.SHEEP), this.crammedTypes, false);
    }

    private void parseTypes(List<String> names, List<EntityType> fallback, List<EntityType> out, boolean breedable) {
        for (final String name : names) {
            try {
                final EntityType type = EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
                final Class<? extends Entity> cls = type.getEntityClass();
                if (cls != null && Mob.class.isAssignableFrom(cls) && (!breedable || Animals.class.isAssignableFrom(cls))) {
                    out.add(type);
                } else {
                    this.ctx.logger().warning("[mobs] unsuitable entity type ignored: " + name);
                }
            } catch (final IllegalArgumentException e) {
                this.ctx.logger().warning("[mobs] unknown entity type ignored: " + name);
            }
        }
        if (out.isEmpty()) {
            out.addAll(fallback);
        }
    }

    private double maxMultiplier() {
        if (this.ctx.isSingleMode()) {
            return 1.0;
        }
        final StageRunner run = this.ctx.plugin().currentRun();
        return run == null ? 1.0 : Math.max(1.0, run.config().maxStageMultiplier());
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /** Splits {@code total} over the mob kinds by weight; deterministic (remainder in enum order). */
    private Map<MobKind, Integer> plan(int total) {
        final Map<MobKind, Integer> result = new EnumMap<>(MobKind.class);
        int sum = 0;
        for (final MobKind kind : MobKind.values()) {
            sum += this.weights.get(kind);
        }
        int assigned = 0;
        for (final MobKind kind : MobKind.values()) {
            final int n = sum == 0 ? 0 : (int) ((long) total * this.weights.get(kind) / sum);
            result.put(kind, n);
            assigned += n;
        }
        for (int i = 0; assigned < total && sum > 0; i = (i + 1) % MobKind.values().length) {
            final MobKind kind = MobKind.values()[i];
            if (this.weights.get(kind) > 0) {
                result.merge(kind, 1, Integer::sum);
                assigned++;
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ layout and build

    private void layoutPens() {
        int n = 0;
        for (final PenKind kind : PenKind.values()) {
            n += this.penCounts.get(kind);
        }
        n = Math.max(1, n);
        final int rows = (n + GRID_COLUMNS - 1) / GRID_COLUMNS;
        this.region = this.ctx.allocateRegion("pens", GRID_COLUMNS * 2, rows * 2);
        final int y0 = this.region.minY();
        int index = 0;
        for (final PenKind kind : PenKind.values()) {
            final List<Pen> list = new ArrayList<>();
            for (int k = 0; k < this.penCounts.get(kind); k++, index++) {
                final int cellX = this.region.minX() + (index % GRID_COLUMNS) * CELL;
                final int cellZ = this.region.minZ() + (index / GRID_COLUMNS) * CELL;
                final Pen pen;
                if (kind == PenKind.CRAMMED) {
                    final int x0 = cellX + CELL / 2 - this.crammedSize / 2;
                    final int z0 = cellZ + CELL / 2 - this.crammedSize / 2;
                    pen = new Pen(index, kind, x0, z0, x0 + this.crammedSize - 1, z0 + this.crammedSize - 1, y0, 2);
                } else {
                    final int x0 = cellX + 2;
                    final int z0 = cellZ + 2;
                    pen = new Pen(index, kind, x0, z0, x0 + this.penSize - 1, z0 + this.penSize - 1, y0, this.wallHeight);
                }
                list.add(pen);
                this.pens.add(pen);
            }
            this.pensByKind.put(kind, list);
        }
    }

    private void queueBuild(Map<MobKind, Integer> maxPlan) {
        final BlockData air = this.ctx.blocks().data("minecraft:air");
        final BlockData glass = this.ctx.blocks().data("minecraft:glass");
        final BlockData bricks = this.ctx.blocks().data("minecraft:stone_bricks");
        final BlockData stone = this.ctx.blocks().data("minecraft:stone");
        final BlockData grass = this.ctx.blocks().data("minecraft:grass_block");
        final Random random = this.ctx.random();
        for (final Pen pen : this.pens) {
            final boolean hostile = pen.kind == PenKind.ZOMBIE || pen.kind == PenKind.SKELETON || pen.kind == PenKind.PIGLIN;
            final int top = pen.y0 + pen.wallHeight;
            // clear the volume (non-flat worlds), floor, walls
            this.build.box(pen.x0 - 1, pen.y0, pen.z0 - 1, pen.x1 + 1, top, pen.z1 + 1, air);
            this.build.box(pen.x0 - 1, pen.y0 - 1, pen.z0 - 1, pen.x1 + 1, pen.y0 - 1, pen.z1 + 1, hostile ? stone : grass);
            this.build.walls(pen.x0 - 1, pen.y0, pen.z0 - 1, pen.x1 + 1, top - 1, pen.z1 + 1, hostile ? bricks : glass);
            switch (pen.kind) {
                case CRAMMED -> this.build.box(pen.x0 - 1, top, pen.z0 - 1, pen.x1 + 1, top, pen.z1 + 1, glass);
                case BEE -> {
                    this.build.box(pen.x0 - 1, top, pen.z0 - 1, pen.x1 + 1, top, pen.z1 + 1, glass);
                    this.queueBeePen(pen, perPen(maxPlan.get(MobKind.BEE), PenKind.BEE), random);
                }
                case VILLAGE -> this.queueVillage(pen, perPen(maxPlan.get(MobKind.VILLAGER), PenKind.VILLAGE));
                default -> {
                }
            }
        }
    }

    private int perPen(int mobs, PenKind kind) {
        final int n = Math.max(1, this.penCounts.get(kind));
        return (mobs + n - 1) / n;
    }

    /** Workstations (job-site POIs) along the north side, beds (home POIs) along the south side. */
    private void queueVillage(Pen pen, int villagers) {
        final int perRow = pen.sizeX() / 2;
        final int stations = Math.min(villagers, perRow * 2);
        for (int i = 0; i < stations; i++) {
            final int x = pen.x0 + 1 + (i % perRow) * 2;
            final int z = pen.z0 + (i / perRow) * 2;
            this.build.set(x, pen.y0, z, this.ctx.blocks().data(WORKSTATIONS[i % WORKSTATIONS.length]));
        }
        final BlockData foot = this.ctx.blocks().data("minecraft:white_bed[facing=south,part=foot]");
        final BlockData head = this.ctx.blocks().data("minecraft:white_bed[facing=south,part=head]");
        final int beds = Math.min(villagers, perRow * 3);
        for (int i = 0; i < beds; i++) {
            final int x = pen.x0 + 1 + (i % perRow) * 2;
            final int zHead = pen.z1 - (i / perRow) * 3;
            this.build.set(x, pen.y0, zHead - 1, foot);
            this.build.set(x, pen.y0, zHead, head);
        }
    }

    /** Beehives along the north and south walls, flowers at seeded positions in between. */
    private void queueBeePen(Pen pen, int bees, Random random) {
        final int perRow = pen.sizeX() / 2;
        final int hives = Math.max(1, Math.min((bees + 2) / 3, perRow * 2));
        final BlockData hiveSouth = this.ctx.blocks().data("minecraft:beehive[facing=south]");
        final BlockData hiveNorth = this.ctx.blocks().data("minecraft:beehive[facing=north]");
        for (int i = 0; i < hives; i++) {
            final int x = pen.x0 + (i % perRow) * 2;
            final boolean north = i < perRow;
            final int z = north ? pen.z0 : pen.z1;
            this.build.set(x, pen.y0, z, north ? hiveSouth : hiveNorth);
            pen.hives.add(new Location(this.ctx.world(), x, pen.y0, z));
        }
        final int flowers = hives * 2;
        for (int i = 0; i < flowers; i++) {
            final int x = pen.x0 + random.nextInt(pen.sizeX());
            final int z = pen.z0 + 2 + random.nextInt(Math.max(1, pen.sizeZ() - 4));
            this.build.set(x, pen.y0, z, this.ctx.blocks().data(FLOWERS[i % FLOWERS.length]));
            pen.flowers.add(new Location(this.ctx.world(), x, pen.y0, z));
        }
    }

    // ------------------------------------------------------------------ spawning

    /** Queues the spawns needed to go from the current plan to {@code total}, interleaving the kinds. */
    private void enqueueSpawns(int total) {
        final Map<MobKind, Integer> next = this.plan(total);
        final Map<MobKind, Integer> cursor = new EnumMap<>(MobKind.class);
        for (final MobKind kind : MobKind.values()) {
            cursor.put(kind, this.planned.getOrDefault(kind, 0));
        }
        boolean added = true;
        while (added) {
            added = false;
            for (final MobKind kind : MobKind.values()) {
                final int j = cursor.get(kind);
                if (j < next.get(kind)) {
                    final List<Pen> list = this.pensByKind.get(kind.pen);
                    // farm animals come in pairs that share a pen
                    final int group = kind == MobKind.FARM ? 2 : 1;
                    this.spawnQueue.addLast(new SpawnOrder(kind, list.get((j / group) % list.size()), j));
                    cursor.put(kind, j + 1);
                    added = true;
                }
            }
        }
        this.planned.clear();
        this.planned.putAll(next);
    }

    private void spawn(SpawnOrder order) {
        final Pen pen = order.pen;
        final Random random = this.spawnRandom;
        final World world = this.ctx.world();
        // keep spawn points away from workstations/beds/hives so nothing spawns inside a block
        final int marginZ = pen.kind == PenKind.VILLAGE ? 5 : pen.kind == PenKind.BEE ? 2 : 0;
        final int spanX = Math.max(1, pen.sizeX() - (pen.kind == PenKind.CRAMMED ? 0 : 2));
        final int spanZ = Math.max(1, pen.sizeZ() - (pen.kind == PenKind.CRAMMED ? 0 : 2) - 2 * marginZ);
        final int offX = pen.kind == PenKind.CRAMMED ? 0 : 1;
        final int offZ = (pen.kind == PenKind.CRAMMED ? 0 : 1) + marginZ;
        final double x = pen.x0 + offX + random.nextInt(spanX) + 0.5;
        final double z = pen.z0 + offZ + random.nextInt(spanZ) + 0.5;
        final double y = pen.y0 + (order.kind == MobKind.BEE ? 1 : 0);
        final float yaw = random.nextInt(360);
        final boolean walker = random.nextDouble() < this.retargetShare;
        final Location loc = new Location(world, x, y, z, yaw, 0f);

        final EntityType type = switch (order.kind) {
            case ZOMBIE -> EntityType.ZOMBIE;
            case SKELETON -> EntityType.SKELETON;
            case VILLAGER -> EntityType.VILLAGER;
            case IRON_GOLEM -> EntityType.IRON_GOLEM;
            case BEE -> EntityType.BEE;
            case PIGLIN -> EntityType.PIGLIN;
            case FARM -> this.farmTypes.get((order.ordinal / 2) % this.farmTypes.size());
            case CRAMMED -> this.crammedTypes.get(order.ordinal % this.crammedTypes.size());
        };
        final Class<? extends Entity> cls = type.getEntityClass();
        if (cls == null) {
            return;
        }
        final Entity entity = this.ctx.spawn(loc, cls, (Entity e) -> this.configure(e, order));
        this.spawned++;
        if (entity instanceof Mob mob && walker && order.kind != MobKind.VILLAGER && order.kind != MobKind.BEE) {
            this.walkers.add(new Walker(mob, pen, this.ctx.newRandom("walker-" + this.walkers.size())));
        }
        if (entity instanceof Animals animals && order.kind == MobKind.FARM) {
            this.breeders.add(animals);
        }
    }

    /** Runs before the entity is added to the world. */
    private void configure(Entity e, SpawnOrder order) {
        e.setPersistent(true);
        if (e instanceof LivingEntity living) {
            living.setRemoveWhenFarAway(false);
            living.setCanPickupItems(false);
        }
        if (e instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
        }
        if (e instanceof AbstractSkeleton skeleton) {
            skeleton.setShouldBurnInDay(false);
        }
        if (e instanceof PiglinAbstract piglin) {
            piglin.setImmuneToZombification(true);
        }
        if (e instanceof Bee bee && !order.pen.hives.isEmpty()) {
            bee.setHive(order.pen.hives.get(order.ordinal % order.pen.hives.size()).clone());
            if (!order.pen.flowers.isEmpty()) {
                bee.setFlower(order.pen.flowers.get(order.ordinal % order.pen.flowers.size()).clone());
            }
        }
    }

    // ------------------------------------------------------------------ events

    private boolean inPens(Location loc) {
        return loc.getWorld() != null && loc.getWorld().equals(this.ctx.world())
            && this.region.contains(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    private final class MobsListener implements Listener {

        /** Tracks mobs born in the pens (breeding, bees leaving hives, golems summoned by villagers). */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onCreatureSpawn(CreatureSpawnEvent event) {
            final MobsModule m = MobsModule.this;
            if (!m.ctx.isActive() || !m.inPens(event.getLocation())) {
                return;
            }
            final CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();
            if (reason == CreatureSpawnEvent.SpawnReason.CUSTOM || reason == CreatureSpawnEvent.SpawnReason.DEFAULT) {
                return; // our own ctx.spawn(...), already tracked
            }
            if (reason == CreatureSpawnEvent.SpawnReason.BREEDING) {
                m.babies.removeIf(e -> !e.isValid());
                if (m.babies.size() >= m.maxBabies) {
                    event.setCancelled(true);
                    m.babiesRefused++;
                    return;
                }
                m.babies.add(event.getEntity());
                m.babiesBorn++;
            }
            event.getEntity().setPersistent(true);
            event.getEntity().setRemoveWhenFarAway(false);
            m.ctx.entities().track(event.getEntity());
        }

        /** No environmental deaths in the pens (suffocation, burning, cramming, ...): the count stays stable. */
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        public void onDamage(EntityDamageEvent event) {
            final MobsModule m = MobsModule.this;
            if (m.ctx.isActive() && ENVIRONMENT_DAMAGE.contains(event.getCause()) && m.inPens(event.getEntity().getLocation())) {
                event.setCancelled(true);
                event.getEntity().setFireTicks(0);
            }
        }
    }
}
