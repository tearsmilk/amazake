# План многопоточности: что патчить в Minecraft

## Выбранный путь

Из вариантов «перенести регионный тик ShreddedPaper на нашу базу Paper», «пересесть на ShreddedPaper (и Purpur)»,
«перенести регионы Folia» и «только параллельные миры» выбран первый: переносим регионный тик ShreddedPaper
по шагам. Каждый шаг — за переключателем в `amazake.yml`, по умолчанию выключен, и
включается только после AmazakeStress. Старые плагины, которые синхронно трогают чужой регион, получают
исключение (никогда — тихую гонку). Игроки на целевом сервере разбросаны по миру, поэтому регионы окупаются.

1. **Подготовка (ничего не меняет, пока многопоточность выключена).** Секции `multithreading` и
   `plugin-threading` в `AmazakeConfig`. Класс `ink.hoka.amazake.threading.PluginLock`: одна общая
   реентерабельная блокировка на весь код плагинов, не готовых к многопоточности, — на всю раздачу синхронного
   события (`PaperEventManager#callEvent`), на каждую синхронную задачу (`CraftScheduler#mainThreadHeartbeat`,
   `FoliaGlobalRegionScheduler`, `FoliaEntityScheduler`) и на команды плагинов и их автодополнение
   (`BukkitCommandNode`). Без блокировки работают плагины с `folia-supported: true`, из `thread-safe`, кроме
   `force-locked`. `EntityTickStats` и `PluginTickStats` пишут в счётчики своего потока (`ThreadLocal`), снимок
   их суммирует; в отчёте профайлера появились `plugins[].lockWaitMsPerTick` и `plugins.lockHeldMsPerTick`.
   Модуль `races` в AmazakeStress проверяет, что код плагина не исполняется в двух потоках сразу.
2. **`multithreading.mode: worlds`.** Каждый мир тикает в своём потоке, общий барьер в
   `MinecraftServer#tickChildren` (форма `tickAsync` из ShreddedPaper). Общее состояние делается
   потокобезопасным: поля захвата в `Level`, `PlayerList`, табло, карты, `DimensionDataStorage`; переходы между
   мирами — через отложенную передачу. Проверка: AmazakeStress с нагрузкой в трёх мирах.
3. **`multithreading.mode: regions`.** Ядро ShreddedPaper: регионы фиксированного размера
   (`multithreading.region-size`) под замками 3×3, планировщик регионов, тикер чанков, прокси тиков блоков,
   `TickThread#isTickThreadFor` по регионам, пакеты игроков в поток их региона, `RegionScheduler`. Затем
   проверки на чужой регион, дюп через общий GUI, тест на взаимную блокировку и матрица популярных плагинов
   (LuckPerms, Vault+Essentials, WorldGuard, PlaceholderAPI+TAB, ProtocolLib, CoreProtect).
4. **Дополнения из Folia (по желанию).** `/tps` и профайлер по регионам, дамп стека зависшего региона в
   watchdog, ограничение одновременных входов. Подавление обновлений блоков на границах регионов (патч Folia
   №4) не берём: оно меняет ванильное поведение.

Ниже — исходная карта точек входа в коде Minecraft; она остаётся справочником для шагов 2–4.

Карта следующих шагов Amazake, для которых нужны патчи к коду Minecraft (`amazake-server/minecraft-patches/`).
Сейчас в Amazake есть только раскладка ядер и профайлер: оба живут в `amazake-server/src/main/java/ink/hoka/amazake/`
и не трогают тик. Всё, что ниже, меняет сам тик, поэтому каждая фича получает переключатель в `amazake.yml`,
а по умолчанию выключена, пока не пройдёт проверку на AmazakeStress (кроме шага 1: он только измеряет).

Документ для разработчиков: проза по-русски, имена классов, методов и полей — как в коде (Mojang-имена,
после патчей Paper).

## Откуда взяты точки входа

Полного кода Minecraft у нас нет (сборка в песочнице не работает: Mojang и PaperMC закрыты, см. «Сборка»).
Точки входа найдены по контексту патчей, и после `./gradlew applyAllPatches` их нужно сверить с настоящим кодом:

| Источник | Версия | Что там |
|---|---|---|
| Paper, `paper-server/patches/sources/net/minecraft/**` | `c5eb0790f199` (наш `paperCommit`) | Патчи Paper к Minecraft по файлам, с контекстом |
| Paper, `paper-server/patches/features/0001-Moonrise-optimisation-patches.patch` | то же | Moonrise: трекер, `NearbyPlayers`, `ServerEntityLookup`, система чанков |
| Paper, `paper-server/src/main/java/**` | то же | CraftBukkit, Moonrise (`ca/spottedleaf/moonrise/**`), `TickThread`, `AsyncCatcher` |
| Leaf, `leaf-server/minecraft-patches/features/*.patch`, `leaf-server/paper-patches/features/*.patch`, `leaf-server/src/main/java/org/dreeam/leaf/**` | `12f438e06b8a` (1.21.11) | Готовые асинхронный трекер, поиск пути, параллельные миры, асинхронный подсчёт мобов для спавна |

Номера строк в цитатах ниже не приводятся: они сдвигаются от патча к патчу. Ищите по имени метода.

### Как устроен тик сейчас (Paper 1.21.11)

```
MinecraftServer#runServer
  runAllTasksAtTickStart()                 пакеты игроков, задачи главного потока (Paper "improve tick loop")
  tickServer(hasTimeLeft)
    ServerTickStartEvent
    tickChildren(hasTimeLeft)
      connection.suspendFlushing() у всех игроков
      CraftScheduler#mainThreadHeartbeat()   задачи плагинов (runTask...)
      FoliaGlobalRegionScheduler#tick, CraftEntity.taskScheduler.executeTick() у каждой сущности
      "commandFunctions"                     функции датапаков
      "levels": processQueue, пакеты времени
      for (ServerLevel serverLevel : getAllLevels())  ← миры строго по очереди
        serverLevel.tick(hasTimeLeft)
      "connection": tickConnection()          сеть: чтение, keepalive
      "players": playerList.tick()
      ...
    autosave, ServerTickEndEvent
```

Внутри `ServerLevel#tick` (по меткам профайлера Minecraft `profilerFiller.push/popPush`):
`world border`/`weather` → сон, время → `tickPending` (`blockTicks`, `fluidTicks`) → `raid` → `chunkSource`
(`ServerChunkCache#tick`: `purge`, `tickChunks` со спавном, случайными тиками, снегом и грозой, затем
`chunkMap.tick()` — **трекер**, затем `unload`) → `blockEvents` (поршни, нотные блоки, сундуки) → `entities`
(дракон, затем `entityTickList.forEach`) → `blockEntities` (`tickBlockEntities`: воронки, печи, спаунеры) →
`entityManagement` → `debugSynchronizers`. Метки `blockTicks`, `fluidTicks`, `blockEntities`, `entityManagement`,
`debugSynchronizers`, `pollingChunks`, `tickingChunks`, `unload` видны в патчах; остальные — по ванильному 1.21.x,
сверить после `applyAllPatches`.

Порядок «трекер до сущностей» подтверждает Leaf 0336 (это Paper PR #13094, MC-297196: трекер отправляет
позиции до того, как сущности сдвинулись в этом тике, отсюда рассинхрон отбрасывания):

```java
// leaf-server/minecraft-patches/features/0336-Use-legacy-tracker-ticking.patch, ServerChunkCache#tick
             this.tickChunks();
-            this.chunkMap.tick();
+            if (!org.dreeam.leaf.config.modules.gameplay.Knockback.useLegacyTrackerTicking) this.chunkMap.tick(); // Leaf - Use legacy tracker ticking
// ServerLevel#tick, после entityManagement
+        if (org.dreeam.leaf.config.modules.gameplay.Knockback.useLegacyTrackerTicking) this.getChunkSource().chunkMap.tick(); // Leaf - Use legacy tracker ticking
```

### Что считается «главным потоком»

```java
// paper-server/src/main/java/ca/spottedleaf/moonrise/common/util/TickThread.java
    public static boolean isTickThread() {
        return Thread.currentThread() instanceof TickThread;
    }
// org/bukkit/craftbukkit/CraftServer.java
    public boolean isPrimaryThread() {
        return ca.spottedleaf.moonrise.common.util.TickThread.isTickThread(); // Paper - rewrite chunk system
    }
// org/spigotmc/AsyncCatcher.java
        if (!ca.spottedleaf.moonrise.common.util.TickThread.isTickThread()) { // Paper - chunk system
            throw new IllegalStateException("Asynchronous " + reason + "!");
```

