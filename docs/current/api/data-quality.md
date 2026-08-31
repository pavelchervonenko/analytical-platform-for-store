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
  - docs/data-quality-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/quality/service/DataQualityService.java
  - backend/src/main/java/com/storeanalytics/quality/web/DataQualityController.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/quality/service/DataQualityServiceTest.java
  - backend/src/test/java/com/storeanalytics/quality/web/DataQualityControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - frontend-product
review_triggers:
  - data-quality-code-change
  - recommended-action-change
  - freshness-rule-change
supersedes:
  - docs/data-quality-api.md
superseded_by: null
---

# Data Quality API

## Endpoints

- `GET /api/data-quality/summary` — сводка по active stores текущего principal.
- `GET /api/stores/{storeId}/data-quality` — store-scoped detail.

Сводка соединяет производный freshness/sync signal и persisted open
`data_quality_issues`. Итоговый health имеет приоритет `ERROR > WARNING > OK`; INFO сам по себе не
ухудшает `OK`.

Detail возвращает safe issue code, source, severity, entity type, timestamp и
`recommendedAction`. Raw payload, internal metadata и upstream identifiers наружу не выдаются.
Frontend маршрутизирует действие по stable code/action, а не по свободному message text.

Quality API не заменяет domain readiness. Payroll, reports и plan продолжают владеть своими
blocking rules. Число открытых issues не означает автоматически, что все числовые KPI недоступны;
интерфейс обязан показывать конкретную severity/ограниченную область.
