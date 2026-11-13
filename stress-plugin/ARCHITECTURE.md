# AmazakeStress — architecture (for module authors)

Package `core.amazake.stress`. Public Paper API only (`io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`, Java 21):
no NMS, no reflection. Code and comments in English; user-facing strings (`status()`, chat) in Russian.

Build: `/home/user/amazake/gradlew -p stress-plugin build` → `stress-plugin/build/libs/AmazakeStress-<version>.jar`.

## Lifecycle of a run

1. `/stress start [all|<module>] [seconds]` (or `/stress run ...`, which also runs the built-in profiler for exactly
   the run) → `StressPlugin#startRun` re-reads `config.yml` (`StressConfig` snapshot).
2. `TestWorld#prepare`: creates/loads `stress_world` (fixed seed, `NORMAL` environment, `generateStructures(false)`,
   `WorldType` from config, gamerules/time/weather/difficulty/autosave from config). If the world was used by an
   earlier run and `world.recreate-on-start` is true, it is unloaded, deleted and created again, so every run
   starts from the same clean world.
3. `StageRunner` schedules ONE repeating task (period 1 tick). Each tick: advance the timeline (counted in
   ticks), start the modules of a newly entered stage, call `tick(runTick)` on every started module.
4. Timeline (`all` mode): `BASELINE(0) → MOBS(1) → PHYSICS(2) → REDSTONE(3) → CHUNKS(4) → PACKETS(5) → MAX(6)`.
   A started module stays active until the end. `MAX` takes the remaining time; `ctx.intensity()` becomes
   `timeline.max-stage-multiplier` (default 1.5) and every module gets `onStageChange(Stage.MAX)`.
   Single mode (`/stress start redstone 120`): stage is `Stage.SINGLE`, the module starts on tick 0.
5. End of timeline or `/stress stop`: modules' `stop()` in reverse start order → their context closes
   (cancel tasks, run `onStop` actions, remove tracked entities) → every remaining non-player entity in
   stress_world is removed → all chunk tickets released → summary printed and saved to
   `plugins/AmazakeStress/results/run-<date>-<mode>.txt`. Built blocks stay until the world is recreated
   (next start) or `/stress cleanup confirm`.

A module that throws from `start`/`tick`/`onStageChange` is stopped and marked failed; the run continues.

## Module contract

```java
public interface LoadModule {
    String id();                         // equals the registry id
    void start(StressContext ctx);       // main thread, once, when the stage begins
    void tick(long tick);                // main thread, every tick; tick = ticks since run start; KEEP CHEAP
    void stop();                         // main thread; neutralise builds, stop own threads
    String status();                     // short Russian line for /stress status, e.g. "мобов 800/800"
    default void onStageChange(Stage stage) {}   // every started module, on every stage change (incl. its own)
}
```

Fixed files and public shape (do not rename; registry calls `XModule::new` with the plugin):

| id | class | constructor | starts on | lane |
|---|---|---|---|---|
| `mobs` | `core.amazake.stress.mobs.MobsModule` | `public MobsModule(StressPlugin plugin)` | `Stage.MOBS` | 0 |
| `physics` | `core.amazake.stress.physics.PhysicsModule` | `public PhysicsModule(StressPlugin plugin)` | `Stage.PHYSICS` | 1 |
| `redstone` | `core.amazake.stress.redstone.RedstoneModule` | `public RedstoneModule(StressPlugin plugin)` | `Stage.REDSTONE` | 2 |
| `chunks` | `core.amazake.stress.chunks.ChunksModule` | `public ChunksModule(StressPlugin plugin)` | `Stage.CHUNKS` | 3 |
| `packets` | `core.amazake.stress.packets.PacketsModule` | `public PacketsModule(StressPlugin plugin)` | `Stage.PACKETS` | 4 |
| `async` | `core.amazake.stress.async.AsyncLoadModule` | `public AsyncLoadModule(StressPlugin plugin)` | `Stage.PACKETS` | 5 |
| `races` | `core.amazake.stress.races.RacesModule` | `public RacesModule(StressPlugin plugin)` | `Stage.PACKETS` | 6 |

