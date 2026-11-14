# AmazakeCore — набор модулей ядра

AmazakeCore — это система Amazake (файл `.azs`), которая объединяет несколько модулей сервера в одном месте.
Каждый модуль включается и настраивается в `config.yml`, а менять настройки, включать и выключать модули можно
**без перезапуска сервера**. Файл поставляется **обфусцированным** (ProGuard): в нём имена классов и методов
заменены, декомпиляция даёт нечитаемый код. Полностью «не достать» байткод, который запускает сервер, нельзя ни у
одной системы или плагина — но выкачать и спокойно читать исходники не выйдет.

Хранилище — встроенная база H2 (один файл `systems/AmazakeCore/amazakecore.mv.db`, чистая Java, без нативного
кода), поэтому система корректно выгружается и перезагружается на лету.

## Установка

1. Нужна версия ядра Amazake с поддержкой систем (api-version 2, build-21 и новее).
2. Скачайте `AmazakeCore.azs` из [релиза](https://github.com/tearsmilk/amazake/releases/latest) и положите в папку
   `systems/` сервера (рядом с `plugins/` и мирами).
3. В консоли: `amazake system enable AmazakeCore` (или `/amazake system enable AmazakeCore` в игре).
4. Настройки появятся в `systems/AmazakeCore/config.yml`. После правок — `/amazakecore reload`.

Обновление: положите новый `AmazakeCore.azs` в `systems/` и выполните `/amazake system restart AmazakeCore`.

## Команда управления

`/amazakecore` (`/acore`), право `amazakecore.admin`:

| Команда | Что делает |
|---|---|
| `/amazakecore modules` | Список модулей: включён/выключен и описание |
| `/amazakecore reload` | Перечитать `config.yml` и применить на лету (вкл/выкл модули, обновить настройки) |
| `/amazakecore module enable <id>` | Включить модуль без рестарта |
| `/amazakecore module disable <id>` | Выключить модуль без рестарта |

В `config.yml` каждый модуль лежит в своей секции `modules.<id>` с ключом `enabled` и своими настройками.

## Модули

### essentials — бытовые команды
- **Дома:** `/sethome [имя]`, `/home [имя]`, `/delhome [имя]`, `/homes`. Лимит домов — `max-homes` (право
  `amazakecore.essentials.homes.unlimited` снимает лимит).
- **Варпы:** `/setwarp <имя>` (право `amazakecore.essentials.setwarp`), `/delwarp`, `/warp`, `/warps`.
- **Телепорт-запросы:** `/tpa`, `/tpahere`, `/tpaccept`, `/tpdeny`, `/tpcancel`. Таймаут — `tpa.timeout-seconds`.
- **Режим игры:** `/gm <0|1|2|3|s|c|a|sp> [игрок]`, а также `/gms /gmc /gma /gmsp` и `/gm0../gm3`
  (`amazakecore.essentials.gamemode`, для других игроков — `.others`).
- **Инвентари:** `/invsee <игрок>` (`amazakecore.essentials.invsee`), `/ec [игрок]` — эндер-сундук свой или чужой
  (`amazakecore.essentials.enderchest.others`).
- Любую подфичу можно выключить в конфиге, изменения применяются по `/amazakecore reload`.

### groups — свои группы и права
Собственная система прав (без LuckPerms): группы с наследованием, весами, префиксами и суффиксами; личные права
игроков. Хранение в базе. Префиксы/суффиксы отдаются модулю чата.
- `/group list|create|delete|setweight|setprefix|setsuffix|perm add/remove|parent add/remove|info` —
  право `amazakecore.groups.admin`.
- `/groupuser <игрок> group set|add|remove <группа> | perm add|remove <право> | info` (работает и для оффлайн-игроков).
- Поддерживаются wildcard-права `*` и `a.b.*`.

### holograms — голограммы
Плавающий текст над землёй; движок на выбор в конфиге и на лету:
- `text_display` — одна сущность TextDisplay (новые дисплеи 1.19.4+, многострочно);
- `armor_stand` — классические невидимые стойки, по стойке на строку;
- `item_display` — летающий предмет (+ текст, если заданы строки).
- `/hologram create|delete|list|near|addline|setline|removeline|lines|movehere|tp|setengine|setitem|reload` —
  право `amazakecore.holograms.admin`. Строки — MiniMessage. Голограммы сохраняются и восстанавливаются при запуске.

### npc — скины на NPC (Citizens)
Ставит игроков-скины на NPC плагина **Citizens** (подключение через рефлексию — жёсткой зависимости нет). Если
Citizens не установлен, модуль остаётся включённым, но бездействует и пишет об этом в консоль.
- `/npccore skin <игрок>` — скин выбранного NPC; `/npccore skin id <id> <игрок>` — по номеру NPC. Право
  `amazakecore.npc.admin`.

### chat — оформление чата
- Каналы **global** и **local** (радиус `local.radius`), формат — MiniMessage с тегами `<prefix> <suffix>
  <player> <group> <message> <world>`. Префикс/суффикс берутся из модуля groups, если он включён.
- Принудительный глобал символом `global-prefix` (по умолчанию `!`), локал — `local-prefix`.
- Задержка между сообщениями `cooldown-seconds` (право `amazakecore.chat.cooldown.bypass` снимает).

## Как всё устроено внутри (для доработок)

AmazakeCore собирается отдельным проектом `amazake-core-system/` (как плагин), против paper-api и
`amazake-systems-api`. Каждый модуль — класс `core.amazake.core.modules.<id>.*Module`, реализующий интерфейс
`core.amazake.core.framework.Module` (`onEnable` / `onDisable` / `onConfigReload`). Фреймворк (`ModuleContext`,
`Database`, `CoreServices`, `GroupService`) даёт модулю общую БД, реестр сервисов и регистрацию команд/слушателей/
задач, которые ядро снимает автоматически при выключении. Потоки — по правилам систем Amazake (см.
[docs/systems.md](systems.md), раздел «Потоки»): работа с игроком — на его потоке, с миром — на потоке региона,
база — только через `context.database()`.
