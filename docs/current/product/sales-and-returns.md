---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/history/audits/2026/08/CUSTOMER_KPI_FORMULA_AUDIT_2026-08-13.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/sync/service/SalesSyncPersistence.java
  - backend/src/main/java/com/storeanalytics/sync/service/ReturnSyncPersistence.java
  - backend/src/main/resources/db/migration/V43__make_livesklad_webhook_inbox_processable.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/StoreKpiIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - product
  - integrations
  - backend
review_triggers:
  - return-attribution-change
  - synchronization-change
  - metric-change
supersedes: []
superseded_by: null
---

# Продажи и возвраты

Продажа создаёт положительные signed-строки, возврат — отрицательные по сумме, количеству и
себестоимости. Удалённый документ/строка не участвует. Повтор одного external ID обновляет тот же
факт. Возврат может сохраниться без найденной исходной продажи и связаться позднее.

## Атрибуция сотруднику

Финансовая атрибуция возврата всегда следует исходной продаже:

- linked return получает `employee_id` исходного SALE;
- processing employee возврата не используется как fallback, даже если успешно разрешён;
- orphan return без найденного SALE сохраняется с `employee_id = null` и входит в «Не назначен»;
- после появления оригинала повторная синхронизация связывает возврат и назначает исходного
  продавца.

`ReturnSyncIntegrationTest` использует двух разных разрешённых сотрудников и проверяет как
приоритет исходного продавца, так и отсутствие fallback у orphan return. Store/category signed
totals от атрибуции не меняются; employee KPI, rating и attach уменьшаются у продавца продажи.
Правило принято в [ADR-0001](../../decisions/ADR-0001-return-employee-attribution.md).

Нулевая оплата не доказывает отсутствие возврата: авторитетны signed items. Missing cost возврата
делает cost/GP/margin неполными; неожиданный ноль остаётся quality gap.
