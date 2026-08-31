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
  - docs/archive/discoveries/analytics-business-rules-draft.md
  - docs/history/audits/2026/08/payroll-classification-review.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/product/model/AnalyticsCategory.java
  - backend/src/main/java/com/storeanalytics/product/service/ProductClassificationResolver.java
  - backend/src/main/resources/db/migration/V38__attach_rate_units_methodology.sql
  - backend/src/main/resources/db/migration/V5__add_payroll.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/product/service/ProductClassificationResolverTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ProductClassificationReconciliationServiceTest.java
  - backend/src/test/java/com/storeanalytics/common/database/CareClassificationMigrationIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - product
  - backend
review_triggers:
  - classification-change
  - metric-change
  - payroll-change
supersedes: []
superseded_by: null
---

# Классификация товаров

Один товар участвует в трёх независимых проекциях.

## Аналитическая категория

Определяет участие в store/category/employee KPI, kind
`DEVICE|ACCESSORY|SERVICE|WARRANTY|PROTECTION` и группы. Отсутствие assignment — `UNMAPPED`:
товар входит в store revenue, но не в группы. `EXCLUDE` исключает из аналитики.

## Attach-rate категория

Использует отдельные numerator/denominator codes. Care-продукт может быть
`PREMIUM_PROTECTION` для units-rate и одновременно warranty/protection в денежной структуре. Это
две проекции, не двойной денежный учёт. См. [attach-rate v3](attach-rate.md).

## Зарплатная категория

Определяет фонд: `TECH_TIER_1`, `TECH_TIER_2`, `ACCESSORY`, `SERVICE`,
`PLAYSTATION_SUBSCRIPTION`, `PAID_REPAIR`, `EXCLUDE`. Она не исправляет analytics assignment и не
меняет attach mapping. UI «Неразмеченные товары» сейчас меняет именно payroll category.

## Подтверждённые gaps

`SOURCE_PRODUCTS_UNMAPPED` означает analytics gap, но действие ведёт в payroll form. После
сохранения issue может остаться; это неверный remediation. Для `SERVICE|WARRANTY|PROTECTION`
ожидаемый ноль себестоимости допустим. В других категориях `ZERO_UNEXPECTED` — возможная ошибка,
которую readiness пока не всегда блокирует.