Profiler: `core.amazake.stress.profiler.StressProfiler` — `public StressProfiler(StressPlugin plugin)`,
implements `ProfilerBridge` (placeholder answers «профайлер ещё не подключён»).

Each module may add helper classes inside its own package. Config keys: only under `modules.<id>` in
`src/main/resources/config.yml` (defaults already there; read them with defaults, e.g.
`ctx.config().getInt("count", 800)`), Russian comments.

## Determinism rules

* Every random choice on the main thread: `ctx.random()` (seeded `java.util.Random`, one per module:
  `new Random(StressContext.deriveSeed(worldSeed, moduleId))`), consumed in a fixed order.
  Async workers: `ctx.newRandom("worker-" + i)`, one per thread. Never `Math.random()`, `ThreadLocalRandom`,
  `UUID.randomUUID()`, wall-clock-dependent decisions or `HashMap`/`HashSet` iteration order of objects
  without stable hashCode.
* Positions: only from `ctx.allocateRegion(...)` / `ctx.regions()`. Allocation order inside a module must be fixed.
* Time: count ticks (`tick` argument, `ctx.moduleTick()`), not `System.nanoTime()`.
* Spawn with `ctx.spawn(...)` (uses `randomizeData=false`: no random equipment/variants).
* No players are needed. Note: Paper/forks apply entity activation range and similar optimisations to
  entities far from players — that is part of what is being compared, do not try to bypass it.

## Framework API (exact signatures)

### `StressContext` (one per module per run)

```java
public StressPlugin plugin()
public java.util.logging.Logger logger()
public World world()                                   // always stress_world
public String moduleId()
public ConfigurationSection config()                   // modules.<id>, empty section if missing; read-only
public long seed()                                     // world.seed
public long moduleSeed()                               // deriveSeed(seed(), moduleId())
public Random random()                                 // new Random(moduleSeed()), main thread
public Random newRandom(String salt)                   // new Random(deriveSeed(moduleSeed(), salt))
public static long deriveSeed(long base, String salt)

public RegionAllocator regions()
public Region allocateRegion(String name, int chunksX, int chunksZ)   // 64 blocks high from baseY()
public int baseY()                                     // FLAT: first air block above the ground (y=-60 by default)
public EntityRegistry entities()                       // this module's entities, removed on stop
public ChunkTickets chunks()                           // shared, reference-counted plugin tickets
public BlockWriter blocks()                            // shared fast block setter
public <T extends Entity> T spawn(Location location, Class<T> type, @Nullable Consumer<? super T> init)
        // stress_world only; World#spawn(loc, type, false, init) + entities().track(...)

public Stage stage()                                   // current stage (SINGLE in single mode)
public boolean isMaxStage()
public double intensity()                              // 1.0, or timeline.max-stage-multiplier during MAX
public int scaled(int base)                            // floor(base * intensity()), >= 0
public boolean isSingleMode()
public long runTick()                                  // ticks since run start
public long moduleTick()                               // ticks since this module started
public long totalTicks()

public boolean isActive()                              // false after stop; thread-safe; async loops must check it
public BukkitTask runTimer(Runnable task, long delay, long period)      // main thread, auto-cancelled
public BukkitTask runLater(Runnable task, long delay)
public BukkitTask runSync(Runnable task)                                // next tick on main; callable from async
public BukkitTask runAsync(Runnable task)
public BukkitTask runAsyncTimer(Runnable task, long delay, long period)
public BukkitTask track(BukkitTask task)                                // register a task made elsewhere
public <T extends AutoCloseable> T closeOnStop(T closeable)             // closed on stop, LIFO
public void onStop(Runnable action)                                     // run on stop, LIFO
```

