---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/store-data-status-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/store/service/StoreDataStatusService.java
  - backend/src/main/java/com/storeanalytics/store/service/StoreDataStatusView.java
  - backend/src/main/java/com/storeanalytics/store/repository/StoreDataStatusRepository.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/store/service/StoreDataStatusServiceTest.java
  - backend/src/test/java/com/storeanalytics/store/repository/StoreDataStatusRepositoryIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/store/web/StoreDataStatusSecurityIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - integration
review_triggers:
  - freshness-rule-change
  - sync-phase-change
  - data-status-dto-change
supersedes:
  - docs/store-data-status-api.md
superseded_by: null
---

# Store data status API

`GET /api/stores/{storeId}/data-status` — store-scoped read-only endpoint. Он не запускает sync.

## Freshness

`expectedThroughDate` — вчера в timezone магазина. Реализованное полное покрытие:

```text
dataThroughDate = min(SALES coverage, RETURNS coverage, ORDERS coverage)
```

Если любой из трёх потоков не имеет покрытия, общий coverage неизвестен. Приоритет статуса:
`SYNCING`, затем latest `ERROR`, затем `NOT_SYNCED`, `STALE`, `CURRENT`. `PARTIAL_SUCCESS` может
давать coverage, но связанные quality issues остаются видимы.

## Visibility gap

Текущий public DTO отдаёт `salesDataThroughDate` и `returnsDataThroughDate`, но не
`ordersDataThroughDate`, хотя ORDERS участвует в общем минимуме. Поэтому клиент может увидеть
`NOT_SYNCED`/`STALE` без возможности точно назвать отстающий ORDERS stream. До DTO/OpenAPI change
frontend не должен приписывать проблему sales или returns по остаточному принципу.

`lastError` — безопасная историческая строка и может сохраниться после success; источник текущего
error state — поле `status`. `openQualityIssueCount` не равен sync failure.
