#!/usr/bin/env bash
# Removes the Amazake profiler site installed by install.sh, and nothing else.
#
#   sudo bash web/deploy/uninstall.sh [--purge] [--dry-run] [--yes]
#
# Removes: amazake-web.service, our nginx site file (+ symlink, then "nginx -t && systemctl reload nginx"),
# /opt/amazake-web, /etc/amazake-web, the amazake-web user.
# Keeps: reports in /var/lib/amazake-web (unless --purge), the nginx and certbot packages, every other
# nginx file and every certificate. With --purge it also deletes /var/lib/amazake-web and the
# certificate that install.sh obtained for the site's domain.

# SC2001: "sed 's/^/  /'" is used on purpose to indent multi-line output.
# shellcheck disable=SC2001
set -Eeuo pipefail
umask 022

readonly APP_NAME="amazake-web"
readonly SERVICE_USER="amazake-web"
readonly APP_DIR="/opt/amazake-web"
readonly CONF_DIR="/etc/amazake-web"
readonly INSTALL_CONF="$CONF_DIR/install.conf"
readonly STATE_DIR="/var/lib/amazake-web"
readonly ACME_DIR="$STATE_DIR/acme"
readonly UNIT_FILE="/etc/systemd/system/amazake-web.service"
readonly MARKER="managed by amazake-web installer"

PURGE=0
DRY_RUN=0
ASSUME_YES=0
HAVE_SYSTEMD=0
DOMAIN=""
WEB_MODE=""
NGINX_FILES=()       # our nginx files (regular file and/or symlink)
CERT_OURS=0
DONE_STEPS=()
CURRENT_STEP="подготовка"
STARTED=0
TMP_DIR=""

if [[ -t 1 ]]; then
    C_B=$'\e[1m'; C_G=$'\e[32m'; C_Y=$'\e[33m'; C_R=$'\e[31m'; C_C=$'\e[36m'; C_0=$'\e[0m'
else
    C_B=""; C_G=""; C_Y=""; C_R=""; C_C=""; C_0=""
fi

say()    { printf '%s\n' "$*"; }
header() { printf '\n%s== %s ==%s\n' "$C_B" "$*" "$C_0"; }
ok()     { printf '  %s[ok]%s %s\n' "$C_G" "$C_0" "$*"; }
info()   { printf '  %s\n' "$*"; }
warn()   { printf '  %s[!]%s %s\n' "$C_Y" "$C_0" "$*"; }
fail()   { printf '%s[ошибка]%s %s\n' "$C_R" "$C_0" "$*" >&2; }
die()    { fail "$*"; report_progress; exit 1; }
done_step() {
    if (( DRY_RUN )); then ok "(dry-run) $*"; return 0; fi
    DONE_STEPS+=("$*"); ok "$*"
}

quote_cmd() {
    local out="" a
    for a in "$@"; do out+="$(printf '%q' "$a") "; done
    printf '%s' "${out% }"
}

run() {
    if (( DRY_RUN )); then
        printf '  %s[dry-run]%s %s\n' "$C_C" "$C_0" "$(quote_cmd "$@")"
    else
        "$@"
    fi
}

usage() {
    cat <<'EOF'
Удаление сайта профайлера Amazake, установленного install.sh. Больше ничего не трогает.

  sudo bash uninstall.sh [--purge] [--dry-run] [--yes]

  --purge      удалить и отчёты (/var/lib/amazake-web), и сертификат Let's Encrypt домена сайта
  --dry-run    только показать, что будет сделано
  --yes        не спрашивать подтверждение
  -h, --help   эта справка

Пакеты nginx и certbot, другие сайты nginx и другие сертификаты не удаляются никогда.
EOF
}

report_progress() {
    (( STARTED )) || return 0
    STARTED=0
    if (( ${#DONE_STEPS[@]} )); then
        printf 'Уже сделано:\n' >&2
        printf '  - %s\n' "${DONE_STEPS[@]}" >&2
    else
        printf 'Ничего не было изменено.\n' >&2
    fi
    printf 'Остальное НЕ сделано. Другие сервисы (в т.ч. бот) не затронуты. Можно запустить uninstall.sh ещё раз.\n' >&2
}

on_error() {
    local code="$1" line="$2" cmd="$3"
    trap - ERR
    fail "шаг «$CURRENT_STEP» не удался (строка $line, код $code): $cmd"
    report_progress
    exit "$code"
}
trap 'on_error $? $LINENO "$BASH_COMMAND"' ERR

cleanup() {
    if [[ -n "$TMP_DIR" && -d "$TMP_DIR" ]]; then rm -rf -- "$TMP_DIR"; fi
}
trap cleanup EXIT

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --purge)    PURGE=1; shift ;;
            --dry-run)  DRY_RUN=1; shift ;;
            -y|--yes)   ASSUME_YES=1; shift ;;
            -h|--help)  usage; exit 0 ;;
            *) fail "неизвестный параметр: $1"; usage >&2; exit 2 ;;
        esac
    done
}

