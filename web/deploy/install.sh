#!/usr/bin/env bash
# Installs the Amazake profiler site (web/server + web/static) on a Debian/Ubuntu VPS
# next to programs that already run there (e.g. a Discord bot) without disturbing them.
#
#   sudo bash web/deploy/install.sh --domain profiler.example.com --email you@example.com [--dry-run]
#
# What it may change (and nothing else):
#   - system user/group amazake-web
#   - /opt/amazake-web, /etc/amazake-web, /var/lib/amazake-web
#   - /etc/systemd/system/amazake-web.service (and only this service is ever restarted)
#   - one nginx site file of its own (+ symlink), followed by "nginx -t && systemctl reload nginx".
#     The file is named zz-amazake-web.conf so it loads last and never becomes nginx's default
#     server for a port another site already uses (checked before and after writing it).
#   - with confirmation: "apt-get install" of nginx and/or certbot when they are missing
#   - a Let's Encrypt certificate for --domain (certbot certonly --webroot, never edits nginx files)
# It never upgrades or removes packages, never restarts/stops other services, never enables an
# nginx it did not install, never touches docker, pm2, the firewall, /etc/hosts, DNS, system Python
# or other nginx/caddy/apache files.

# SC2001: "sed 's/^/  /'" is used on purpose to indent multi-line output.
# shellcheck disable=SC2001
set -Eeuo pipefail
umask 022

# ---------------------------------------------------------------------------
# Constants (our footprint)
# ---------------------------------------------------------------------------
readonly APP_NAME="amazake-web"
readonly SERVICE_USER="amazake-web"
readonly APP_DIR="/opt/amazake-web"
readonly CONF_DIR="/etc/amazake-web"
readonly ENV_FILE="$CONF_DIR/amazake-web.env"
readonly TOKENS_FILE="$CONF_DIR/tokens"
readonly INSTALL_CONF="$CONF_DIR/install.conf"
readonly STATE_DIR="/var/lib/amazake-web"
readonly DATA_DIR="$STATE_DIR/data"
readonly ACME_DIR="$STATE_DIR/acme"
readonly UNIT_FILE="/etc/systemd/system/amazake-web.service"
readonly MARKER="managed by amazake-web installer"
readonly LOCK_DIR="/run/amazake-web-install.lock"
readonly USER_COMMENT="Amazake profiler site"
# Our nginx file name: "zz-" makes it load after the owner's sites in the same include folder.
readonly NGINX_NAME="zz-amazake-web.conf"
readonly NGINX_LEGACY_NAME="amazake-web.conf"
# With the stock server_names_hash_bucket_size (64) nginx -t fails for names this long or longer.
readonly LONG_DOMAIN=48
PYTHON="/usr/bin/python3"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR

# ---------------------------------------------------------------------------
# Options
# ---------------------------------------------------------------------------
DOMAIN=""
EMAIL=""
PORT=""
SOURCE_DIR=""
TOKEN=""
GENERATE_TOKEN=0
REMOVE_TOKEN=0
WANT_HTTPS=""        # "" = take from the previous install (default 1)
FORCE_NO_NGINX=""    # "" = take from the previous install (default 0)
DRY_RUN=0
ASSUME_YES=0
RETENTION_DAYS=""
RATE_LIMIT=""

# ---------------------------------------------------------------------------
# State filled by preflight
# ---------------------------------------------------------------------------
HAVE_SYSTEMD=0
IS_ROOT=0
BLOCKERS=()
WARNINGS=()
OWNER_80="none"
OWNER_443="none"
DESC_80=""
DESC_443=""
PORT_STATE="free"     # free | ours | busy | unknown
WEB_MODE=""           # nginx | external
NEED_APT=()           # packages to apt-get install
NGINX_INSTALLED=0
NGINX_ACTIVE=0
NGINX_START=0         # nginx installed but stopped, nothing on 80/443: start it
NGINX_ENABLED=""      # systemctl is-enabled nginx (enabled | disabled | masked | ...)
NGINX_FROM_APT=0      # this run apt-installed nginx (only then we may enable it)
NGINX_SITE=""
NGINX_LINK=""
LEGACY_NGINX=()       # our files under the old name amazake-web.conf (removed on apply)
USER_OURS=0           # amazake-web user exists and was created by this installer
CREATED_USER=0        # value written to install.conf
HAVE_V6=0
CERT_EXISTS=0
CERTBOT_BIN=""
PREV_DOMAIN=""
PREV_PORT=""
PREV_NGINX_SITE=""
PREV_NGINX_LINK=""
PREV_CHOICES=()       # options remembered from the previous install, for the message

# Progress for the error trap
DONE_STEPS=()
CURRENT_STEP="подготовка"
TMP_DIR=""

# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------
if [[ -t 1 ]]; then
    C_B=$'\e[1m'; C_G=$'\e[32m'; C_Y=$'\e[33m'; C_R=$'\e[31m'; C_C=$'\e[36m'; C_0=$'\e[0m'
else
    C_B=""; C_G=""; C_Y=""; C_R=""; C_C=""; C_0=""
fi

say()     { printf '%s\n' "$*"; }
header()  { printf '\n%s== %s ==%s\n' "$C_B" "$*" "$C_0"; }
ok()      { printf '  %s[ok]%s %s\n' "$C_G" "$C_0" "$*"; }
info()    { printf '  %s\n' "$*"; }
warn()    { printf '  %s[!]%s %s\n' "$C_Y" "$C_0" "$*"; WARNINGS+=("$*"); }
fail()    { printf '%s[ошибка]%s %s\n' "$C_R" "$C_0" "$*" >&2; }
die()     { fail "$*"; report_progress; exit 1; }
blocker() { printf '  %s[стоп]%s %s\n' "$C_R" "$C_0" "$*"; BLOCKERS+=("$*"); }
done_step() {
    if (( DRY_RUN )); then ok "(dry-run) $*"; return 0; fi
    DONE_STEPS+=("$*"); ok "$*"
}

quote_cmd() {
    local out="" a
    for a in "$@"; do out+="$(printf '%q' "$a") "; done
    printf '%s' "${out% }"
}

# Runs a command, or only prints it with --dry-run.
run() {
    if (( DRY_RUN )); then
        printf '  %s[dry-run]%s %s\n' "$C_C" "$C_0" "$(quote_cmd "$@")"
    else
        "$@"
    fi
}

usage() {
    cat <<'EOF'
Установка сайта профайлера Amazake на VPS (Debian/Ubuntu), не задевая другие программы.

  sudo bash install.sh --domain ДОМЕН [--email ПОЧТА] [параметры]

Обязательно:
  --domain ДОМЕН        домен сайта, например profiler.example.com (A-запись должна вести на этот VPS)

Параметры:
  --email ПОЧТА         почта для Let's Encrypt (нужна для HTTPS)
  --no-https            не получать сертификат, только HTTP
  --https               вернуть HTTPS, если прошлая установка была с --no-https
  --port ПОРТ           внутренний порт сайта на 127.0.0.1 (по умолчанию 8787)
  --no-nginx            не трогать nginx вообще, только поставить сервис сайта
  --nginx               снова разрешить nginx, если прошлая установка была с --no-nginx

  При повторном запуске --email, --no-https, --no-nginx, порт, срок хранения и лимит
  берутся из прошлой установки, если их не указать.
  --token ТОКЕН         включить токен для загрузки отчётов (записать этот токен)
  --generate-token      создать случайный токен, если его ещё нет (показывается один раз)
  --remove-token        отключить токен (загрузка без токена)
  --retention-days N    сколько дней хранить отчёты (по умолчанию 30, 0 = всегда)
  --rate-limit N        загрузок в час с одного IP (по умолчанию 30, 0 = без ограничения)
  --source ПАПКА        откуда брать код (корень репозитория amazake; по умолчанию тот, где лежит скрипт)
  --dry-run             только показать, что будет сделано, ничего не менять
  --yes                 не спрашивать подтверждение
  -h, --help            эта справка
EOF
}

# ---------------------------------------------------------------------------
# Error handling and cleanup
# ---------------------------------------------------------------------------
INSTALL_STARTED=0

