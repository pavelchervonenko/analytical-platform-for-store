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

**Статус deploy-path: CONDITIONAL GO после release-specific gates.** Три прежних P1-gap закрыты:
API/worker quiesce до Flyway, exact-target ACL repair из reviewed release env и проверяемая связь
`release env → immutable digest → OCI revision → commit`. Это не является общей авторизацией:
production write остаётся NO-GO, пока конкретный релиз не прошёл CI, GHCR publication, backup и
production-read-only preflight и владелец не подтвердил показанный exact release plan.

## Влияние и требуемая авторизация

- Затрагиваются production database schema, API, worker и web.
- Запуск подтверждают operations owner и reviewer релиза; migration требует backup owner.
- Возможна недоступность API и остановка background jobs.

## Предусловия

- Reviewed tag/commit находится на approved branch, CI зелёный.
- Backend/web заданы как exact `ghcr.io/...@sha256:<64 hex>`; оба OCI revision равны
  `RELEASE_COMMIT`, а digest-поля совпадают с image references.
- Fresh backup checkpoint с доказанной возможностью restore; отсутствие активной migration/recovery.
- Release env — root-owned regular file `0600`; секреты provisioned отдельно.
- Exact DB cert host/hostaddr/port/name/schema и runtime/migrator/backup roles заданы в release env.
- Recorded source schema входит в migration range, packaged target совпадает с `SCHEMA_VERSION`.
- Назначены observer, rollback/forward-fix owner и окно наблюдения.

## Критерии остановки

- Target host/database/release не совпал с change record.
- `database-schema-version` отсутствует, нечисловой или равен `MIGRATION_IN_PROGRESS`.
- Live Flyway history содержит failed migration или расходится с state-файлом.
- Deploy bundle не содержит quiesce worker/API до `MIGRATION_IN_PROGRESS` и Flyway.
- ACL repair не получает exact reviewed release env или target/roles расходятся с change record.
- Backup/restore, remote/local image provenance, Compose config или required secrets не подтверждены.
- Preflight не проверил owner/mode release env, оба immutable image reference/digest и обе OCI
  revision относительно `RELEASE_COMMIT`.
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

Preflight fail-closed проверяет release env, required secret files, exact DB target, immutable
references/digests, remote OCI revisions и Compose config. Reviewer отдельно сверяет:

- owner/mode равны approved host policy;
- `RELEASE_COMMIT` равен reviewed commit/tag target;
- backend/web digest и OCI revision совпадают с опубликованными CI coordinates;
- source/target schema и exact DB target совпадают с change record;
- deploy bundle содержит остановку worker, затем API до Flyway;
- ACL script читает target/roles только из переданного release env.

Read-only ролью проверить последнюю успешную Flyway version и отсутствие failed rows. Любой
непроверяемый пункт означает stop, а не ручной обход.

## Точный target

Change record обязан содержать host fingerprint, environment, database host/name, release ID,
commit/tag, backend/web digests, source/target schema, release-env SHA-256 и время окна. Любое
расхождение останавливает процедуру.

## Процедура

1. Зафиксировать CI/GHCR, preflight/backup evidence и повторно подтвердить exact target.
2. Показать владельцу release ID, commit/tag, backend/web digests, source/target schema, DB target,
   ожидаемое короткое окно недоступности и post-deploy checks.
3. После отдельного подтверждения production write запустить ровно один раз:
   `sudo /opt/store-analytics/deploy/bin/deploy.sh /etc/store-analytics/release.env`.
4. Скрипт проверит remote/local provenance, остановит worker и API, запустит Flyway и ACL repair,
   затем поднимет API → worker → web и выполнит smoke.
5. Не запускать второй deploy, пока первый не завершён или неизвестная точка сбоя не исследована.
6. Проверить container image digests/health, public smoke, live Flyway и critical queues.

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
Runbook остаётся draft до staging rehearsal и production-read-only evidence конкретного release.
Оставшиеся ограничения: OCI revision не является cryptographic signature/attestation, а migration
failure может оставить writers остановленными и `MIGRATION_IN_PROGRESS` без автоматического
recovery. В этом состоянии сначала исследуют Flyway/containers и используют reviewed forward-fix;
blind retry запрещён.