Любой поток-наследник `TickThread` для Bukkit — главный: `Bukkit.isPrimaryThread()` на нём `true`, `AsyncCatcher`
пропускает. А `PaperEventManager#callEvent` бросает исключение, если синхронное событие вызвано не из главного потока
(`"may only be triggered synchronously"`). Отсюда два правила плана:

- потоки, которые **тикают мир** (шаг 4), — наследники `TickThread`;
- потоки, которые **только считают** (трекер, поиск пути), — обычные потоки. Они не вызывают события, не трогают
  `AsyncCatcher` и не меняют мир; всё это делается потом на потоке мира.

Все «Amazake …» потоки сейчас попадают в группу `amazake` → роль `background` (`cpu/ThreadClassifier.java`). Для
каждой новой группы потоков ниже указано, куда её отнести в `ThreadClassifier` и раскладке ядер.

---

## 1. Замер времени по мирам и фазам тика

**Цель.** Знать, сколько миллисекунд тика уходит на каждый мир и внутри мира на каждую фазу: сущности, блок-сущности,
запланированные тики блоков (редстоун), тики чанков, трекер, события блоков; и на общие фазы сервера: планировщик
плагинов, сеть, игроки. Без этих цифр нельзя ни доказать выигрыш шагов 2–4, ни раскладывать миры по потокам (шаг 4
использует эти же счётчики). Поведение сервера не меняется.

**Точки входа.**

| Где | Метод | Что ставим |
|---|---|---|
| `net/minecraft/server/MinecraftServer.java` | `tickChildren` | Общие фазы: `scheduler` (от `mainThreadHeartbeat()` до `"commandFunctions"`), `functions`, `levels` (весь цикл миров), `network` (`tickConnection()`), `players` (`playerList.tick()`), `other` |
| то же | цикл `for (ServerLevel serverLevel : this.getAllLevels())` | `PhaseClock#beginTick/endTick` вокруг `serverLevel.tick(hasTimeLeft)` |
| то же | `runAllTasksAtTickStart` (Paper) | фаза `packets` (обработка пакетов в начале тика) |
| `net/minecraft/server/level/ServerLevel.java` | `tick(BooleanSupplier)` | `clock.enter(Phase.X, now)` рядом с каждым `profilerFiller.push/popPush`: `blockTicks` (блоки + жидкости), `raids`, `blockEvents`, `entities`, `blockEntities`, `entityManagement`, остальное — `other` |
| `net/minecraft/server/level/ServerChunkCache.java` | `tick(BooleanSupplier, boolean)` | `chunkTick` вокруг `this.tickChunks(...)`, `tracker` вокруг `this.chunkMap.tick()` |
| `ca/spottedleaf/moonrise/...` (Moonrise, `ChunkSystemMinecraftServer#moonrise$executeMidTickTasks`) | | Время задач чанков между сущностями считать отдельно (`chunkTasks`) и вычитать из фазы, где они выполнились |

Точка в цикле миров (цитата из патча Paper):

```java
// paper-server/patches/sources/net/minecraft/server/MinecraftServer.java.patch, tickChildren
+        this.isIteratingOverLevels = true; // Paper - Throw exception on world create while being ticked
         for (ServerLevel serverLevel : this.getAllLevels()) {
+            serverLevel.hasPhysicsEvent = org.bukkit.event.block.BlockPhysicsEvent.getHandlerList()...
             profilerFiller.push(() -> serverLevel + " " + serverLevel.dimension().identifier());
             ...
             profilerFiller.push("tick");
             ...
         }
+        this.isIteratingOverLevels = false; // Paper - Throw exception on world create while being ticked
         profilerFiller.popPush("connection");
         this.tickConnection();
```

Leaf уже меряет целый мир (SparklyPaper «Track each world MSPT»), но без фаз:

```java
// leaf-server/minecraft-patches/features/0201-SparklyPaper-Track-each-world-MSPT.patch, MinecraftServer#tickLevel
+            long i = Util.getNanos(); // SparklyPaper - track world's MSPT
             serverLevel.tick(hasTimeLeft);
+            long j = Util.getNanos() - i;
+            serverLevel.tickTimes5s.add(this.tickCount, j);
```

**Редстоун отдельной фазой не меряется**: повторители, компараторы и наблюдатели работают в `blockTicks`, поршни — в
`blockEvents`, провода — в обновлениях соседей внутри той фазы, которая поставила блок. В отчёте это «блоки»:
`blockTicks + blockEvents + blockEntities`.

**Код.** Счётчики — свой класс без Bukkit: `ink.hoka.amazake.tick.PhaseClock` (прототип с тестом:
`/tmp/claude-0/-home-user/d8dff9cc-7075-58a3-a329-0259f695514b/scratchpad/reserve/src/ink/hoka/amazake/tick/PhaseClock.java`).
Один экземпляр на мир (`ServerLevel` получает поле `public final ink.hoka.amazake.tick.PhaseClock amazake$phases`) и
один на общие фазы сервера. Пишет только поток, который тикает мир; счётчики только растут (`AtomicLongArray`, запись
`lazySet` одним писателем). Профайлер в своём потоке берёт два снимка и вычитает — главный поток для этого не нужен
(в отличие от `profiler/WorldSnapshots.java`). Максимум тика мира за интервал: писатель хранит `max`, читатель
сбрасывает `getAndSet(0)`; гонка может один раз завысить максимум, но не потерять тик.

**Что снимать в снимок.** Ничего: только числа. Имя мира берётся из `serverLevel.getWorld().getName()` один раз при
создании `PhaseClock`.

**Параллельно / на главном потоке.** Всё на главном (на потоке мира в шаге 4). Чтение — в потоке профайлера.

**Риски потоков.** Нет: один писатель. Миры, созданные и выгруженные во время замера: профайлер держит
`Map<String, Snapshot>` и пропускает мир, которого не было в начале интервала.

**Риски для плагинов.** Нет. Стоимость: около 12 вызовов `System.nanoTime()` на мир за тик (~0,3 мкс).

**`amazake.yml`.** `profiler.tick-phases: true` (включено по умолчанию, применяется через `/amazake reload`). При
`false` вызовы `enter` остаются, но `PhaseClock` не создаётся (проверка `null` на каждой фазе) — для сравнения
«с замером / без».

**Формат отчёта.** Версия остаётся 1: только новые необязательные поля (правила `docs/profiler-format.md`). Числа —
средние **миллисекунды на тик** за интервал.

```json
{
  "tickPhases": {
    "serverPhases": ["packets", "scheduler", "functions", "levels", "network", "players", "other"],
    "worldPhases": ["blockTicks", "raids", "chunkTick", "tracker", "blockEvents", "entities",
                    "blockEntities", "entityManagement", "chunkTasks", "other"],
    "server": { "packets": 0.8, "scheduler": 0.4, "functions": 0.0, "levels": 31.2,
                "network": 1.9, "players": 0.3, "other": 0.6 },
    "worlds": [
      { "name": "world", "ticks": 600, "msptAvg": 18.3, "msptMax": 41.0,
        "phases": { "blockTicks": 2.1, "raids": 0.0, "chunkTick": 3.4, "tracker": 2.6,
                    "blockEvents": 0.3, "entities": 8.7, "blockEntities": 0.9,
                    "entityManagement": 0.1, "chunkTasks": 0.2, "other": 0.0 } }
    ]
  },
  "samples": [
    {
      "t": 1000, "tps": 20.0, "msptAvg": 33.1, "...": "...",
      "serverPhases": { "packets": 0.8, "scheduler": 0.4, "levels": 31.2, "network": 1.9, "players": 0.3, "other": 0.5 },
      "worlds": [
        { "name": "world", "msptAvg": 18.3, "msptMax": 22.0,
          "phases": { "blockTicks": 2.1, "chunkTick": 3.4, "tracker": 2.6, "entities": 8.7, "blockEntities": 0.9 } }
      ],
      "lanes": [["world", "world_the_end"], ["world_nether"]]
    }
  ]
}
```

| Поле | Тип | Смысл |
|---|---|---|
| `tickPhases` | object, *optional* | Итог за весь замер. Нет, если `profiler.tick-phases: false` |
| `tickPhases.serverPhases`, `worldPhases` | string[] | Порядок и имена фаз; читатель показывает незнакомые имена как есть |
| `tickPhases.server` | `{phase: ms}` | Общие фазы, мс на тик |
| `tickPhases.worlds[]` | object | `name`, `ticks` (тиков мира за замер), `msptAvg`, `msptMax`, `phases` (`{phase: ms}`) |
| `samples[].serverPhases` | `{phase: ms}`, *optional* | То же за интервал; фазы с 0 можно не писать |
| `samples[].worlds[]` | object, *optional* | `name`, `msptAvg`, `msptMax`, `phases` за интервал |
| `samples[].lanes` | string[][], *optional* | Только при параллельных мирах (шаг 4): миры по потокам в этом интервале, первый список — на главном потоке |

