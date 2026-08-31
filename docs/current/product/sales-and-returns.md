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
  - docs/CUSTOMER_KPI_FORMULA_AUDIT_2026-08-13.md
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

Реализация использует сотрудника исходной продажи; `ReturnSyncIntegrationTest` передаёт отличный
`return-processor`, но employee directory его не разрешает, а cash/original employee остаётся
`employee-1`. Сценария с двумя разными успешно разрешёнными сотрудниками нет, поэтому precedence
не защищён полноценным regression test.
Методика заказчика требует сотрудника строки/документа возврата.

- Store/category totals не меняются.
- Employee revenue, GP, mix, rating и employee attach-rate могут быть распределены неверно.
- Employee-level показатели периода с возвратами не полностью авторитетны до решения и сверки.
- Full employee reconciliation доказывает арифметику, но не правильного ответственного.
- В normalized document хранится один `employee_id`; processing employee возврата остаётся только
  в retained raw source. Для проверяемой смены правила нужен отдельный normalized provenance.

Целевое предложение и миграционные gates — в
[ADR-0001](../../decisions/ADR-0001-return-employee-attribution.md), статус `proposed`.

Нулевая оплата не доказывает отсутствие возврата: авторитетны signed items. Missing cost возврата
делает cost/GP/margin неполными; неожиданный ноль остаётся quality gap.
