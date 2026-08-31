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
  - docs/period-quality-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/quality/service/StorePeriodQualityService.java
  - backend/src/main/java/com/storeanalytics/quality/web/StorePeriodQualityController.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/quality/service/StorePeriodQualityServiceTest.java
  - backend/src/test/java/com/storeanalytics/quality/web/StorePeriodQualityControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - frontend-product
review_triggers:
  - period-quality-change
  - readiness-change
  - quality-severity-change
supersedes:
  - docs/period-quality-api.md
superseded_by: null
---

# Period Quality API

`GET /api/stores/{storeId}/period-quality/{yyyy-MM}?asOf=YYYY-MM-DD` оценивает конкретный месяц до
явного cutoff. `asOf` обязан принадлежать месяцу и не должен интерпретироваться как конец месяца.

Композиция объединяет source coverage, classification, plan, rating и payroll signals, но не
пересчитывает их domain rules. `readyForDecisions` означает отсутствие blocking `ERROR`; WARNING
может снижать уверенность и остаётся в списке ограничений.

Известная граница: `ZERO_UNEXPECTED` в текущей реализации не блокирует решение и не превращает
gross profit в `null`; это нельзя трактовать как доказательство корректной себестоимости товара.
Если правило изменится, quality service, KPI semantics и UI должны обновиться одновременно.

Frontend не объединяет period-quality с произвольным выбранным range: endpoint по определению
month-to-`asOf` и должен иметь явную подпись периода.
