# Сайт профайлера Amazake: бэкенд

`amazake_web.py` принимает отчёты `/amazake profile` от сервера, хранит их и отдаёт просмотрщик
(`web/static/`) по адресу `/r/<id>`. Формат отчёта и API описаны в `docs/profiler-format.md`.

Нужен только Python 3.8+ из системы. **Никаких `pip install`, venv и новых пакетов не требуется**,
системный Python не меняется, поэтому соседние программы на VPS (например, Discord-бот) не задеваются.

## Запуск локально

```bash
cd web/server
python3 amazake_web.py --data-dir /tmp/x --port 8787
```

Откройте http://127.0.0.1:8787/ . Проверка: `curl http://127.0.0.1:8787/healthz` отвечает `ok`.
Остановка: Ctrl+C (или `SIGTERM`, сервер завершается аккуратно).

Тесты (из корня репозитория):

```bash
python3 -m unittest discover -s web/server -v
```

## Настройки

Каждый флаг можно задать и переменной окружения `AMAZAKE_WEB_*` (флаг важнее).

| Флаг | Переменная | По умолчанию | Что делает |
|---|---|---|---|
| `--host` | `AMAZAKE_WEB_HOST` | `127.0.0.1` | Адрес. Оставьте `127.0.0.1` и поставьте nginx впереди |
| `--port` | `AMAZAKE_WEB_PORT` | `8787` | Порт |
| `--data-dir` | `AMAZAKE_WEB_DATA_DIR` | `./data` | Где лежат отчёты (`reports/<id>.json.gz`) |
| `--static-dir` | `AMAZAKE_WEB_STATIC_DIR` | `../static` от этого файла | Файлы просмотрщика |
| `--public-url` | `AMAZAKE_WEB_PUBLIC_URL` | из заголовка `Host` | Адрес сайта для ссылок, например `https://example.com` |
| `--token-file` | `AMAZAKE_WEB_TOKEN_FILE` | нет | Файл с токенами (по одному в строке, `#` — комментарий). Если задан, загрузка требует `X-Amazake-Token` |
| `--max-bytes` | `AMAZAKE_WEB_MAX_BYTES` | 8 МиБ | Предельный размер отчёта в распакованном виде |
| `--retention-days` | `AMAZAKE_WEB_RETENTION_DAYS` | `30` | Через сколько дней удалять отчёты, `0` — хранить всегда |
| `--rate-limit` | `AMAZAKE_WEB_RATE_LIMIT` | `30` | Загрузок в час с одного IP (для IPv6 — с одной сети /64), `0` — без ограничения |
| `--max-total-mb` | `AMAZAKE_WEB_MAX_TOTAL_MB` | `1024` | Сколько МиБ на диске могут занимать все отчёты вместе, `0` — без ограничения |
| `--min-free-mb` | `AMAZAKE_WEB_MIN_FREE_MB` | `1024` | Не принимать отчёты, если на диске останется меньше стольких МиБ, `0` — не проверять |

## Защита от перегрузки

Сайт сам следит, чтобы чужие загрузки не заняли память и диск, нужные другим программам на VPS:

- Отчёт разбирается только один за раз. До разбора сайт оценивает, сколько памяти займёт JSON,
  и отвечает `413`, если слишком много (обычный отчёт до 8 МиБ проходит). Так сайт держится
  примерно в 150 МБ памяти даже под атакой, в том числе при запуске вручную, без systemd.
- Отчёты отдаются с диска по частям, поэтому много одновременных просмотров не раздувают память.
- Ограничение частоты помнит не больше 10 000 адресов; адреса IPv6 считаются по сетям /64.
- Когда отчёты заняли `--max-total-mb` или свободного места на диске меньше `--min-free-mb`,
  новые отчёты не принимаются (ответ `507`, в журнале строка `upload refused`), старые остаются.
  Место освобождается само, когда истекает срок хранения (`--retention-days`).
- Если загружать отчёты будете только вы, включите токен (`--token-file`): тогда посторонние
  вообще не смогут ничего записать на ваш сервер.

## Как не задеть Discord-бота на том же VPS

- Перед установкой посмотрите, какие порты уже заняты: `ss -ltnp`. Если `8787` занят ботом,
  выберите другой порт (`--port`). Сайт сам по себе слушает только `127.0.0.1`.
- Ставьте сайт отдельным сервисом systemd под отдельным пользователем. **Не перезапускайте и не
  останавливайте другие сервисы**, не трогайте файлы бота, не делайте `apt upgrade` и `pip install`.
  Ниже пример юнита: он ограничивает память и CPU сайта, чтобы даже под нагрузкой он не отнял их у бота.
- Если в nginx уже есть конфиг бота, добавьте сайт отдельным файлом `server { ... }`, проверьте
  `sudo nginx -t` и только потом `sudo systemctl reload nginx` (reload, не restart).

Пример `/etc/systemd/system/amazake-web.service` (пути поправьте под себя):

```ini
[Unit]
Description=Amazake profiler site
After=network.target

[Service]
User=amazake-web
Group=amazake-web
WorkingDirectory=/opt/amazake/web/server
ExecStart=/usr/bin/python3 /opt/amazake/web/server/amazake_web.py --data-dir /var/lib/amazake-web --port 8787 --public-url https://example.com
Restart=on-failure
MemoryMax=300M
CPUQuota=50%
Nice=10
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/amazake-web
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

```bash
sudo useradd --system --home /var/lib/amazake-web --shell /usr/sbin/nologin amazake-web
sudo install -d -o amazake-web -g amazake-web -m 750 /var/lib/amazake-web
sudo systemctl daemon-reload
sudo systemctl enable --now amazake-web     # затрагивает только этот сервис
journalctl -u amazake-web -f                # журнал сайта
```

В nginx передавайте адрес клиента, сайт верит этим заголовкам только от `127.0.0.1`:

```nginx
location / {
    proxy_pass http://127.0.0.1:8787;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    client_max_body_size 9m;
}
```