report_progress() {
    (( INSTALL_STARTED )) || return 0
    INSTALL_STARTED=0
    if (( ${#DONE_STEPS[@]} )); then
        printf 'Уже сделано:\n' >&2
        printf '  - %s\n' "${DONE_STEPS[@]}" >&2
    else
        printf 'Ничего не было изменено.\n' >&2
    fi
    printf 'Остальное НЕ сделано. Другие сервисы (в т.ч. бот) не перезапускались и не останавливались.\n' >&2
    printf 'Можно исправить причину и запустить install.sh ещё раз, или удалить всё: sudo bash %s/uninstall.sh\n' "$SCRIPT_DIR" >&2
}

on_error() {
    local code="$1" line="$2" cmd="$3"
    trap - ERR
    printf '\n' >&2
    fail "шаг «$CURRENT_STEP» не удался (строка $line, код $code): $cmd"
    INSTALL_STARTED=1
    report_progress
    exit "$code"
}
trap 'on_error $? $LINENO "$BASH_COMMAND"' ERR

LOCK_HELD=0
cleanup() {
    if [[ -n "$TMP_DIR" && -d "$TMP_DIR" ]]; then rm -rf -- "$TMP_DIR"; fi
    if (( LOCK_HELD )); then rm -rf -- "$LOCK_DIR"; fi
}

# A directory lock (not flock on an fd: services started from here must not inherit it).
acquire_lock() {
    local pid
    if mkdir "$LOCK_DIR" 2>/dev/null; then
        LOCK_HELD=1; printf '%s\n' "$$" > "$LOCK_DIR/pid"; return 0
    fi
    pid="$(cat "$LOCK_DIR/pid" 2>/dev/null || true)"
    if [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null; then
        die "install.sh уже запущен (pid $pid) в другом окне"
    fi
    rm -rf -- "$LOCK_DIR"
    mkdir "$LOCK_DIR" || die "не удалось создать $LOCK_DIR"
    LOCK_HELD=1; printf '%s\n' "$$" > "$LOCK_DIR/pid"
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
# Arguments
# ---------------------------------------------------------------------------
need_value() {
    if [[ $# -lt 2 || -z "$2" || "$2" == --* ]]; then
        fail "параметру $1 нужно значение"; usage >&2; exit 2
    fi
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --domain)          need_value "$@"; DOMAIN="$2"; shift 2 ;;
            --domain=*)        DOMAIN="${1#*=}"; shift ;;
            --email)           need_value "$@"; EMAIL="$2"; shift 2 ;;
            --email=*)         EMAIL="${1#*=}"; shift ;;
            --port)            need_value "$@"; PORT="$2"; shift 2 ;;
            --port=*)          PORT="${1#*=}"; shift ;;
            --source)          need_value "$@"; SOURCE_DIR="$2"; shift 2 ;;
            --source=*)        SOURCE_DIR="${1#*=}"; shift ;;
            --token)           need_value "$@"; TOKEN="$2"; shift 2 ;;
            --token=*)         TOKEN="${1#*=}"; shift ;;
            --retention-days)  need_value "$@"; RETENTION_DAYS="$2"; shift 2 ;;
            --retention-days=*) RETENTION_DAYS="${1#*=}"; shift ;;
            --rate-limit)      need_value "$@"; RATE_LIMIT="$2"; shift 2 ;;
            --rate-limit=*)    RATE_LIMIT="${1#*=}"; shift ;;
            --generate-token)  GENERATE_TOKEN=1; shift ;;
            --remove-token)    REMOVE_TOKEN=1; shift ;;
            --no-https)        WANT_HTTPS=0; shift ;;
            --https)           WANT_HTTPS=1; shift ;;
            --no-nginx)        FORCE_NO_NGINX=1; shift ;;
            --nginx)           FORCE_NO_NGINX=0; shift ;;
            --dry-run)         DRY_RUN=1; shift ;;
            -y|--yes)          ASSUME_YES=1; shift ;;
            -h|--help)         usage; exit 0 ;;
            *) fail "неизвестный параметр: $1"; usage >&2; exit 2 ;;
        esac
    done

    if [[ -z "$DOMAIN" ]]; then
        fail "не указан --domain"; usage >&2; exit 2
    fi
    DOMAIN="${DOMAIN,,}"
    DOMAIN="${DOMAIN%.}"
    local domain_re='^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]([a-z0-9-]{0,61}[a-z0-9])?$'
    local email_re='^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$'
    local token_re='^[A-Za-z0-9._~+/=-]{16,200}$'
    if [[ ${#DOMAIN} -gt 253 ]] || ! [[ "$DOMAIN" =~ $domain_re ]]; then
        die "--domain «$DOMAIN» не похож на домен (нужно что-то вроде profiler.example.com, без http:// и без IP)"
    fi
    if [[ -n "$EMAIL" ]] && ! [[ "$EMAIL" =~ $email_re ]]; then
        die "--email «$EMAIL» не похож на адрес почты"
    fi
    if [[ -n "$PORT" ]]; then
        if ! [[ "$PORT" =~ ^[0-9]{1,5}$ ]] || (( 10#$PORT < 1024 || 10#$PORT > 65535 )); then
            die "--port должен быть числом от 1024 до 65535"
        fi
        PORT=$((10#$PORT))
    fi
    local n
    for n in RETENTION_DAYS RATE_LIMIT; do
        if [[ -n "${!n}" ]] && ! [[ "${!n}" =~ ^[0-9]{1,6}$ ]]; then
            die "--$(tr '[:upper:]_' '[:lower:]-' <<<"$n") должен быть целым числом >= 0"
        fi
    done
    if [[ -n "$TOKEN" ]]; then
        if (( GENERATE_TOKEN || REMOVE_TOKEN )); then
            die "--token нельзя сочетать с --generate-token или --remove-token"
        fi
        if ! [[ "$TOKEN" =~ $token_re ]]; then
            die "--token: от 16 до 200 символов, только латиница, цифры и . _ ~ + / = -"
        fi
    fi
    if (( GENERATE_TOKEN && REMOVE_TOKEN )); then
        die "--generate-token и --remove-token вместе не имеют смысла"
    fi
    if [[ -n "$SOURCE_DIR" ]]; then
        SOURCE_DIR="$(cd "$SOURCE_DIR" 2>/dev/null && pwd)" || die "--source: папка не найдена"
    else
        SOURCE_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
    fi
}

# Reads KEY=value from one of our own files without executing it.
conf_get() {
    local file="$1" key="$2"
    [[ -r "$file" ]] || return 0
    sed -n "s/^${key}=//p" "$file" | tail -n 1 | sed -e 's/^"//' -e 's/"$//'
}

load_previous_install() {
    PREV_DOMAIN="$(conf_get "$INSTALL_CONF" DOMAIN)"
    PREV_PORT="$(conf_get "$INSTALL_CONF" PORT)"
    PREV_NGINX_SITE="$(conf_get "$INSTALL_CONF" NGINX_SITE)"
    PREV_NGINX_LINK="$(conf_get "$INSTALL_CONF" NGINX_LINK)"
    local prev
    if [[ -z "$EMAIL" ]]; then
        prev="$(conf_get "$INSTALL_CONF" EMAIL)"
        if [[ -n "$prev" && "$prev" =~ ^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$ ]]; then
            EMAIL="$prev"; PREV_CHOICES+=("--email $prev")
        fi
    fi
    if [[ -z "$WANT_HTTPS" ]]; then
        if [[ "$(conf_get "$INSTALL_CONF" NO_HTTPS)" == "1" ]]; then
            WANT_HTTPS=0; PREV_CHOICES+=("--no-https")
        else
            WANT_HTTPS=1
        fi
    fi
    if [[ -z "$FORCE_NO_NGINX" ]]; then
        if [[ "$(conf_get "$INSTALL_CONF" NO_NGINX)" == "1" ]]; then
            FORCE_NO_NGINX=1; PREV_CHOICES+=("--no-nginx")
        else
            FORCE_NO_NGINX=0
        fi
    fi
    if [[ -z "$PORT" ]]; then
        if [[ "$PREV_PORT" =~ ^[0-9]+$ ]]; then PORT="$PREV_PORT"; else PORT=8787; fi
    fi
    if [[ -z "$RETENTION_DAYS" ]]; then
        RETENTION_DAYS="$(conf_get "$ENV_FILE" AMAZAKE_WEB_RETENTION_DAYS)"
        [[ "$RETENTION_DAYS" =~ ^[0-9]+$ ]] || RETENTION_DAYS=30
    fi
    if [[ -z "$RATE_LIMIT" ]]; then
        RATE_LIMIT="$(conf_get "$ENV_FILE" AMAZAKE_WEB_RATE_LIMIT)"
        [[ "$RATE_LIMIT" =~ ^[0-9]+$ ]] || RATE_LIMIT=30
    fi
}

# ---------------------------------------------------------------------------
# Preflight helpers (read-only)
# ---------------------------------------------------------------------------

# Prints "name pid" for every process listening on TCP port $1 (needs root for names).
port_procs() {
    ss -ltnpH "sport = :$1" 2>/dev/null \
        | grep -o '("[^"]*",pid=[0-9]*' \
        | sed -E 's/^\("([^"]*)",pid=([0-9]+)$/\1 \2/' \
        | sort -u || true
}

port_listening() {
    [[ -n "$(ss -ltnH "sport = :$1" 2>/dev/null)" ]]
}

same_mount_ns() {
    local pid="$1"
    [[ "$(readlink "/proc/$pid/ns/mnt" 2>/dev/null)" == "$(readlink /proc/self/ns/mnt)" ]]
}

# Classifies who owns a public port: none | nginx | other | unknown. Sets REPLY_OWNER/REPLY_DESC.
classify_port() {
    local port="$1" procs names
    REPLY_OWNER="none"; REPLY_DESC="свободен"
    if ! port_listening "$port"; then return 0; fi
    procs="$(port_procs "$port")"
    if [[ -z "$procs" ]]; then
        REPLY_OWNER="unknown"; REPLY_DESC="занят, но не видно кем (нужен root)"
        return 0
    fi
    names="$(awk '{print $1}' <<<"$procs" | sort -u | paste -sd, -)"
    REPLY_DESC="занят: $names (pid $(awk '{print $2}' <<<"$procs" | paste -sd, -))"
    if [[ "$names" == "nginx" ]] && (( NGINX_ACTIVE )); then
        local pid
        while read -r _ pid; do
            if ! same_mount_ns "$pid"; then
                REPLY_OWNER="other"
                REPLY_DESC+=", это nginx в контейнере, а не системный"
                return 0
            fi
        done <<<"$procs"
        REPLY_OWNER="nginx"
    else
        REPLY_OWNER="other"
    fi
}

check_os() {
    local id="" like="" pretty=""
    if [[ -r /etc/os-release ]]; then
        id="$(sed -n 's/^ID=//p' /etc/os-release | tr -d '"')"
        like="$(sed -n 's/^ID_LIKE=//p' /etc/os-release | tr -d '"')"
        pretty="$(sed -n 's/^PRETTY_NAME=//p' /etc/os-release | tr -d '"')"
    fi
    if [[ " $id $like " == *" debian "* || " $id $like " == *" ubuntu "* ]]; then
        ok "система: ${pretty:-$id}"
    else
        blocker "поддерживаются только Debian и Ubuntu, а здесь: ${pretty:-неизвестно}"
    fi
}

check_root() {
    if [[ "$(id -u)" -eq 0 ]]; then
        IS_ROOT=1; ok "запущено от root"
    elif (( DRY_RUN )); then
        warn "запущено не от root: часть сведений (кто занимает порты) не видна. Для установки нужен sudo"
    else
        blocker "нужны права root: запустите через sudo"
    fi
}

check_systemd() {
    if [[ -d /run/systemd/system ]] && command -v systemctl >/dev/null 2>&1; then
        HAVE_SYSTEMD=1; ok "systemd работает"
    else
        blocker "systemd не запущен (это контейнер или необычная система): сервис сайта поставить некуда"
    fi
}

check_python() {
    if [[ ! -x "$PYTHON" ]]; then
        blocker "нет $PYTHON (на Debian/Ubuntu он есть всегда; сами мы системный Python не ставим и не меняем)"
        return 0
    fi
    local ver
    ver="$("$PYTHON" -c 'import sys; print("%d.%d" % sys.version_info[:2])' 2>/dev/null || echo "?")"
    if "$PYTHON" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)' 2>/dev/null; then
        ok "Python $ver ($PYTHON), только стандартная библиотека, ничего не доустанавливается"
    else
        blocker "нужен Python 3.8 или новее, а $PYTHON версии $ver"
    fi
}

check_source() {
    if [[ -f "$SOURCE_DIR/web/server/amazake_web.py" && -f "$SOURCE_DIR/web/static/index.html" ]]; then
        ok "код сайта: $SOURCE_DIR/web/{server,static}"
    else
        blocker "в $SOURCE_DIR нет web/server/amazake_web.py или web/static/index.html (укажите --source)"
    fi
    if [[ ! -f "$SCRIPT_DIR/amazake-web.service" || ! -f "$SCRIPT_DIR/nginx-site.conf" ]]; then
        blocker "рядом со скриптом нет шаблонов amazake-web.service и nginx-site.conf"
    fi
}

check_tools() {
    local t missing=()
    for t in ss sed awk grep install getent; do
        command -v "$t" >/dev/null 2>&1 || missing+=("$t")
    done
    if (( ${#missing[@]} )); then
        blocker "нет нужных программ: ${missing[*]} (ss из пакета iproute2)"
    fi
}

show_services() {
    header "Что сейчас работает (только смотрим, ничего не трогаем)"
    if (( HAVE_SYSTEMD )); then
        local units u frag admin=() other=()
        units="$(systemctl list-units --type=service --state=running --no-legend --plain --no-pager 2>/dev/null | awk '{print $1}')" || units=""
        while IFS= read -r u; do
            [[ -n "$u" ]] || continue
            frag="$(systemctl show -p FragmentPath --value "$u" 2>/dev/null || true)"
            if [[ "$frag" == /etc/systemd/system/* ]]; then admin+=("$u"); else other+=("${u%.service}"); fi
        done <<<"$units"
        say "  Сервисы systemd, добавленные вручную (/etc/systemd/system) — бот может быть одним из них:"
        if (( ${#admin[@]} )); then printf '    - %s\n' "${admin[@]}"; else say "    (нет)"; fi
        say "  Остальные запущенные сервисы (из пакетов системы, ${#other[@]} шт.):"
        if (( ${#other[@]} )); then printf '%s\n' "${other[*]}" | fold -s -w 100 | sed 's/^/    /'; fi
    else
        say "  systemd не запущен, список сервисов недоступен."
    fi

    # pm2: only look at processes, never run "pm2" itself (it could start a daemon).
    local pm2_pids
    pm2_pids="$(pgrep -f 'PM2 v[0-9.]*: God Daemon' 2>/dev/null || true)"
    if [[ -n "$pm2_pids" ]]; then
        say "  pm2 (только просмотр процессов, pm2 не вызывается):"
        local p
        for p in $pm2_pids; do
            ps -o user=,pid=,etime=,args= -p "$p" 2>/dev/null | cut -c1-140 | sed 's/^/    демон: /' || true
            ps -o user=,pid=,etime=,args= --ppid "$p" 2>/dev/null | cut -c1-140 | sed 's/^/      - /' || true
        done
    else
        say "  pm2: не запущен"
    fi

    # docker: "docker ps" is called only when dockerd already runs. Otherwise socket activation
    # (docker.socket) could start dockerd and, with it, every container with a restart policy,
    # for example an old copy of the bot.
    local docker_up=0
    if (( HAVE_SYSTEMD )) && systemctl is-active --quiet docker 2>/dev/null; then docker_up=1; fi
    if pgrep -x dockerd >/dev/null 2>&1; then docker_up=1; fi
    if command -v docker >/dev/null 2>&1 && (( ! docker_up )); then
        say "  docker установлен, демон не запущен (docker ps не вызываем: он мог бы запустить демон и контейнеры)"
    elif command -v docker >/dev/null 2>&1; then
        local out
        if out="$(timeout 10 docker ps --format '{{.Names}}  {{.Image}}  {{.Status}}  {{.Ports}}' 2>/dev/null)"; then
            say "  Контейнеры docker (docker ps, только чтение):"
            if [[ -n "$out" ]]; then sed 's/^/    - /' <<<"$out"; else say "    (нет запущенных)"; fi
        else
            say "  docker установлен, но список контейнеров не получен (нет прав или демон не запущен)"
        fi
    else
        say "  docker: не установлен"
    fi

    say "  Похожие на ботов процессы (node/python/java/bun/deno/dotnet):"
    local procs
    procs="$(ps -eo user=,pid=,etime=,args= 2>/dev/null \
        | awk '$4 ~ /(^|\/)(node|nodejs|python[0-9.]*|java|bun|deno|dotnet)$/' \
        | grep -v -e 'amazake_web.py' -e 'unattended-upgrade' -e 'networkd-dispatcher' \
        | cut -c1-140 || true)"
    if [[ -n "$procs" ]]; then sed 's/^/    - /' <<<"$procs"; else say "    (не найдено)"; fi

    say "  Открытые TCP-порты (ss -ltnp):"
    if command -v ss >/dev/null 2>&1; then
        ss -ltnpH 2>/dev/null | awk '{ $2=""; $3=""; print }' | sed -E 's/ +/ /g; s/^/    /' | cut -c1-150 || true
    else
        say "    ss не найден"
    fi
    say "  Всё перечисленное выше установка не перезапускает, не останавливает и не меняет."
}

check_nginx_state() {
    if command -v nginx >/dev/null 2>&1 || [[ -x /usr/sbin/nginx ]]; then NGINX_INSTALLED=1; fi
    if (( HAVE_SYSTEMD )) && systemctl is-active --quiet nginx 2>/dev/null; then NGINX_ACTIVE=1; fi
    if (( HAVE_SYSTEMD && NGINX_INSTALLED )); then
        NGINX_ENABLED="$(systemctl is-enabled nginx 2>/dev/null || true)"
    fi
    if [[ -f /proc/net/if_inet6 ]]; then HAVE_V6=1; fi
}

check_ports() {
    header "Порты"
    classify_port 80;  OWNER_80="$REPLY_OWNER";  DESC_80="$REPLY_DESC"
    classify_port 443; OWNER_443="$REPLY_OWNER"; DESC_443="$REPLY_DESC"
    info "80:  $DESC_80"
    info "443: $DESC_443"

    local main_pid="" procs pid name
    if (( HAVE_SYSTEMD )); then
        main_pid="$(systemctl show -p MainPID --value "$APP_NAME" 2>/dev/null || true)"
    fi
    if ! port_listening "$PORT"; then
        PORT_STATE="free"; ok "порт сайта $PORT на 127.0.0.1 свободен"
        return 0
    fi
    procs="$(port_procs "$PORT")"
    if [[ -z "$procs" ]]; then
        if [[ -n "$main_pid" && "$main_pid" != "0" ]]; then
            PORT_STATE="ours"; info "порт $PORT занят, скорее всего нашим $APP_NAME (без root не проверить)"
        else
            PORT_STATE="unknown"
            blocker "порт $PORT уже занят, не видно кем. Выберите другой: --port 8788"
        fi
        return 0
    fi
    PORT_STATE="ours"
    while read -r name pid; do
        if [[ -z "$main_pid" || "$pid" != "$main_pid" ]]; then PORT_STATE="busy"; fi
    done <<<"$procs"
    if [[ "$PORT_STATE" == "ours" ]]; then
        ok "порт $PORT занят нашим $APP_NAME (это повторная установка, он будет перезапущен)"
    else
        blocker "порт $PORT уже занят другой программой ($(awk '{print $1" pid "$2}' <<<"$procs" | paste -sd, -)). Её не трогаем. Выберите другой порт: --port 8788"
    fi
}

# Is $1 one of our nginx files (a file with our marker, or a symlink to one / to a missing file
# with one of our names)?
nginx_file_ours() {
    local f="$1" real base
    base="$(basename "$f")"
    if [[ -L "$f" ]]; then
        real="$(readlink -f "$f" || true)"
        [[ "$base" == "$NGINX_NAME" || "$base" == "$NGINX_LEGACY_NAME" ]] || return 1
        [[ ! -e "$real" ]] || grep -q "$MARKER" "$real" 2>/dev/null
    else
        [[ -f "$f" ]] && grep -q "$MARKER" "$f" 2>/dev/null
    fi
}

nginx_layout() {
    # Picks where our site file goes, without editing nginx.conf.
    local conf=/etc/nginx/nginx.conf
    NGINX_SITE=""; NGINX_LINK=""; LEGACY_NGINX=()
    if [[ ! -f "$conf" ]] || grep -Eq '^[[:space:]]*include[[:space:]]+/etc/nginx/sites-enabled/' "$conf"; then
        # A fresh install from the Debian/Ubuntu package uses sites-enabled too.
        NGINX_SITE="/etc/nginx/sites-available/$NGINX_NAME"
        NGINX_LINK="/etc/nginx/sites-enabled/$NGINX_NAME"
    elif grep -Eq '^[[:space:]]*include[[:space:]]+/etc/nginx/conf\.d/\*\.conf' "$conf"; then
        NGINX_SITE="/etc/nginx/conf.d/$NGINX_NAME"
    fi
    # Our files under the old name (it sorted first and could become the default server).
    local f
    for f in "$PREV_NGINX_SITE" "$PREV_NGINX_LINK" \
             "/etc/nginx/sites-available/$NGINX_LEGACY_NAME" "/etc/nginx/sites-enabled/$NGINX_LEGACY_NAME" \
             "/etc/nginx/conf.d/$NGINX_LEGACY_NAME"; do
        [[ -n "$f" && "$f" == /etc/nginx/* && "$(basename "$f")" == "$NGINX_LEGACY_NAME" ]] || continue
        if [[ -e "$f" || -L "$f" ]] && nginx_file_ours "$f"; then LEGACY_NGINX+=("$f"); fi
    done
    if (( ${#LEGACY_NGINX[@]} )); then
        mapfile -t LEGACY_NGINX < <(printf '%s\n' "${LEGACY_NGINX[@]}" | awk '!seen[$0]++')
    fi
}

# Reads an "nginx -T" dump on stdin and prints one line per fact about http server blocks:
#   L <n> <ours> <file> <addr:port> <default>   a listen socket of server n (no listen = *:80)
#   N <n> <ours> <file> <name>                  a server_name of server n
# Statements are joined up to ";" (so multi-line server_name works); quoted text and comments are
# skipped. nginx -T prints each included file separately, so a listen inside a nested include is
# not seen (the real check after writing our file still runs nginx -t on the full config).
nginx_scan() {
    awk -v marker="$MARKER" '
        function sock(a,   host, port) {
            if (a ~ /^unix:/) return ""
            if (a ~ /^[0-9]+$/) return "*:" a
            if (a == "*") return "*:80"
            if (a ~ /^\[/) { if (a ~ /\]:[0-9]+$/) return tolower(a); return tolower(a) ":80" }
            if (a ~ /:[0-9]+$/) {
                host = a; sub(/:[0-9]+$/, "", host); port = a; sub(/^.*:/, "", port)
                if (host == "0.0.0.0") host = "*"
                return tolower(host) ":" port
            }
            return tolower(a) ":80"
        }
        function http_ctx(   i) {
            for (i = 0; i < depth; i++) if (stack[i] == "stream" || stack[i] == "upstream" || stack[i] == "mail") return 0
            return 1
        }
        function words(   n) { n = split(stmt, w, /[[:space:]]+/); if (n && w[1] == "") { shift_w(n); n-- } return n }
        function shift_w(n,   i) { for (i = 1; i < n; i++) w[i] = w[i + 1]; delete w[n] }
        function open_block(   n) {
            n = words()
            name = (n ? w[1] : "")
            if (name == "server" && cur == 0 && http_ctx()) {
                srv++; cur = srv; cur_depth = depth + 1; nlisten = 0
                sfile[srv] = file; sours[srv] = ours_file
            }
            stack[depth++] = name; stmt = ""
        }
        function close_block() {
            if (cur && depth == cur_depth) {
                if (!nlisten) print "L", cur, sours[cur], sfile[cur], "*:80", 0
                cur = 0
            }
            if (depth > 0) depth--
            stmt = ""
        }
        function statement(   n, i, s, d) {
            n = words(); stmt = ""
            if (!n || !cur || depth != cur_depth) return
            if (w[1] == "listen" && n >= 2) {
                s = sock(w[2]); if (s == "") return
                d = 0
                for (i = 3; i <= n; i++) if (w[i] == "default_server" || w[i] == "default") d = 1
                nlisten++
                print "L", cur, sours[cur], sfile[cur], s, d
            } else if (w[1] == "server_name") {
                for (i = 2; i <= n; i++) if (w[i] != "") print "N", cur, sours[cur], sfile[cur], tolower(w[i])
            }
        }
        /^# configuration file / {
            file = $4; sub(/:$/, "", file); depth = 0; cur = 0; stmt = ""; ours_file = 0; next
        }
        {
            line = $0
            if (index(line, marker)) ours_file = 1
            gsub(/"[^"]*"/, "\"\"", line)
            gsub(/\047[^\047]*\047/, "\047\047", line)
            sub(/#.*/, "", line)
            gsub(/[{};]/, "\n&\n", line)
            np = split(line, parts, "\n")
            for (k = 1; k <= np; k++) {
                p = parts[k]
                if (p == "{") open_block()
                else if (p == "}") close_block()
                else if (p == ";") statement()
                else stmt = stmt " " p
            }
        }
    '
}

# From nginx_scan output: sockets where OUR server would be the first one loaded while other
# sites listen there too and none has default_server. Then our site would become the default
# for that port and take requests by IP / for unknown names away from the owner's site.
# Prints "<addr:port> <file of the site that is the default now>".
nginx_default_takeover() {
    awk '
        $1 != "L" { next }
        {
            s = $5
            if (!(s in first)) { first[s] = $3; firstfile[s] = $4 }
            if ($3 == 0 && !(s in otherfile)) otherfile[s] = $4
            if ($3 == 1) oursock[s] = 1
            if ($6 == 1) hasdef[s] = 1
        }
        END {
            for (s in oursock)
                if (first[s] == 1 && (s in otherfile) && !(s in hasdef)) { print s, otherfile[s]; found = 1 }
            exit found ? 0 : 1
        }
    '
}

# From nginx_scan output: other (not our) server blocks whose server_name catches $DOMAIN.
# Prints "C <file>" for an exact or ".domain" match (a real conflict), "W <file> <name>" for a
# wildcard that serves the domain now and would lose it to our exact name.
nginx_name_matches() {
    awk -v dom="$DOMAIN" '
        $1 != "N" || $3 == 1 { next }
        {
            n = $5
            if (n == dom || n == "." dom) { print "C", $4; next }
            if (n ~ /^\./) { p = substr(n, 2) } else if (n ~ /^\*\./) { p = substr(n, 3) } else next
            if (length(dom) > length(p) && substr(dom, length(dom) - length(p)) == "." p) print "W", $4, n
        }
    '
}

# Builds a throw-away copy of the nginx config in $TMP_DIR where our site file (variant $1) is
# added next to the owner's files, and runs "nginx -T" on it. Nothing under /etc/nginx changes.
# Sets SIM_RC, SIM_ERR (nginx -t messages) and SIM_DUMP (path of the dump).
SIM_RC=1; SIM_ERR=""; SIM_DUMP=""; SIM_ROOT=""
nginx_simulate() {
    local variant="$1" sim="$TMP_DIR/nginx-sim" incdir incbase e
    incdir="$(dirname "${NGINX_LINK:-$NGINX_SITE}")"
    incbase="$(basename "$incdir")"
    rm -rf -- "$sim"; mkdir -p "$sim/$incbase"
    SIM_ROOT="$sim"; SIM_DUMP="$TMP_DIR/nginx-sim.dump"
    # Mirror /etc/nginx with symlinks, so relative includes ("include snippets/x.conf") resolve.
    for e in /etc/nginx/*; do
        [[ "$(basename "$e")" == "nginx.conf" || "$(basename "$e")" == "$incbase" ]] && continue
        ln -s "$e" "$sim/$(basename "$e")"
    done
    for e in "$incdir"/*; do
        [[ -e "$e" || -L "$e" ]] || continue
        nginx_file_ours "$e" && continue
        ln -s "$e" "$sim/$incbase/$(basename "$e")"
    done
    if [[ "$variant" == "https-test" ]]; then
        # No certificate yet: the same server blocks with a throw-away self-signed certificate in
        # $TMP_DIR (or, without openssl, plain "listen 443" and no ssl_* lines).
        if command -v openssl >/dev/null 2>&1 \
            && openssl req -x509 -newkey rsa:2048 -nodes -days 1 -subj "/CN=$DOMAIN" \
                -keyout "$TMP_DIR/sim-key.pem" -out "$TMP_DIR/sim-cert.pem" >/dev/null 2>&1; then
            render_nginx https | sed -e "s#ssl_certificate .*#ssl_certificate $TMP_DIR/sim-cert.pem;#" \
                                     -e "s#ssl_certificate_key .*#ssl_certificate_key $TMP_DIR/sim-key.pem;#"
        else
            render_nginx https | sed -e 's/ ssl;/;/' -e '/ssl_certificate/d'
        fi
    else
        render_nginx "$variant"
    fi > "$sim/$incbase/$NGINX_NAME"
    sed "s#/etc/nginx/$incbase/#$sim/$incbase/#g" /etc/nginx/nginx.conf > "$sim/nginx.conf"
    SIM_RC=0
    nginx -T -c "$sim/nginx.conf" > "$SIM_DUMP" 2> "$TMP_DIR/nginx-sim.err" || SIM_RC=$?
    SIM_ERR="$(sed "s#$sim/#/etc/nginx/#g" "$TMP_DIR/nginx-sim.err")"
    if (( SIM_RC == 0 )) && ! grep -q "$MARKER" "$SIM_DUMP"; then
        SIM_RC=99; SIM_ERR="наш файл не подключился бы: include в nginx.conf не захватывает имя $NGINX_NAME"
    fi
}

# Shows why "default server" matters and what the owner can do; $1 = lines from nginx_default_takeover.
takeover_message() {
    local lines="$1" s f example
    while read -r s f; do
        [[ -n "$s" ]] || continue
        example="listen ${s#\*:} default_server;"
        if [[ "$s" == *:443 ]]; then example="listen ${s#\*:} ssl default_server;"; fi
        printf 'наш сайт стал бы сайтом по умолчанию для %s (сейчас это %s): запросы по IP и с доменами, которых нет в nginx, ушли бы к нам. Чтобы этого не было, в %s допишите default_server в конец listen для этого порта (например %s), проверьте nginx -t, сделайте systemctl reload nginx и запустите установку снова. Или используйте --no-nginx\n' \
            "$s" "$f" "$f" "$example"
    done <<<"$lines"
}

# Preflight with nginx installed: test our real site file against the owner's config before
# anything is installed (catches long domains, duplicate names, default-server takeover).
nginx_preflight_test() {
    local first="http" out takeover names
    if (( WANT_HTTPS && CERT_EXISTS )); then first="https"; fi
    nginx_simulate "$first"
    if (( SIM_RC != 0 )); then
        blocker "nginx -t не примет наш файл сайта рядом с вашей конфигурацией (ничего не менялось):"
        sed 's/^/      /' <<<"$SIM_ERR"
        if grep -q 'server_names_hash' <<<"$SIM_ERR"; then
            info "    Домен длинный для настроек nginx. Это правится одной строкой в блоке http {} файла"
            info "    /etc/nginx/nginx.conf: server_names_hash_bucket_size 128; затем nginx -t и systemctl reload nginx."
        fi
        return 0
    fi
    names="$(nginx_scan < "$SIM_DUMP" | nginx_name_matches | sed "s#$SIM_ROOT/#/etc/nginx/#g" || true)"
    if grep -q "conflicting server name \"$DOMAIN\"" <<<"$SIM_ERR" && ! grep -q '^C ' <<<"$names"; then
        blocker "в nginx уже есть другой сайт с server_name $DOMAIN (nginx: conflicting server name). Его не трогаем; выберите другой домен или --no-nginx"
    fi
    if grep -q '^C ' <<<"$names"; then
        blocker "в nginx уже есть другой сайт для $DOMAIN ($(awk '$1 == "C" {print $2}' <<<"$names" | sort -u | paste -sd, -)). Его не трогаем; выберите другой домен или --no-nginx"
    fi
    if grep -q '^W ' <<<"$names"; then
        warn "$DOMAIN сейчас обслуживает сайт $(awk '$1 == "W" {print $2 " (" $3 ")"}' <<<"$names" | sort -u | paste -sd, -). После установки этот поддомен будет открывать наш сайт, остальные поддомены не изменятся"
    fi
    if takeover="$(nginx_scan < "$SIM_DUMP" | nginx_default_takeover)"; then
        local line
        while IFS= read -r line; do [[ -n "$line" ]] && blocker "$line"; done \
            <<<"$(takeover_message "$(sed "s#$SIM_ROOT/#/etc/nginx/#g" <<<"$takeover")")"
    else
        ok "проверка nginx с нашим файлом ($first) на копии конфигурации: nginx -t ок, сайты по умолчанию на портах не меняются"
    fi
    if (( WANT_HTTPS && ! CERT_EXISTS )); then
        # Port 443 is added only after the certificate; check it now with a stand-in file.
        nginx_simulate https-test
        if (( SIM_RC != 0 )); then
            info "HTTPS-вариант заранее проверить не удалось; он будет проверен nginx -t перед применением"
        elif takeover="$(nginx_scan < "$SIM_DUMP" | nginx_default_takeover)"; then
            local line
            while IFS= read -r line; do [[ -n "$line" ]] && blocker "$line"; done \
                <<<"$(takeover_message "$(sed "s#$SIM_ROOT/#/etc/nginx/#g" <<<"$takeover")")"
        fi
    fi
}

# Certificates of other sites that renew by binding port 80 themselves (standalone) or through
# a web server that is not running now. If we start nginx on :80 for good, their renewal breaks.
check_cert_renewals() {
    local f auth others=()
    [[ -d /etc/letsencrypt/renewal ]] || return 0
    for f in /etc/letsencrypt/renewal/*.conf; do
        [[ -f "$f" ]] || continue
        # Our own certificate (webroot in our acme folder) is fine.
        grep -q "$ACME_DIR" "$f" 2>/dev/null && continue
        auth="$(sed -n 's/^[[:space:]]*authenticator[[:space:]]*=[[:space:]]*//p' "$f" | tail -n 1 | tr -d '[:space:]')"
        case "$auth" in
            standalone|nginx|apache) others+=("$(basename "$f" .conf) ($auth)") ;;
        esac
    done
    (( ${#others[@]} )) || return 0
    blocker "на сервере есть сертификаты, которые продлеваются через порт 80: ${others[*]}. Если сейчас запустить nginx, он займёт порт 80 навсегда и их продление (например, сертификата бота) перестанет работать. Мы их не трогаем. Варианты: --no-nginx (сайт подключите к своему веб-серверу) или сами переведите эти сертификаты на webroot"
}

decide_web_mode() {
    header "Веб-сервер"
    if (( FORCE_NO_NGINX )); then
        WEB_MODE="external"; info "--no-nginx: nginx не трогаем, ставим только сервис сайта"
        return 0
    fi
    if [[ "$OWNER_80" == "unknown" || "$OWNER_443" == "unknown" ]]; then
        WEB_MODE="external"
        info "Не удалось определить, кто занимает порты 80/443. На всякий случай nginx НЕ ставим и не трогаем."
        info "Поставим только сервис сайта на 127.0.0.1:$PORT и покажем, как подключить домен."
        return 0
    fi
    if [[ "$OWNER_80" == "other" || "$OWNER_443" == "other" ]]; then
        WEB_MODE="external"
        info "Порты 80/443 заняты не системным nginx. Свой nginx НЕ ставим, чужую программу не трогаем."
        info "Поставим только сервис сайта на 127.0.0.1:$PORT и покажем, как подключить домен."
        return 0
    fi
    WEB_MODE="nginx"
    if [[ "$OWNER_80" == "nginx" || "$OWNER_443" == "nginx" ]]; then
        ok "на 80/443 работает nginx: добавим ТОЛЬКО свой файл сайта для $DOMAIN и сделаем reload (не restart)"
    elif (( NGINX_INSTALLED )); then
        NGINX_START=1
        if [[ "$NGINX_ENABLED" == "masked" ]]; then
            blocker "nginx установлен, но отключён через systemctl mask (кто-то запретил его запуск). Не трогаем; используйте --no-nginx или сами выполните systemctl unmask nginx"
        elif [[ "$NGINX_ENABLED" == "enabled" ]]; then
            info "nginx установлен и включён в автозапуск, но сейчас не запущен, порты 80/443 свободны: запустим его (systemctl start nginx)"
        else
            info "nginx установлен, но не запущен и не в автозапуске (${NGINX_ENABLED:-неизвестно}); порты 80/443 свободны: запустим его (systemctl start nginx)."
            warn "автозапуск nginx мы не включаем (его выключили не мы). После перезагрузки сервера сайт откроется только если включить его самим: sudo systemctl enable nginx"
        fi
    else
        NEED_APT+=("nginx")
        info "порты 80/443 свободны, nginx нет: предложим установить nginx (apt-get install nginx)"
        if (( ${#DOMAIN} >= LONG_DOMAIN )); then
            blocker "домен длиннее $((LONG_DOMAIN - 1)) символов: со стандартными настройками nginx его не примет (server_names_hash_bucket_size). Поставьте nginx сами (apt-get install nginx), добавьте в блок http {} файла /etc/nginx/nginx.conf строку server_names_hash_bucket_size 128; и запустите установку снова. Или возьмите домен короче"
        fi
    fi
    if (( NGINX_START )) || [[ " ${NEED_APT[*]} " == *" nginx "* ]]; then
        check_cert_renewals
    fi

    if (( WANT_HTTPS )); then
        if [[ -z "$EMAIL" ]]; then
            blocker "для HTTPS нужен --email (почта для Let's Encrypt). Или добавьте --no-https"
        fi
        if [[ -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]]; then
            CERT_EXISTS=1; ok "сертификат для $DOMAIN уже есть"
        fi
        CERTBOT_BIN="$(command -v certbot 2>/dev/null || true)"
        if [[ -z "$CERTBOT_BIN" && $CERT_EXISTS -eq 0 ]]; then
            NEED_APT+=("certbot")
            info "certbot нет: предложим установить (apt-get install certbot)"
        fi
    else
        info "--no-https: только HTTP"
    fi

    nginx_layout
    if (( NGINX_INSTALLED )); then
        local nginx_ok=0
        if (( IS_ROOT )); then
            local t
            if t="$(nginx -t 2>&1)"; then
                nginx_ok=1
                ok "текущая конфигурация nginx корректна (nginx -t)"
            else
                blocker "nginx -t уже сейчас выдаёт ошибку (это не наша конфигурация, её не трогаем):"
                sed 's/^/      /' <<<"$t"
            fi
        else
            info "без root nginx заранее не проверить; при установке проверка будет"
        fi
        if [[ -z "$NGINX_SITE" ]]; then
            blocker "в /etc/nginx/nginx.conf нет include для sites-enabled или conf.d; nginx.conf не правим. Используйте --no-nginx и подключите сайт вручную"
        elif (( nginx_ok )); then
            nginx_preflight_test
        fi
    fi
    if [[ -n "$NGINX_SITE" ]]; then info "наш файл nginx: $NGINX_SITE${NGINX_LINK:+ (+ ссылка $NGINX_LINK)}"; fi
    if (( ${#LEGACY_NGINX[@]} )); then
        info "старое имя нашего файла будет заменено новым (оно загружалось первым): ${LEGACY_NGINX[*]}"
    fi

}

check_dns() {
    header "DNS"
    local ips local_ips ip match=0
    ips="$(getent ahostsv4 "$DOMAIN" 2>/dev/null | awk '{print $1}' | sort -u | paste -sd' ' - || true)"
    if [[ -z "$ips" ]]; then
        warn "$DOMAIN пока не находится в DNS. Сайт установится, но по домену откроется только после A-записи на IP этого VPS; HTTPS добавится при повторном запуске"
        return 0
    fi
    local_ips="$(hostname -I 2>/dev/null || true)"
    for ip in $ips; do
        if [[ " $local_ips " == *" $ip "* ]]; then match=1; fi
    done
    if (( match )); then
        ok "$DOMAIN -> $ips (это адрес этого сервера)"
    else
        info "$DOMAIN -> $ips; среди адресов сервера (${local_ips:-нет данных}) его нет."
        info "Это нормально за NAT; перед получением сертификата сделаем настоящую проверку по HTTP."
    fi
}

check_misc() {
    local avail
    avail="$(df -Pm /var/lib 2>/dev/null | awk 'NR==2 {print $4}' || true)"
    if [[ "$avail" =~ ^[0-9]+$ ]] && (( avail < 1024 )); then
        warn "на диске свободно всего ${avail} МБ. Отчёты занимают место; храните их меньше дней (--retention-days) и включите токен"
    fi
    if (( IS_ROOT )) && command -v ufw >/dev/null 2>&1; then
        if ufw status 2>/dev/null | grep -q '^Status: active'; then
            info "файрвол ufw включён. Мы его не меняем; если сайт не открывается снаружи, разрешите порты сами: ufw allow 80,443/tcp"
        fi
    fi
    if [[ ! -f "$TOKENS_FILE" && -z "$TOKEN" && $GENERATE_TOKEN -eq 0 ]]; then
        warn "загрузка отчётов будет открыта для всех (без токена). Рекомендуется --generate-token"
    fi
}

# The amazake-web user must be ours (created by this installer) or not exist at all, so that we
# never adopt, and uninstall.sh never deletes, an account someone else made.
check_service_user() {
    local ent gecos
    if ent="$(getent passwd "$SERVICE_USER" 2>/dev/null)"; then
        gecos="$(cut -d: -f5 <<<"$ent")"
        if [[ "$(conf_get "$INSTALL_CONF" CREATED_USER)" == "1" || "$gecos" == "$USER_COMMENT" ]]; then
            USER_OURS=1; CREATED_USER=1
            ok "пользователь $SERVICE_USER уже есть (создан этим установщиком раньше)"
        else
            blocker "пользователь $SERVICE_USER уже есть, но создан не этим установщиком. Чужую учётную запись не используем и не меняем; переименуйте или удалите её сами"
        fi
    elif getent group "$SERVICE_USER" >/dev/null 2>&1; then
        blocker "группа $SERVICE_USER уже есть, но пользователя нет: группа чужая. Её не используем (её участники видели бы токен); удалите или переименуйте её сами"
    fi
}

preflight() {
    header "Проверка (ничего не меняется)"
    if (( ${#PREV_CHOICES[@]} )); then
        info "из прошлой установки: ${PREV_CHOICES[*]} (поменять: --https, --nginx, --email ...)"
    fi
    check_os
    check_root
    check_systemd
    check_tools
    check_python
    check_source
    check_service_user
    if [[ -f "$INSTALL_CONF" ]]; then
        info "найдена прошлая установка (домен ${PREV_DOMAIN:-?}, порт ${PREV_PORT:-?}): файлы будут обновлены"
        if [[ -n "$PREV_DOMAIN" && "$PREV_DOMAIN" != "$DOMAIN" ]]; then
            warn "домен меняется: $PREV_DOMAIN -> $DOMAIN (старый сертификат останется, удалить: certbot delete --cert-name $PREV_DOMAIN)"
        fi
    fi
    show_services
    check_nginx_state
    if command -v ss >/dev/null 2>&1; then
        check_ports
    else
        OWNER_80="unknown"; OWNER_443="unknown"
    fi
    decide_web_mode
    check_dns
    check_misc
}

# ---------------------------------------------------------------------------
# Plan and confirmation
# ---------------------------------------------------------------------------
public_url() {
    if (( CERT_EXISTS )) || [[ "$WEB_MODE" == "external" && $WANT_HTTPS -eq 1 ]]; then
        printf 'https://%s' "$DOMAIN"
    else
        printf 'http://%s' "$DOMAIN"
    fi
}

show_plan() {
    header "План"
    local i=1
    step() { printf '  %d. %s\n' "$i" "$*"; i=$((i + 1)); }
    if (( ${#NEED_APT[@]} )); then
        step "apt-get update и apt-get install --no-install-recommends ${NEED_APT[*]} (только эти пакеты; если apt захочет ОБНОВИТЬ или УДАЛИТЬ уже установленные пакеты, установка остановится; needrestart не будет перезапускать сервисы)"
    fi
    if (( USER_OURS )); then
        step "пользователь $SERVICE_USER уже есть"
    else
        step "создать системного пользователя $SERVICE_USER (без входа и без домашней папки)"
    fi
    step "папки: $APP_DIR, $CONF_DIR, $DATA_DIR (отчёты), $ACME_DIR"
    step "скопировать код: $SOURCE_DIR/web/server и web/static -> $APP_DIR/{server,static}"
    step "настройки: $ENV_FILE (порт $PORT, хранить $RETENTION_DAYS дн., лимит $RATE_LIMIT/ч)"
    if [[ -n "$TOKEN" ]]; then step "записать токен в $TOKENS_FILE (0640 root:$SERVICE_USER)"
    elif (( GENERATE_TOKEN )) && [[ ! -f "$TOKENS_FILE" ]]; then step "создать случайный токен в $TOKENS_FILE и показать его один раз"
    elif (( REMOVE_TOKEN )); then step "удалить $TOKENS_FILE (загрузка без токена)"
    elif [[ -f "$TOKENS_FILE" ]]; then step "оставить существующий токен $TOKENS_FILE"
    fi
    step "systemd: $UNIT_FILE, daemon-reload, enable и (пере)запуск ТОЛЬКО $APP_NAME.service"
    if [[ "$WEB_MODE" == "nginx" ]]; then
        if (( NGINX_START )); then
            if [[ "$NGINX_ENABLED" == "enabled" ]]; then
                step "запустить остановленный nginx (systemctl start nginx) после nginx -t; автозапуск у него уже включён"
            else
                step "запустить остановленный nginx (systemctl start nginx) после nginx -t; автозапуск НЕ включаем (systemctl enable не делаем)"
            fi
        fi
        if [[ " ${NEED_APT[*]} " == *" nginx "* ]]; then
            step "новый nginx пакет запустит и включит в автозапуск сам (порты 80/443 сейчас никем не заняты)"
        fi
        if (( ${#LEGACY_NGINX[@]} )); then
            step "убрать наш файл nginx со старым именем (${LEGACY_NGINX[*]})"
        fi
        step "nginx: свой файл $NGINX_SITE${NGINX_LINK:+ + ссылка $NGINX_LINK}, server_name $DOMAIN; nginx -t и проверка, что наш сайт не стал сайтом по умолчанию; только если всё ок: systemctl reload nginx (иначе наш файл убирается)"
        if (( WANT_HTTPS )) && (( ! CERT_EXISTS )); then
            step "проверить, что http://$DOMAIN ведёт на этот сервер; если да — certbot certonly --webroot (certbot не меняет конфиги nginx), затем HTTPS-версия нашего файла, nginx -t, reload"
        elif (( CERT_EXISTS )); then
            step "использовать существующий сертификат $DOMAIN (HTTPS, редирект с 80 на 443)"
        fi
    else
        step "nginx/caddy/apache не трогаем; покажем, как направить $DOMAIN на 127.0.0.1:$PORT"
    fi
    step "проверить, что сайт отвечает на http://127.0.0.1:$PORT/healthz"
    unset -f step
}

confirm() {
    (( DRY_RUN )) && return 0
    (( ASSUME_YES )) && return 0
    local answer=""
    # "[[ -r /dev/tty ]]" only checks mode bits; opening it is the real test (ENXIO without a terminal).
    if { exec 3<>/dev/tty; } 2>/dev/null; then
        printf '\nПродолжить? [y/N] ' >&3
        read -r answer <&3 || answer=""
        exec 3>&-
    else
        die "нет терминала для подтверждения; запустите с --yes, если план выше вас устраивает"
    fi
    case "${answer,,}" in
        y|yes|д|да) ;;
        *) say "Отменено, ничего не изменено."; exit 0 ;;
    esac
}

# ---------------------------------------------------------------------------
# Actions
# ---------------------------------------------------------------------------

# Refuses to delete anything outside our own paths.
safe_rm_rf() {
    local p="$1"
    case "$p" in
        "$APP_DIR"|"$APP_DIR".new|"$APP_DIR".old|"$APP_DIR"/*) ;;
        *) die "внутренняя ошибка: отказ удалять $p" ;;
    esac
    run rm -rf -- "$p"
}

# Writes stdin to $1 with mode $2 and owner $3 (user:group), atomically; prints in dry-run.
write_file() {
    local dest="$1" mode="$2" owner="$3" tmp
    tmp="$(mktemp "$TMP_DIR/file.XXXXXX")"
    cat > "$tmp"
    if (( DRY_RUN )); then
        if [[ -f "$dest" ]] && cmp -s "$tmp" "$dest"; then
            printf '  %s[dry-run]%s %s без изменений\n' "$C_C" "$C_0" "$dest"
        else
            printf '  %s[dry-run]%s записать %s (%s %s, %s строк)\n' "$C_C" "$C_0" "$dest" "$mode" "$owner" "$(wc -l < "$tmp")"
        fi
        return 0
    fi
    install -m "$mode" -o "${owner%%:*}" -g "${owner##*:}" "$tmp" "$dest.amazake-tmp"
    mv -f -- "$dest.amazake-tmp" "$dest"
}

render_unit() {
    sed -e "s|@PYTHON@|$PYTHON|g" \
        -e "s|@PORT@|$PORT|g" \
        -e "s|@ENV_FILE@|$ENV_FILE|g" \
        -e "s|@APP_DIR@|$APP_DIR|g" \
        -e "s|@DATA_DIR@|$DATA_DIR|g" \
        "$SCRIPT_DIR/amazake-web.service"
}

# $1 = http | https
render_nginx() {
    local variant="$1" keep drop v6
    if [[ "$variant" == "https" ]]; then keep="HTTPS"; drop="HTTP-ONLY"; else keep="HTTP-ONLY"; drop="HTTPS"; fi
    v6="$HAVE_V6"
    awk -v drop="$drop" -v keep="$keep" -v v6="$v6" '
        $0 == "#@BEGIN-" drop { skip = 1; next }
        $0 == "#@END-" drop   { skip = 0; next }
        $0 == "#@BEGIN-" keep || $0 == "#@END-" keep { next }
        skip { next }
        /^[[:space:]]*#@V6 / { if (v6 == 1) { sub(/#@V6 /, "") } else { next } }
        { print }
    ' "$SCRIPT_DIR/nginx-site.conf" \
        | sed -e "s|@DOMAIN@|$DOMAIN|g" -e "s|@PORT@|$PORT|g" -e "s|@ACME_DIR@|$ACME_DIR|g" \
        | cat -s
}

apt_install() {
    (( ${#NEED_APT[@]} )) || return 0
    CURRENT_STEP="установка пакетов ${NEED_APT[*]}"
    header "Пакеты: ${NEED_APT[*]}"
    # needrestart (Ubuntu) would otherwise offer to restart services after apt, which could hit the bot.
    local -a aptenv=(env DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=l NEEDRESTART_SUSPEND=1)
    # --no-remove: apt fails instead of removing an installed package that conflicts.
    local -a aptopts=(-y -q --no-install-recommends --no-upgrade --no-remove -o DPkg::Lock::Timeout=300
                      -o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold)
    run "${aptenv[@]}" apt-get -q -o DPkg::Lock::Timeout=300 update
    if (( ! DRY_RUN )); then
        local sim upgrades removals
        if ! sim="$("${aptenv[@]}" apt-get -s "${aptopts[@]}" install "${NEED_APT[@]}" 2>&1)"; then
            sed 's/^/    /' <<<"$sim" >&2
            die "apt не может поставить ${NEED_APT[*]}, не удаляя и не обновляя уже установленные пакеты (вывод выше). Ничего не установлено. Варианты: --no-nginx или установить ${NEED_APT[*]} вручную"
        fi
        # "Inst pkg [oldversion] (...)" means an already installed package would be upgraded.
        upgrades="$(grep -E '^Inst [^ ]+ \[' <<<"$sim" | awk '{print $2}' | paste -sd' ' - || true)"
        removals="$(grep -E '^Remv ' <<<"$sim" | awk '{print $2}' | paste -sd' ' - || true)"
        if [[ -n "$upgrades" || -n "$removals" ]]; then
            die "apt хочет${upgrades:+ обновить уже установленные пакеты ($upgrades)}${removals:+ удалить пакеты ($removals)} ради ${NEED_APT[*]}. Мы так не делаем, чтобы не задеть другие программы. Ничего не установлено. Варианты: --no-nginx (и подключить сайт к своему веб-серверу) или установить ${NEED_APT[*]} вручную, когда будете готовы"
        fi
        info "будут установлены новые пакеты: $(grep -E '^Inst ' <<<"$sim" | awk '{print $2}' | paste -sd' ' -)"
    else
        printf '  %s[dry-run]%s проверка: apt-get -s install ... (стоп, если обновляются или удаляются уже установленные пакеты)\n' "$C_C" "$C_0"
    fi
    run "${aptenv[@]}" apt-get "${aptopts[@]}" install "${NEED_APT[@]}"
    done_step "установлены пакеты: ${NEED_APT[*]}"
    if [[ " ${NEED_APT[*]} " == *" nginx "* ]]; then NGINX_INSTALLED=1; NGINX_FROM_APT=1; fi
    if [[ " ${NEED_APT[*]} " == *" certbot "* ]]; then CERTBOT_BIN="$(command -v certbot 2>/dev/null || echo /usr/bin/certbot)"; fi
}

ensure_user() {
    CURRENT_STEP="пользователь $SERVICE_USER"
    # Preflight already stopped if a user or group of this name exists and is not ours.
    if (( USER_OURS )); then
        ok "пользователь $SERVICE_USER уже есть"
        return 0
    fi
    # The comment is how a later run (and uninstall.sh) recognises the account as ours.
    run useradd --system --user-group --no-create-home --home-dir /nonexistent \
        --shell /usr/sbin/nologin --comment "$USER_COMMENT" "$SERVICE_USER"
    CREATED_USER=1
    done_step "создан пользователь $SERVICE_USER"
}

ensure_dirs() {
    CURRENT_STEP="папки"
    run install -d -m 0755 -o root -g root "$APP_DIR"
    run install -d -m 0750 -o root -g "$SERVICE_USER" "$CONF_DIR"
    run install -d -m 0755 -o root -g root "$STATE_DIR"
    run install -d -m 0750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$DATA_DIR"
    run install -d -m 0755 -o root -g root "$ACME_DIR"
    # Reports kept from an earlier install may belong to an old uid of amazake-web.
    run chown -R "$SERVICE_USER:$SERVICE_USER" "$DATA_DIR"
    done_step "папки $APP_DIR, $CONF_DIR, $STATE_DIR"
}

copy_code() {
    CURRENT_STEP="копирование кода"
    local stage="$APP_DIR.new"
    if [[ -e "$stage" ]]; then safe_rm_rf "$stage"; fi
    run install -d -m 0755 -o root -g root "$stage"
    run cp -R -- "$SOURCE_DIR/web/server" "$stage/server"
    run cp -R -- "$SOURCE_DIR/web/static" "$stage/static"
    if (( ! DRY_RUN )); then
        find "$stage" -name '__pycache__' -type d -prune -exec rm -rf -- {} +
        find "$stage" -name 'test_*.py' -type f -delete
        chown -R root:root "$stage"
        find "$stage" -type d -exec chmod 0755 {} +
        find "$stage" -type f -exec chmod 0644 {} +
    else
        printf '  %s[dry-run]%s убрать __pycache__ и тесты, права root:root 0644/0755\n' "$C_C" "$C_0"
    fi
    # Swap only server/ and static/; nothing else lives in $APP_DIR.
    local part
    for part in server static; do
        if [[ -e "$APP_DIR/$part" ]]; then
            if [[ -e "$APP_DIR.old" ]]; then safe_rm_rf "$APP_DIR.old"; fi
            run mv -- "$APP_DIR/$part" "$APP_DIR.old"
            run mv -- "$stage/$part" "$APP_DIR/$part"
            safe_rm_rf "$APP_DIR.old"
        else
            run mv -- "$stage/$part" "$APP_DIR/$part"
        fi
    done
    safe_rm_rf "$stage"
    done_step "код скопирован в $APP_DIR"
}

write_tokens() {
    CURRENT_STEP="токен"
    if [[ -n "$TOKEN" ]]; then
        printf '# %s\n# Upload tokens, one per line. Header X-Amazake-Token.\n%s\n' "$MARKER" "$TOKEN" \
            | write_file "$TOKENS_FILE" 0640 "root:$SERVICE_USER"
        done_step "токен записан в $TOKENS_FILE"
    elif (( GENERATE_TOKEN )) && [[ ! -f "$TOKENS_FILE" ]]; then
        if (( DRY_RUN )); then
            printf '  %s[dry-run]%s создать случайный токен в %s\n' "$C_C" "$C_0" "$TOKENS_FILE"
        else
            TOKEN="$("$PYTHON" -c 'import secrets; print(secrets.token_urlsafe(32))')"
            printf '# %s\n# Upload tokens, one per line. Header X-Amazake-Token.\n%s\n' "$MARKER" "$TOKEN" \
                | write_file "$TOKENS_FILE" 0640 "root:$SERVICE_USER"
            done_step "создан новый токен в $TOKENS_FILE"
        fi
    elif (( GENERATE_TOKEN )); then
        ok "токен уже есть в $TOKENS_FILE, оставляем его (новый не создаём)"
    elif (( REMOVE_TOKEN )) && [[ -f "$TOKENS_FILE" ]]; then
        run rm -f -- "$TOKENS_FILE"
        done_step "токен удалён, загрузка без токена"
    fi
}

write_config() {
    CURRENT_STEP="настройки $CONF_DIR"
    local pub="$1"
    local token_line="# AMAZAKE_WEB_TOKEN_FILE is not set: uploads need no token"
    if [[ -f "$TOKENS_FILE" && $REMOVE_TOKEN -eq 0 ]] || [[ -n "$TOKEN" ]] || (( GENERATE_TOKEN )); then
        token_line="AMAZAKE_WEB_TOKEN_FILE=$TOKENS_FILE"
    fi
    write_file "$ENV_FILE" 0640 "root:$SERVICE_USER" <<EOF
# $MARKER. Re-running install.sh rewrites this file
# (retention and rate limit are kept unless passed again).
AMAZAKE_WEB_PUBLIC_URL=$pub
AMAZAKE_WEB_RETENTION_DAYS=$RETENTION_DAYS
AMAZAKE_WEB_RATE_LIMIT=$RATE_LIMIT
$token_line
EOF
    write_file "$INSTALL_CONF" 0644 root:root <<EOF
# $MARKER. Used by install.sh (re-runs) and uninstall.sh.
DOMAIN=$DOMAIN
EMAIL=$EMAIL
PORT=$PORT
WEB_MODE=$WEB_MODE
NO_NGINX=$FORCE_NO_NGINX
NO_HTTPS=$(( WANT_HTTPS ? 0 : 1 ))
NGINX_SITE=$NGINX_SITE
NGINX_LINK=$NGINX_LINK
CREATED_USER=$CREATED_USER
EOF
    done_step "настройки в $CONF_DIR"
}

install_service() {
    CURRENT_STEP="сервис $APP_NAME"
    render_unit | write_file "$UNIT_FILE" 0644 root:root
    run systemctl daemon-reload
    run systemctl enable --quiet "$APP_NAME.service"
    run systemctl restart "$APP_NAME.service"
    done_step "сервис $APP_NAME.service установлен и (пере)запущен"
}

health_check() {
    CURRENT_STEP="проверка сайта"
    if (( DRY_RUN )); then
        printf '  %s[dry-run]%s проверить http://127.0.0.1:%s/healthz\n' "$C_C" "$C_0" "$PORT"
        return 0
    fi
    local i
    for i in $(seq 1 15); do
        if "$PYTHON" - "$PORT" <<'PY' 2>/dev/null
import sys, urllib.request
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
with opener.open("http://127.0.0.1:%s/healthz" % sys.argv[1], timeout=3) as r:
    sys.exit(0 if r.status == 200 else 1)
PY
        then
            ok "сайт отвечает на 127.0.0.1:$PORT"
            return 0
        fi
        sleep 1
    done
    fail "сайт не ответил на 127.0.0.1:$PORT/healthz. Журнал:"
    journalctl -u "$APP_NAME" -n 30 --no-pager >&2 || true
    die "сервис $APP_NAME не запустился (другие сервисы не затронуты)"
}

# Checks the live nginx config after our file was written (before any reload). Prints the
# reason and returns 1 if it must not be applied: nginx -t fails, our domain is a duplicate, or
# our site would become the default server for a port that other sites use.
nginx_check_live() {
    local out dump takeover
    if ! out="$(nginx -t 2>&1)"; then
        printf 'nginx -t не принял наш файл:\n%s\n' "$out"
        return 1
    fi
    if grep -q "conflicting server name \"$DOMAIN\"" <<<"$out"; then
        printf 'в nginx уже есть другой сайт с server_name %s:\n%s\n' "$DOMAIN" "$out"
        return 1
    fi
    if dump="$(nginx -T 2>/dev/null)" && takeover="$(nginx_scan <<<"$dump" | nginx_default_takeover)"; then
        takeover_message "$takeover"
        return 1
    fi
    return 0
}

# Installs our nginx site file (variant http|https). If the live check fails, restores the
# previous state of OUR files and stops without reloading; nginx keeps running with its old config.
apply_nginx_site() {
    local variant="$1"
    CURRENT_STEP="файл сайта nginx ($variant)"
    local backup="" had_link=0 new legacy_tar=""
    new="$(mktemp "$TMP_DIR/nginx.XXXXXX")"
    render_nginx "$variant" > "$new"
    if (( DRY_RUN )); then
        if (( ${#LEGACY_NGINX[@]} )); then run rm -f -- "${LEGACY_NGINX[@]}"; fi
        write_file "$NGINX_SITE" 0644 root:root < "$new"
        if [[ -n "$NGINX_LINK" ]]; then run ln -sfn "$NGINX_SITE" "$NGINX_LINK"; fi
        run nginx -t
        printf '  %s[dry-run]%s %s\n' "$C_C" "$C_0" "nginx -T: проверить, что наш сайт не стал сайтом по умолчанию"
        if [[ " ${NEED_APT[*]} " == *" nginx "* ]]; then
            printf '  %s[dry-run]%s %s\n' "$C_C" "$C_0" "systemctl reload nginx (nginx только что поставлен пакетом)"
        elif (( NGINX_START )); then
            run systemctl start nginx
        else
            run systemctl reload nginx
        fi
        return 0
    fi
    if [[ -f "$NGINX_SITE" ]]; then
        grep -q "$MARKER" "$NGINX_SITE" || die "$NGINX_SITE существует, но это не наш файл. Не трогаем его"
        backup="$(mktemp "$TMP_DIR/nginx-backup.XXXXXX")"
        cp -p -- "$NGINX_SITE" "$backup"
    fi
    if [[ -n "$NGINX_LINK" && -L "$NGINX_LINK" ]]; then had_link=1; fi
    if [[ -n "$NGINX_LINK" && -e "$NGINX_LINK" && ! -L "$NGINX_LINK" ]]; then
        die "$NGINX_LINK существует и это не ссылка на наш файл. Не трогаем его"
    fi
    if (( ! ${#LEGACY_NGINX[@]} )) && [[ -n "$backup" ]] && cmp -s "$new" "$NGINX_SITE" \
        && { [[ -z "$NGINX_LINK" ]] || (( had_link )); }; then
        ok "файл nginx не изменился"
        if ! systemctl is-active --quiet nginx; then start_nginx; fi
        return 0
    fi
    if (( ${#LEGACY_NGINX[@]} )); then
        # Our files under the old name: keep a copy (symlinks too) to put back on failure.
        legacy_tar="$TMP_DIR/nginx-legacy.tar"
        tar -C / -cpf "$legacy_tar" "${LEGACY_NGINX[@]#/}"
        rm -f -- "${LEGACY_NGINX[@]}"
    fi
    write_file "$NGINX_SITE" 0644 root:root < "$new"
    if [[ -n "$NGINX_LINK" ]]; then ln -sfn "$NGINX_SITE" "$NGINX_LINK"; fi
    local why
    if ! why="$(nginx_check_live)"; then
        # Put our files back as they were; other files were never touched.
        if [[ -n "$backup" ]]; then
            cp -p -- "$backup" "$NGINX_SITE"
            if [[ -n "$NGINX_LINK" ]] && (( ! had_link )); then rm -f -- "$NGINX_LINK"; fi
        else
            rm -f -- "$NGINX_SITE"
            if [[ -n "$NGINX_LINK" ]] && (( ! had_link )); then rm -f -- "$NGINX_LINK"; fi
        fi
        if [[ -n "$legacy_tar" ]]; then tar -C / -xpf "$legacy_tar"; fi
        fail "наш файл nginx не применён и убран обратно; nginx НЕ перезагружался и работает как раньше. Причина:"
        sed 's/^/    /' <<<"$why" >&2
        die "файл сайта nginx не применён"
    fi
    if (( ${#LEGACY_NGINX[@]} )); then
        done_step "убран наш файл nginx со старым именем: ${LEGACY_NGINX[*]}"
        LEGACY_NGINX=()
    fi
    if systemctl is-active --quiet nginx; then
        systemctl reload nginx
    else
        # Only reachable when ports 80/443 were free (checked in preflight).
        start_nginx
    fi
    done_step "nginx: файл $NGINX_SITE ($variant), проверка ок, reload"
}

# Starts nginx. Autostart is turned on only for an nginx this run installed: an nginx the owner
# had stopped or disabled keeps its boot setting.
start_nginx() {
    if (( NGINX_FROM_APT )); then systemctl enable --quiet nginx; fi
    systemctl start nginx
    NGINX_START=0
    NGINX_ACTIVE=1
    done_step "nginx запущен$( (( NGINX_FROM_APT )) && printf ' и включён в автозапуск')"
}

# Checks over plain HTTP that $DOMAIN reaches this nginx (what Let's Encrypt will do).
domain_reaches_us() {
    local name value url
    name="amazake-probe-$(od -An -N8 -tx1 /dev/urandom | tr -d ' \n')"
    value="$name"
    printf '%s' "$value" > "$ACME_DIR/.well-known/acme-challenge/$name"
    chmod 0644 "$ACME_DIR/.well-known/acme-challenge/$name"
    url="http://$DOMAIN/.well-known/acme-challenge/$name"
    local rc=0
    "$PYTHON" - "$url" "$value" <<'PY' 2>/dev/null || rc=$?
import sys, urllib.request
try:
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(sys.argv[1], timeout=10) as r:
        sys.exit(0 if r.read().decode("utf-8", "replace").strip() == sys.argv[2] else 1)
except Exception:
    sys.exit(1)
PY
    rm -f -- "$ACME_DIR/.well-known/acme-challenge/$name"
    return "$rc"
}

setup_https() {
    (( WANT_HTTPS )) || return 0
    CURRENT_STEP="HTTPS"
    header "HTTPS"
    if (( CERT_EXISTS )); then
        ok "сертификат $DOMAIN уже есть, используется"
        return 0
    fi
    if (( DRY_RUN )); then
        printf '  %s[dry-run]%s проверить, что http://%s/.well-known/acme-challenge/... открывается на этом сервере\n' "$C_C" "$C_0" "$DOMAIN"
        run "${CERTBOT_BIN:-certbot}" certonly --webroot -w "$ACME_DIR" -d "$DOMAIN" --cert-name "$DOMAIN" \
            --email "$EMAIL" --agree-tos --no-eff-email --non-interactive --keep-until-expiring \
            --deploy-hook "nginx -t -q && systemctl reload nginx"
        apply_nginx_site https
        return 0
    fi
    install -d -m 0755 -o root -g root "$ACME_DIR/.well-known" "$ACME_DIR/.well-known/acme-challenge"
    if ! domain_reaches_us; then
        warn "http://$DOMAIN пока не ведёт на этот сервер (нет A-записи, она ещё не обновилась, или порт 80 закрыт файрволом/провайдером). Оставляем только HTTP; всё остальное работает. Когда DNS заработает, просто запустите эту же команду ещё раз — HTTPS добавится"
        return 0
    fi
    ok "http://$DOMAIN ведёт на этот сервер"
    [[ -n "$CERTBOT_BIN" ]] || CERTBOT_BIN="$(command -v certbot)"
    # --webroot: certbot only writes a file into our acme dir, never edits nginx configs.
    # --deploy-hook is saved in /etc/letsencrypt/renewal/$DOMAIN.conf and runs after each renewal.
    if ! "$CERTBOT_BIN" certonly --webroot -w "$ACME_DIR" -d "$DOMAIN" --cert-name "$DOMAIN" \
        --email "$EMAIL" --agree-tos --no-eff-email --non-interactive --keep-until-expiring \
        --deploy-hook "nginx -t -q && systemctl reload nginx"; then
        warn "certbot не смог получить сертификат (вывод выше). Оставляем HTTP; повторите запуск позже"
        return 0
    fi
    if [[ ! -f "/etc/letsencrypt/live/$DOMAIN/fullchain.pem" ]]; then
        warn "certbot завершился, но /etc/letsencrypt/live/$DOMAIN/fullchain.pem нет. Оставляем HTTP"
        return 0
    fi
    CERT_EXISTS=1
    done_step "получен сертификат Let's Encrypt для $DOMAIN"
    apply_nginx_site https
    if systemctl list-unit-files certbot.timer >/dev/null 2>&1 && systemctl is-enabled --quiet certbot.timer 2>/dev/null; then
        ok "продление: certbot.timer включён, после продления выполняется reload nginx"
    elif systemctl is-enabled --quiet snap.certbot.renew.timer 2>/dev/null || [[ -f /etc/cron.d/certbot ]]; then
        ok "продление сертификата настроено (таймер/cron certbot)"
    else
        warn "не найден таймер продления certbot. Проверьте: systemctl list-timers | grep certbot"
    fi
}

external_instructions() {
    header "Как подключить домен к вашему веб-серверу"
    say "  Порты 80/443 заняты не системным nginx ($DESC_80; $DESC_443)."
    say "  Мы его не трогали. Сайт работает на http://127.0.0.1:$PORT. Добавьте в ваш веб-сервер:"
    say ""
    say "  Caddy (Caddyfile), затем: caddy validate && systemctl reload caddy"
    say "      $DOMAIN {"
    say "          reverse_proxy 127.0.0.1:$PORT"
    say "          request_body { max_size 9MB }"
    say "      }"
    say ""
    say "  Apache (a2enmod proxy proxy_http; отдельный VirtualHost), затем: apache2ctl configtest && systemctl reload apache2"
    say "      <VirtualHost *:80>"
    say "          ServerName $DOMAIN"
    say "          ProxyPreserveHost On"
    say "          ProxyPass / http://127.0.0.1:$PORT/"
    say "          ProxyPassReverse / http://127.0.0.1:$PORT/"
    say "          RequestHeader set X-Forwarded-Proto expr=%{REQUEST_SCHEME}"
    say "      </VirtualHost>"
    say ""
    say "  nginx (в т.ч. в docker; из контейнера адрес хоста обычно 172.17.0.1, тогда сайт нужно"
    say "  слушать не на 127.0.0.1 — проще поставить прокси на хосте), отдельный server {}:"
    say "      server {"
    say "          listen 80;"
    say "          server_name $DOMAIN;"
    say "          location / {"
    say "              proxy_pass http://127.0.0.1:$PORT;"
    say "              proxy_set_header Host \$host;"
    say "              proxy_set_header X-Real-IP \$remote_addr;"
    say "              proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;"
    say "              proxy_set_header X-Forwarded-Proto \$scheme;"
    say "              client_max_body_size 9m;"
    say "          }"
    say "      }"
    say ""
    say "  Адрес сайта для ссылок записан как $(public_url). Если у вас только HTTP, поправьте"
    say "  AMAZAKE_WEB_PUBLIC_URL в $ENV_FILE и выполните: systemctl restart $APP_NAME"
}

summary() {
    local url="$1"
    header "Готово"
    say "  Сайт:                 $url"
    say "  Состояние сервиса:    systemctl status $APP_NAME"
    say "  Журнал:               journalctl -u $APP_NAME -f"
    say "  Настройки:            $ENV_FILE (после правки: systemctl restart $APP_NAME)"
    say ""
    say "  В amazake.yml на Minecraft-сервере:"
    say "    profiler:"
    say "      upload-url: \"$url\""
    if [[ -n "$TOKEN" ]]; then
        say "      upload-token: \"$TOKEN\""
        if (( GENERATE_TOKEN )); then
            say ""
            say "  ${C_B}Токен показан один раз. Сохраните его.${C_0} Он лежит в $TOKENS_FILE (читают только root и сайт)."
        fi
    elif [[ -f "$TOKENS_FILE" ]]; then
        say "      upload-token: \"<токен из $TOKENS_FILE: sudo cat $TOKENS_FILE>\""
    else
        say "      upload-token: \"\"   # сайт сейчас принимает отчёты без токена"
    fi
    say ""
    # --email/--no-https/--no-nginx are also remembered in install.conf; printed for clarity.
    local flags="--domain $DOMAIN${EMAIL:+ --email $EMAIL}"
    if (( ! WANT_HTTPS )); then flags+=" --no-https"; fi
    if (( FORCE_NO_NGINX )); then flags+=" --no-nginx"; fi
    say "  Обновить:  sudo git -C $SOURCE_DIR pull --ff-only && sudo bash $SCRIPT_DIR/install.sh $flags"
    say "  Удалить:   sudo bash $SCRIPT_DIR/uninstall.sh   (отчёты останутся; --purge удалит и их)"
    if (( ${#WARNINGS[@]} )); then
        say ""
        say "  Предупреждения:"
        printf '    - %s\n' "${WARNINGS[@]}"
    fi
    say ""
    say "  Другие сервисы (в том числе бот) не перезапускались и не останавливались."
}

main() {
    parse_args "$@"
    load_previous_install
    say "${C_B}Установка сайта профайлера Amazake${C_0} для $DOMAIN$( (( DRY_RUN )) && printf ' (dry-run: ничего не меняется)')"
    # Scratch space for read-only checks (nginx config test copy) and later for temp files.
    TMP_DIR="$(mktemp -d)"
    preflight

    if (( ${#BLOCKERS[@]} )); then
        header "Установка невозможна"
        printf '  - %s\n' "${BLOCKERS[@]}"
        if (( ! DRY_RUN )); then
            say "Ничего не изменено."
            exit 3
        fi
        say ""
        say "dry-run: ниже план для справки, но настоящий запуск остановился бы на этой проверке."
    fi

    show_plan
    confirm

    if (( ! DRY_RUN )); then
        acquire_lock
    fi
    INSTALL_STARTED=1
    header "Установка$( (( DRY_RUN )) && printf ' (dry-run)')"

    apt_install
    ensure_user
    ensure_dirs
    copy_code
    local url
    url="$(public_url)"
    write_tokens
    write_config "$url"
    install_service
    health_check

    if [[ "$WEB_MODE" == "nginx" ]]; then
        apply_nginx_site "$( (( CERT_EXISTS )) && echo https || echo http )"
        setup_https
        local new_url
        new_url="$(public_url)"
        if [[ "$new_url" != "$url" ]]; then
            url="$new_url"
            write_config "$url"
            CURRENT_STEP="перезапуск $APP_NAME с новым адресом"
            run systemctl restart "$APP_NAME.service"
            health_check
        fi
    else
        external_instructions
    fi

    if (( DRY_RUN )); then
        say ""
        if (( ${#BLOCKERS[@]} )); then
            say "dry-run закончен: ничего не изменено. Настоящий запуск сейчас остановился бы на проверке:"
            printf '  - %s\n' "${BLOCKERS[@]}"
            exit 3
        fi
        say "dry-run закончен: ничего не изменено. Уберите --dry-run, чтобы установить."
        return 0
    fi
    summary "$url"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
