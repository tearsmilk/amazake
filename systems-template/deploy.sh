#!/usr/bin/env bash
# Собирает систему и заливает .azs на сервер по SSH, не останавливая сервер.
#
#   SERVER=user@host SERVER_DIR=/путь/к/серверу ./deploy.sh
#
# Файл пишется во временный и переименовывается поверх старого (mv): работающая система дочитывает свой старый
# файл, а новый подхватывается командой /amazake system restart <Имя>.
# Чтобы команда ушла в консоль сервера сама, укажите, где она запущена:
#   CONSOLE=screen:<имя сессии>   (сервер в screen)
#   CONSOLE=tmux:<имя сессии>     (сервер в tmux)
# Иначе скрипт напечатает, что ввести в консоли или в игре.
# shellcheck disable=SC2029 # пути намеренно подставляются на этой стороне
set -euo pipefail
cd "$(dirname "$0")"
: "${SERVER:?укажите SERVER=user@host}"
: "${SERVER_DIR:?укажите SERVER_DIR=/путь/к/папке/сервера (там, где plugins/ и systems/)}"
name=$(sed -n 's/^rootProject.name *= *"\(.*\)"/\1/p' settings.gradle.kts)
./gradlew azs ${AMAZAKE_API_JAR:+-PamazakeApiJar="$AMAZAKE_API_JAR"}
file="build/libs/$name.azs"
echo "Заливаю $file -> $SERVER:$SERVER_DIR/systems/$name.azs"
ssh "$SERVER" "mkdir -p '$SERVER_DIR/systems'"
scp -q "$file" "$SERVER:$SERVER_DIR/systems/.$name.azs.upload"
ssh "$SERVER" "mv -f '$SERVER_DIR/systems/.$name.azs.upload' '$SERVER_DIR/systems/$name.azs'"
command="amazake system restart $name"
case "${CONSOLE:-}" in
  screen:*) ssh "$SERVER" "screen -S '${CONSOLE#screen:}' -p 0 -X stuff '$command\r'"; echo "В консоль отправлено: $command" ;;
  tmux:*) ssh "$SERVER" "tmux send-keys -t '${CONSOLE#tmux:}' '$command' Enter"; echo "В консоль отправлено: $command" ;;
  *) echo "Готово. Введите в консоли сервера: $command   (или в игре: /$command)" ;;
esac