conf_get() {
    local file="$1" key="$2"
    [[ -r "$file" ]] || return 0
    sed -n "s/^${key}=//p" "$file" | tail -n 1
}

is_ours() {
    [[ -f "$1" ]] && grep -q "$MARKER" "$1" 2>/dev/null
}

find_nginx_files() {
    local site link candidates=() c real
    site="$(conf_get "$INSTALL_CONF" NGINX_SITE)"
    link="$(conf_get "$INSTALL_CONF" NGINX_LINK)"
    [[ -n "$site" ]] && candidates+=("$site")
    [[ -n "$link" ]] && candidates+=("$link")
    candidates+=(/etc/nginx/sites-available/amazake-web.conf /etc/nginx/sites-enabled/amazake-web.conf
                 /etc/nginx/conf.d/amazake-web.conf)
    for c in "${candidates[@]}"; do
        case "$c" in /etc/nginx/*) ;; *) continue ;; esac
        if [[ -L "$c" ]]; then
            real="$(readlink -f "$c" || true)"
            # A symlink is ours only if it points to our file (or dangles where our file was).
            if [[ "$(basename "$c")" == "amazake-web.conf" ]] && { [[ ! -e "$real" ]] || is_ours "$real"; }; then
                NGINX_FILES+=("$c")
            fi
        elif is_ours "$c"; then
            NGINX_FILES+=("$c")
        fi
    done
    # Unique, keep order.
    if (( ${#NGINX_FILES[@]} )); then
        mapfile -t NGINX_FILES < <(printf '%s\n' "${NGINX_FILES[@]}" | awk '!seen[$0]++')
    fi
}

preflight() {
    header "Проверка (ничего не меняется)"
    if [[ "$(id -u)" -ne 0 ]]; then
        if (( DRY_RUN )); then warn "не root: показываю план, но для удаления нужен sudo"
        else die "нужны права root: запустите через sudo"; fi
    fi
    if [[ -d /run/systemd/system ]] && command -v systemctl >/dev/null 2>&1; then
        HAVE_SYSTEMD=1
    else
        warn "systemd не запущен: шаги с systemctl будут пропущены"
    fi
    DOMAIN="$(conf_get "$INSTALL_CONF" DOMAIN)"
    WEB_MODE="$(conf_get "$INSTALL_CONF" WEB_MODE)"
    if [[ -f "$INSTALL_CONF" ]]; then
        ok "найдена установка: домен ${DOMAIN:-?}, режим ${WEB_MODE:-?}"
    else
        warn "нет $INSTALL_CONF: удаляю то, что найду по известным путям"
    fi
    find_nginx_files
    if (( ${#NGINX_FILES[@]} )); then
        info "наши файлы nginx: ${NGINX_FILES[*]}"
    fi
    if (( PURGE )) && [[ -n "$DOMAIN" ]]; then
        local renewal="/etc/letsencrypt/renewal/$DOMAIN.conf"
        if [[ -f "$renewal" ]] && grep -q "$ACME_DIR" "$renewal"; then
            CERT_OURS=1
            info "сертификат $DOMAIN получен install.sh (webroot $ACME_DIR): будет удалён"
        elif [[ -f "$renewal" ]]; then
            warn "сертификат $DOMAIN есть, но получен не install.sh. Его не трогаем"
        fi
    fi
}

show_plan() {
    header "План"
    local i=1
    step() { printf '  %d. %s\n' "$i" "$*"; i=$((i + 1)); }
    step "остановить и отключить ТОЛЬКО $APP_NAME.service, удалить $UNIT_FILE"
    if (( ${#NGINX_FILES[@]} )); then
        step "удалить наши файлы nginx (${NGINX_FILES[*]}), nginx -t; если ок — systemctl reload nginx, если нет — вернуть файлы и остановиться"
    fi
    if (( CERT_OURS )); then step "certbot delete --cert-name $DOMAIN (только сертификат этого сайта)"; fi
    step "удалить $APP_DIR и $CONF_DIR"
    if (( PURGE )); then
        step "удалить $STATE_DIR (все отчёты)"
    else
        step "оставить $STATE_DIR (отчёты); удалить их: --purge"
    fi
    step "удалить пользователя $SERVICE_USER"
    step "пакеты nginx/certbot, другие сайты и сертификаты не трогаются"
    unset -f step
}

confirm() {
    (( DRY_RUN )) && return 0
    (( ASSUME_YES )) && return 0
    local answer=""
    [[ -r /dev/tty ]] || die "нет терминала для подтверждения; запустите с --yes"
    printf '\nПродолжить? [y/N] ' > /dev/tty
    read -r answer < /dev/tty || answer=""
    case "${answer,,}" in
        y|yes|д|да) ;;
        *) say "Отменено, ничего не изменено."; exit 0 ;;
    esac
}

remove_service() {
    CURRENT_STEP="сервис $APP_NAME"
    if (( HAVE_SYSTEMD )); then
        if systemctl list-unit-files "$APP_NAME.service" --no-legend 2>/dev/null | grep -q "$APP_NAME" \
            || [[ -f "$UNIT_FILE" ]]; then
            run systemctl disable --now "$APP_NAME.service" || true
        fi
    fi
    if [[ -f "$UNIT_FILE" ]]; then
        if is_ours "$UNIT_FILE"; then
            run rm -f -- "$UNIT_FILE"
        else
            warn "$UNIT_FILE не наш (нет пометки), не удаляю"
        fi
    fi
    if (( HAVE_SYSTEMD )); then
        run systemctl daemon-reload
        run systemctl reset-failed "$APP_NAME.service" 2>/dev/null || true
    fi
    done_step "сервис $APP_NAME удалён"
}

remove_nginx() {
    (( ${#NGINX_FILES[@]} )) || return 0
    CURRENT_STEP="файлы nginx"
    if (( DRY_RUN )); then
        run rm -f -- "${NGINX_FILES[@]}"
        run nginx -t
        run systemctl reload nginx
        return 0
    fi
    TMP_DIR="$(mktemp -d)"
    # Back up both symlinks and files so a failed "nginx -t" restores exactly what was there.
    tar -C / -cpf "$TMP_DIR/nginx-backup.tar" "${NGINX_FILES[@]#/}"
    rm -f -- "${NGINX_FILES[@]}"
    if command -v nginx >/dev/null 2>&1; then
        local out
        if ! out="$(nginx -t 2>&1)"; then
            tar -C / -xpf "$TMP_DIR/nginx-backup.tar"
            fail "после удаления нашего файла nginx -t выдаёт ошибку (значит, проблема в другой конфигурации). Наши файлы возвращены, nginx не перезагружался:"
            sed 's/^/    /' <<<"$out" >&2
            die "удаление остановлено"
        fi
        if (( HAVE_SYSTEMD )) && systemctl is-active --quiet nginx; then
            systemctl reload nginx
        fi
    fi
    done_step "наши файлы nginx удалены, nginx -t ок, reload"
}

remove_cert() {
    (( CERT_OURS )) || return 0
    CURRENT_STEP="сертификат $DOMAIN"
    if command -v certbot >/dev/null 2>&1; then
        run certbot delete --cert-name "$DOMAIN" --non-interactive
        done_step "сертификат $DOMAIN удалён"
    else
        warn "certbot не найден, сертификат $DOMAIN не удалён"
    fi
}

remove_dirs() {
    CURRENT_STEP="папки"
    local d
    for d in "$APP_DIR" "$APP_DIR.new" "$APP_DIR.old" "$CONF_DIR"; do
        if [[ -e "$d" ]]; then run rm -rf -- "$d"; fi
    done
    done_step "удалены $APP_DIR и $CONF_DIR"
    if (( PURGE )) && [[ -e "$STATE_DIR" ]]; then
        run rm -rf -- "$STATE_DIR"
        done_step "удалены отчёты $STATE_DIR"
    fi
}

remove_user() {
    CURRENT_STEP="пользователь $SERVICE_USER"
    if getent passwd "$SERVICE_USER" >/dev/null 2>&1; then
        if ! run userdel "$SERVICE_USER"; then
            # Only happens if something still runs as amazake-web; we do not kill processes here.
            warn "пользователь $SERVICE_USER не удалён (им ещё что-то запущено: ps -u $SERVICE_USER). Это безвредно; удалить позже: userdel $SERVICE_USER"
            return 0
        fi
    fi
    if getent group "$SERVICE_USER" >/dev/null 2>&1; then
        if (( DRY_RUN )); then
            run groupdel "$SERVICE_USER"
        else
            groupdel "$SERVICE_USER" 2>/dev/null || true
        fi
    fi
    done_step "пользователь $SERVICE_USER удалён"
}

main() {
    parse_args "$@"
    say "${C_B}Удаление сайта профайлера Amazake${C_0}$( (( DRY_RUN )) && printf ' (dry-run: ничего не меняется)')"
    preflight
    show_plan
    confirm
    STARTED=1
    header "Удаление$( (( DRY_RUN )) && printf ' (dry-run)')"
    remove_service
    remove_nginx
    remove_cert
    remove_dirs
    remove_user
    header "Готово"
    if (( ! PURGE )) && [[ -d "$STATE_DIR" ]]; then
        say "  Отчёты остались в $STATE_DIR (удалить: sudo bash $0 --purge или sudo rm -rf $STATE_DIR)."
    fi
    if [[ "$WEB_MODE" == "external" && -n "$DOMAIN" ]]; then
        say "  Сайт подключался к вашему веб-серверу вручную: уберите там блок для $DOMAIN сами."
    fi
    say "  Пакеты nginx и certbot не удалялись. Другие сервисы (в том числе бот) не затронуты."
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
