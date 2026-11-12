# Amazake profiler report, format version 1

`/amazake profile` measures the server for N seconds and produces one JSON document in this format. The server
uploads it to the profiler site (`web/`), which stores it and shows it at `/r/<id>`. This file is the contract
between the three parts: the collector in the server (`amazake-server/.../profiler/`), the site's backend
(`web/server/`) and its viewer (`web/static/`). Change it here first.

Rules:
- UTF-8 JSON, one object. Sent gzip-compressed (`Content-Encoding: gzip`) or plain.
- Readers ignore fields they don't know. Fields marked *optional* may be absent or `null`; everything else is
  always present.
- Times are milliseconds unless the name says otherwise. CPU amounts are in **cores**: `1.0` is one logical core
  fully busy for the interval, so a value can exceed 1 for a group of threads and is at most `system.logicalCores`.
- A new optional field may be added in version 1. Renaming, removing or changing the meaning of a field makes
  version 2.

## Top level

```json
{
  "format": "amazake-profile",
  "version": 1,
  "createdAt": "2026-11-11T16:30:00Z",
  "durationMs": 30000,
  "intervalMs": 1000,
  "server": { ... },
  "system": { ... },
  "samples": [ ... ],
  "threads": [ ... ],
  "groups": [ ... ],
  "lagSpikes": [ ... ],
  "migrations": [ ... ],
  "entities": { ... }
}
```

