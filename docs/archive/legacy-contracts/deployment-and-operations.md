---
doc_schema: 1
doc_type: archive
status: archived
owner: operations
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/runbooks/README.md"
original_content_sha256: 6cc40783a06feb36fbd0b4e0fcad0c8785daeb333f4b6295faed8884a178a2bc
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/runbooks/README.md`.

# Production deployment and operations

Status: current production architecture and operational standard, 2026-08-24.

Практические команды находятся в
[production-deployment-runbook.md](production-deployment-runbook.md). Этот документ фиксирует
топологию, ответственность, безопасность, backup/recovery и правила изменений.

## 1. Текущее production-состояние

- origin: `https://store-analytics.net`;
- release: `v0.1.0-pilot.22`, commit `2e8f9c2`;
- schema: Flyway `V44`;
- application host: Ubuntu 24.04 LTS;
- database: managed PostgreSQL 16 по private network, TLS `verify-full`;
- containers: `web`, `backend-api`, `backend-worker`;
- edge: Caddy, automatic HTTPS и same-origin SPA/API;
- encrypted logical backup timer и application health timer включены;
- SSH: public-key only; root/password login disabled; UFW ограничивает ingress.

Текущий релиз-кандидат еще не изменил production. Его состав:
[RELEASE_CANDIDATE_2026-08-24.md](../../history/releases/2026/08/RELEASE_CANDIDATE_2026-08-24.md).

## 2. Топология

```text
Internet
  |
  v
Caddy/web (80/443)
  |-- static SPA
  +-- /api, /livez, /readyz -> backend-api (private)
                                  |
LiveSklad -> webhook endpoint ----+
                                  v
                          managed PostgreSQL
                                  ^
                                  |
                     backend-worker (private)
                     sync / webhook / LLM / Telegram / maintenance
```

Management ports, worker HTTP и PostgreSQL не публикуются в internet. Caddy — единственный trusted
proxy. `TRUSTED_PROXY_CIDRS` содержит только выделенную container subnet.

## 3. Runtime-роли

Один backend image запускается с разными ролями:

| Роль | Назначение | Flyway |
| --- | --- | --- |
| `API` | HTTP, auth, KPI, admin mutations, webhook receiver | disabled |
| `WORKER` | schedulers и durable workers | disabled |
| `MIGRATION` | one-shot Flyway/expected-schema check | enabled |
| `COMBINED` | локальная разработка | по локальной конфигурации |

Порядок production startup: migration → API healthy → worker healthy → web → smoke. API и worker
проверяют packaged schema version read-only.

## 4. Database roles

- migrator владеет schema и применяет DDL;
- runtime получает только необходимые DML/sequence/schema-history read permissions;
- backup reader имеет read-only доступ;
- после каждой миграции `repair-production-database-acls.sh` восстанавливает минимальные ACL;
- connection использует pinned CA и hostname verification;
- credentials разделены и доставляются Docker secrets/config-tree.

Runtime login не получает `CREATE`, `TRUNCATE`, superuser или role-management privileges.

## 5. Immutable artifacts и release metadata

Production не собирает source. CI/local release flow создает и публикует immutable images, а
release env фиксирует digest.

Обязательные metadata:

- `RELEASE_ID`;
- `BACKEND_IMAGE`, `BACKEND_IMAGE_DIGEST`;
- `WEB_IMAGE`;
- `SCHEMA_VERSION`;
- compatible migration source/runtime schema ranges;
- feature flags без секретных значений.

Released `contracts/openapi/baselines/` неизменяемы. Совместимое API-изменение обновляет current
artifact; breaking change требует нового baseline и явной версии.

## 6. Secrets

Root-owned secret files находятся вне Git и имеют минимальные права. В частности:

- runtime/migrator DB passwords;
- LiveSklad login/password;
- отдельные sale-return и order-return webhook secrets;
- Yandex API key;
- Telegram bot/webhook secrets;
- security telemetry pseudonym key;
- Prometheus scrape token;
- bootstrap password при его использовании;
- backup encryption/S3 credentials.

Нельзя:

- хранить value в Compose, source, image layer, Markdown или ticket;
- передавать secret command-line argument;
- печатать весь release env;
- копировать development `.env` в production;
- повторно использовать sale secret для order endpoint.

Secret rotation выполняется контролируемо через current/previous secret и завершается удалением
grace value после проверки.

## 7. LiveSklad synchronization и webhook

Production flags на 2026-08-24:

- `LIVESKLAD_WEBHOOK_ENABLED=true`;
- `LIVESKLAD_WEBHOOK_WORKER_ENABLED=true`;
- `LIVESKLAD_ORDER_RETURN_WEBHOOK_WORKER_ENABLED=false`;
- scheduled sync enabled;
- incremental overlap: три дня.

Webhook — быстрый путь коррекции. Плановый overlap остается recovery source of truth для последних
дней. Backfill и историческая CRM-сверка обязательны для периода до включения webhook и для
документов, отсутствующих в vendor list feed.

Order worker включается отдельным canary. Validated manual recovery — отдельная ADMIN-операция с
positive expected amount/position count и idempotency key.

## 8. LLM и Telegram

Внешние интеграции асинхронны и не блокируют KPI/login/reports.

