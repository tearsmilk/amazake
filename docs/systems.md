# Системы Amazake: полный гайд

Система — модуль ядра Amazake в файле `.azs`. Сервер включает, выключает и обновляет её **без перезапуска**.
Этот гайд — всё, что нужно, чтобы написать систему, собрать её и залить на сервер. Он самодостаточный: его можно
целиком отдать другому разработчику или ИИ-ассистенту в другом чате (см. «Работа в другом чате»).

Что где лежит в репозитории [tearsmilk/amazake](https://github.com/tearsmilk/amazake):

| Что | Где |
|---|---|
| Этот гайд | `docs/systems.md` |
| Проект-шаблон с рабочим примером и скриптом заливки | `systems-template/` (в релизах — `amazake-system-template.zip`) |
| API систем (исходники) | `amazake-server/src/main/java/core/amazake/systems/api/` (полный текст — в приложении в конце) |
| API систем (jar для сборки) | в каждом релизе: `amazake-systems-api.jar` (всегда последний) и `amazake-systems-api-1.21.11-N.jar` + `-sources.jar` |
| Встроенная система AmazakeTab — большой пример | `amazake-server/src/systems/amazake-tab/` |
| Как ядро загружает системы | `amazake-server/src/main/java/core/amazake/systems/` |

## 1. Чем система отличается от плагина

| | Плагин | Система |
|---|---|---|
| Файл | `plugins/*.jar` | `systems/*.azs` (тот же jar, другое расширение) |
| Описание | `plugin.yml` | `system.yml` |
| Включить, выключить, обновить | перезапуск сервера | командой, без перезапуска |
| `/pl` | секции Paper/Bukkit | своя малиновая секция **AmazakeSystems** |
| Код | загружается один раз | при каждом включении — заново, своим загрузчиком классов; при выключении выгружается из памяти |
| Потоки | Bukkit: всё в главном потоке | пишется сразу под многопоточный тик (регионы): см. «Потоки» |
| Регистрации | плагин снимает сам или они живут до остановки | всё, что зарегистрировано через `SystemContext`, ядро снимает само |
| Пакет | любой | принято `core.amazake.systems.<система>` |

## 2. Быстрый старт

Нужны Java 21 и интернет (Gradle скачает Paper API и API систем).

1. Возьмите шаблон: папку `systems-template/` из репозитория или `amazake-system-template.zip` из
   [последнего релиза](https://github.com/tearsmilk/amazake/releases/latest).
2. Переименуйте систему:
   - `settings.gradle.kts`: `rootProject.name = "Greeter"` — это имя системы, папки её настроек и файла `.azs`;
   - пакет `core.amazake.systems.example` → `core.amazake.systems.greeter` (и папки в `src/main/java`,
     `src/test/java`);
   - `src/main/resources/system.yml`: `main: core.amazake.systems.greeter.GreeterSystem`, `description`, `authors`.
3. Соберите: `./gradlew azs` → `build/libs/Greeter.azs`. Тесты: `./gradlew test`.
4. Залейте на сервер: положите файл в `systems/` и введите в консоли `amazake system enable Greeter`
   (или скриптом: `SERVER=user@host SERVER_DIR=/opt/mc ./deploy.sh`, см. «Заливка на сервер»).
5. Поменяли код — пересобрали, залили, `amazake system restart Greeter`. Поменяли только конфиг на сервере —
   `amazake system reload Greeter`.

## 3. Команды и папка systems/

Все команды — подкоманды `/amazake`, нужны права `amazake.command` и `amazake.command.systems` (у OP есть,
в консоли работают всегда).

| Команда | Что делает |
|---|---|
| `/amazake systems` | Список систем в `systems/`: версия, включена или нет, описание, последняя ошибка |
| `/amazake system enable <имя>` | Включить. Записывается в `systems/systems.yml`: при следующих запусках включится сама |
| `/amazake system disable <имя>` | Выключить и убрать из `systems.yml` |
| `/amazake system reload <имя>` | Вызвать `reload()` системы (перечитать настройки). Код не перезагружается |
| `/amazake system restart <имя>` | Выключить и включить заново **с файла**: так подхватывается новая сборка `.azs` |
| `/amazake system info <имя>` | Версия, авторы, главный класс, файл, папка, последняя ошибка |
| `/amazake system update <имя>` | Только для встроенных систем: заменить файл версией из ядра |

```
systems/
  Greeter.azs           # система (имя файла любое: система определяется по system.yml)
  Greeter/              # её папка: config.yml и любые данные (context.dataFolder())
  AmazakeTab.azs        # встроенная система
  AmazakeTab/
  systems.yml           # enabled: [...] — какие включены; ядро переписывает его само
  .installed            # какие встроенные системы ядро уже распаковало
```

Жизненный цикл:

- **Запуск сервера.** После включения плагинов (POSTWORLD) ядро сканирует `systems/` и включает всё из
  `systems.yml`. Если файла системы из списка нет, в консоли будет предупреждение, а запись останется.
- **Остановка сервера.** Все системы выключаются до выключения плагинов (`disable()` вызывается, можно сохранять
  данные).
- **Bukkit `/reload`.** Системы выключаются и включаются снова из `systems.yml`.
- **Ошибка в `enable()`.** Система остаётся выключенной, ядро снимает всё, что она успела зарегистрировать.
  Стек — в консоли, класс и текст ошибки — в `/amazake system info` («Последняя ошибка»).
- **Ошибка в `reload()`.** Система выключается (чтобы не остаться наполовину настроенной), но остаётся в
  `systems.yml`: исправьте конфиг и включите снова.
- Система не может называться как установленный плагин. Две системы с одним `name`: работает первый файл
  по алфавиту, о втором — предупреждение.

## 4. Проект и файл .azs

```
systems-template/
  settings.gradle.kts         # rootProject.name = имя системы
  build.gradle.kts            # paper-api + API систем (compileOnly), jar -> <Имя>.azs
  deploy.sh                   # сборка и заливка на сервер по SSH
  src/main/java/core/amazake/systems/example/
    ExampleSystem.java        # главный класс (implements AmazakeSystem)
    ExampleCommand.java       # команда
    Settings.java             # неизменяемый снимок config.yml
  src/main/resources/
    system.yml                # описание системы
    config.yml                # настройки по умолчанию
  src/test/java/...           # юнит-тесты чистой логики
```

`.azs` — обычный jar (zip): `system.yml` в корне, рядом классы и ресурсы. Paper API и API систем внутрь **не
кладутся** — они есть на сервере.

### system.yml

```yaml
name: Greeter                                   # [A-Za-z0-9_-], 1–32 символа; имя папки и пространство имён команд
version: 1.0.0                                  # любая строка; сравнивается как 1.2.10 > 1.2.9, 1.0.0-beta < 1.0.0
main: core.amazake.systems.greeter.GreeterSystem # класс с публичным конструктором без аргументов, implements AmazakeSystem
description: Приветствует игроков               # необязательно
authors: [tearsmilk]                            # необязательно (или author: tearsmilk)
api-version: 2                                  # версия API систем; это ядро понимает 2
conflicts: [SomePlugin]                         # необязательно: плагины, которые делают то же самое (предупреждение при включении)
```

`main` не может лежать в `core.amazake.systems.api`, `org.bukkit` и `net.minecraft`. В шаблоне `name` и
`version` подставляет сборка (`${name}`, `${version}`).

### build.gradle.kts (из шаблона)

```kotlin
plugins { java }

val systemName = rootProject.name
version = "1.0.0"

// Jar API: -PamazakeApiJar=путь, иначе скачивается из последнего релиза Amazake в libs/.
val apiUrl = "https://github.com/tearsmilk/amazake/releases/latest/download/amazake-systems-api.jar"
val apiJar: File = (findProperty("amazakeApiJar") as String?)?.let { file(it) }
    ?: layout.projectDirectory.file("libs/amazake-systems-api.jar").asFile.also { jar ->
        if (!jar.exists()) { /* скачать apiUrl в jar */ }
    }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files(apiJar))
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

tasks.processResources { filesMatching("system.yml") { expand("name" to systemName, "version" to version) } }
tasks.jar { archiveFileName.set("$systemName.azs") }
```

Полная версия — в `systems-template/build.gradle.kts` (там же задачи `azs`, `refreshSystemsApi` и тесты).

**Свои библиотеки** (которых нет в Paper) кладите внутрь `.azs`: объявите их как `implementation` и добавьте в
jar:

```kotlin
tasks.jar {
    from(configurations.runtimeClasspath.map { cp -> cp.map { if (it.isDirectory) it else zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
```

Лучше — плагином shadow с переносом пакетов (`relocate("com.example.lib", "core.amazake.systems.greeter.lib")`),
чтобы библиотека не столкнулась с такой же у плагинов. Paper, Adventure, MiniMessage, Gson, SnakeYAML, Guava уже есть
на сервере — их не кладите.

## 5. API — справочник

Пакет `core.amazake.systems.api`. Система может пользоваться им и обычным API Bukkit/Paper (Adventure,
MiniMessage, события, планировщики Paper). Внутренности сервера (`net.minecraft.*`, `org.bukkit.craftbukkit.*`)
не используйте: они меняются от версии к версии и не потокобезопасны.

### AmazakeSystem — главный класс

```java
public interface AmazakeSystem {
    void enable(SystemContext context) throws Exception;   // включение; глобальный поток тика
    void disable(SystemContext context) throws Exception;  // выключение (и остановка сервера); глобальный поток тика
    default void reload(SystemContext context) throws Exception { disable(context); enable(context); }
}
```

Каждое включение — новый объект в новом загрузчике классов: статические поля системы при `restart` и
`disable`/`enable` начинаются с нуля. Переопределите `reload`, чтобы перечитывать настройки без полного
перезапуска (как в шаблоне).

### SystemContext — что ядро даёт системе

Все методы потокобезопасны. Всё, что зарегистрировано через контекст, ядро уберёт при выключении само.

| Метод | Что делает |
|---|---|
| `SystemInfo info()` | Данные `system.yml`: `name`, `version`, `main`, `description`, `authors`, `apiVersion` |
| `Logger logger()` | Логгер; в консоли `[Greeter] ...` |
| `Path dataFolder()` | `systems/<имя>/` (создаётся при обращении) |
| `YamlConfiguration loadConfig()` | `systems/<имя>/config.yml`. При каждом вызове: если файла нет — копируется из `config.yml` внутри `.azs` (существующий не перезаписывается), затем читается заново. Нет `config.yml` и в `.azs` — пустая конфигурация; ошибка YAML — `IllegalStateException` (в `enable` система не включится, в `reload` — выключится) |
| `void saveDefaultResource(String path)` | Скопировать ресурс из `.azs` в папку системы, если файла ещё нет (`"messages.yml"`, `"lang/ru.yml"`) |
| `Plugin plugin()` | Объект `Plugin` системы — для API, которое требует плагин (`Bukkit.getScheduler()`, `NamespacedKey`, `ServicesManager`…). Включён ровно пока включена система, в списке плагинов его нет |
| `void registerListener(Listener)` | Зарегистрировать все `@EventHandler` объекта |
| `void registerCommand(Command)` | Команда `/<label>` и `/<имя системы в нижнем регистре>:<label>` (с алиасами). Дерево команд игрокам отправляется заново после `enable()` и `reload()`, поэтому регистрируйте команды там, а не из таймеров и событий |
| `ScheduledTask runGlobalTimer(Consumer<ScheduledTask>, Duration period)` | Повтор в глобальном потоке тика; первый запуск через `period` |
| `ScheduledTask runAsyncTimer(Consumer<ScheduledTask>, Duration period)` | Повтор вне потоков тика (файлы, сеть, база) |
| `ScheduledTask runForPlayer(Player, Consumer<ScheduledTask>, long periodTicks)` | Повтор каждые `periodTicks` в потоке, которому принадлежит игрок; кончается, когда игрок вышел, задача отменила себя или система выключена. `null`, если игрок уже невалиден |
| `boolean runOnceForPlayer(Player, Runnable)` | Один раз в потоке игрока; `false`, если игрок невалиден |
| `ServerStats stats()` | `tps()`, `mspt()` (средние за 5 с), `onlinePlayers()`, `maxPlayers()` — из любого потока |

`ScheduledTask` — интерфейс Paper (`io.papermc.paper.threadedregions.scheduler.ScheduledTask`): `cancel()`,
`isCancelled()`, `getExecutionState()`.

## 6. Потоки — главное правило систем

Сегодня Amazake тикает мир в одном потоке, но системы с первого дня пишутся под многопоточный тик (регионы, как в
Folia), чтобы потом ничего не переписывать. Ядро **никогда** не запускает системы под блокировкой плагинов.

Где что выполняется:

| Код | Поток |
|---|---|
| `enable`, `disable`, `reload` | глобальный поток тика |
| `runGlobalTimer` | глобальный поток тика |
| `runForPlayer`, `runOnceForPlayer` | поток, которому принадлежит игрок (его регион) |
| `runAsyncTimer` | пул асинхронных потоков |
| обработчик события игрока (`PlayerJoinEvent`, `PlayerMoveEvent`, `PlayerInteractEvent`…) | поток этого игрока |
| обработчик события блока или сущности | поток региона, где это происходит |
| асинхронные события (`AsyncChatEvent`, `AsyncPlayerPreLoginEvent`…) | асинхронный поток |
| команда игрока | поток этого игрока |
| команда из консоли | глобальный поток тика |

Правила:

1. **Игрока трогает только его поток.** Сообщения, таб, скорборд, инвентарь, телепорт, эффекты, чтение его
   позиции и пинга — в `runForPlayer`/`runOnceForPlayer`, в его событиях или его командах. Не меняйте игрока A из
   задачи игрока B или из глобального таймера: из глобального таймера пройдитесь по игрокам и поставьте каждому
   `runOnceForPlayer`.
2. **Мир трогает поток его региона.** Блоки и сущности — в событиях этого места или в задаче игрока, который там
   стоит. Глобальное (время, погода, общий счётчик кадров анимации) — в `runGlobalTimer`.
3. **Общие данные — только потокобезопасные.** `ConcurrentHashMap`, `AtomicReference`, `AtomicLong`, `LongAdder`,
   неизменяемые `record` и `List.copyOf`. Обновили — подменили снимок целиком, а не правите поля:

   ```java
   record Entry(String prefix, String suffix, String sortKey) {}
   final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
   // задача игрока A пишет только свою запись:
   entries.put(a.getUniqueId(), new Entry(prefix, suffix, key));
   // задача игрока B читает чужие записи и меняет только своё:
   entries.forEach((uuid, entry) -> applyTo(boardOfB, uuid, entry));
   ```

   Обычные `HashMap`, `ArrayList` и поля без `volatile` между потоками — это гонка: сегодня работает, на
   многопоточном тике сломается.
4. **Медленное — вне тика.** Файлы, база, HTTP — в `runAsyncTimer` или в своём пуле. Результат обратно к игроку —
   через `runOnceForPlayer`.
5. **Свои потоки и пулы** останавливайте в `disable()`: ядро о них не знает, а живой поток держит систему в
   памяти.
6. **Не блокируйте** `enable`/`disable`/`reload` и задачи тика: никаких `Thread.sleep`, `future.get()`, сетевых
   запросов.
7. Исключение в задаче не валит систему: ядро пишет его в консоль со стеком, задача продолжает работать. Дальше
   ошибки задач этой системы (любые) пишутся не чаще раза в минуту, с числом пропущенных.

## 7. Рецепты

Все примеры — из рабочего шаблона (`systems-template/`) или в его стиле.

### Настройки и reload

```java
public record Settings(String welcome, boolean actionbarEnabled, long actionbarIntervalTicks, String actionbarFormat) {
    public static final Settings DEFAULTS = new Settings("<green>Привет, <player>!</green>", true, 20L, "<gray>TPS <tps></gray>");

    public static Settings from(ConfigurationSection config, Consumer<String> warn) {
        long interval = config.getLong("actionbar.interval-ticks", DEFAULTS.actionbarIntervalTicks());
        if (interval < 1 || interval > 72000) {
            warn.accept("actionbar.interval-ticks: " + interval + " вне 1..72000, беру 20");
            interval = DEFAULTS.actionbarIntervalTicks();
        }
        return new Settings(config.getString("welcome", DEFAULTS.welcome()),
            config.getBoolean("actionbar.enabled", true), interval,
            config.getString("actionbar.format", DEFAULTS.actionbarFormat()));
    }
}

// в системе:
private final AtomicReference<Settings> settings = new AtomicReference<>(Settings.DEFAULTS);

private void loadSettings(SystemContext context) {
    settings.set(Settings.from(context.loadConfig(), w -> context.logger().warning(w)));
}

@Override public void reload(SystemContext context) { loadSettings(context); /* перезапустить задачи, если нужно */ }
```

Разбор настроек держите отдельно от Bukkit-логики — его легко проверить юнит-тестом (`SettingsTest` в шаблоне).

### Команда с правом и автодополнением

```java
final class ExampleCommand extends Command {
    ExampleCommand() {
        super("example", "Пример команды", "/example [ping]", List.of("ex"));
        setPermission("example.use");
    }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermission(sender)) return true;
        if (args.length >= 1 && args[0].equalsIgnoreCase("ping") && sender instanceof Player player) {
            // команда игрока выполняется в его потоке
            sender.sendMessage(Component.text("Пинг: " + player.getPing() + " мс", NamedTextColor.AQUA));
            return true;
        }
        sender.sendMessage(Component.text("Использование: " + getUsage(), NamedTextColor.GRAY));
        return true;
    }

    @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return args.length == 1 && "ping".startsWith(args[0].toLowerCase(Locale.ROOT)) ? List.of("ping") : List.of();
    }
}

// в enable():
context.registerCommand(new ExampleCommand());
```

Право по умолчанию есть у OP. Чтобы дать всем, выдайте его через LuckPerms (`lp group default permission set
example.use true`).

### События

```java
public final class GreeterSystem implements AmazakeSystem, Listener {
    @Override public void enable(SystemContext context) { context.registerListener(this); }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {   // поток этого игрока
        event.getPlayer().sendMessage(MiniMessage.miniMessage().deserialize("<gold>Привет!</gold>"));
    }

    @Override public void disable(SystemContext context) { }
}
```

### Задача для каждого игрока (с учётом тех, кто уже онлайн)

```java
private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();

@Override public void enable(SystemContext context) {
    this.context = context;
    context.registerListener(this);
    for (Player player : Bukkit.getOnlinePlayers()) start(player);  // систему могли включить при игроках онлайн
}

@EventHandler public void onJoin(PlayerJoinEvent e) { start(e.getPlayer()); }
@EventHandler public void onQuit(PlayerQuitEvent e) { tasks.remove(e.getPlayer().getUniqueId()); }

private void start(Player player) {
    ScheduledTask task = context.runForPlayer(player, t -> player.sendActionBar(render(player)), 20L);
    if (task != null) {
        ScheduledTask old = tasks.put(player.getUniqueId(), task);
        if (old != null) old.cancel();
    }
}
```

### Глобальный таймер и рассылка игрокам

```java
private final AtomicLong frame = new AtomicLong();

context.runGlobalTimer(t -> {
    long f = frame.incrementAndGet();                       // общий счётчик кадров анимации
    for (Player player : Bukkit.getOnlinePlayers()) {
        context.runOnceForPlayer(player, () -> player.sendPlayerListHeader(header(f)));  // игрока трогает его поток
    }
}, Duration.ofMillis(500));
```

### Данные в файл без лагов

```java
private final ConcurrentHashMap<UUID, Integer> points = new ConcurrentHashMap<>();
private final AtomicBoolean dirty = new AtomicBoolean();

@Override public void enable(SystemContext context) {
    load(context.dataFolder().resolve("points.yml"));
    context.runAsyncTimer(t -> { if (dirty.getAndSet(false)) save(context); }, Duration.ofMinutes(1));
}

@Override public void disable(SystemContext context) { save(context); }  // при остановке сервера тоже

private void save(SystemContext context) {
    YamlConfiguration yaml = new YamlConfiguration();
    points.forEach((uuid, value) -> yaml.set(uuid.toString(), value));
    Path file = context.dataFolder().resolve("points.yml");
    Path tmp = file.resolveSibling("points.yml.tmp");
    try {
        yaml.save(tmp.toFile());
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
        context.logger().log(Level.WARNING, "Не удалось сохранить points.yml", e);
    }
}
```

### Свой скорборд игроку

У каждого игрока — свой `Scoreboard` (`Bukkit.getScoreboardManager().getNewScoreboard()`), и меняет его только
задача этого игрока. В `disable()` верните игроку главный скорборд
(`player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard())`) через `runOnceForPlayer`. Если у игрока
чужой скорборд (мини-игра, другой плагин), не трогайте его. Полная реализация — AmazakeTab
(`amazake-server/src/systems/amazake-tab/java/core/amazake/systems/tab/PlayerView.java`, `TabRuntime.java`).

### Другие плагины (Vault, PlaceholderAPI, LuckPerms)

Жёстко от плагина не зависьте: проверяйте, что он включён, и обращайтесь к нему через его API (`compileOnly` в
Gradle) или рефлексией, как AmazakeTab (`Hooks.java`):

```java
if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
    text = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, text);
}
```

Сервисы — через `Bukkit.getServicesManager().getRegistration(...)`. Плагин может выключиться или включиться позже
системы: слушайте `PluginEnableEvent`/`PluginDisableEvent`, если это важно.

### Тексты: MiniMessage

```java
MiniMessage mm = MiniMessage.miniMessage();
Component c = mm.deserialize("<gradient:#DC143C:#FFB6C1>Привет, <player>!</gradient>",
    Placeholder.unparsed("player", player.getName()));   // unparsed: ник не может вставить свои теги
```

## 8. Что ядро убирает само

При выключении (команда, `restart`, ошибка перезагрузки, остановка сервера) ядро вызывает `disable()`, потом:

- снимает все обработчики событий системы (и зарегистрированные напрямую через `plugin()`);
- отменяет её задачи: планировщик Bukkit, глобальный, асинхронный и региональный планировщики Paper, задачи
  `runForPlayer`. Задачи `runOnceForPlayer`, поставленные в `disable()`, успевают выполниться;
- удаляет её команды и заново отправляет игрокам список команд; пересобирает `/help`;
- снимает её сервисы (`ServicesManager`) и каналы плагинных сообщений;
- закрывает её загрузчик классов: код системы выгружается из памяти.

Сами верните то, о чём ядро не знает: таб, скорборд, боссбары, показанные игрокам; свои потоки и соединения;
несохранённые данные. Задачи в планировщике сущностей Paper (`entity.getScheduler()`) ядро не отслеживает — для
игроков используйте `runForPlayer`.

Не оставляйте ссылок на свои классы в чужих местах (статические поля других плагинов, `ThreadLocal` чужих потоков,
`ConfigurationSerialization.registerClass`, свои `Event`-классы в чужих списках): иначе выключенная система
останется в памяти.

## 9. Заливка на сервер и обновление

**Вручную.** Скопируйте `build/libs/<Имя>.azs` в папку `systems/` сервера (рядом с `plugins/`), затем:

- новая система: `amazake system enable <Имя>`;
- обновление включённой: `amazake system restart <Имя>`;
- поменяли только `systems/<Имя>/config.yml`: `amazake system reload <Имя>`.

Файл включённой системы заменяйте **переименованием**, а не перезаписью: загрузите во временный файл и сделайте
`mv` поверх старого. Работающая система дочитывает свой старый файл, а `restart` подхватывает новый.

```bash
scp build/libs/Greeter.azs user@host:/opt/mc/systems/.Greeter.azs.upload
```

```bash
ssh user@host "mv -f /opt/mc/systems/.Greeter.azs.upload /opt/mc/systems/Greeter.azs"
```

**Скриптом** `deploy.sh` (в шаблоне) — сборка, загрузка через временный файл и `mv`, и команда в консоль.
`SERVER_DIR` — абсолютный путь к папке сервера (без `~`):

```bash
SERVER=user@host SERVER_DIR=/opt/mc ./deploy.sh
```

```bash
SERVER=user@host SERVER_DIR=/opt/mc CONSOLE=screen:mc ./deploy.sh
```

С `CONSOLE=screen:<сессия>` или `CONSOLE=tmux:<сессия>` скрипт сам отправит `amazake system restart <Имя>` в
консоль сервера, иначе напечатает, что ввести. Для первой заливки новой системы выполните `amazake system enable
<Имя>` (restart выключенную систему тоже включает).

## 10. Отладка

| Симптом | Причина и что делать |
|---|---|
| `api-version 1 не поддерживается этим ядром` | Система собрана под старый API. Обновите `api-version` до 2, `./gradlew refreshSystemsApi`, пересоберите |
| `NoClassDefFoundError: ink/hoka/...` | Старые пакеты API (до build-21). Импорты — `core.amazake.systems.api.*` |
| `main «…» не похож на полное имя класса` / `ClassNotFoundException` | `main` в `system.yml` не совпадает с пакетом и именем класса |
| `есть плагин с таким же именем` | Переименуйте систему (`rootProject.name` и `name`) |
| `Система X уже включена` | Для новой сборки — `restart`, не `enable` |
| `restart` отказался: «новый файл … не годится» | В новом `.azs` ошибка в `system.yml`; старая версия продолжает работать, исправьте и залейте снова |
| Команда не появилась у игрока | Нет права (`setPermission`), или клиент не обновил список — перезайдите |
| Система не выгружается из памяти | Свой поток не остановлен в `disable()` или ссылка на класс системы осталась в чужом объекте |

Что смотреть: `/amazake system info <Имя>` (последняя ошибка), консоль (строки `[<Имя>]` и `Системы: ...`).

## 11. Чек-лист перед заливкой

- `system.yml`: `name` = `rootProject.name`, `main` указывает на класс, `api-version: 2`, `authors`.
- Пакет `core.amazake.systems.<система>`, импорты API — `core.amazake.systems.api.*`.
- Всё регистрируется через `context` (`registerListener`, `registerCommand`, `run…`), а не мимо него.
- Игрок меняется только в его потоке (`runForPlayer`/`runOnceForPlayer`, его события и команды).
- Общие данные — `ConcurrentHashMap`/`Atomic*`/неизменяемые снимки.
- `enable()` учитывает игроков, которые уже онлайн.
- `disable()` возвращает игрокам показанное, сохраняет данные, останавливает свои потоки.
- `reload()` перечитывает настройки и перезапускает задачи, которые от них зависят.
- Тексты в MiniMessage, пользовательский ввод — через `Placeholder.unparsed`.
- `./gradlew build` проходит (с тестами).

## 12. Работа в другом чате (ИИ-ассистент)

Чтобы писать системы в отдельном чате и сразу заливать их на сервер, дайте ассистенту доступ к репозиторию
`tearsmilk/amazake` (или этот файл и папку `systems-template/`) и вставьте:

```text
Пишем систему для ядра Amazake (Minecraft 1.21.11, форк Paper). Гайд: docs/systems.md в репозитории
tearsmilk/amazake — прочитай его целиком, особенно «Потоки» и «Чек-лист». Начни с копии systems-template/:
переименуй систему (settings.gradle.kts, пакет core.amazake.systems.<система>, system.yml), напиши код,
собери ./gradlew build (Java 21; API систем скачается из последнего релиза или -PamazakeApiJar=путь) и
проверь, что build/libs/<Имя>.azs собран. Пиши в стиле шаблона: настройки — неизменяемый record в
AtomicReference, игрока трогает только его поток, всё регистрируется через SystemContext, тексты — MiniMessage,
сообщения игрокам и конфиг — по-русски, authors: [tearsmilk]. Система: <описание того, что нужно>.
```

Залить готовую систему: `deploy.sh` из шаблона (раздел 9) или скопировать `.azs` в `systems/` и
`amazake system enable <Имя>` / `restart <Имя>`.

## Приложение: исходники API систем (api-version 2)

Актуальная версия всегда в `amazake-server/src/main/java/core/amazake/systems/api/` и в
`amazake-systems-api-1.21.11-N-sources.jar` каждого релиза.

<!-- api-sources:begin -->
### AmazakeSystem.java

```java
package core.amazake.systems.api;

/**
 * A system of the Amazake core: a module in a {@code .azs} file in the server's {@code systems/} folder, enabled and
 * disabled at runtime (without a restart) with {@code /amazake system enable|disable|reload <name>}.
 *
 * <p>The main class named in the file's {@code system.yml} implements this interface and has a public no-argument
 * constructor. Every enable creates a new instance in a new class loader; after {@link #disable} the instance and its
 * class loader are dropped, so a system must not leave anything behind that the core does not clean up itself (see
 * {@link SystemContext}).</p>
 *
 * <p><b>Threads.</b> Systems are written for regionized ticking from day one: they are never run under the plugin
 * lock, their listeners may be called on several tick threads at once, and per-player or per-entity work must be
 * scheduled with {@link SystemContext#runForPlayer} / the entity scheduler, which runs it on the thread that owns that
 * entity. Shared state must be thread-safe (concurrent collections, immutable snapshots). See docs/systems.md.</p>
 */
public interface AmazakeSystem {

    /**
     * Starts the system. Called on the global tick thread. Register listeners, commands and tasks through
     * {@code context}: everything registered there is removed by the core when the system is disabled.
     * Throwing leaves the system disabled (the core cleans up what was registered and logs the error).
     */
    void enable(SystemContext context) throws Exception;

    /**
     * Stops the system: undo what the core cannot undo on its own (scoreboards and tab lists shown to players,
     * files to save). Called on the global tick thread, also at server shutdown. The core then unregisters the
     * listeners, cancels the tasks and unregisters the commands registered through the context.
     */
    void disable(SystemContext context) throws Exception;

    /**
     * Reloads the configuration without a full disable/enable. The default does a disable followed by an enable.
     */
    default void reload(final SystemContext context) throws Exception {
        this.disable(context);
        this.enable(context);
    }
}
```

### SystemContext.java

```java
package core.amazake.systems.api;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Logger;
import org.bukkit.command.Command;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

/**
 * What the core gives a running system. Everything registered through it is tracked and removed when the system
 * is disabled. All methods are thread-safe.
 */
public interface SystemContext {

    /** The system's description from its system.yml. */
    SystemInfo info();

    /** A logger whose messages are prefixed with the system's name. */
    Logger logger();

    /** {@code systems/<name>/}, created if missing. */
    Path dataFolder();

    /**
     * {@code systems/<name>/config.yml}, written from the {@code config.yml} inside the .azs file on first use
     * (existing files are never overwritten), then loaded. Each call reads the file again.
     */
    YamlConfiguration loadConfig();

    /** Copies a resource of the .azs file into the data folder unless the target exists. */
    void saveDefaultResource(String path);

    /**
     * The Bukkit-side owner of the system's registrations: a plugin object that is not in the plugin list (it shows
     * in /pl under AmazakeSystems) and is enabled exactly while the system is. Use it where Bukkit or Paper API asks
     * for a {@link Plugin}; registrations made with it directly are cleaned up too (listeners, Bukkit scheduler,
     * global/async/region schedulers), except entity-scheduler tasks: schedule those with {@link #runForPlayer}.
     */
    Plugin plugin();

    /** Registers a listener (all its {@code @EventHandler} methods), unregistered on disable. */
    void registerListener(Listener listener);

    /** Registers a command under the system's namespace ({@code /<name>:<label>} and {@code /<label>}), removed on disable. */
    void registerCommand(Command command);

    /** Runs {@code task} on the global tick thread every {@code period} (first run after {@code period}). */
    ScheduledTask runGlobalTimer(Consumer<ScheduledTask> task, Duration period);

    /** Runs {@code task} off the tick threads every {@code period}. */
    ScheduledTask runAsyncTimer(Consumer<ScheduledTask> task, Duration period);

    /**
     * Runs {@code task} on the thread that owns {@code player} every {@code periodTicks} ticks, until the player
     * leaves, the task cancels itself or the system is disabled. The place for per-player work (their tab list,
     * scoreboard, name tags). Returns null if the player is no longer valid.
     */
    ScheduledTask runForPlayer(Player player, Consumer<ScheduledTask> task, long periodTicks);

    /** Runs {@code task} once on the thread that owns {@code player}; false if the player is no longer valid. */
    boolean runOnceForPlayer(Player player, Runnable task);

    /** Server-wide numbers the core already measures, safe to call from any thread. */
    ServerStats stats();
}
```

### SystemInfo.java

```java
package core.amazake.systems.api;

import java.util.List;

/** The {@code system.yml} of a .azs file. */
public record SystemInfo(String name, String version, String main, String description, List<String> authors, String apiVersion) {
}
```

### ServerStats.java

```java
package core.amazake.systems.api;

/** Server-wide numbers for placeholders; any thread. */
public interface ServerStats {

    /** TPS over the last 5 s, at most 20. */
    double tps();

    /** Mean tick duration over the last 5 s, ms. */
    double mspt();

    int onlinePlayers();

    int maxPlayers();
}
```

### package-info.java

```java
/**
 * The API of Amazake systems ({@code .azs} modules in the server's {@code systems/} folder). This package is the
 * whole contract between the core and a system: a system may use it plus the Bukkit/Paper API. Published as the
 * {@code amazake-systems-api} jar for system authors.
 */
package core.amazake.systems.api;
```
<!-- api-sources:end -->
