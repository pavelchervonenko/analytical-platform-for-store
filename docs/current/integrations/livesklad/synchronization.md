---
doc_schema: 1
doc_type: current
status: current
owner: integrations
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/archive/legacy-contracts/synchronization-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/sync
  - backend/src/main/resources/application.yml
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/sync/service/SyncJobIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/SyncJobWorkerTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/StoreSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/OrderSyncIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - integration
  - backend-data
review_triggers:
  - sync-phase-change
  - retry-policy-change
  - livesklad-client-change
supersedes:
  - docs/archive/legacy-contracts/synchronization-api.md
superseded_by: null
---

# Синхронизация LiveSklad

## Durable lifecycle

Backfill и incremental sync принадлежат `sync_jobs`, а не HTTP request. Один connection имеет не
более одной active job. Каждое окно проходит фазы:

```text
STORES → EMPLOYEES → SALES → RETURNS → ORDERS → следующее окно
```

Cursor и phase коммитятся после каждого шага. Child attempts связаны через `sync_job_id`, lease
позволяет восстановиться после падения worker, а cancellation текущей фазы cooperative.

## Defaults source-tree

| Параметр | Default |
|---|---:|
| Window | 6 часов |
| Минимальное adaptive window | 15 минут |
| Incremental overlap | 3 дня |
| Maximum backfill | 730 дней |
| Attempts | 5 |
| Lease | 2 часа |

Это defaults из `application.yml`, не утверждение о production flags. Schedule creation default-off,
worker default-on. Фактические значения разрешено фиксировать только runtime evidence.

## Retry и source races

Rate limit, transport, retryable HTTP, transient DB, `LIVESKLAD_ORDER_CHANGED` и
`LIVESKLAD_RETURN_CHANGED` повторяются с bounded backoff. Source-capacity/rate pressure может
уменьшить child window. List/detail mismatch обычного изменяемого документа — source race, а не
безвозвратная ошибка.

Malformed/rejected payload и unclassified `LiveSkladException` завершаются permanent code
(`LIVESKLAD_PERMANENT` для последнего случая) и требуют анализа причины. Нельзя автоматически
повторять любой permanent failure, не уточнив классификацию.

Targeted webhook sync не запускает period-wide absence/deletion. Period sync делает deletion
detection только после полного успешного чтения соответствующей области.

## Coverage и API

ADMIN API из OpenAPI v10 создаёт backfill, читает readiness/list/detail и запрашивает cancel.
Backfill dates включительны в reporting zone; внутри хранятся instant-полуинтервалы. Создание
требует effective classification на начало периода и ограничено 730 днями.

Freshness магазина использует минимум coverage SALES, RETURNS и ORDERS. Public data-status DTO пока
не раскрывает отдельную дату ORDERS; gap описан в
[`../../api/store-data-status.md`](../../api/store-data-status.md).

## Инварианты

- Raw payload version hash делает повторное чтение идемпотентным.
- Все необходимые pages/details валидируются до normalization transaction.
- Никакой token, credential, upstream body или PII не входит в error summary.
- Полный historical backfill не заменяет ежедневный overlap и webhook correction path.
