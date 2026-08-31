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
  - docs/average-kpi-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/service/AverageKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/AverageKpiRepository.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/AverageKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/AverageKpiServiceTest.java
  - backend/src/test/java/com/storeanalytics/metrics/web/AverageKpiControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
review_triggers:
  - average-kpi-change
  - comparison-period-change
supersedes:
  - docs/average-kpi-api.md
superseded_by: null
---

# Average KPI API

`GET /api/stores/{storeId}/kpi/averages?periodStart=...&periodEnd=...` возвращает current period и
непосредственно предшествующий период такой же включительной календарной длины.

Основные расчёты:

```text
averageReceipt = signed net revenue / count(non-deleted SALE documents)
additionalPerPhone = signed additional revenue / signed phone quantity
categoryAverage = signed category revenue / signed category quantity
change = (current raw average - previous raw average) / previous raw average × 100%
```

Возвраты уменьшают числитель среднего чека, но не количество sale documents. Nonpositive
denominator или zero previous value даёт `null`; это не «0% изменений». Display rounding
выполняется после division, dynamics — по unrounded values.