All `run*` tasks are wrapped: they do nothing once the module is stopped, and exceptions are logged instead
of killing the task. They are safe to call from any thread. Do not register an `ExecutorService` with
`closeOnStop` (its `close()` waits for termination on the main thread); use
`ctx.onStop(pool::shutdownNow)` and make workers check `ctx.isActive()`.

### `Region` (record, chunk-aligned, inclusive block bounds)

```java
public record Region(String owner, String name, int minChunkX, int minChunkZ, int chunksX, int chunksZ, int minY, int maxY)
public int maxChunkX(); public int maxChunkZ()
public int minX(); public int minZ(); public int maxX(); public int maxZ()
public int sizeX(); public int sizeZ(); public int height()
public int centerX(); public int centerZ(); public int chunkCount()
public boolean contains(int x, int y, int z)
public boolean containsChunk(int chunkX, int chunkZ)
public Location at(World world, double dx, double dy, double dz)   // relative to (minX, minY, minZ)
public Location center(World world)                                // (centerX+0.5, minY, centerZ+0.5)
public void forEachChunk(Region.ChunkAction action)                // fixed x-major order
public interface ChunkAction { void accept(int chunkX, int chunkZ); }
```

### `RegionAllocator` (one per module, fixed lane)

```java
public static final int LANE_SPACING_CHUNKS = 128;   // lanes are 2048 blocks apart along X
public static final int LANE_WIDTH_CHUNKS = 96;      // max chunksX
public static final int LANE_LENGTH_CHUNKS = 1024;   // max total Z per lane
public static final int LANE_ORIGIN_CHUNK_X = 8;     // lane L starts at chunkX = 8 + 128*L
public static final int LANE_ORIGIN_CHUNK_Z = 8;
public static final int GAP_CHUNKS = 2;              // gap between regions of one lane
public static final int DEFAULT_HEIGHT = 64;
public Region allocate(String name, int chunksX, int chunksZ)
public Region allocate(String name, int chunksX, int chunksZ, int height)
public List<Region> allocated()
public int lane(); public int baseY(); public String owner()
```

Regions of one module are stacked along +Z in allocation order, so coordinates are identical on every
server and in both `all` and single-module mode. Space outside your regions but inside your lane
(chunk X in `[8+128L, 8+128L+127]`) is also yours (e.g. the chunks module moving its ring along Z).

### `ChunkTickets` (shared by the run; main thread)

```java
public boolean hold(int chunkX, int chunkZ)                    // World#addPluginChunkTicket (sync load if needed); ref-counted
public boolean release(int chunkX, int chunkZ)                 // removes ticket when the last hold is released
public CompletableFuture<Chunk> loadAndHold(int chunkX, int chunkZ)   // World#getChunkAtAsync(x, z, true) then hold
public CompletableFuture<Void> loadAndHold(Region region)
public void holdNow(Region region)                             // sync
public void release(Region region)
public int heldCount()
public boolean isReleased()                                    // true after the run stopped (holds are ignored)
```

Typical build: `ctx.chunks().loadAndHold(region).thenRun(() -> { if (ctx.isActive()) build(); })` — the future
completes on the main thread. All tickets are released on stop (`World#removePluginChunkTickets(plugin)`).

### `BlockWriter` (shared; main thread)

```java
public BlockData data(String blockData)        // cached Bukkit.createBlockData; shared instance — clone() before mutating
public BlockData data(Material material)       // cached material.createBlockData()
public void set(int x, int y, int z, BlockData data)            // Block#setBlockData(data, false) — no physics
public void set(int x, int y, int z, Material material)
public void setWithPhysics(int x, int y, int z, BlockData data) // Block#setBlockData(data, true)
public Block block(int x, int y, int z)
public int fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockData data)   // inclusive, no physics
public int fill(Region region, int y1, int y2, BlockData data)
public long changedBlocks()
public World world()
```

### `EntityRegistry` (per module; main thread)

```java
public <T extends Entity> T track(T entity)    // players ignored
public void untrack(Entity entity)
public int trackedCount()
public long totalTracked()
public int aliveCount()                        // prunes invalid entities
public List<Entity> snapshot()
public int removeAll()
```

