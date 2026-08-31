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
  - docs/store-kpi-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/service/StoreKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/StoreKpiRepository.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/StoreKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/StoreKpiServiceTest.java
  - backend/src/test/java/com/storeanalytics/metrics/web/StoreKpiControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
review_triggers:
  - store-kpi-change
  - return-sign-change
  - cost-quality-change
supersedes:
  - docs/store-kpi-api.md
superseded_by: null
---

# Store KPI API

`GET /api/stores/{storeId}/kpi?periodStart=YYYY-MM-DD&periodEnd=YYYY-MM-DD` возвращает KPI всех
включённых фактов магазина за включительный период.

```text
sign = +1 для SALE, -1 для RETURN
netRevenue = sum(sign × netAmount)
quantity = sum(sign × quantity)
cost = sum(sign × costAmount)
grossProfit = netRevenue - cost
margin = grossProfit / netRevenue × 100%
```

Soft-deleted documents/items и `EXCLUDE` не участвуют. `UNMAPPED` входит в store revenue, но не в
классифицированные category groups. Если хотя бы у включённой позиции отсутствует cost,
`cost`, `grossProfit` и `margin` возвращаются `null`; revenue и quantity остаются доступны. `null`
нельзя отображать как `0`.

Store total включает весь магазин и не ограничивается roster сотрудников рейтинга. Поэтому сумма
строк видимых «основных продавцов» может отличаться от store KPI и должна иметь отдельную подпись.
