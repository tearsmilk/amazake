# Amazake

Серверное ядро Minecraft **1.21.11**: форк [Paper](https://github.com/PaperMC/Paper). Цель — быстрое и удобное ядро для серверов Hoka в духе Leaf, но со своим набором изменений.

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

## Сборка

Нужны JDK 21 и git.

```bash
./gradlew applyAllPatches
```

```bash
./gradlew createMojmapPaperclipJar
```

Готовый сервер: `amazake-server/build/libs/amazake-paperclip-1.21.11-R0.1-SNAPSHOT-mojmap.jar`. Он запускается как обычный Paper.

То же самое собирает GitHub Actions на каждый пуш: jar лежит в Artifacts прогона «Build Amazake».

## Как вносить изменения

1. `./gradlew applyAllPatches`. Появятся `paper-server/`, `paper-api/` и `amazake-server/src/minecraft/`. Это git-репозитории с кодом.
2. Правите код там и делаете коммиты внутри этих папок (один коммит — один патч).
3. `./gradlew rebuildAllServerPatches` (для API — `rebuildAllApiPatches`). Коммиты превратятся в файлы в `*-patches/`.
4. Коммитите файлы патчей в этот репозиторий.

Отмечайте изменения в коде Minecraft и Paper комментариями `// Amazake - что и зачем`, а блоки — `// Amazake start - …` и `// Amazake end - …`. Так при обновлении Paper сразу видно, где наш код.

Проверить локально: `./gradlew runDevServer`.

## Лицензия

Paper и его патчи распространяются под GPLv3 (часть кода — под MIT). Патчи Amazake — тоже GPLv3. Если раздаёте собранный jar, нужно открыть исходники этого репозитория.
