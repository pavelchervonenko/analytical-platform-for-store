---
doc_schema: 1
doc_type: evidence
status: historical
owner: operations
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved legacy evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/runbooks/production-deployment.md"
original_content_sha256: acbd9350a289ad278495c82f578cfc588447e2a054c0208b0e8ff8f2c41f22b7
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/runbooks/production-deployment.md`.

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
6. После успешной миграции сначала поднимается и проходит healthcheck API; только затем запускается
   worker, чтобы фоновые задания не начали работу на непроверенном runtime.
7. Внешний smoke test проверяет HTTPS, liveness, readiness, HSTS и недоступность actuator.
8. Release env сохраняется как `current.env`; предыдущее значение — как `previous.env`.

Миграции обязаны быть backward-compatible по схеме expand → migrate/backfill → contract.
Удаление/переименование столбца не совмещается с релизом, который перестаёт его использовать.
Поэтому container rollback безопасен только в пределах явно сохранённой совместимости схемы;
`rollback.sh` намеренно не выполняет Flyway undo.

Перед каждой миграцией оператор выполняет тот же обязательный preflight, который встроен в
`deploy.sh`:

```bash
sudo /opt/store-analytics/deploy/bin/provision-release-secrets.sh
sudo /opt/store-analytics/deploy/bin/preflight-release.sh \
  /etc/store-analytics/release.env
sudo /opt/store-analytics/deploy/bin/deploy.sh \
  /etc/store-analytics/release.env
```

Preflight проверяет права release env, наличие и формат всех secret files, включая два отдельных
LiveSklad webhook secrets, CA-файл, schema compatibility metadata и полный `docker compose config`.
Любая ошибка останавливает релиз до Flyway. Release env обязан содержать:

- `SCHEMA_VERSION` — версию Flyway, упакованную в backend image;
- `MIGRATION_SOURCE_MIN_VERSION` — минимальную схему, с которой разрешён forward migration;
- `RUNTIME_SCHEMA_MIN_VERSION` и `RUNTIME_SCHEMA_MAX_VERSION` — диапазон схем, на котором image
  разрешено запускать.

После Flyway `deploy.sh` сохраняет фактическую версию в
`/var/lib/store-analytics/release-state/database-schema-version`. `rollback.sh` запускает старый
image только если сохранённая версия входит в его runtime-диапазон; отсутствие metadata считается
несовместимостью, а не разрешением на рискованный rollback.
Непосредственно перед Flyway файл получает marker `MIGRATION_IN_PROGRESS`. Если миграция оборвалась,
и rollback, и автоматический forward-fix остаются заблокированы до проверки реальной версии в
`flyway_schema_history`; старое сохранённое число повторно не используется.

Для перехода production с V39.1 на V42 текущий V39.1 image не объявлен совместимым с V42. Поэтому
после успешной миграции V42 container rollback к нему запрещён. Если новый runtime не проходит
readiness или smoke, исправление выпускается только вперёд из проверенного candidate env:

```bash
sudo /opt/store-analytics/deploy/bin/forward-fix.sh \
  /etc/store-analytics/forward-fix.env
```

V39.1 остаётся в image с исходным checksum, а V42 идемпотентно завершает обе допустимые линии
`database-schema-version` вручную не редактируется. При повреждении данных применяется restore из
проверенного pre-deployment backup, а не container rollback.

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

DBaaS physical backups остаются первым быстрым слоем восстановления. Дополнительно systemd time
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
- отдельные LiveSklad sale-return и order-return webhook secrets;
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

## 7. Production evidence: восстановление nightly sync 2026-08-10

Инцидент: плановые задачи 2026-08-10 не смогли завершить синхронизацию за 9 августа из-за
`LIVESKLAD_RATE_LIMIT`. Ранее повтор выполнял то же крупное окно и терял прогресс текущего окна.

В production выпущен backend `v0.1.0-pilot.8` из commit `e6a3126` с image ID
`sha256:25125e0c32df203e7e9b9251b407aa35353a85ac810f2b825205634094bf618a`. Перед релизом
создан и проверен encrypted logical backup:

```text
s3://5e8de462-4a0c-42a7-9a3b-e4d432c18eaf/postgres/daily/2026/08/10/
store-analytics-20260810T092606Z.dump.gpg
```

Runtime использует шестичасовые окна. При provider rate limit worker сохраняет завершённые
шаги, уменьшает только текущее окно вдвое и следует `Retry-After`. Recovery cron запускается
ежечасно с 03:15 до 08:15 `Europe/Kaliningrad`; создание overlap-задач остаётся идемпотентным.

Точечный backfill за 9 августа:

- job `45d41d1e-f5dc-4fff-948e-a4c0ae1dcbfc`;
- итог `SUCCESS`, 12 завершённых шагов, один контролируемый retry;
- окно `16:00–22:00 Europe/Kaliningrad` после rate limit уменьшилось с 6 до 3 часов;
- «МАГАЗИН»: 49 документов; «МобиСфера»: 17 документов; deleted documents: 0.

Автоматический weekly pipeline за 3–9 августа после backfill:

- оба snapshot jobs завершились `SUCCESS/CREATED`, snapshots имеют revision 1 и
  `quality_status=PARTIAL` из-за реальных ограничений исходных данных;
- обе Yandex AI jobs завершились `SUCCESS/PUBLISH` без validation retry;
- опубликованы interpretation revision 1 для обоих магазинов;
- структурная проверка без вывода персональных текстов: «МАГАЗИН» — 6 сотрудников и 12
  employee summary blocks, «МобиСфера» — 3 сотрудника и 6 employee summary blocks;
- необоснованные optional `teamRelationships` удалены валидатором без повторного provider call.

Acceptance: внешний HTTPS, `/livez` и `/readyz` отвечали HTTP 200 во время backfill; интерфейс
оставался доступен руководителям. Повторная полная загрузка июля/августа не выполнялась.


## 8. Production evidence: automatic product classification 2026-08-10

В production выпущен backend `v0.1.0-pilot.9` из commit `732bdc0`, image ID
`sha256:5e58a342c0d72def4af3b4f1685549c1f1986fa1fbe3be557c5189cadbf61115`.
Web оставлен на проверенном `v0.1.0-pilot.6`. До изменения создан и проверен encrypted backup
`postgres/daily/2026/08/10/store-analytics-20260810T125754Z.dump.gpg`.

Классификация выполняется в порядке: exact customer-approved product assignment, затем
`livesklad-product-rules-v1`, затем безопасный `UNMAPPED` для неоднозначного товара. Production
reconciliation был ограничен утверждёнными 36 external IDs и expected count 44. Транзакция
завершилась с `44/44`, unresolved 0 и закрыла 36 соответствующих data-quality issues. Итоговое
распределение совпало с dry-run по всем девяти category/condition группам; глобальные active
`UNMAPPED` и open `UNMAPPED_PRODUCT` равны нулю. Повторный provider sync не выполнялся.

После acceptance one-shot properties возвращены в `false/empty/0`, worker пересоздан и повторно
прошёл readiness. Сохранённые production flags подтверждены: nightly sync, snapshot, Yandex AI
generation и publication включены. API, worker и web healthy; public HTTPS, liveness и readiness
smoke прошли. Previous release pilot.8 сохранён для container rollback.

Release gates: полный backend suite — 749 tests, 0 failures/errors/skips; Checkstyle — success;
operator security — success; Gradle supply-chain — 449 components и 840 artifacts.