### Other framework types

```java
public enum Stage { SINGLE(-1), BASELINE(0), MOBS(1), PHYSICS(2), REDSTONE(3), CHUNKS(4), PACKETS(5), MAX(6);
    public static final Stage[] TIMELINE;   // BASELINE..MAX
    public int index(); public String key(); public String title(); public String display(); }

public interface ProfilerBridge {           // implemented by profiler.StressProfiler
    boolean isAvailable();
    boolean isRunning();
    void start(CommandSender sender, int seconds);   // stops by itself after `seconds` of wall time
    void stop(CommandSender sender);
    String status();
    void shutdown(); }

public final class StressPlugin extends JavaPlugin {
    public StressConfig loadStressConfig();
    public ModuleRegistry modules();
    public TestWorld testWorld();
    public TickMonitor tickMonitor();
    public ProfilerBridge profiler();
    public void setProfiler(ProfilerBridge profiler);
    public @Nullable StageRunner currentRun();
    public @Nullable StageRunner lastRun();
    public @Nullable Path lastResult();
    public boolean isPreparing();
    public void startRun(CommandSender sender, String mode, int seconds);
    public boolean stopRun(String reason); }

public final class TickMonitor {            // ServerTickEndEvent based, last 1200 ticks
    public double averageMspt(int ticks); public double maxMspt(int ticks); public double tps(int ticks); public long ticksSeen(); }

public final class StageRunner {            // read-only view of the active run
    public StressConfig config(); public World world(); public String mode(); public boolean isSingleMode();
    public Stage stage(); public long tick(); public long totalTicks(); public long stageEnd(int index);
    public boolean isFinished(); public int baseY(); public ChunkTickets tickets(); public BlockWriter blocks();
    public List<StageRunner.Handle> handles(); public List<StageRunner.StageStats> stats(); }

public final class Msg {                    // Russian chat output with the [AmazakeStress] prefix
    public static final String PERMISSION = "stress.admin";
    public static void info/ok/warn/error(CommandSender to, String text);
    public static void plain(CommandSender to, String text, TextColor color);
    public static void broadcast(String text, TextColor color, @Nullable CommandSender skip); }

public final class TestWorld {
    public static final String NAME = "stress_world";
    public @Nullable World loaded(); public Path folder(); public int baseY(World world, StressConfig config); }

public final class StressConfig {
    public ConfigurationSection moduleSection(String id); public boolean moduleEnabled(String id);
    public long seed(); public double maxStageMultiplier(); public int totalSeconds(); /* ... */ }
```

## Verified Paper 1.21.11 API notes

* Gamerules are a registry in 1.21.11 with new snake_case names (`GameRules.SPAWN_MOBS`, `GameRules.ADVANCE_TIME`,
  `GameRules.RANDOM_TICK_SPEED`, ...). The old `GameRule.DO_MOB_SPAWNING` etc. are deprecated for removal.
  The framework applies config gamerules via `Registry.GAME_RULE.get(NamespacedKey)`.
* `WorldCreator#keepSpawnLoaded` and `World#setKeepSpawnInMemory` are deprecated for removal (spawn chunks
  no longer exist since 1.21.9) — not used.
* `RegionAccessor#spawn(Location, Class<T>, boolean randomizeData, Consumer<? super T>)` is used for deterministic spawns.
* MSPT is measured with `com.destroystokyo.paper.event.server.ServerTickEndEvent#getTickDuration()`.

## Checking a module without Gradle

Gradle cannot reach repo.papermc.io in the sandbox. Type-check against the Paper API sources with
`/tmp/claude-0/-home-user/d8dff9cc-7075-58a3-a329-0259f695514b/scratchpad/stress-check.sh` (prints only diagnostics
in `stress-plugin/src/...`; empty output = clean). It runs javac with `-sourcepath` on the Paper API sources and
`-XDshould-stop.ifError=FLOW` (errors from missing third-party deps of Paper itself are filtered out).
