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
  - docs/category-kpi-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/service/CategoryKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/CategoryKpiRepository.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/CategoryKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/CategoryKpiServiceTest.java
  - backend/src/test/java/com/storeanalytics/metrics/web/CategoryKpiControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
review_triggers:
  - category-kpi-change
  - analytics-category-change
  - group-definition-change
supersedes:
  - docs/category-kpi-api.md
superseded_by: null
---

# Category KPI API

`GET /api/stores/{storeId}/kpi/categories?periodStart=...&periodEnd=...` возвращает reference
categories, включая zero rows, и derived overlapping groups. Signed amount/quantity и missing-cost
правила совпадают со store KPI.

Иерархия групп:

```text
Техника
└── Телефоны

Допы
├── Аксессуары
└── Услуги
```

Телефоны уже входят в технику, аксессуары и услуги — в допы. Родитель и детей нельзя складывать
повторно. В действующем classifier:

```text
Допы = Аксессуары + Услуги
Услуги включают SERVICE, WARRANTY и PROTECTION
```

`UNMAPPED` учитывается в общей store revenue, но не получает бизнес-группу; `EXCLUDE` отсутствует
во всех KPI. Missing cost инвалидирует только зависимые cost/gross-profit/margin поля категории или
группы, а не её revenue.
