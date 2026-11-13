#!/usr/bin/env bash
# shellcheck disable=SC2029 # команды для сервера собираются здесь, пути экранированы printf %q
# Собирает систему и заливает .azs на сервер по SSH, не останавливая сервер.
#
#   SERVER=user@host SERVER_DIR=/абсолютный/путь/к/серверу ./deploy.sh
#
# Файл пишется во временный и переименовывается поверх старого (mv): работающая система дочитывает свой старый
# файл, а новый подхватывается командой /amazake system restart <Имя>.
# Чтобы команда ушла в консоль сервера сама, укажите, где он запущен:
#   CONSOLE=screen:<имя сессии>   (сервер в screen)
#   CONSOLE=tmux:<имя сессии>     (сервер в tmux)
# Иначе скрипт напечатает, что ввести в консоли или в игре.
# AMAZAKE_API_JAR=путь — собрать против своего jar API систем (иначе он скачивается из последнего релиза).
set -euo pipefail
: "${SERVER:?укажите SERVER=user@host}"
: "${SERVER_DIR:?укажите SERVER_DIR=/абсолютный/путь/к/папке/сервера (там, где plugins/ и systems/)}"
case "$SERVER_DIR" in
  /*) ;;
  *) echo "SERVER_DIR должен быть абсолютным путём (без ~): $SERVER_DIR" >&2; exit 1 ;;
esac
api_jar=""
if [ -n "${AMAZAKE_API_JAR:-}" ]; then
  api_jar=$(realpath "$AMAZAKE_API_JAR")
fi
cd "$(dirname "$0")"
name=$(sed -n 's/^rootProject.name *= *"\(.*\)"/\1/p' settings.gradle.kts)
if ! [[ "$name" =~ ^[A-Za-z0-9_-]{1,32}$ ]]; then
  echo "Имя системы в settings.gradle.kts («$name») должно быть из латинских букв, цифр, _ и -, до 32 символов" >&2
  exit 1
fi
if [ -n "$api_jar" ]; then
  ./gradlew azs -PamazakeApiJar="$api_jar"
else
  ./gradlew azs
fi
file="build/libs/$name.azs"
systems="$SERVER_DIR/systems"
upload="$systems/.$name.azs.upload"
target="$systems/$name.azs"
echo "Заливаю $file -> $SERVER:$target"
# Пути на сервере экранируются здесь (printf %q), имя системы проверено выше.
ssh "$SERVER" "mkdir -p $(printf '%q' "$systems")"
ssh "$SERVER" "cat > $(printf '%q' "$upload")" < "$file"
ssh "$SERVER" "mv -f $(printf '%q' "$upload") $(printf '%q' "$target")"
command="amazake system restart $name"
case "${CONSOLE:-}" in
  screen:*)
    ssh "$SERVER" "screen -S $(printf '%q' "${CONSOLE#screen:}") -p 0 -X stuff $(printf '%q' "$command^M")"
    echo "В консоль отправлено: $command" ;;
  tmux:*)
    ssh "$SERVER" "tmux send-keys -t $(printf '%q' "${CONSOLE#tmux:}") $(printf '%q' "$command") Enter"
    echo "В консоль отправлено: $command" ;;
  "") echo "Готово. Введите в консоли сервера: $command   (или в игре: /$command)" ;;
  *) echo "CONSOLE=${CONSOLE} не понят (нужно screen:<сессия> или tmux:<сессия>). Введите в консоли: $command" ;;
esac
