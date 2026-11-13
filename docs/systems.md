# Как написать систему Amazake

Система — модуль ядра в файле `.azs`, который сервер включает и выключает **без перезапуска**
(`/amazake system enable|disable|reload <имя>`). Встроенная система AmazakeTab написана ровно так же, как
написали бы вы: её исходники — `amazake-server/src/systems/amazake-tab/`.

Система — это не плагин:

- она не появляется в списке плагинов Bukkit (в `/pl` у неё своя строка **AmazakeSystems**);
- каждое включение загружает её код заново, новым загрузчиком классов, а выключение выгружает его;
- она обязана быть потокобезопасной (см. «Потоки»): ядро никогда не запускает её под блокировкой плагинов.

## Что внутри .azs

`.azs` — обычный jar (zip). В корне лежит `system.yml`, рядом — классы и ресурсы:

```
MySystem.azs
  system.yml
  config.yml                  # настройки по умолчанию (необязательно)
  com/example/mysystem/MySystem.class
  ...
```

`system.yml`:

```yaml
name: MySystem                       # [A-Za-z0-9_-], от 1 до 32 символов, уникальное
version: 1.0.0
main: com.example.mysystem.MySystem  # класс, реализующий AmazakeSystem, с публичным конструктором без аргументов
description: Что делает система       # необязательно
authors: [Вы]                         # необязательно (или author: Вы)
api-version: 2                        # версия API систем; это ядро понимает 2
conflicts: [SomePlugin]               # необязательно: плагины, которые делают то же самое (при включении будет предупреждение)
```

Имя системы — это и имя папки с настройками (`systems/<имя>/`), и пространство имён её команд (`/<имя>:<команда>`).
Системе нельзя называться так же, как установленный плагин.

## API

API систем — пакет `core.amazake.systems.api` (jar `amazake-systems-api-<версия>.jar` есть в каждом
релизе, исходники рядом). Система может пользоваться им и обычным API Bukkit/Paper (Adventure, MiniMessage
и т. д.). Внутренние классы ядра (`net.minecraft`, `org.bukkit.craftbukkit`) не используйте: они меняются от
версии к версии.

```java
public interface AmazakeSystem {
    void enable(SystemContext context) throws Exception;   // включение, в глобальном потоке тика
    void disable(SystemContext context) throws Exception;  // выключение (и при остановке сервера)
    default void reload(SystemContext context) throws Exception { disable(context); enable(context); }
}
```

`SystemContext` — всё, что ядро даёт системе. Всё, что зарегистрировано через него, ядро уберёт само:

| Метод | Что делает |
|---|---|
| `info()` | Данные из `system.yml` (`SystemInfo`: name, version, main, description, authors, apiVersion) |
| `logger()` | Логгер; строки в консоли выглядят как `[MySystem] ...` |
| `dataFolder()` | `systems/<имя>/` (создаётся, если нет) |
| `loadConfig()` | `systems/<имя>/config.yml`: при первом вызове копируется из `.azs`, существующий файл не перезаписывается; каждый вызов читает файл заново |
| `saveDefaultResource(path)` | Скопировать ресурс из `.azs` в папку системы, если файла ещё нет |
| `plugin()` | Объект `Plugin` системы — для API Bukkit/Paper, которое просит плагин (он включён, пока включена система) |
| `registerListener(listener)` | Зарегистрировать обработчики событий (`@EventHandler`) |
| `registerCommand(command)` | Зарегистрировать команду (`org.bukkit.command.Command`) как `/<label>` и `/<имя в нижнем регистре>:<label>` |
| `runGlobalTimer(task, period)` | Повторяющаяся задача в глобальном потоке тика |
| `runAsyncTimer(task, period)` | Повторяющаяся задача вне потоков тика |
| `runForPlayer(player, task, periodTicks)` | Повторяющаяся задача в потоке, которому принадлежит игрок; заканчивается, когда игрок вышел |
| `runOnceForPlayer(player, task)` | Один раз выполнить задачу в потоке игрока |
| `stats()` | TPS и MSPT за 5 секунд, онлайн, максимум игроков — из любого потока |

Пример целиком:

```java
package com.example.mysystem;

import core.amazake.systems.api.AmazakeSystem;
import core.amazake.systems.api.SystemContext;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class MySystem implements AmazakeSystem, Listener {

    /** Настройки читают все потоки: неизменяемый снимок в AtomicReference. */
    private final AtomicReference<String> greeting = new AtomicReference<>("Привет");
    private SystemContext context;

    @Override
    public void enable(final SystemContext context) {
        this.context = context;
        this.greeting.set(context.loadConfig().getString("greeting", "Привет"));
        context.registerListener(this);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            this.start(player);
        }
        context.runGlobalTimer(task -> context.logger().fine("TPS " + context.stats().tps()), Duration.ofMinutes(1));
    }

    @EventHandler
    public void onJoin(final PlayerJoinEvent event) {
        this.start(event.getPlayer());
    }

    private void start(final Player player) {
        // Всё, что меняет игрока, — только в его собственной задаче.
        this.context.runForPlayer(player, task -> player.sendActionBar(Component.text(this.greeting.get())), 20L);
    }

    @Override
    public void disable(final SystemContext context) {
        for (final Player player : Bukkit.getOnlinePlayers()) {
            context.runOnceForPlayer(player, () -> player.sendActionBar(Component.empty()));
        }
    }

    @Override
    public void reload(final SystemContext context) {
        this.greeting.set(context.loadConfig().getString("greeting", "Привет"));
    }
}
```

