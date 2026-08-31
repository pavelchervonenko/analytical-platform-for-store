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
  - docs/archive/legacy-contracts/employee-rating-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingService.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingFinalizationService.java
  - backend/src/main/java/com/storeanalytics/performance/web/EmployeeRatingController.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/performance/repository/EmployeeRatingIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/performance/service/EmployeeRatingServiceTest.java
  - backend/src/test/java/com/storeanalytics/performance/web/EmployeeRatingControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
review_triggers:
  - rating-formula-change
  - rating-roster-change
  - return-employee-attribution-change
supersedes:
  - docs/archive/legacy-contracts/employee-rating-api.md
superseded_by: null
---

# Employee Rating API

## Endpoints

- `GET /api/stores/{storeId}/employee-ratings?periodStart=...&periodEnd=...` читает LIVE result до
  finalization и immutable snapshot после неё.
- `POST /api/stores/{storeId}/employee-ratings/finalize?...` создаёт snapshot только после конца
  периода в timezone магазина; повторный конкурентный запрос идемпотентно возвращает существующий.

Кандидат рейтинга: active employee, active assignment, `participatesInRanking=true` и хотя бы одна
смена. Это уже, чем полный employee KPI. Snapshot read проверяет payload hash и не вызывает live
aggregation.

Rating v1 агрегирует contribution, efficiency, sales structure и attach-rate с bounded scores;
overall нормализуется по фактическому coverage. Rank доступен только при минимальном coverage,
недоступная база не превращается в нулевую эффективность.

Store attach benchmark включает весь магазин, не только roster. Return employee attribution сейчас
следует исходному продавцу; изменение правила требует одновременного пересчёта employee KPI,
attach-rate, rating и versioned snapshots.