- production ИИ default: prompt v4/content schema 2;
- `v21/schema3` имеет evaluation evidence, но требует отдельного rollout;
- snapshot/job/attempt/publication immutable или durable в соответствии с lifecycle;
- provider calls ограничены timeout, размером, retry и cost budget;
- Telegram delivery использует outbox, lease, retry, TTL и operator recovery;
- токены/chat IDs/raw provider payload не показываются manager UI.

Каждое включение нового worker/contract выполняется отдельным feature-flag change с canary и
rollback.

## 9. Backup

Минимальный pipeline:

1. `pg_dump --format=custom` от read-only backup role;
2. `pg_restore --list`;
3. шифрование до upload;
4. SHA-256 manifest;
5. upload во временный/целевой private object;
6. `head-object`/checksum verification;
7. retention/versioning/Object Lock;
8. alert при failure/stale backup.

Provider physical backup не заменяет логический encrypted backup. Backup считается рабочим только
при наличии свежего restore evidence.

Целевые RPO/RTO: один час / четыре часа. Фактические значения подтверждаются drill, а не
конфигурацией timer.

## 10. Restore и аварии

### Отказ контейнера

Compose restart policy и health monitor восстанавливают процесс. При повторяющемся crash — не
перезапускать бесконечно, сохранить logs/correlation IDs и выполнить rollback/forward fix.

### Ошибка релиза

- если schema совместима — `deploy/bin/rollback.sh`;
- если previous runtime несовместим — reviewed `deploy/bin/forward-fix.sh`;
- нельзя обходить schema guard или редактировать release state вручную.

### Потеря application VM

Восстановить host hardening, Docker, deployment/config roots, CA и secret files из независимого
источника; затем deploy exact current digests. База не восстанавливается без необходимости.

### Потеря/повреждение DB

Остановить writers, выбрать проверенный backup, восстановить в изолированную DB, проверить schema,
integrity и business reconciliation, затем переключить endpoint по incident plan.

### Компрометация credentials

Отозвать credential у provider, выпустить новый, обновить root-owned secret, перезапустить только
нужные services, проверить audit и убрать previous secret после grace.

## 11. Observability

- public `/livez` и `/readyz` не раскрывают детали;
- Prometheus scrape защищен и доступен только monitoring boundary;
- metric labels low-cardinality, без store/user/job/correlation IDs;
- logs structured, secret/raw payload redaction fail-closed;
- correlation ID связывает safe client error и server log;
- LiveSklad health не входит в liveness, чтобы upstream outage не создавал restart loop.

Обязательные сигналы:

- public HTTPS/readiness/certificate;
- container health/restart;
- DB connectivity/schema mismatch/pool saturation;
- failed/stale sync;
- webhook stale, terminal failure, expired lease, payload mismatch;
- backup failure/age;
- LLM/Telegram terminal delivery failures и budget.

## 12. Resource baseline

Compose задает read-only filesystem, dropped capabilities, `no-new-privileges`, tmpfs, PID/CPU/RAM
limits и bounded logs. API/worker имеют разные DB pools и CPU/memory budgets.

HTTP body, headers, parameter count, LiveSklad response bytes/complexity, raw payload, collection
cardinality и provider output ограничены. Лимит повышается только по измеренным valid payloads.

## 13. Security baseline

- externally open: 80/443 и rate-limited key-only SSH; provider monitoring — только allowlist;
- no public DB/API management/worker ports;
- cookie `Secure`/`HttpOnly`, CSRF и same origin;
- route default deny, ADMIN/store authorization;
- SSRF host allowlist и HTTPS-only LiveSklad;
- webhook exact secret, body bound, durable replay/dedup;
- no multipart upload boundary;
- dependency locking/verification, SBOM/scan evidence;
- secrets/personal data не включаются в screenshots или logs.

Подробности: [security-hardening.md](security-hardening.md).

## 14. Ownership и change management

Cloud account, domain, database, object storage и external provider accounts принадлежат заказчику.
Есть минимум два ответственных за billing/recovery. Developer/operations access временный,
именованный и отзываемый.

Каждая production mutation имеет:

- цель и scope;
- точный commit/digest/config delta;
- preflight/backup evidence;
- owner и окно наблюдения;
- acceptance и rollback/forward-fix;
- запись результата.

Deploy, backfill, historical recovery, order canary и AI activation не объединяются в одну
неразличимую операцию.

## 15. Release checklist

- [ ] reviewed commit и clean expected tree;
- [ ] fetch/divergence проверены;
- [ ] backend/frontend/contract/security checks green;
- [ ] material UI прошел local visual review;
- [ ] immutable images и scan/SBOM evidence;
- [ ] release env/secret files provisioned;
- [ ] Compose preflight до migration;
- [ ] fresh backup и health;
- [ ] previous runtime/schema compatibility известна;
- [ ] deploy script завершился;
- [ ] public и authenticated smoke;
- [ ] flags соответствуют change plan;
- [ ] rollback window закрыта без регрессии;
- [ ] change record обновлен.

Историческая детализация первоначального pilot находится в датированных
`PRODUCTION_*.md`/release-файлах и Git history; она не должна заменять этот действующий стандарт.