## Потоки

Сегодня Amazake тикает мир в одном потоке, но системы с первого дня пишутся под многопоточный тик (регионы, как
в Folia), чтобы не переписывать их потом. Правила:

1. **Систему никогда не запускают под блокировкой плагинов.** Обработчики событий системы могут вызываться в
   нескольких потоках тика одновременно (для разных регионов), задачи — тоже.
2. **Игрока трогает только его поток.** Всё, что меняет конкретного игрока или сущность (таб, скорборд, команды
   игрока, инвентарь, телепорт), делайте в `runForPlayer` / `runOnceForPlayer`: ядро выполнит задачу в потоке,
   которому принадлежит игрок. Не меняйте игрока A из задачи игрока B.
3. **Общие данные — потокобезопасные.** То, что нужно нескольким потокам, держите в `ConcurrentHashMap`,
   `AtomicReference`, `AtomicLong` и неизменяемых объектах (`record`, `List.copyOf`). Обновили — подменили снимок
   целиком, а не правите его поля:

   ```java
   record Entry(String prefix, String suffix, String sortKey) {}
   final ConcurrentHashMap<UUID, Entry> entries = new ConcurrentHashMap<>();
   // задача игрока A пишет только свою запись:
   entries.put(a.getUniqueId(), new Entry(prefix, suffix, key));
   // задача игрока B читает чужие записи и меняет только свой скорборд:
   entries.forEach((uuid, entry) -> applyTo(boardOfB, uuid, entry));
   ```

4. **Мировое и глобальное** (время, погода, общий счётчик анимации) — в `runGlobalTimer`. Медленное и сетевое
   (файлы, базы, HTTP) — в `runAsyncTimer` или своём пуле, но потоки, которые вы создали сами, нужно остановить в
   `disable()`.
5. **`enable`, `disable` и `reload` вызываются в глобальном потоке тика.** Не блокируйте их надолго.
6. Если задача системы бросила исключение, ядро пишет его в консоль (одна и та же ошибка — не чаще раза в минуту)
   и задача продолжает работать.

## Что ядро убирает само

При выключении (команда, ошибка перезагрузки, остановка сервера) ядро вызывает `disable()`, а потом:

- снимает все обработчики событий системы (и зарегистрированные напрямую через `plugin()`);
- отменяет её задачи: планировщик Bukkit, глобальный, асинхронный и региональный планировщики Paper, задачи
  `runForPlayer`; задачи `runOnceForPlayer`, поставленные в `disable()`, успевают выполниться (при остановке
  сервера и в потоке, который владеет игроком, они выполняются сразу);
- удаляет её команды из списка команд и заново отправляет его игрокам; пересобирает `/help`;
- снимает её сервисы (`ServicesManager`) и каналы плагинных сообщений;
- закрывает её загрузчик классов: код системы выгружается из памяти.

Сами вы должны вернуть то, что ядро не знает: скорборды и таб, которые вы показали игрокам, свои потоки и
соединения, несохранённые файлы. Задачи, которые вы поставили в планировщик сущностей Paper напрямую
(`entity.getScheduler()`), ядро не отслеживает: используйте `runForPlayer`.

Не оставляйте ссылок на свои классы в чужих местах: статические поля других плагинов, `ThreadLocal` чужих потоков,
`ConfigurationSerialization.registerClass` — иначе выключенная система останется в памяти.

Если `enable()` бросил исключение, система остаётся выключенной, всё, что она успела зарегистрировать, убирается,
а ошибка со стеком пишется в консоль (и видна в `/amazake system info <имя>`).

## Сборка .azs Gradle

`build.gradle.kts` отдельного проекта:

```kotlin
plugins {
    java
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Скачайте amazake-systems-api-1.21.11-N.jar из релиза Amazake в libs/.
    compileOnly(files("libs/amazake-systems-api-1.21.11-N.jar"))
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

// Обычный jar, только с расширением .azs.
tasks.jar {
    archiveExtension = "azs"
    archiveFileName = "MySystem.azs"
}
```

`system.yml` и `config.yml` положите в `src/main/resources/`. `./gradlew jar` соберёт `build/libs/MySystem.azs`:
скопируйте его в `systems/` сервера и выполните `/amazake system enable MySystem`. Обновить включённую систему:
`/amazake system disable MySystem`, заменить файл, `/amazake system enable MySystem`.

Библиотеки, которых нет в Paper, кладите внутрь `.azs` (shadow jar), лучше с переносом пакетов (relocate), чтобы
они не столкнулись с библиотеками плагинов. Классы ядра, Bukkit и Paper берутся из сервера: свои копии API в
`.azs` не кладите.

## Файлы в папке systems/

| Файл | Что это |
|---|---|
| `<Имя>.azs` | Сама система; имя файла может быть любым, система определяется по `system.yml` |
| `<Имя>/` | Её настройки и данные |
| `systems.yml` | `enabled: [...]` — какие системы включены; переписывается при каждом enable/disable, при запуске включаются все из списка |
| `.installed` | Встроенные системы, которые ядро уже распаковало; удалённая встроенная система обратно не распаковывается, пока её имя здесь |

Если два файла содержат систему с одним именем, используется первый по алфавиту, о втором пишется предупреждение.
