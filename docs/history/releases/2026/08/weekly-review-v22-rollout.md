---
doc_schema: 1
doc_type: evidence
status: historical
owner: ai
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved legacy evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/current/ai/README.md"
original_content_sha256: c6a1fd3c096c2ed654416de3253283b03bc3915b6d9260352297d7906608db6f
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/ai/README.md`.

# Weekly review v22/schema4 — production rollout

Дата: 2026-08-29
Статус: исторический runbook immutable v22; активный candidate описан в `weekly-review-v25-rollout.md`

> Этот документ применяется только к неизменяемому `weekly-interpretation-v22`. Указание
> не повторять paid shadow относится только к v22 и не распространяется на v23/v24/v25.

## 1. Первый deploy без активации

Миграции V45–V48 применяются только с выключенным новым контуром:

```dotenv
WEEKLY_REVIEW_ENABLED=false
WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_ENABLED=false
WEEKLY_REVIEW_AI_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_WORKER_ENABLED=false
```

Перед migrator обязательны:

```bash
sudo /opt/store-analytics/deploy/bin/preflight-release.sh \
  /etc/store-analytics/release.env
sudo docker compose \
  --env-file /etc/store-analytics/release.env \
  -f /opt/store-analytics/deploy/compose.production.yml \
  config --quiet
```

Release metadata: `SCHEMA_VERSION=48`, `RUNTIME_SCHEMA_MIN_VERSION=48`,
`RUNTIME_SCHEMA_MAX_VERSION=48`. Миграции additive; down migration не выполняется. После V48
нельзя запускать backend image с runtime max ниже 48. Rollback backend выполняется только
V48-compatible forward-fix image.

После deploy проверить schema 48, health API/worker и legacy
`GET /api/stores/{storeId}/insights/weekly/current`. Пока `WEEKLY_REVIEW_ENABLED=false`, новый
endpoint отдаёт 404, а frontend автоматически показывает прежний v21/schema3 экран.

## 2. Семантический допуск

External eval для `weekly-interpretation-v22/schema4` выполнен: 4/4 semantic-valid cases,
независимый blind review 4.75/5, статус `CANDIDATE_ELIGIBLE_FOR_CANARY`. Повторять платный shadow
перед тем же неизменённым prompt/schema не требуется. Provider payload, receipts и API key остаются
в ignored `build/` contour и не коммитятся.

## 3. Canary одного магазина

Не включать автоматические planner. Для одного ручного canary:

```dotenv
WEEKLY_REVIEW_ENABLED=true
WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_ENABLED=true
WEEKLY_REVIEW_AI_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_WORKER_ENABLED=true
WEEKLY_REVIEW_AI_MAX_PROVIDER_CALLS=2
WEEKLY_REVIEW_AI_MAX_ESTIMATED_COST_RUB=10.00
WEEKLY_REVIEW_AI_DAILY_COST_LIMIT_RUB=100.00
```

После preflight и recreate backend создать deterministic snapshot только выбранного магазина:

```http
POST /api/admin/weekly-reviews/stores/{storeId}/generate
```

Зафиксировать `snapshotId`, period, revision, reportState и contentHash. Допустимы
`READY`/`PARTIAL`. Затем:

```http
POST /api/admin/weekly-review-ai/snapshots/{snapshotId}/generate
GET  /api/admin/weekly-review-ai/jobs/{jobId}
GET  /api/stores/{storeId}/weekly-reviews/current
```

POST идемпотентен для exact snapshot/prompt/schema. Второй магазин не ставится до terminal status
первого. У магазина без v22 snapshot frontend продолжает показывать v21/schema3.

## 4. Проверка canary

Проверить:

- job `SUCCEEDED`, `attemptCount <= 2`, `lastValidationCodes` пуст;
- periods и все числа совпадают с deterministic snapshot;
- AI изменил только разрешённые тексты summary/factor/action title;
- нет месячного плана, текущей неполной недели, придуманных чисел, UUID и кадровых оценок;
- provider calls/tokens/cost ожидаемы, суточный cap не превышен;
- unknown outcome сохраняет estimated reserve до конца UTC-дня;
- legacy endpoint, остальные магазины и основные метрики не изменились.

При `FAILED` не повторять вызов вручную до разбора `lastErrorCode` и immutable attempt receipt.
Исчерпанные две попытки требуют исправления input/prompt/config либо новой snapshot/prompt revision.

После успешного первого canary тем же ручным порядком проверить второй магазин.

## 5. Полная автоматизация

Только после двух успешных canary:

```dotenv
WEEKLY_REVIEW_ENABLED=true
WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=true
WEEKLY_REVIEW_AI_ENABLED=true
WEEKLY_REVIEW_AI_PLANNER_ENABLED=true
WEEKLY_REVIEW_AI_WORKER_ENABLED=true
```

Deterministic planner каждые пять минут проходит все активные LiveSklad-магазины страницами по
стабильному `storeId`. Он создаёт снимок последней полностью завершённой недели только после
совокупной непрерывной цепочки успешных синхронизаций, покрывающей обе сравниваемые недели:
от начала предыдущей недели до конца текущей. При неизменившемся source повторной revision нет.
AI planner ставит exact job только для
последней `READY`/`PARTIAL` revision без опубликованного enrichment.

После включения наблюдать минимум один полный цикл planner → job → publication для обоих магазинов.

## 6. Rollback и forward-fix

Немедленный функциональный rollback:

```dotenv
WEEKLY_REVIEW_ENABLED=false
WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_ENABLED=false
WEEKLY_REVIEW_AI_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_WORKER_ENABLED=false
```

После preflight и recreate:

- frontend возвращается на v21/schema3 через 404 fallback;
- новые snapshots/jobs не создаются;
- опубликованные enrichment не читаются;
- V45–V48 rows не удаляются и остаются для диагностики;
- legacy API и расчёты продолжают работать.

При дефекте backend выпускается V48-compatible forward-fix. Запуск прежнего backend image с
обходом schema guard и удаление immutable history запрещены.
