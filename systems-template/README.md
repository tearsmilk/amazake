# Шаблон системы Amazake

Готовый проект системы (`.azs`) для ядра [Amazake](https://github.com/tearsmilk/amazake). Полный гайд —
[docs/systems.md](https://github.com/tearsmilk/amazake/blob/main/docs/systems.md).

## Быстрый старт

1. Скопируйте папку `systems-template/` (или скачайте `amazake-system-template.zip` из последнего релиза).
2. Переименуйте систему:
   - `settings.gradle.kts`: `rootProject.name = "МояСистема"` — это имя системы и файла `.azs`;
   - пакет `core.amazake.systems.example` → `core.amazake.systems.<ваша_система>` (папка в `src/main/java` и
     `src/test/java`);
   - `src/main/resources/system.yml`: `main` — полное имя вашего главного класса, `description`, `authors`.
3. Соберите: `./gradlew azs` (Java 21). Получится `build/libs/<Имя>.azs`. API систем при первой сборке скачается
   из последнего релиза Amazake в `libs/`; после выхода нового ядра — `./gradlew refreshSystemsApi`.
4. Залейте на сервер:
   - вручную: положите `.azs` в папку `systems/` сервера и введите `/amazake system enable <Имя>`
     (новая система) или `/amazake system restart <Имя>` (обновление уже включённой);
   - скриптом: `SERVER=user@host SERVER_DIR=/путь/к/серверу ./deploy.sh` (с `CONSOLE=screen:<сессия>` или
     `CONSOLE=tmux:<сессия>` скрипт сам отправит команду в консоль).

## Что в примере

| Файл | Что делает |
|---|---|
| `ExampleSystem.java` | Главный класс: `enable`/`disable`/`reload`, приветствие при входе, actionbar каждому игроку в его потоке |
| `ExampleCommand.java` | Команда `/example` (и `/example ping`) с автодополнением |
| `Settings.java` | Неизменяемый снимок `config.yml`, разбор с проверкой значений |
| `config.yml` | Настройки по умолчанию (копируются в `systems/<Имя>/config.yml` при первом включении) |
| `system.yml` | Описание системы |
| `SettingsTest.java` | Юнит-тест разбора настроек (`./gradlew test`) |
