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
  - docs/store-plan-progress-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/performance/service/StorePlanProgressService.java
  - backend/src/main/java/com/storeanalytics/performance/web/StorePlanProgressController.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/performance/service/StorePlanProgressServiceTest.java
  - backend/src/test/java/com/storeanalytics/performance/web/StorePlanProgressControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
  - frontend-product
review_triggers:
  - plan-formula-change
  - period-scope-change
  - plan-dto-change
supersedes:
  - docs/store-plan-progress-api.md
superseded_by: null
---

# Store Plan Progress API

`GET /api/stores/{storeId}/performance-plans/{yyyy-MM}/progress?asOf=YYYY-MM-DD` всегда считает
month-to-`asOf`. Это не произвольный выбранный range и не значение только одного дня.

Для направления доли:

```text
actualShare = directionAmount / actualNetRevenue × 100%
targetAmount = actualNetRevenue × targetShare / 100%
remaining = max(targetAmount - directionAmount, 0)
neededPerDay = remaining / remainingDays
```

Achievement сравнивает unrounded values. Response также содержит calendar pace, forecast, focus,
coverage и classification completeness; frontend отображает backend-owned значения и не строит
другую цель.

Будущий revenue baseline использует среднюю фактическую выручку завершённых дней, а при отсутствии
факта — monthly revenue target/число дней. Остаток направления рассчитывается от projected month
revenue и детерминированно распределяется по будущим дням.

Известная UI-интеграционная граница: если рядом показывать category amount за week/custom period и
plan share из этого endpoint, под одной подписью смешиваются разные периоды. Клиент обязан явно
показывать month-to-date scope либо запрашивать согласованный контракт; frontend-пересчёт не
устраняет расхождение.