Сумма фаз мира равна его `msptAvg` (фаза `other` добирает остаток). Сумма `levels` при последовательных мирах равна
сумме `msptAvg` миров; при параллельных она меньше, и разница — выигрыш.

**Как проверить.** `/stress run 300` на AmazakeStress: этапы нагрузки должны появляться в «своих» фазах — `mobs` в
`entities`, `redstone` в `blockTicks`/`blockEvents`/`blockEntities`, `chunks` в `chunkTasks` и `chunkTick`,
`packets` в `tracker` и `network`. Сумма фаз сервера должна сходиться с `msptAvg` выборки с точностью ±5 %
(расхождение — значит, пропущен кусок тика). Сравнить MSPT с `profiler.tick-phases` вкл/выкл: разница в пределах шума.

**Объём.** Патчи: ~15 строк в `ServerLevel`, ~12 в `MinecraftServer`, ~4 в `ServerChunkCache`. Свой код: `PhaseClock`
~120 строк + тесты, профайлер ~150 строк (`Report`, `ProfilerSession`), просмотрщик `web/static` ~200 строк (стопка
фаз по мирам). 1–2 дня.

---

## 2. Асинхронный трекер сущностей

**Цель.** Трекер решает, какой игрок видит какую сущность, и рассылает изменения (позиция, поворот, метаданные,
атрибуты, пассажиры). Его цена ≈ «сущности × игроки рядом»; на ферме с сотнями мобов и десятком игроков это 3–10 мс
тика. Расчёт «кто кого видит» и сборку пакетов можно делать на нескольких ядрах, а применять — на потоке мира.

**Как устроено в Paper (Moonrise).** `ChunkMap#tick` вызывает `newTrackerTick()`: обход
`ServerEntityLookup.trackerEntities`, для каждой сущности `TrackedEntity#moonrise$tick(NearbyPlayers.TrackedChunk)`
(игроки рядом берутся из `NearbyPlayers`, а не перебором всех) и `serverEntity.sendChanges()`:

```java
// paper-server/patches/features/0001-Moonrise-optimisation-patches.patch, ChunkMap
+    private void newTrackerTick() {
+        final ...ServerEntityLookup entityLookup = (...)((...ChunkSystemServerLevel)this.level).moonrise$getEntityLookup();;
+        final ca.spottedleaf.moonrise.common.list.ReferenceList<net.minecraft.world.entity.Entity> trackerEntities = entityLookup.trackerEntities;
+        for (int i = 0, len = trackerEntities.size(); i < len; ++i) {
+            final ChunkMap.TrackedEntity tracker = ((...EntityTrackerEntity)entity).moonrise$getTrackedEntity();
+            ((...EntityTrackerTrackedEntity)tracker).moonrise$tick(((...ChunkSystemEntity)entity).moonrise$getChunkData().nearbyPlayers);
+            if (((...EntityTrackerTrackedEntity)tracker).moonrise$hasPlayers()
+                || ((...ChunkSystemEntity)entity).moonrise$getChunkStatus().isOrAfter(FullChunkStatus.ENTITY_TICKING)) {
+                tracker.serverEntity.sendChanges();
```

`TrackedEntity#updatePlayer(ServerPlayer)` считает дистанцию (`getEffectiveRange`, `trackingRangeY` из Paper),
`entity.broadcastToPlayer`, `isChunkTracked(player, chunkX, chunkZ)`, `canSeeChunkMapUpdatePlayer` (скрытие через
API), затем меняет `seenBy`, вызывает `PlayerTrackEntityEvent` и `serverEntity.addPairing(player)` (пакет спавна).
В нём стоит `org.spigotmc.AsyncCatcher.catchOp("player tracker update")`.

**Как сделал Leaf** (`0291-Multithreaded-Tracker.patch`, `org/dreeam/leaf/async/tracker/*`, конфиг
`async.async-entity-tracker.enabled/threads`, по умолчанию выключен, «Experimental»):

- `ChunkMap#tick` при включённой фиче отдаёт работу `level.leaf$asyncTracker.tick(level)`;
- `AsyncTracker#tick`: сначала на главном `handlePlayer` (`player.updateDataBeforeSync()`, `PlayerVelocityEvent` для
  `hurtMarked`), потом копия `trackerEntities` режется на куски (`EntitySlice`, не меньше 16 сущностей) и уходит в
  пул `"Leaf Async Tracker Thread"` задачами `TrackerTask`;
- `TrackerTask#call` для каждой сущности вызывает новый `TrackedEntity#leaf$tick(TrackerCtx, TrackedChunk)` и
  `ServerEntity#leaf$sendChanges(ctx, tracker, false)`: пакеты не отправляются, а копятся в `TrackerCtx`
  (`Map<ServerPlayerConnection, List<Packet<?>>>`), а всё, что трогает чужое состояние или вызывает события,
  записывается как отложенное действие (`startSeen`, `stopSeen`, `forceResync`, `wantUpdateData`, `syncAttributes`,
  `updateItemFrame`, `citizensEntity`);
- результат забирается в `ServerLevel#tick` в конце (`leaf$asyncTracker.onEntitiesTickEnd()`, если уже готово) или
  после всех миров в `MinecraftServer#tickChildren` (`onTickEnd()`, ждёт); `TrackerCtx#handle` на главном потоке
  вызывает события, `addPairing/removePairing` и отправляет накопленные пакеты.

```java
// leaf-server/minecraft-patches/features/0291-Multithreaded-Tracker.patch, ChunkMap#tick
     protected void tick() {
+        if (org.dreeam.leaf.config.modules.async.MultithreadedTracker.enabled) {
+            final ServerLevel level = this.level;
+            level.leaf$asyncTracker.tick(level);
+            return;
+        }
// leaf-server/src/main/java/org/dreeam/leaf/async/tracker/TrackerTask.java
            if (tracker.getClass() != ChunkMap.TrackedEntity.class) {
                ctx.citizensEntity(tracker);   // подкласс трекера от плагина (Citizens): только на главном
                continue;
            }
            boolean flag = tracker.leaf$tick(ctx, chunkData == null ? null : chunkData.nearbyPlayers);
```

**Подводные камни, которые Leaf обходит (и почему у них их больше, чем будет у нас).** У Leaf расчёт трекера идёт
**одновременно** с тиком сущностей того же мира: трекер стартует в `ChunkMap#tick` (до `entities`), а забирается в
конце тика. Поэтому ему пришлось:

- сделать `seenBy` синхронизированным множеством с кэшем-массивом `seenBy()` (и править все места, где Paper и
  CraftBukkit перебирают `seenBy`: `CraftEntity`, `CraftPlayer` — `paper-patches/features/0043-Multithreaded-Tracker.patch`);
- синхронизировать `sentChunks` игрока в `FeatureHooks#getSentChunkKeys` и `NearbyPlayers`, `lerpSteps` у
  `NewMinecartBehavior`, `invertedVisibilityEntities` у `CraftPlayer`, переписать `EntityEquipment#isEmpty`;
- чинить «двойное» срабатывание целей странствующего торговца (`WanderingTrader`: трекер видит старое состояние
  невидимости).

Позиции, которые читает поток трекера, при этом могут быть «порваны» (x уже нового тика, z — старого).

**Предлагаемая схема Amazake: fork-join без перекрытия.**

