---
doc_schema: 1
doc_type: runbook
status: draft
owner: operations
audience:
  - operator
last_verified: 2026-08-31
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - staging
  - production-read-only
operation_type: migration
environments:
  - staging
  - production
risk_level: high
source_of_truth:
  - deploy/bin/deploy.sh
  - deploy/bin/preflight-release.sh
  - deploy/bin/release-safety.sh
  - deploy/compose.production.yml
verification_evidence:
  - level: static
    scope: deploy order, schema compatibility guards and secret preflight
    verified_at: 2026-08-31
    evidence: scripts/tests/deploy-release-safety-test.sh
required_reviewers:
  - operations
  - security-privacy
review_triggers:
  - deployment-change
  - migration
  - release-metadata-change
supersedes: []
superseded_by: null
---

# Развёртывание production-релиза

## Цель и область

Развернуть exact immutable backend/web images через штатный deploy path. Процедура включает Flyway
migration и не является разрешением на запуск без свежего backup/restore evidence и change owner.

**Текущий authorization status: NO-GO для production write.** Репозиторный preflight пока не
закрывает три обязательных gate: совместимость работающего runtime с target schema на время
migration, exact-target ACL repair и доказуемую связь release env → digest → commit. До исправления
кода deploy path разделы ниже являются проектом безопасной процедуры и read-only checklist, а не
разрешением запускать `deploy.sh`.

## Влияние и требуемая авторизация

- Затрагиваются production database schema, API, worker и web.
- Запуск подтверждают operations owner и reviewer релиза; migration требует backup owner.
- Возможна недоступность API и остановка background jobs.

## Предусловия

- Reviewed tag/commit, CI, exact `repository@sha256` coordinates и schema compatibility matrix.
- Current API/worker runtime явно поддерживает target schema во время Flyway. Если его
  `RUNTIME_SCHEMA_MAX_VERSION` ниже target, штатный `deploy.sh` запускать нельзя: старый runtime
  остаётся работающим во время migration.
- Fresh backup checkpoint с доказанной возможностью restore; отсутствие активной migration/recovery.
- Release env — root-owned regular file `0600`; секреты provisioned отдельно.
- ACL repair получает тот же exact DB target и роли из проверенного release/config, без
  infrastructure defaults.
- Backend digest внутри image, release metadata и `BACKEND_IMAGE` доказуемо относятся к одному
  reviewed commit.
- Назначены observer, rollback/forward-fix owner и окно наблюдения.

## Критерии остановки

- Target host/database/release не совпал с change record.
- `database-schema-version` отсутствует, нечисловой или равен `MIGRATION_IN_PROGRESS`.
- Live Flyway history содержит failed migration или расходится с state-файлом.
- Current running runtime не поддерживает target schema, а deploy path не останавливает writers до
  migration.
- ACL repair использует hardcoded/default DB target или роли, не сверенные с change record.
- Backup/restore, image provenance, Compose config или required secrets не подтверждены.
- Preflight не проверил owner/mode release env, immutable image references и соответствие
  `BACKEND_IMAGE_DIGEST` фактическому `BACKEND_IMAGE`.
- В очередях выполняется конфликтующая recovery/backfill, которую change owner не разрешил.

## Preflight

На exact host сохранить только sanitized вывод:

```bash
sudo /opt/store-analytics/deploy/bin/preflight-release.sh /etc/store-analytics/release.env
sudo stat --format='%U:%G %a %F' /etc/store-analytics/release.env
sudo docker compose --env-file /etc/store-analytics/release.env \
  -f /opt/store-analytics/deploy/compose.production.yml --profile tools config --quiet
sudo systemctl show store-analytics-backup.timer store-analytics-backup.service \
  --property=ActiveState,Result,LastTriggerUSec --no-pager
```

Эти команды не закрывают gate автоматически. Reviewer отдельно сверяет:

- owner/mode равны approved host policy;
- backend/web references имеют форму `repository@sha256:<64 hex>`;
- target schema не выше runtime max работающих API/worker;
- declared backend digest равен digest в image reference и release provenance;
- ACL script target/roles совпадают с change record без fallback defaults.

Read-only ролью проверить последнюю успешную Flyway version и отсутствие failed rows. Любой
непроверяемый пункт означает stop, а не ручной обход. Compose сейчас не передаёт
`RUNTIME_SCHEMA_MAX_VERSION` приложению, поэтому рабочей команды для проверки current runtime max
нет; это часть NO-GO gap, а не значение, которое operator должен угадывать.

## Точный target

Change record обязан содержать host fingerprint, environment, database host/name, release ID,
commit/tag, backend/web digests, source/target schema, release-env SHA-256 и время окна. Любое
расхождение останавливает процедуру.

## Процедура

1. Зафиксировать preflight/backup evidence и повторно подтвердить exact target.
2. Только после реализации и проверки трёх NO-GO gates запустить один раз:
   `sudo /opt/store-analytics/deploy/bin/deploy.sh /etc/store-analytics/release.env`.
3. Не запускать второй deploy, пока первый не завершён или неизвестная точка сбоя не исследована.
4. Проверить container image digests/health, public smoke и live Flyway version.
5. Сравнить разрешённые feature flags и critical queue counts с change record.

## Проверка результата

- API и worker используют ожидаемый backend digest, web — ожидаемый web digest.
- `/livez` и `/readyz` отвечают успешно; schema history совпадает с packaged expected version.
- Нет новых failed jobs/migrations, smoke проверяет HTTPS headers и закрытый public Prometheus.
- Бизнес-инварианты, указанные релизом, проверены на ограниченной выборке.

## Повторный запуск и конкурентность

Deploy не считается полностью идемпотентным из-за migration/state transition. При обрыве сначала
определяется фактическая Flyway/database/container state; blind retry запрещён.

## Rollback или forward-fix

Application rollback не откатывает БД. `rollback.sh` допустим только при числовом schema state и
явной совместимости previous runtime. Иначе применяется reviewed compatible forward-fix. Restore
используется только по DR runbook и не выполняется поверх production.

## Evidence и известные пробелы

Сохранить timestamps, digests, release/schema identities, health, sanitized flags/queues и verdict.
Runbook остаётся draft: preflight не проверяет image signature/provenance, а migration failure может
оставить `MIGRATION_IN_PROGRESS` без штатного recovery tool. Кроме того, текущий deploy запускает
Flyway при работающем старом runtime и ACL repair не выводит exact target из release env. До
исправления этих P1 gaps production deployment по этому документу запрещён.
