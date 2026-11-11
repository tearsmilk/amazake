# Amazake

Серверное ядро Minecraft **1.21.11**: форк [Paper](https://github.com/PaperMC/Paper). Цель — многопоточное, быстрое и удобное ядро для любых серверов: работают обычные плагины Paper, а каждая многопоточная фича включается переключателем в `amazake.yml`.

## Устройство

Как у всех форков Paper (Purpur, Leaf…): в репозитории **только наши патчи**. Paper и код Minecraft не хранятся. [paperweight](https://github.com/PaperMC/paperweight) скачивает Paper на закреплённом коммите, Minecraft — у Mojang, накладывает наши патчи и собирает paperclip-jar. При запуске jar сам скачивает ванильный сервер.

| Путь | Что |
|---|---|
| `gradle.properties` | Версия Minecraft и коммит Paper (`paperCommit`) |
| `amazake-server/minecraft-patches/` | Патчи к коду Minecraft: `sources/` — по файлу, `features/` — крупные изменения коммитами |
| `amazake-server/paper-patches/` | Патчи к серверной части Paper (CraftBukkit и сам Paper) |
| `amazake-server/src/` | Наши собственные классы сервера |
| `amazake-api/paper-patches/` | Патчи к API (то, что видят плагины) |
| `amazake-*/build.gradle.kts.patch` | Сборка: подключение форка, ребрендинг |
| `build-data/amazake.at` | Access transformers: открыть приватные поля и методы Minecraft |

## Скачать

Последняя сборка: **https://github.com/tearsmilk/amazake/releases/latest** (`amazake-1.21.11-N.jar`).

Запускается как обычный Paper: положите jar в папку сервера и запустите `java -jar amazake-1.21.11-N.jar --nogui`. Ванильный сервер jar докачивает сам.

## Сборка

Нужны JDK 21 и git.

```bash
./gradlew applyAllPatches
```

```bash
./gradlew createMojmapPaperclipJar
```

Готовый сервер появится в `amazake-server/build/libs/amazake-paperclip-1.21.11-R0.1-SNAPSHOT-mojmap.jar`.

Каждый пуш в `main` GitHub Actions собирает сам. Перед публикацией собранный сервер запускается, и проверяются `/version`, `/amazake`, `/spark` и `amazake.yml`. Если всё прошло, jar выкладывается в Releases.

## Команды и права

| Команда | Право | Что делает |
|---|---|---|
| `/amazake version` | `amazake.command` | Версия сборки |
| `/amazake reload` | `amazake.command.reload` | Перечитать `amazake.yml` |
| `/amazake profile [секунды]` | `amazake.command.profile` | Замерить нагрузку сервера и получить отчёт (см. «Профайлер») |
| `/amazake profile stop` | `amazake.command.profile` | Закончить замер раньше и сразу получить отчёт |
| `/spark …` | `spark` | Встроенный профайлер spark из Paper (`/spark profiler`, `/spark tps`, `/spark health`) |

По умолчанию эти права есть только у OP. Из консоли команды работают всегда.

Для подкоманд `/amazake …` нужно ещё и общее право `amazake.command`.

## Профайлер

`/amazake profile [секунды]` замеряет сервер (по умолчанию 30 с, не дольше `profiler.max-seconds`): загрузку каждого ядра, CPU сервера по потокам и группам потоков (главный поток, генерация и загрузка чанков, сеть, GC, JIT, плагины…), TPS и MSPT, лаги дольше 50 мс, память, сборки мусора, игроков, сущности и чанки. Одновременно идёт только один замер; замер работает в своём потоке и не нагружает главный.

Куда попадает отчёт:

- **На ваш сайт профайлера** — если в `amazake.yml` указан `profiler.upload-url` (например `https://profiler.example.com`). Сервер отправляет отчёт на `<адрес>/api/reports` и присылает кликабельную ссылку вида `https://profiler.example.com/r/<id>`. Если сайт просит токен, впишите его в `profiler.upload-token`.
- **В файл** — если `profiler.upload-url` пустой или отправить не удалось: `profiles/amazake-profile-<дата-время>.json` в папке сервера. Этот файл можно просто перетащить на страницу сайта профайлера, и он откроется так же, как по ссылке.

Настройки в `amazake.yml` (применяются через `/amazake reload`):

| Настройка | По умолчанию | Что |
|---|---|---|
| `profiler.upload-url` | `""` | Адрес сайта профайлера; пусто — только файлы |
| `profiler.upload-token` | `""` | Токен для сайта (заголовок `X-Amazake-Token`) |
| `profiler.default-seconds` | `30` | Длина замера без аргумента |
| `profiler.max-seconds` | `600` | Самый долгий замер |
| `profiler.interval-ms` | `1000` | Интервал между выборками, не меньше 250 мс |

Формат отчёта и API сайта описаны в [`docs/profiler-format.md`](docs/profiler-format.md).

## Как вносить изменения

1. `./gradlew applyAllPatches`. Появятся `paper-server/`, `paper-api/` и `amazake-server/src/minecraft/` — git-репозитории с кодом.
2. Правите код там и делаете коммиты внутри этих папок (один коммит — один патч).
3. Пересобираете патчи. Для API и одиночных файлов Paper — `./gradlew rebuildPaperPatches`. Полный список задач пересборки серверной части выводит `./gradlew tasks --all | grep -i rebuild`.
4. Коммитите изменённые файлы `*-patches/` в этот репозиторий.

Собственные классы Amazake (не патчи) лежат в `amazake-server/src/main/java/ink/hoka/amazake/`.

Отмечайте изменения в коде Minecraft и Paper комментариями `// Amazake - что и зачем`, а блоки — `// Amazake start - …` и `// Amazake end - …`. Так при обновлении Paper сразу видно, где наш код.

Проверить локально: `./gradlew runDevServer`.

## Лицензия

Paper и его патчи распространяются под GPLv3 (часть кода — под MIT). Патчи Amazake — тоже GPLv3. Если раздаёте собранный jar, нужно открыть исходники этого репозитория.