1. Трекер переносится **после** тика сущностей мира (порядок Paper PR #13094, как в Leaf 0336): в `ServerLevel#tick`
   после `entityManagement`, а в `ServerChunkCache#tick` вызов `chunkMap.tick()` убирается. Это заодно лечит
   MC-297196. Отдельный переключатель `tracker.after-entities` (по умолчанию `true` вместе с async-трекером; можно
   включить и без него).
2. **Фаза A (параллельно).** Главный поток (поток мира в шаге 4) режет копию `trackerEntities` на куски, раздаёт их
   пулу `"Amazake Tracker #N"` и сам берёт один кусок; затем ждёт остальные. Пока идёт фаза A, мир никто не меняет:
   главный поток занят этой же работой, плагины в этот момент не выполняются (их синхронный код идёт только на
   главном). Поэтому `seenBy`, `sentChunks`, `NearbyPlayers` и прочее можно читать без замков — ровно то, что сделало
   у Leaf большую часть синхронизации ненужной.
3. **Фаза B (на потоке мира, по порядку кусков).** Применить отложенные действия: `PlayerTrackEntityEvent` /
   `PlayerUntrackEntityEvent`, `addPairing`/`removePairing`, `debugSynchronizers`, рассылка пакетов в том же порядке,
   в каком их собрал бы обычный трекер (по сущностям в порядке `trackerEntities`).

Выигрыш: время фазы `tracker` делится примерно на число потоков минус накладные расходы; перекрытия с тиком нет, зато
нет и гонок. Режим с перекрытием (считать трекер мира A, пока главный тикает мир B) — возможный следующий шаг, только
после шага 4 и только с проверкой «мир A никто не трогает» (см. ниже).

**Точки входа (Paper-имена).**

| Файл | Метод | Изменение |
|---|---|---|
| `net/minecraft/server/level/ChunkMap.java` | `tick()`, `newTrackerTick()` | Разделить на `amazake$computeTracker(slice, ctx)` и `amazake$applyTracker(ctx)` |
| то же, `ChunkMap.TrackedEntity` | `moonrise$tick(TrackedChunk)`, `updatePlayer(ServerPlayer)`, `removePlayer` | Вариант без событий и без `AsyncCatcher`, пишет в `ctx` |
| `net/minecraft/server/level/ServerEntity.java` | `sendChanges()`, `addPairing`, `removePairing`, `sendDirtyEntityData()` | `sendChanges(ctx)`: пакеты в буфер; `packDirty` метаданных — на своей сущности, это безопасно в фазе A |
| `net/minecraft/server/level/ServerChunkCache.java` | `tick(BooleanSupplier, boolean)` | Убрать `this.chunkMap.tick()` (при `tracker.after-entities`) |
| `net/minecraft/server/level/ServerLevel.java` | `tick` | Вызов трекера после `entityManagement` |
| `ca/spottedleaf/moonrise/patches/chunk_system/level/entity/server/ServerEntityLookup.java` | `trackerEntities` | Только читаем (копия массива) |
| `io/papermc/paper/FeatureHooks.java` | `getSentChunkKeys`, `getSentChunks` | Не меняются (в fork-join их не вызывают одновременно) |

**Что снимать в снимок.** Копия массива `trackerEntities` (как у Leaf: `System.arraycopy` из
`getRawDataUnchecked()`), остальное читается вживую, потому что мир в фазе A стоит.

**На главном потоке остаётся.** `player.updateDataBeforeSync()`, `PlayerVelocityEvent` (`hurtMarked`), все события,
`addPairing` (вызывает `Entity#startSeenByPlayer`, который переопределяют мобы и плагины), карты в рамках
(`MapItemSavedData` общая для всех рамок с этой картой), трекеры-подклассы от плагинов (`tracker.getClass() !=
ChunkMap.TrackedEntity.class` — как у Leaf), отправка пакетов.

**Риски потоков и защита.**

- Исключение в потоке трекера: поймать, записать в лог с сущностью и миром, досчитать этот кусок синхронно на главном.
- Порядок пакетов одному игроку должен остаться прежним (спавн раньше метаданных и движения): буфер на соединение,
  слив в порядке кусков. Пакеты собираются `ClientboundBundlePacket` там, где их так собирает ваниль.
- Код, который зовёт `updatePlayer` вне трекера (вход игрока, смена мира, `CraftPlayer#showEntity`), остаётся
  синхронным старым путём; фаза A не идёт в это время по построению.
- `ServerEntity` хранит «последнее отправленное» (`lastSentXa`, `teleportDelay`...) — поля одной сущности, сущность
  в одном куске: гонки нет.
- Плагин из своего асинхронного потока меняет сущность (`setCustomName` и т. п.) — это уже сейчас небезопасно в Paper;
  ничего не добавляем, но в логах это будет видно как «порванные» метаданные.

**Риски для плагинов.**

- Citizens и другие NPC на «настоящих» сущностях с собственным `TrackedEntity`: обрабатываются на главном (как у Leaf).
- `PlayerTrackEntityEvent`/`PlayerUntrackEntityEvent` вызываются в фазе B того же тика — как и раньше, на главном.
  Отмена события должна откатить `seenBy` (проверить: Leaf добавляет в `seenBy` в потоке, событие — потом).
- ProtocolLib/packetevents видят те же пакеты в том же порядке, но отправленные чуть позже внутри тика.
- `Entity#getTrackedBy()` (`CraftEntity`, перебирает `seenBy`) из асинхронного потока плагина — как и сейчас без
  гарантий; с главного — всегда согласовано.

**`amazake.yml`.**

| Настройка | По умолчанию | Что |
|---|---|---|
| `tracker.async.enabled` | `false` (после проверки — `true`) | Считать трекер на нескольких ядрах |
| `tracker.async.threads` | `auto` | `auto`: ядра роли `chunks`, но не больше 4 (как `threads: 0` у Leaf: `min(cores, 4)`) |
| `tracker.async.min-entities` | `256` | Меньше сущностей с трекером — считать синхронно (разрезание дороже выигрыша) |
| `tracker.after-entities` | `true` | Трекер после тика сущностей (MC-297196) |

Изменение `enabled` и `threads` — после перезапуска (пул создаётся один раз).

**Ядра.** Пул `"Amazake Tracker #N"`: новая группа `tracker` в `ThreadClassifier` (правило `PREFIX "Amazake Tracker"`
до общего `"Amazake "`), роль `chunks`: трекер работает, пока главный поток ждёт, а чанки в это время уже отработали
свою часть тика. Главный поток в фазе A считает свой кусок на ядре `tick`.

**Как мерить.** Трекер почти ничего не стоит без игроков, поэтому AmazakeStress запускать с 1–2 игроками в
`stress_world` (модуль `packets`, `teleport-players: true`) или ботами. Этапы `mobs` (800 мобов) и `packets`
(300 стоек, 150 обновлений метаданных и 300 движений за тик). Сравнивать фазу `tracker` из шага 1 и общий MSPT при
`tracker.async.enabled` вкл/выкл, плюс CPU группы `tracker` в отчёте. Ожидание: фаза `tracker` в 2–3 раза меньше на
4 потоках. Проверка корректности: на клиенте нет «невидимых» и «застывших» мобов, отбрасывание без рывков.

**Объём.** Leaf: 1187 строк патча к Minecraft, ~60 строк к CraftBukkit, ~560 строк своего кода. У нас в fork-join
режиме синхронизации меньше: ~600–800 строк патчей, ~400 строк своего кода (`ink.hoka.amazake.tracker`). 4–6 дней
с проверкой.

---

## 3. Асинхронный поиск пути

**Цель.** A* в `PathFinder#findPath` — самая дорогая часть ИИ мобов (зомби у фермы, жители, пиглины). Считать путь в
пуле потоков; моб получает путь через тик-другой, а пока стоит или идёт по старому.

**Точки входа (по Leaf `0105-Petal-Async-Pathfinding.patch`, Kaiiju/Petal).**

| Файл | Метод | Изменение у Leaf |
|---|---|---|
| `net/minecraft/world/entity/ai/navigation/PathNavigation.java` | `createPath(Set<BlockPos>, Entity, int, boolean, int, float)` (перегрузка Paper с `EntityPathfindEvent`) | Возвращает `AsyncPath`-заглушку; `targetPos` ставится сразу, остальное — колбэком `AsyncPathProcessor.awaitProcessing` |
| то же | `tick()`, `followThePath()`, `moveTo(Path, double)`, `shouldRecomputePath(BlockPos)` | Пропускать, пока `!path.isProcessed()` |
| `.../navigation/GroundPathNavigation`, `FlyingPathNavigation`, `WaterBoundPathNavigation`, `AmphibiousPathNavigation` | `createPathFinder(int)` | `new PathFinder(nodeEvaluator, max, nodeEvaluatorGenerator)`: генератор свежего `NodeEvaluator` по набору флагов |
| `net/minecraft/world/level/pathfinder/PathFinder.java` | `findPath(PathNavigationRegion, Mob, Set<BlockPos>, float, int, float)` | `prepare` на главном, сам A* — в задаче; свои `BinaryHeap` и `neighbors` на поток (`NodeEvaluatorCache.HEAP_LOCAL`) |
| `.../pathfinder/Path.java` | класс | Не `final`; `isProcessed()`; `sameAs` учитывает необработанные пути |
| `.../pathfinder/NodeEvaluator.java` | | `invalidateCache()` (отключить общий кэш типов клеток), `clearNodes()` |
| `.../pathfinder/PathfindingContext.java` | поле `cache` | Делается изменяемым, чтобы обнулить |
| `.../ai/behavior/MoveToTargetSink`, `AcquirePoi`, `SetClosestHomeAsWalkTarget`, `.../sensing/NearestBedSensor`, `.../goal/DoorInteractGoal` | | Поведение мозга, которое сразу читает путь (`canReach`, узлы): переносится в колбэк |
| мобы `Bee`, `Frog`, `Strider`, `Rabbit`, `Warden`, `Drowned` | свои навигации | То же, что у базовых навигаций |

```java
// leaf-server/minecraft-patches/features/0105-Petal-Async-Pathfinding.patch, PathFinder#findPath
+        NodeEvaluator nodeEvaluator = this.nodeEvaluatorGenerator == null
+            ? this.nodeEvaluator
+            : org.dreeam.leaf.async.path.NodeEvaluatorCache.takeNodeEvaluator(this.nodeEvaluatorGenerator, this.nodeEvaluator);
+        nodeEvaluator.prepare(region, mob);
...
+            return new org.dreeam.leaf.async.path.AsyncPath(this, Lists.newArrayList(), targets, finder -> {
+                try {
+                    nodeEvaluator.invalidateCache();
+                    return finder.findPath(nodeEvaluator, ...HEAP_LOCAL.get(), ...NEIGHBORS_LOCAL.get(), ...);
+                } finally {
+                    nodeEvaluator.done();
+                    nodeEvaluator.clearNodes();
+                    org.dreeam.leaf.async.path.NodeEvaluatorCache.returnNodeEvaluator(nodeEvaluator);
// PathNavigation#tick
+        if (this.path != null && !this.path.isProcessed()) return; // Kaiiju - petal - async path processing - skip pathfinding if we're still processing
```

`AsyncPath` (`org/dreeam/leaf/async/path/AsyncPath.java`) ставит задачу в пул при создании. Любой метод `Path`,
вызванный до готовности (`getNodeCount`, `getTarget`, `advance`...), досчитывает путь **синхронно на вызывающем
потоке** под `synchronized (finder)` — это и есть запасной путь. Пул: `ThreadPoolExecutor` с очередью
`queue-size` (по умолчанию `max-threads × 256`) и политикой при переполнении `FLUSH_ALL` (выполнить всю очередь на
главном) или `CALLER_RUNS`; задачи с таймаутом 60 с. Конфиг Leaf: `async.async-pathfinding.enabled` (выкл),
`max-threads` (по умолчанию `cores / 4`), `keepalive`, `queue-size`, `reject-policy`.

**Что снимать в снимок.** `PathNavigationRegion` — это массив ссылок на **живые** чанки, а не копия блоков. Поток
пути читает секции чанков, пока главный поток ставит блоки: путь может учесть блок «на тик старше» — это допустимо,
путь и так пересчитывается. `PalettedContainer` при росте палитры заменяет объект данных целиком, читатель видит
старый или новый — без исключения (проверить на стресс-тесте с `physics`). Копировать секции — дороже самого поиска.
Состояние моба (позиция, размеры, `getPathfindingMalus`, флаги дверей и плавания) читается в `prepare` на главном;
дальше A* читает `mob` только через `NodeEvaluator` — эти чтения стоит перенести в `prepare` (свой патч поверх идеи
Leaf), чтобы поток пути не трогал моба вовсе.

**Параллельно / на главном.** Параллельно — только A* (`findPath` с узлами). На главном — `EntityPathfindEvent`
(Paper вызывает его в `createPath` до поиска), `prepare`, применение пути, `moveTo`, обход пути, поведение мозга.

**Риски потоков и защита.**

- `ServerLevel.pathTypesByPosCache` (`PathTypeCache`) общий на мир и сбрасывается в `sendBlockUpdated` на главном.
  Leaf просто отключает его в задаче (`invalidateCache()`); у нас — так же на первом шаге, позже — кэш на поток.
- `NodeEvaluator` не потокобезопасен: пул экземпляров по набору флагов (`NodeEvaluatorFeatures`), как у Leaf.
- **Устаревание пути.** Ваниль пересчитывает пути при изменении блока: `ServerLevel#sendBlockUpdated` обходит
  `navigatingMobs` и спрашивает `navigation.shouldRecomputePath(pos)`. Leaf для необработанного пути отвечает `false`
  — изменение блока во время расчёта теряется, моб идёт в новую стену до таймаута «застрял». Наша защита: если путь
  ещё считается и `pos` внутри области `PathNavigationRegion`, ставить `hasDelayedRecomputation = true`; после
  готовности путь сразу пересчитается.
- Результат для «чужого» состояния: колбэк проверяет `processedPath == this.path` (Leaf), а мы ещё `!mob.isRemoved()`
  и тот же `mob.level()` (моб сменил мир, пока считали).
- Моб удалён — отменять задачу (сейчас она досчитывается впустую).
- Переполнение очереди: `run-sync` (как `CALLER_RUNS`) по умолчанию; `FLUSH_ALL` на главном может дать скачок тика.

**Риски для плагинов.**

- **Paper API `Mob#getPathfinder()`** (`com/destroystokyo/paper/entity/PaperPathfinder.java`) читает поле
  `path.nodes` напрямую (`getPoints()`, `getNextPoint()`) и `getFinalPoint()`; у `AsyncPath` до готовности список
  пустой. Leaf `PaperPathfinder` не правит (в `leaf-server/paper-patches` его нет). Защита: в `PaperPathfinder#findPath`
  и `moveTo(Location)` досчитывать путь синхронно (вызов любого метода `Path` запускает `process()`), то есть путь
  из API — всегда готовый. **Это касается самого AmazakeStress**: модуль `mobs` двигает мобов через `Pathfinder#moveTo`.
- Плагины ИИ (MythicMobs, Citizens) на NMS-навигации видят пустой путь до готовности; на них распространяется тот же
  синхронный запасной путь через методы `Path`, но не прямое чтение `nodes`.
- `EntityPathfindEvent` остаётся синхронным и до поиска — поведение не меняется.

**`amazake.yml`.**

| Настройка | По умолчанию | Что |
|---|---|---|
| `pathfinding.async.enabled` | `false` | Искать путь в пуле потоков |
| `pathfinding.async.threads` | `auto` | `auto`: четверть ядер, не меньше 1 |
| `pathfinding.async.queue-size` | `512` | Длина очереди |
| `pathfinding.async.on-full` | `run-sync` | `run-sync` или `flush-all` |
| `pathfinding.async.sync-entity-types` | `[]` | Типы сущностей, которым всегда считать синхронно (`WARDEN`, `VILLAGER`...) |

Включение — после перезапуска (меняются конструкторы навигаций).

**Ядра.** Пул `"Amazake Pathfinder #N"`: группа `pathfinding`, роль `background` (не мешает главному потоку, а
очередь терпит задержку).

**Как мерить.** AmazakeStress этап `mobs`: 800 мобов, половине новые цели каждые 40 тиков
(`retarget-interval-ticks`, `retarget-share`), жители с верстаками и кроватями (поиск POI и путей). Фаза `entities`
из шага 1 до/после, CPU группы `pathfinding`, MSPT p95. В spark: доля `PathFinder#findPath` в главном потоке должна
упасть почти до нуля. Корректность: мобы доходят до целей (счётчик в `/stress status` для `retarget`), нет мобов,
застрявших у стен после этапа `physics`.

**Объём.** Leaf: 1116 строк патча + ~600 строк своего кода. У нас то же плюс `PaperPathfinder` и отслеживание
изменений блоков: ~1200 строк патчей, ~600 своего. 4–5 дней.

---

## 4. Адаптивные параллельные миры

**Цель.** Тикать разные миры (обычный мир, Незер, Энд, миры Multiverse) одновременно на разных ядрах, но только когда
это выгодно, и сгруппировать миры по измеренной цене так, чтобы самая долгая группа была как можно короче. Сервер с
одним нагруженным миром не получает ничего, кроме накладных расходов, поэтому режим включается сам.

**Как сделал Leaf** (SparklyPaper, `0200-SparklyPaper-Parallel-world-ticking.patch` + `paper-patches/features/0049`,
`0050`; конфиг `async.parallel-world-ticking.enabled` (выкл, «Experimental»), `threads: 8`,
`async-unsafe-read-handling: STRICT|BUFFERED|DISABLED`, `disable-hard-throw`, `log-container-creation-stacktraces`):

```java
// leaf 0200, MinecraftServer#tickChildren
+                if (org.dreeam.leaf.config.modules.async.SparklyPaperParallelWorldTicking.enabled) {
+                    serverLevelTickingSemaphore.acquire();
+                    tasks.add(
+                        serverLevel.tickExecutor.submit(() -> {
+                            ...ServerLevelTickThread currentThread = (...ServerLevelTickThread) Thread.currentThread();
+                            currentThread.currentTickingServerLevel = serverLevel;
+                            try {
+                                tickLevel(serverLevel, hasTimeLeft);
+                            } finally {
+                                serverLevelTickingSemaphore.release();
+                            }
+                        }, serverLevel)
+                    );
...
+            while (!tasks.isEmpty()) {
+                tasks.poll().get();
+            }
// leaf paper-patches 0049, TickThread
+    public static boolean isTickThreadFor(final Level world) {
+        if (Thread.currentThread() instanceof ServerLevelTickThread serverLevelTickThread) {
+            return serverLevelTickThread.currentTickingServerLevel == world;
+        }
         return isTickThread();
```

Каждый мир получает свой однопоточный `tickExecutor` (поток `ServerLevelTickThread extends TickThread`), главный
поток ставит все миры и ждёт все `Future` до `"connection"`. Что ещё пришлось сделать Leaf (готовый список ловушек):

- **mid-tick задачи чанков** (`moonrise$executeMidTickTasks` обходит *все* миры) — на потоке мира только свой мир
  (`pwt$tickLevelMidTickTasks`), а вызовы между сущностями и тиками блоков отключены;
- **статические поля → ThreadLocal или поле мира**: `SaplingBlock.treeType` (`get/setTreeTypeRT`, ещё в
  `FungusBlock`, `MushroomBlock`, `TreeGrower`), `CraftEventFactory.sourceBlockOverride`, `RedStoneWireBlock.shouldSignal`,
  `RedstoneWireTurbo` (Eigencraft) — один на мир (`level.turbo`);
- **порталы и межмировые телепорты** откладываются: `Entity#handlePortal` → `pwt$handleTeleport` ставит задачу в
  `taskScheduler` сущности (выполнится в начале следующего тика на главном), `PortalProcessor` помнит «телепорт уже
  запланирован»; так же `ThrownEnderpearl` между измерениями, освобождение POI жителя в другом мире
  (`GoToPotentialJobSite`, `Villager` через `scheduleChunkTask`);
- **игрок**: `PlayerList#respawn` с потока мира выполняется через `server.submit(...).join()`; после смены мира
  запрещено открывать контейнер, пока игрок не протикал в новом мире (`hasTickedAtLeastOnceInNewWorld`);
- **проверки** `TickThread.ensureTickThread(level, ...)` в `ServerLevel#addEntity`, `addPlayer`, `Level#setBlock`,
  `getEntities`, `ServerWaypointManager`, `CraftWorld` (загрузка и выгрузка чанков, взрывы, деревья, биомы), и огромный
  патч `CraftBlock` с политикой чтения чужого мира (`UnsafeReadPolicy`: `STRICT` — бросать, `BUFFERED` — поставить
  чтение в очередь мира `asyncReadRequestQueue` и ждать, `DISABLED` — без проверки);
- **планировщик**: синхронная одноразовая задача с задержкой 0, поставленная с потока мира, выполняется **сразу на
  этом потоке** (`CraftScheduler#handle`, «MythicMobs callSyncAndJoin»), иначе — взаимная блокировка;
- **API**: `Server#isParallelWorldTickingEnabled`, `World#getTickTimes`, `World#getAverageTickTime` (Fish, `0050`).

**Схема Amazake.**

1. **Замер** — шаг 1 (`PhaseClock` даёт время мира за тик).
2. **Решение** — `WorldGroupPlanner` (без Bukkit; прототип с тестом:
   `/tmp/claude-0/-home-user/d8dff9cc-7075-58a3-a329-0259f695514b/scratchpad/reserve/src/ink/hoka/amazake/tick/WorldGroupPlanner.java`).
   Раз в тик на главном потоке получает время каждого мира, сглаживает (EWMA, `alpha 0.05`, ≈1 с) и возвращает
   «полосы» на следующий тик:
   - параллельный режим **включается**, если сумма миров ≥ `on-ms` (30 мс) подряд `on-ticks` (100) тиков и хотя бы
     два мира по ≥ `min-world-ms` (2 мс); **выключается**, если сумма < `off-ms` (20 мс) или тяжёлый мир остался один,
     подряд `off-ticks` (200) тиков. Между 20 и 30 мс режим не меняется — гистерезис;
   - миры раскладываются по полосам жадно (LPT: самый тяжёлый мир — на самую лёгкую полосу), полос не больше
     `threads`; перераскладка не чаще раза в `regroup-ticks` (200) и только если новая раскладка короче текущей
     (пересчитанной по свежим ценам) на `regroup-gain` (15 %). Мир загрузился или выгрузился — перераскладка сразу;
   - режим и полосы меняются только между тиками.
3. **Исполнение.** Полоса 0 — на самом главном потоке (`Server thread`), и в ней всегда основной мир (`world`):
   события основного мира, где играет большинство, остаются на настоящем главном потоке. Остальные полосы — на потоках
   `"Amazake World #N"` (наследники `TickThread`, поле `currentLevel`). Главный поток после своей полосы ждёт остальные
   (не простой `Future.get()`: см. «блокировки» ниже) и только потом идёт в общую часть.
4. **Общая часть (только главный поток, после соединения):** очередь межмировых операций этого тика, `tickConnection`,
   `playerList.tick()`, автосохранение, конец тика. До миров, как и сейчас: пакеты, планировщик Bukkit, планировщики
   сущностей, функции.

**Точки входа.**

| Файл | Метод | Изменение |
|---|---|---|
| `net/minecraft/server/MinecraftServer.java` | `tickChildren` | Цикл миров → полосы `WorldGroupPlanner`; ожидание; затем `amazake$runCrossWorldQueue()`; дальше как было |
| то же | `tickMidTickTasks`, `moonrise$executeMidTickTasks` | На потоке полосы — только миры этой полосы |
| то же | `removeLevel`, `addLevel` (CraftBukkit) | Создание/выгрузка мира — только в общей части (Paper уже бросает при `isIteratingOverLevels`) |
| `ca/spottedleaf/moonrise/common/util/TickThread.java` | все `isTickThreadFor(Level, ...)`, `isTickThreadFor(Entity)` | Сейчас просто `isTickThread()`; станет «текущий поток тикает этот мир». Для главного потока: «тикает» мир полосы 0 во время параллельной части и все миры вне её |
| `net/minecraft/world/entity/Entity.java` | `handlePortal`, `teleport(TeleportTransition)` | Межмировой переход с потока полосы → в очередь общей части этого же тика (а не следующего, как у Leaf) |
| `net/minecraft/server/players/PlayerList.java` | `respawn`, `placeNewPlayer` | Только в общей части; вызов с потока полосы — в очередь |
| `net/minecraft/world/entity/projectile/throwableitemprojectile/ThrownEnderpearl.java`, `world/entity/ai/behavior/GoToPotentialJobSite.java`, `world/entity/npc/villager/Villager.java` | межмировые случаи Leaf | В очередь общей части |
| `net/minecraft/world/level/block/SaplingBlock.java`, `FungusBlock`, `MushroomBlock`, `grower/TreeGrower`, `RedStoneWireBlock`, `io/papermc/paper/redstone/RedstoneWireTurbo.java`, `org/bukkit/craftbukkit/event/CraftEventFactory.java` | статические поля | ThreadLocal или поле мира (список Leaf) |
| `net/minecraft/world/level/block/entity/HopperBlockEntity.java` | `skipHopperEvents` (static, выставляется в цикле миров) | В поле мира: у миров разные `hopper.disable-move-event` (у Leaf осталось статическим — гонка) |
| `io/papermc/paper/plugin/manager/PaperEventManager.java` | `callEvent` | Замок событий (см. «плагины») |
| `org/bukkit/craftbukkit/scheduler/CraftScheduler.java`, `CraftFuture` | `callSyncMethod(...).get()` с потока полосы | Не выполнять «сразу» как Leaf, а ставить в очередь общей части; ожидание с потока полосы — ошибка с понятным текстом (см. ниже) |
| `org/spigotmc/WatchdogThread.java` | дамп | Печатать стеки потоков полос, а не только главного |

**Глобальное состояние, которое общее для всех миров** (проверить каждое, Leaf закрыл не всё):

- `ServerScoreboard` (очки за убийства, команды) — один на сервер → замок или очередь;
- карты: `MapItemSavedData` хранятся в `DimensionDataStorage` **основного мира**, а обновляются из любого мира;
  `MapIndex` (выдача id) — Leaf правил `MapIndex`; нужен замок;
- `PlayerAdvancements`, статистика — у игрока, игрок в одном мире: безопасно, кроме смены мира (она в общей части);
- `MinecraftServer.tickCount` и прочее только для чтения — безопасно;
- `LegacyRandomSource` при одновременном доступе из двух потоков бросает «Accessing LegacyRandomSource from multiple
  threads» — `level.random` у каждого мира свой, а общие `RandomSource` (если найдутся) — по ThreadLocal.

**Bukkit.isPrimaryThread, AsyncCatcher, планировщик.**

- `Bukkit.isPrimaryThread()` на потоках полос — `true` (они `TickThread`), иначе синхронные события падают в
  `PaperEventManager#callEvent`. Значит, проверка `isPrimaryThread()` у плагинов больше не значит «нет гонок».
- `Bukkit.isOwnedByCurrentRegion(world/location/entity)` (Paper API для Folia) начинает отвечать честно через
  `TickThread.isTickThreadFor` — плагины с поддержкой Folia получают правильный ответ.
- `AsyncCatcher.catchOp` пропускает потоки полос; поэтому к опасным записям (`addEntity`, `setBlock`, загрузка чанков)
  добавляем проверку **мира**: `TickThread.ensureTickThread(level, ...)`.
- Задачи `runTask`/`runTaskLater` выполняются, как и раньше, в `mainThreadHeartbeat()` до миров — на главном потоке,
  не параллельно с мирами. Ставить задачи можно из любого потока (очередь `CraftScheduler` уже потокобезопасна).
- **Блокировки.** Поток полосы, ждущий главный (`callSyncMethod(...).get()`, `server.submit(...).join()`), повиснет:
  главный ждёт полосу. Решение Leaf (выполнить задачу сразу на потоке полосы) молча меняет поток выполнения и может
  нарушить порядок. Наше: главный поток ждёт полосы не `Future.get()`, а циклом, который выполняет задачи из
  отдельной очереди «срочно на главный» (только простые: телепорт, респавн, задачи плагинов с задержкой 0), — так
  `join()` с полосы не виснет. Если задача требует мира, который сейчас тикает другая полоса, — выполнить после
  ожидания всех полос и записать предупреждение один раз на плагин.

**Риски для плагинов (главный риск всего плана).**

- Обработчики событий из разных миров выполняются **одновременно**. Плагин с `HashMap` в слушателе ломается тихо.
  Защита по умолчанию: `parallel-worlds.plugin-events: serialized` — в `PaperEventManager#callEvent` на потоке полосы
  брать один общий замок на время вызова слушателей. Параллельными остаются сами тики (сущности, блоки, чанки), а код
  плагинов идёт по одному. Цена — ожидание замка в мирах с частыми событиями (`EntityMoveEvent`, физика блоков);
  `parallel` снимает замок для серверов с проверенным набором плагинов.
- Плагин с потока полосы трогает другой мир: `parallel-worlds.cross-world-access: warn` (по умолчанию: разрешить
  чтение, записать в лог плагин и стек один раз), `throw` (как `STRICT` у Leaf), `allow`. Запись в чужой мир
  (`setBlock`, спавн, телепорт сущности чужого мира) — всегда в очередь общей части.
- Телепорт в другой мир через API (`Entity#teleport(Location в другом мире)`) из события на потоке полосы выполняется
  в конце тика, а метод вернёт `true` сразу: плагин, который сразу читает `getLocation()`, увидит старый мир.
  `teleportAsync` такой проблемы не имеет. Описать в README.
- Плагины, которые создают/выгружают миры (Multiverse) — только в задачах планировщика (общая часть), как Paper уже
  требует (`isIteratingOverLevels`).

**`amazake.yml`** (всё — после перезапуска, кроме порогов):

| Настройка | По умолчанию | Что |
|---|---|---|
| `parallel-worlds.enabled` | `false` | Разрешить параллельные миры |
| `parallel-worlds.mode` | `adaptive` | `adaptive` (по замерам) или `always` |
| `parallel-worlds.threads` | `auto` | Полос всего (вместе с главным потоком); `auto`: ядра роли `worlds` + 1, не больше числа миров |
| `parallel-worlds.on-ms` / `off-ms` | `30` / `20` | Пороги суммы миров, мс на тик |
| `parallel-worlds.on-ticks` / `off-ticks` | `100` / `200` | Сколько тиков подряд держится условие |
| `parallel-worlds.min-world-ms` | `2` | Мир легче этого не считается «тяжёлым» |
| `parallel-worlds.regroup-ticks` / `regroup-gain` | `200` / `0.15` | Как часто и при каком выигрыше перекладывать миры |
| `parallel-worlds.plugin-events` | `serialized` | `serialized` или `parallel` |
| `parallel-worlds.cross-world-access` | `warn` | `warn`, `throw`, `allow` |

**Ядра.** Новая роль `worlds` в `cpu.layout` (Role, LayoutPlanner, README): при `parallel-worlds.enabled` получает
`threads − 1` физических ядер из `background` (после `tick`, до `chunks`); потоки `"Amazake World #N"` — группа
`world-tick` в `ThreadClassifier` → роль `worlds`. Без роли они попали бы в `background` (правило `"Amazake "`), а с
ролью `tick` (одно ядро) — встали бы в очередь за главным потоком. Это изменение раскладки без Bukkit, его можно
сделать и проверить тестами заранее. Резервное ядро (`cpu.reserve`, переезд главного потока — в рабочей копии сейчас
идёт эта работа) роли `worlds` не отдаётся: если главный поток переехал, полосы остаются на своих ядрах.

**Как мерить.** AmazakeStress сейчас грузит только `stress_world`, а параллельным мирам нужна нагрузка в нескольких
мирах. Добавить в плагин `world.copies: N` (N одинаковых миров `stress_world_0..N-1` с теми же постройками) или
развести модули по мирам (`mobs` в одном, `redstone` в другом). Мерить: `samples[].lanes` и время миров из шага 1,
`levels` против суммы миров (ожидание: `levels ≈ max(полоса)`, а не сумма), MSPT p95, лаги. Отдельно — прогон с
одним нагруженным миром: режим не должен включаться (`lanes` нет), MSPT не хуже, чем без фичи. Прогон с
переходами через порталы (свой модуль) — нет потерянных сущностей и дублей.

**Объём.** Leaf: 1573 строки патча к Minecraft, ~1800 к CraftBukkit (из них ~1260 — `CraftBlock`), ~400 своего кода.
У нас плюс адаптивная часть, замок событий и очередь общей части: ~3500–4000 строк патчей, ~800 своего кода.
2–3 недели и долгий тест на сервере с плагинами.

---

## 5. Чего не делаем и почему

**Сущности и редстоун одного мира на разных ядрах одновременно — нельзя.** Мир — одно общее изменяемое состояние без
деления на владельцев:

- **Редстоун и обновления блоков.** Провод, поршень, наблюдатель меняют соседей, те — своих соседей, цепочка может
  уйти сколь угодно далеко. Порядок обновлений наблюдаем: квазисвязность, 0-тиковые механизмы, тайминги компараторов —
  технические игроки на них строят. Alternate Current и Eigencraft (Paper) рассчитаны на один поток. Два потока,
  обновляющие соседние блоки, дают недетерминированный порядок и гонки в секциях чанка.
- **Сущности.** Столкновения и толкание (пары сущностей), ИИ читает других сущностей, предметы сливаются, воронки
  забирают предметы, сущности переходят между секциями (`EntitySection`), общий `level.random` (`LegacyRandomSource`
  в ванили бросает исключение при одновременном доступе), POI, скорборд, порядок событий для плагинов.
- **Случайные тики и тики блок-сущностей** — те же записи блоков и обновления соседей.

Единственный известный рабочий способ — регионы Folia: мир делится на области с владельцем-потоком, сущность или
блок в чужой области трогать нельзя, переход — задачей. Это ломает Bukkit API для обычных плагинов (Folia требует
отдельной поддержки плагинов), а цель Amazake — работать с обычными плагинами Paper. Поэтому внутри одного мира
параллельно только то, что **считает и не пишет**, с применением результата на потоке мира: трекер (шаг 2), поиск
пути (шаг 3), подсчёт мобов для спавна (ниже), свет и чанки (уже в Moonrise). Между мирами — шаг 4.

**Ещё не делаем:**

- Перекрытие трекера мира с тиком того же мира (как у Leaf): позиции читаются во время движения, нужны замки на
  каждом наборе. Fork-join даёт почти тот же выигрыш без гонок.
- Асинхронное выполнение `runTask` плагинов, асинхронные события вместо синхронных — меняет контракт API.
- Отказ от `AsyncCatcher` / «тихий» режим без проверок — ошибки плагинов станут повреждением мира вместо исключения.

**Кандидат поменьше: асинхронный подсчёт мобов для спавна** (Pufferfish, Leaf `0085-Pufferfish-Optimize-mob-spawning.patch`,
`async.async-mob-spawning.enabled`, у Leaf по умолчанию **включено**). `NaturalSpawner.createState` (перебор всех
сущностей для лимитов) считается в потоке `mobSpawnExecutor` по `entityTickList.entities.iterator(ITERATOR_FLAG_SEE_ADDITIONS)`,
`ServerChunkCache#tickChunks` берёт готовый `lastSpawnState` прошлого тика; спавн ждёт, пока счёт готов
(`_pufferfish_spawnCountsReady`). Лимиты мобов отстают на тик. ~150 строк, 1 день — можно сделать между шагами 2 и 3
(`spawning.async-mob-counts`, по умолчанию `false`, фаза `chunkTick` в замере).

---

## Атрибуция и лицензии

Amazake — GPLv3. Если переносим **код** (а не только идею), сохраняем в сообщении коммита патча строки
`Original license` / `Original project` / `Co-authored-by` из исходного патча и упоминаем проект в README. Идеи
без кода лицензии не требуют, но авторов всё равно указываем.

| Что | Откуда (по заголовкам патчей Leaf) | Лицензия | Авторы |
|---|---|---|---|
| Многопоточный трекер (`0291`, `org/dreeam/leaf/async/tracker`) | Airplane → Pufferfish → Petal → Leaf | GPL-3.0-only | peaches94, Paul Sauve, Kevin Raneri, HaHaWTH, hayanesuru |
| Асинхронный поиск пути (`0105`, `org/dreeam/leaf/async/path`) | Petal, Kaiiju | GPL-3.0-only | peaches94, HaHaWTH, Taiyou06, Altiami |
| Параллельные миры (`0200`, paper `0049`) и время миров (`0201`) | SparklyPaper | в заголовке не указана — уточнить в репозитории SparklyPaper | MrPowerGamerBR, Altiami, Taiyou06, MrlingXD |
| API параллельных миров (paper `0050`) | Fish | уточнить | — |
| Асинхронный подсчёт мобов (`0085`) | Pufferfish | GPL-3.0-only | Kevin Raneri, booky10 |
| Время последнего тика (`0074`) | Gale / YAPFA | MIT | Martijn Muijsers, tr7zw |
| Трекер после сущностей (`0336`) | Paper PR #13094 | GPL-3.0-only | Alexandra-Myers |
| Свой код Leaf (`org/dreeam/leaf/**`) | Leaf | по `LICENSE.md` Leaf: патчи MIT, если в заголовке не указано иное; сборки GPL-3.0 | Leaf |

---

## Сборка: какие хосты нужны и как собрать без CI

### Хосты

Песочнице Claude и любой машине без полного доступа в интернет для `./gradlew applyAllPatches` и
`createMojmapPaperclipJar` нужны:

| Хост | Зачем | Откуда известно |
|---|---|---|
| `services.gradle.org` (+ `downloads.gradle.org`, куда он перенаправляет) | Gradle 9.4.1 для `gradlew` | `gradle/wrapper/gradle-wrapper.properties` |
| `plugins.gradle.org`, `plugins-artifacts.gradle.org` | `gradlePluginPortal()`: плагин `foojay-resolver-convention` | `settings.gradle.kts` |
| `repo.papermc.io` | paperweight (`io.papermc.paperweight.patcher` 2.0.0-beta.19), `mache` 1.21.11, `paperclip`, зависимости Paper | `settings.gradle.kts`, `build.gradle.kts`, `paper-server/build.gradle.kts` |
| `repo.maven.apache.org` (`repo1.maven.org`) | `mavenCentral()` | `build.gradle.kts` |
| `github.com` (+ `codeload.github.com`, `objects.githubusercontent.com`) | исходники Paper на `paperCommit` (paperweight-patcher берёт PaperMC/Paper с GitHub) | `upstreams.paper` в `build.gradle.kts` |
| `piston-meta.mojang.com` | манифест версий и описание 1.21.11 | paperweight (загрузка Minecraft) |
| `piston-data.mojang.com` | `server.jar` и маппинги Mojang | то же |
| `libraries.minecraft.net` | библиотеки Minecraft (brigadier, DataFixerUpper, authlib) | то же |
| `hub.spigotmc.org` | git-репозиторий BuildData (`stash/scm/spigot/builddata.git`): в Paper 1.21.11 включено `spigot { enabled = true; buildDataRef = "17f77cee..." }` | `paper-server/build.gradle.kts` |
| `api.foojay.io` (+ хост загрузки JDK, например `api.adoptium.net` и `github.com`) | только если нет JDK 21: тулчейн докачивается | `foojay-resolver-convention` в `settings.gradle.kts` |

Не проверено (нет jar paperweight в песочнице): нужны ли ещё `maven.fabricmc.net`, `maven.neoforged.net` или
`maven.parchmentmc.org` для инструментов декомпиляции. Если сборка упадёт на загрузке, хост будет в тексте ошибки.
`gradle-build-cache.papermc.io` есть только в настройках самого Paper, нам не нужен.

### Собрать самому, если GitHub Actions не работает

1. Нужны **JDK 21** (Temurin) и **git**; 8 ГБ RAM (Gradle берёт 3 ГБ, `org.gradle.jvmargs=-Xmx3G`), ~6 ГБ на диске.
   На Windows — WSL2 (или Git Bash и `git config --global core.longpaths true`).
2. Указать git имя и почту — paperweight делает коммиты при наложении патчей:
   `git config --global user.name "Имя"` и `git config --global user.email "почта"`.
3. `git clone https://github.com/tearsmilk/amazake && cd amazake` (не zip: `settings.gradle.kts` требует `.git`).
4. `./gradlew applyAllPatches` — первый раз 10–20 минут (скачивание и декомпиляция Minecraft).
5. `./gradlew createMojmapPaperclipJar` → `amazake-server/build/libs/amazake-paperclip-1.21.11-R0.1-SNAPSHOT-mojmap.jar`.
6. Проверить, как это делает CI: в пустой папке `java -XX:ActiveProcessorCount=<ядра> -jar <jar> --nogui`, принять
   EULA (`eula=true` в `eula.txt`), затем в консоли `/version`, `/amazake`, `/amazake cores`, `/spark tps`; проверить,
   что появился `amazake.yml`.
7. Выложить сборку вручную: `gh release create 1.21.11-N <jar> --title "Amazake 1.21.11 #N"` (или через сайт GitHub).
8. Стресс-плагин: `./gradlew -p stress-plugin build` → `stress-plugin/build/libs/`.

---

## Порядок работ и оценка

| Шаг | Зависит от | Объём | По умолчанию |
|---|---|---|---|
| 1. Замер по мирам и фазам | — | 1–2 дня | включено |
| Роль `worlds` в раскладке ядер (без Bukkit) | — | 0,5 дня | неактивна, пока выключен шаг 4 |
| 2. Трекер (fork-join) + `tracker.after-entities` | 1 | 4–6 дней | выключено |
| Асинхронный подсчёт мобов | 1 | 1 день | выключено |
| 3. Поиск пути | 1 | 4–5 дней | выключено |
| 4. Параллельные миры | 1, роль `worlds`, мультимир в AmazakeStress | 2–3 недели | выключено |

Каждый шаг: патч с комментариями `// Amazake start - …` / `// Amazake end - …`, переключатель в `amazake.yml` с
комментарием по-русски, строка в README, прогон AmazakeStress «до/после» с отчётами профайлера.

### Сделано

- Разбивка тика сущностей (`ink.hoka.amazake.tick.EntityTickStats`, блок `entities` отчёта): мобы, остальные
  сущности и игроки, по типам и фазам. Это часть шага 1 для фазы `entities`; фазы мира целиком ещё не меряются.
- Роль `plugins` в раскладке ядер: асинхронная работа плагинов (планировщики Bukkit и Folia, асинхронный чат,
  пулы входа и конфигурации, потоки, которые плагины создали сами) на своём ядре; ядро-подложка `background`,
  где у потоков плагинов приоритет (nice только у известных серверных потоков). Код плагинов, который трогает
  мир, по-прежнему идёт в главном потоке: гонок нет.
- Замер «мостика» к главному потоку (`ink.hoka.amazake.plugin.PluginTickStats`, блок `plugins` отчёта): мс/тик
  каждого плагина на синхронные задачи, события и команды, и CPU его асинхронных задач.