| Field | Type | Meaning |
|---|---|---|
| `format` | string | Always `"amazake-profile"` |
| `version` | int | `1` |
| `createdAt` | string | ISO-8601 UTC instant the measurement started |
| `durationMs` | int | How long it measured |
| `intervalMs` | int | Time between two samples (default 1000) |
| `migrations` | object[], *optional* | Moves of the main thread to a reserve core during the measurement, oldest first; see [`migrations[]`](#migrations). Absent when there were none |
| `entities` | object, *optional* | Where the entity tick time went: mobs vs other entities vs players, by entity type and by phase; see [`entities`](#entities). Only Amazake writes it (its server counts it); absent on other servers and with `amazake.yml` `profiler.entity-breakdown: false` |

## `server`

| Field | Type | Meaning |
|---|---|---|
| `brand` | string | `"Amazake"` |
| `version` | string | Full build string (`ServerBuildInfo` VERSION_SIMPLE), e.g. `1.21.11-7-b3a4d61` |
| `minecraftVersion` | string | `1.21.11` |
| `javaVersion` | string | `System.getProperty("java.version")` |
| `javaVendor` | string | `java.vendor` |
| `jvmArgs` | string[] | JVM input arguments (`RuntimeMXBean.getInputArguments()`), with any argument containing `password`, `token` or `secret` (case-insensitive) replaced by `"<hidden>"` |
| `onlinePlayers` | int | At the start |
| `maxPlayers` | int | |
| `plugins` | `{name, version, enabled}[]` | Installed plugins |
| `worlds` | `{name, environment, loadedChunks, entities, tileEntities, players}[]` | At the end of the measurement; `environment` is `NORMAL`, `NETHER`, `THE_END` or `CUSTOM` |
| `threadPools` | object | `{ "chunkWorkers": int, "chunkIo": int, "netty": int }`, the sizes the server uses |

## `system`

| Field | Type | Meaning |
|---|---|---|
| `os` | string | `os.name` + `os.version` |
| `arch` | string | `os.arch` |
| `logicalCores` | int | Logical CPUs the server may use: the process's CPU set without offline CPUs, capped by a cgroup CPU quota (rounded up). This is what `Runtime.availableProcessors()` answers before any thread is pinned; it is read once at startup because, once the core layout pins threads, `availableProcessors()` answers from the calling thread's CPU set. Not Linux: `Runtime.availableProcessors()` |
| `cpuModel` | string, *optional* | From `/proc/cpuinfo` (`model name`) on Linux |
| `totalMemoryBytes` | long, *optional* | Physical memory |
| `heapMaxBytes` | long | `-Xmx` |
| `perCore` | bool | Whether `samples[].cores` holds per-core loads (Linux `/proc/stat`); false elsewhere |
| `perThreadSource` | string | `"proc"` (Linux `/proc/self/task`: JVM-internal threads such as GC and JIT included) or `"jmx"` (Java threads only) |
| `coreRoles` | string[], *optional* | The core layout's job of each logical core, one per entry of `samples[].cores` and in the same order (the N of `cpuN` in `/proc/stat`). Values: `tick` (main thread), `chunks` (chunk workers and chunk I/O), `network` (Netty), `gc` (GC, JIT, VM threads; stop-the-world GC workers run on every core but the reserve during a pause), `background` (all other threads), `reserve` (a reserve core: kept empty, no thread of the server runs there; the main thread moves to it when its own core is busy with foreign load, see `migrations`); several roles on one core are joined with `+` in this order, e.g. `chunks+gc` and `network+background` (the automatic layouts for 2 to 4 cores always combine roles); `shared` means no role: an idle core (the tick core's SMT sibling) or one outside the CPUs the server may use. `reserve` is never combined with another role. Readers should split on `+` and treat unknown names as `shared`. The roles are those at the end of the measurement: after a move the old tick core is `reserve` and the reserve core it moved to is `tick`. Present only when `perCore` and a core layout (`amazake.yml` `cpu.layout`) is active |

## `samples[]`

One per interval, in time order.

| Field | Type | Meaning |
|---|---|---|
| `t` | int | Milliseconds since the start, at the end of this interval |
| `cores` | number[], *optional* | Busy fraction 0..1 of each logical core over the interval, for the whole machine (all processes). Present when `system.perCore` |
| `systemCpu` | number | Whole machine, 0..1 of all cores |
| `processCpu` | number | This server process, 0..1 of all cores |
| `tps` | number | Ticks completed in the interval, scaled to per second (max 20) |
| `msptAvg` | number | Mean tick duration in the interval, ms |
| `msptMax` | number | Longest tick in the interval, ms |
| `heapUsedBytes` | long | After the interval |
| `gcCount` | int | GC runs during the interval (all collectors) |
| `gcTimeMs` | int | GC time during the interval (all collectors) |
| `players` | int | Online players |
| `entities` | int, *optional* | All loaded entities, all worlds |
| `loadedChunks` | int, *optional* | All loaded chunks, all worlds |
| `entityTicks` | object, *optional* | Entity tick time in this interval, ms per tick: `{"loop": 210.4, "mob": 120.2, "other": 80.1, "player": 0.2}` (the four keys of [`entities.msPerTick`](#entities) `loop` and of the classes). Absent when the report has no top-level `entities`, or when the main thread did not answer for this interval (the next sample then covers both intervals) |

## `threads[]`

Every thread seen during the measurement, by CPU used, most first.

| Field | Type | Meaning |
|---|---|---|
| `name` | string | Thread name. From `/proc` names are cut to 15 characters by Linux; Java threads take their full name from the JVM |
| `group` | string | One of the groups below |
| `cpuMs` | int | CPU time used during the measurement |
| `avgCores` | number | `cpuMs / durationMs`: 1.0 means one core busy the whole time |
| `series` | number[], *optional* | Cores used per sample interval (same length as `samples`). Only for the 24 busiest threads |

## `groups[]`

The threads summed by group, most CPU first. Groups with no threads are left out.

| Field | Type | Meaning |
|---|---|---|
| `group` | string | Group id |
| `threads` | int | Threads in the group |
| `cpuMs` | int | |
| `avgCores` | number | |
| `peakCores` | number | Highest per-interval value |
| `series` | number[] | Cores used per sample interval |

Group ids, matched in this order on the thread name (first match wins):

| Group | Thread names |
|---|---|
| `main` | `Server thread` |
| `chunk-workers` | `* Common Worker #*` (Moonrise worker pool: chunk generation, loading, light) |
| `chunk-io` | `* I/O Worker #*` |
| `netty` | `Netty *` (network IO, compression, encryption) |
| `gc` | `GC Thread*`, `G1 *`, `GC *`, `VM Thread`, `ZGC*`, `Shenandoah*` |
| `jit` | `C1 CompilerThre*`, `C2 CompilerThre*`, `C1 Compiler*`, `C2 Compiler*` |
| `plugins-async` | `Craft Scheduler Thread*`, `Folia Async Scheduler*` |
| `chat` | `Async Chat Thread*`, `Async Tab Complete*` |
| `paper-async` | `Paper Async *`, `Paper Plugin Remapper*`, `User Authenticator*`, `Configuration Thread*` |
| `spark` | `spark-*` |
| `amazake` | `Amazake *` (Amazake's own pools) |
| `other` | everything else |

## `lagSpikes[]`

Ticks that took longer than 50 ms, at most 200, longest first.

| Field | Type | Meaning |
|---|---|---|
| `t` | int | Milliseconds since the start (sample resolution) |
| `ms` | number | Tick duration |

## `migrations[]`

*Optional* (added in version 1; older reports and readers simply lack it). Each entry is one move of the main
thread to a reserve core (`amazake.yml` `cpu.reserve`): its core was busy with work that was not the main thread
(other processes, interrupts, time stolen by the hypervisor) above the threshold on two checks in a row, so the
tick moved to an idle reserve core and its old core became the reserve.

| Field | Type | Meaning |
|---|---|---|
| `t` | int | Milliseconds since the start of the measurement (the same clock as `samples[].t`) |
| `from` | int | The old tick core: the N of `cpuN`, the same numbering as `coreRoles` and `samples[].cores` |
| `to` | int | The new tick core (a reserve core until then) |
| `foreignLoad` | number | Load on `from` that was not the main thread, over the last check, as a fraction of one core (0..1, steal included) |

## `entities`

*Optional* (added in version 1). Amazake's server times every entity tick, but only while a measurement asks
for it, so it costs nothing otherwise. The `entities` sections of all worlds (`ServerLevel#tick`, from the
dragon fight to just before block entities) make the **loop**. Inside it each ticked entity is one of three
**classes**:

- `mob`: a `net.minecraft.world.entity.Mob`, everything with AI: zombies, villagers, animals, bees, piglins, the dragon.
- `other`: every other non-player entity: items, XP orbs, falling blocks, arrows, minecarts, armor stands, displays.
- `player`: `Player`. Only their light `tick()` runs here; the heavy part (`doTick`) runs with the network, outside the loop.

Each entity's own time (`tick`/`postTick`, or `inactiveTick` when the entity activation range made it
inactive; its passengers are timed separately) is split into **phases**. A phase's time is **self time**:
a phase nested inside another (pathfinding inside goals, `move` inside `travel`) is not counted twice.

| Phase | What runs there |
|---|---|
| `base` | Everything of the entity's tick that no phase below covers (`baseTick`, fire, effects, equipment, age, ...) |
| `goals` | `Mob#serverAiStep`: the target and goal selectors (`canUse`, `tick` of goals, their entity searches and line-of-sight checks) |
| `brain` | `customServerAiStep`: brain behaviours of villagers, piglins and other brain mobs, villager trades and gossip |
| `sensors` | `Brain#tick` sensors (nearest entities, items, POI, beds, ...) |
| `pathfind` | Building a path: `PathFinder#findPath` (A*), wherever it is called from |
| `navigation` | Following a path: `PathNavigation#tick` |
| `travel` | `LivingEntity#travel`: movement physics of living entities, without `move` |
| `move` | `Entity#move`: collisions with blocks and entities, stepping, landing |
| `push` | `LivingEntity#pushEntities`: entity cramming and pushing |
| `loot` | `Mob#aiStep` item pick-up search (zombies, skeletons, piglins, villagers) |
| `fluids` | Water and lava checks and pushing (`updateInWaterStateAndDoFluidPushing`) |
| `merge` | Item and XP orb merging (`ItemEntity#mergeWithNeighbours`, `ExperienceOrb#scanForMerges`) |
| `inactive` | `inactiveTick()` of entities outside the activation range, without the phases nested in it |

Readers keep unknown phase names (show them as they are) and treat a missing phase as 0.

```json
"entities": {
  "ticks": 600,
  "phases": ["base", "goals", "brain", "sensors", "pathfind", "navigation", "travel", "move", "push",
             "loot", "fluids", "merge", "inactive"],
  "msPerTick": { "loop": 210.4, "ear": 1.2, "despawn": 0.8, "overhead": 3.1 },
  "classes": [
    { "class": "mob", "msPerTick": 120.2, "entitiesPerTick": 1180.0, "inactiveShare": 0.05,
      "phases": { "base": 10.1, "goals": 30.4, "brain": 18.0, "sensors": 9.2, "pathfind": 12.5,
                  "navigation": 3.3, "travel": 6.0, "move": 14.1, "push": 11.2, "loot": 2.0, "fluids": 3.4 } },
    { "class": "other", "msPerTick": 80.1, "entitiesPerTick": 52000.0, "inactiveShare": 0.6, "phases": { ... } },
    { "class": "player", "msPerTick": 0.2, "entitiesPerTick": 1.0, "inactiveShare": 0.0, "phases": { ... } }
  ],
  "types": [
    { "type": "minecraft:villager", "class": "mob", "msPerTick": 40.3, "entitiesPerTick": 120.0,
      "usPerEntity": 335.8, "inactiveShare": 0.0, "phases": { "brain": 15.0, "sensors": 8.1, ... } }
  ]
}
```

| Field | Type | Meaning |
|---|---|---|
| `ticks` | int | Server ticks the numbers cover. Every value below is **milliseconds per tick** (sum over the measurement / `ticks`) unless named otherwise |
| `phases` | string[] | The phase names in display order |
| `msPerTick.loop` | number | The whole entities sections of all worlds |
| `msPerTick.ear` | number | Entity activation range (`ActivationRange.activateEntities`), once per world per tick |
| `msPerTick.despawn` | number | `checkDespawn` of all entities |
| `msPerTick.overhead` | number | `loop` − `ear` − `despawn` − all classes: iteration, activation checks, chunk tasks run between entities, the dragon fight |
| `classes[]` | object[] | Always the three classes in the order `mob`, `other`, `player` (a class with nothing ticked has zeros) |
| `classes[].msPerTick` | number | Time of the class's entities (sum of its phases) |
| `classes[].entitiesPerTick` | number | Entity ticks of the class / `ticks`: how many entities of it ticked per tick on average |
| `classes[].inactiveShare` | number | 0..1, share of those entity ticks that were `inactiveTick` |
| `classes[].phases` | `{phase: ms}` | Per phase; phases with 0 may be left out |
| `types[]` | object[] | The most expensive entity types by `msPerTick`, at most 40, most first |
| `types[].type` | string | Registry id, e.g. `minecraft:zombie` |
| `types[].class` | string | `mob`, `other` or `player` |
| `types[].usPerEntity` | number | Microseconds per entity tick (`msPerTick * 1000 / entitiesPerTick`) |
| `types[].msPerTick`, `entitiesPerTick`, `inactiveShare`, `phases` | | As in `classes[]` |

The AmazakeStress plugin writes the same object (when it runs on Amazake) at the top level of its report and,
per stage, as `stress.stageResults[].entities`. (Per-sample objects are `samples[].entityTicks`: `samples[].entities` is the loaded entity count.)

## Upload API (site)

- `POST /api/reports` with the JSON body (optionally gzip with `Content-Encoding: gzip`), `Content-Type: application/json`.
  Optional header `X-Amazake-Token` when the site requires a token.
  - `201 {"id": "aB3xY9kQ2m", "url": "https://<site>/r/aB3xY9kQ2m"}`
  - `400` not a version-1 report, `401` token missing or wrong, `413` too large (limit 8 MiB uncompressed), `429` too many uploads.
- `GET /api/reports/<id>` → the report JSON (`404` if unknown or expired).
- `GET /r/<id>` → the viewer page, which loads `/api/reports/<id>`.
- The viewer also opens a report file from disk (drag and drop), for reports saved locally when upload is off.
