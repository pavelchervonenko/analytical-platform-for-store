# Pilot production deployment standard

Этот документ задаёт воспроизводимый порядок первого запуска, обновлений и восстановления
Store Analytics. Инвентаризация купленной инфраструктуры и уже выполненные security checks
ведутся в `production-deployment-runbook.md`.

## 1. Deployment contract

Production-релиз состоит из двух неизменяемых OCI images: `store-analytics-backend` и
`store-analytics-web`. Один backend image запускается в трёх ролях: `MIGRATION` как одноразовая
задача, `API` как единственный HTTP backend и `WORKER` для фоновой синхронизации, отчётов,
Telegram и LLM. Исходный код и build toolchain на VPS не нужны.

Канонические файлы:

- `frontend/Dockerfile` — Node 22.22 build с contract/lint/test/build gate и минимальный Caddy runtime;
- `backend/Dockerfile` — Java 21 build и непривилегированный JRE runtime;
- `deploy/compose.production.yml` — production topology, сети, лимиты, healthchecks и secrets;
- `deploy/Caddyfile` — single-origin HTTPS, security headers, request limit и скрытый actuator;
- `deploy/bin/deploy.sh` — pull, migrate, readiness, edge switch и smoke test;
- `deploy/bin/rollback.sh` — возврат к предыдущим images без обратного применения миграций;
- `deploy/bin/backup-postgres.sh` — custom dump, проверка, AES-256 encryption и upload verification;
- `.github/workflows/release-images.yml` — проверки и публикация images в GHCR по digest.

На VPS используется следующая раскладка:

```text
/opt/store-analytics/deploy/              versioned deployment artifacts
/etc/store-analytics/release.env          runtime configuration, root:root 0600
/etc/store-analytics/backup.env           backup configuration, root:root 0600
/etc/store-analytics/monitor.env          monitoring configuration, root:root 0600
/etc/store-analytics/secrets/*            one secret per file, root:root 0600
/etc/store-analytics/pki/postgresql-ca.crt DBaaS CA, root:root 0644
/var/lib/store-analytics/backup-tmp        encrypted-backup workspace
/var/lib/store-analytics/release-state     current and previous release coordinates
```

Только `web` публикует host ports 80/443. API доступен Caddy по внутренней сети; worker,
migration и management endpoints не публикуются. API и worker используют `store_runtime`, а
migration — `store_migrator`. Пароли и provider tokens монтируются как Docker secrets с именами
Spring properties и читаются через `configtree:/run/secrets/`.

## 2. Порядок выпуска и обновления

Каждое изменение проходит одинаковый путь:

1. CI проверяет backend, Flyway migrations, OpenAPI contracts, frontend, lint и tests.
2. Release workflow собирает images один раз и публикует их в GHCR.
3. В release env записываются полные references `repository@sha256:digest`, а не mutable tags.
4. Перед изменением приложения создаётся и проверяется logical backup.
5. `deploy.sh` скачивает images и запускает `MIGRATION` отдельно от runtime.
6. После успешной миграции поднимаются API/worker; Caddy запускается только после API readiness.
7. Внешний smoke test проверяет HTTPS, liveness, readiness, HSTS и недоступность actuator.
8. Release env сохраняется как `current.env`; предыдущее значение — как `previous.env`.

Миграции обязаны быть backward-compatible по схеме expand → migrate/backfill → contract.
Удаление/переименование столбца не совмещается с релизом, который перестаёт его использовать.
Поэтому container rollback безопасен только в пределах явно сохранённой совместимости схемы;
`rollback.sh` намеренно не выполняет Flyway undo.

Изменения выполняются в согласованное окно 22:00–06:00 `Europe/Kaliningrad`. Для обычного
релиза ожидается кратковременный restart на single-VM topology. Перед релизом фиксируются release
ID, digests, backup object key и оператор; после — smoke evidence и время завершения.

## 3. Первый pilot rollout

Первый запуск выполняется с выключенными schedule, Telegram delivery и Yandex generation.
После миграции и создания трёх именных manager accounts включение идёт по этапам:

1. загрузить и сверить справочники двух магазинов — «Магазин» и «Мобисфера»;
2. выполнить управляемый backfill с 2026-07-01 по последний полностью завершённый день;
3. сверить продажи, возвраты, деньги, сотрудников и payroll на контрольных датах;
4. включить nightly schedule с первым запуском в 03:15 `Europe/Kaliningrad`,
   recovery-проверками до 08:15, overlap 3 дня и шестичасовыми дочерними окнами;
5. включить snapshot/publication, затем Yandex generation с лимитом 30 RUB/job и максимумом
   двух provider calls;
6. включить Telegram linking/webhook, связать каждого руководителя с собственной учётной записью;
7. включить fanout/delivery, затем daily pulse;
8. данные за январь–июнь 2026 загружать отдельными месячными окнами с повторной сверкой;
   backfill остаётся идемпотентным и не заменяет текущую nightly sync.

Общий месячный лимит Yandex AI контролируется также в Yandex Cloud billing budget на уровне
500–600 RUB. Application per-job guard не является заменой provider-side budget alert.

## 4. Backup и recovery standard

DBaaS physical backups остаются первым быстрым слоем восстановления. Дополнительно systemd timer
создаёт ежедневный logical backup схемы `app`: `pg_dump` custom format → `pg_restore --list` →
GPG AES-256 → SHA-256 manifest → private S3 → `head-object` size verification. Encryption
passphrase хранится отдельно от S3 credentials и передаётся заказчику через защищённый канал.

Object Lock 48 часов защищает свежие objects. Lifecycle нельзя включать короче Object Lock.
До включения automatic deletion проводится restore drill в изолированный PostgreSQL 16 и
фиксируются фактические RPO/RTO. Рекомендуемая схема после drill: daily 14 дней, weekly 8 недель,
monthly 12 месяцев. При лимите bucket 100 GB нужен alert не позднее 70/85/95%.

## 5. Secret delivery

Секреты не передаются в чат и не коммитятся. На VPS должны существовать root-owned files для:

- runtime, migration и backup PostgreSQL passwords;
- LiveSklad login/password;
- Yandex API key;
- Telegram bot token и webhook secret;
- security telemetry pseudonym key и Prometheus scrape token;
- одноразового bootstrap admin password;
- backup encryption passphrase;
- Telegram chat ID разработчика для technical alerts.

Разработчик сначала отправляет `/start` боту `@store_analytics_notify_bot`, после чего его chat ID
сохраняется в отдельный root-owned file. Bootstrap password после первого входа меняется;
`BOOTSTRAP_ADMIN_EMAIL` очищается, а password file заменяется пустым file с mode `0600`.

## 6. Launch gates

- [ ] CI и release workflow зелёные; images указаны по digest.
- [ ] DNS указывает только A record на `92.53.127.24`; HTTPS certificate выпущен.
- [ ] Снаружи доступны только 22/80/443 и ограниченный Timeweb agent 10050.
- [ ] Flyway выполнен `store_migrator`; API/worker используют только `store_runtime`.
- [ ] Три manager accounts созданы и каждый сменил initial password.
- [ ] Первый encrypted logical backup загружен; isolated restore drill прошёл.
- [ ] Public smoke, TLS, security headers и hidden actuator проверены.
- [ ] Technical alert и recovery доставлены разработчику через Telegram.
- [ ] July/August контрольные данные сверены до включения nightly schedule.
- [ ] Telegram и Yandex AI включены поэтапно, а не одновременно с первым запуском.
- [ ] Timeweb billing, domain expiry и Yandex budget alerts направлены минимум двум ответственным.
