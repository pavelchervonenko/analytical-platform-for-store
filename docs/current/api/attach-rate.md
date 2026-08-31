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
  - docs/attach-rate-api.md
implementation_sources:
  - backend/src/main/resources/db/migration/V38__attach_rate_units_methodology.sql
  - backend/src/main/java/com/storeanalytics/metrics/service/AttachRateService.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/AttachRateIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/AttachRateServiceTest.java
  - backend/src/test/java/com/storeanalytics/metrics/web/AttachRateControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
review_triggers:
  - attach-rate-methodology-change
  - attach-rate-category-change
  - return-employee-attribution-change
supersedes:
  - docs/attach-rate-api.md
superseded_by: null
---

# Attach-rate API

`GET /api/stores/{storeId}/kpi/attach-rates?periodStart=...&periodEnd=...` реализует V38 unit
methodology, а не старую чековую co-occurrence формулу.

```text
N = sold units add-on - returned units add-on
B = sold units relevant device - returned units relevant device
attachRate = max(0, N) / B × 100%, если B > 0
attachRate = null, если B <= 0
```

Доп и техника учитываются независимо от совместного присутствия в одном чеке. Возвраты уменьшают
числитель/базу по signed facts. Store benchmark использует все facts магазина; employee rows могут
использовать отдельный отображаемый roster, поэтому остаток «вне рейтинга / без сотрудника» должен
быть видим.

Действующие направления и bases:

| Направление | База |
|---|---|
| Чехлы/стекло/камера iPhone | iPhone |
| Зарядки, кабели и плёнка телефона | Все телефоны |
| Настройка | Телефоны + MacBook + PlayStation |
| Чехлы/стекло/камера Samsung | Новые и Б/У Samsung |
| AirPods/Watch accessories | AirPods + Apple Watch |
| iPad accessories | iPad |
| Гарантия Б/У | Б/У iPhone + Samsung |
| Гарантия новых | Новые/ASIS iPhone + новые Samsung |
| Премиум-протекция | Согласованный набор техники без Dyson |

Premium protection numerator ограничен классифицированными Care-продуктами, зафиксированными
версией methodology. Изменение списка — versioned product/formula change, а не UI-фильтр.

Quality counters сопровождают неоднозначные facts, но endpoint не должен молча придумывать
condition/base. Employee rows наследуют открытое расхождение return attribution из
[`employee-kpi.md`](employee-kpi.md).
