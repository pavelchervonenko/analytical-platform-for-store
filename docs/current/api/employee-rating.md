---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - manager
last_verified: 2026-10-06
requirement_sources:
  - docs/archive/legacy-contracts/employee-rating-api.md
  - docs/decisions/ADR-0001-return-employee-attribution.md
  - docs/decisions/ADR-0003-warranty-attach-attribution.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingService.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingQueryService.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingSnapshotCodec.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingFinalizationService.java
  - backend/src/main/java/com/storeanalytics/performance/repository/EmployeeAttachRateRepository.java
  - backend/src/main/resources/db/migration/V55__attach_warranty_attribution.sql
  - backend/src/main/resources/db/migration/V86__apply_confirmed_catalog_attach_roles.sql
  - backend/src/main/java/com/storeanalytics/performance/web/EmployeeRatingController.java
  - backend/src/main/java/com/storeanalytics/performance/web/EmployeeRatingSettingsController.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingSettingsService.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/performance/repository/EmployeeRatingIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/performance/service/EmployeeRatingServiceTest.java
  - backend/src/test/java/com/storeanalytics/performance/web/EmployeeRatingControllerTest.java
  - backend/src/test/java/com/storeanalytics/performance/web/EmployeeRatingSettingsControllerTest.java
  - backend/src/test/java/com/storeanalytics/store/web/StoreDataStatusSecurityIntegrationTest.java
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
- `GET /api/stores/{storeId}/employee-rating-settings` читает store-scoped roster.
- `PUT /api/stores/{storeId}/employee-rating-settings/{employeeId}` изменяет участие только при
  одновременном доступе к магазину и функции `SHIFTS`; composite assignment не позволяет применить
  идентификатор сотрудника к другому магазину.

Кандидат рейтинга: active employee, active assignment, `participatesInRanking=true` и хотя бы одна
смена. Это уже, чем полный employee KPI. Snapshot read проверяет payload hash и не вызывает live
aggregation.

Rating v1 агрегирует contribution, efficiency, sales structure и attach-rate с bounded scores;
overall нормализуется по фактическому coverage. Rank доступен только при минимальном coverage,
недоступная база не превращается в нулевую эффективность.

Store attach benchmark включает весь магазин, не только roster. Финансовые составляющие employee
KPI и рейтинга относят возврат к продавцу исходной продажи; пока оригинал не найден, финансовый
сотрудник не назначен. Это правило [ADR-0001](../../decisions/ADR-0001-return-employee-attribution.md)
не заменяет отдельную аналитическую атрибуцию attach-rate:

- В `attach-rate-v3` используется финансовый сотрудник документа; возврат относится к исходному
  продавцу в фактическом периоде возврата.
- В `attach-rate-v4` обычные метрики, включая Care, используют фактический период возврата и
  сотрудника строки LiveSklad (`detail.customer.id`, сохранённый отдельно в
  `attach_source_employee_external_id`). Неизвестный сотрудник не заменяется финансовым:
  store totals учитывают возврат, а сравнение сотрудников по затронутой метрике недоступно.
- В `attach-rate-v4` обычная гарантия относится к продавцу и периоду продажи устройства.
  Возврат гарантии наследует исходное распределение; возврат устройства уменьшает гарантийную
  базу исходного периода. Правила связей и предварительности определены
  [ADR-0003](../../decisions/ADR-0003-warranty-attach-attribution.md) и
  [attach-rate](../product/attach-rate.md).

LIVE выбирает v3/v4 по настройке атрибуции. `formula.version` содержит код схемы рейтинга,
с суффиксом `-attach-v4` при v4; store attach API отдельно возвращает `formulaVersion`.
FINALIZED возвращает сохранённые формулу, roster, значения и scores после проверки целостности,
без текущего пересчёта. Последующие изменения атрибуции, назначений или смен не переписывают
этот snapshot. Его нельзя сверять с текущими LIVE facts как с тем же срезом.
