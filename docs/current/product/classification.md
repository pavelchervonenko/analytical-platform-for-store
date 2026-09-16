---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-09-16
requirement_sources:
  - docs/archive/discoveries/analytics-business-rules-draft.md
  - docs/history/audits/2026/08/payroll-classification-review.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/product/model/AnalyticsCategory.java
  - backend/src/main/java/com/storeanalytics/product/service/ProductAutoClassificationRuleEngine.java
  - backend/src/main/java/com/storeanalytics/product/service/ProductCategoryImportService.java
  - backend/src/main/java/com/storeanalytics/product/service/ProductClassificationReconciliationService.java
  - backend/src/main/java/com/storeanalytics/product/service/ProductClassificationResolver.java
  - backend/src/main/resources/db/migration/V38__attach_rate_units_methodology.sql
  - backend/src/main/resources/db/migration/V5__add_payroll.sql
  - frontend/src/admin/CategoryImportPanel.tsx
  - frontend/src/admin/ClassificationPanel.tsx
verification_sources:
  - backend/src/test/java/com/storeanalytics/product/service/ProductAutoClassificationRuleEngineTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ProductCategoryImportIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ProductClassificationResolverTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ProductClassificationReconciliationServiceTest.java
  - backend/src/test/java/com/storeanalytics/common/database/CareClassificationMigrationIntegrationTest.java
  - frontend/src/quality/actions.test.ts
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

Автоклассификация сначала применяет более специфичные правила коммерческих услуг, например
гарантий и Care-продуктов. После них подтверждённый source type `SERVICE` имеет приоритет над
лексическими правилами товаров: работа со словами `стекло`, `камера`, `iPhone`, `клавиатура` и
подобными относится к `SETUP_SERVICE`, а не к аксессуару или устройству. Лексические правила
защитных стёкол и других товаров продолжают применяться к source type `PRODUCT`.

## Attach-rate категория

Использует отдельные numerator/denominator codes. Care-продукт может быть
`PREMIUM_PROTECTION` для units-rate и одновременно warranty/protection в денежной структуре. Это
две проекции, не двойной денежный учёт. См. [attach-rate v3](attach-rate.md).

## Зарплатная категория

Определяет фонд: `TECH_TIER_1`, `TECH_TIER_2`, `ACCESSORY`, `SERVICE`,
`PLAYSTATION_SUBSCRIPTION`, `PAID_REPAIR`, `EXCLUDE`. Она не исправляет analytics assignment и не
меняет attach mapping. UI «Категории зарплаты» меняет только payroll category.

Для подтверждённой платной ремонтной работы используется payroll category `PAID_REPAIR`; её
analytics category при этом остаётся `SETUP_SERVICE`. Назначение одной проекции не создаёт и не
изменяет назначение другой.

## Ручное исправление

UI «Категории аналитики» создаёт effective-dated analytics assignment. Действие
`CLASSIFY_PRODUCTS` для `SOURCE_PRODUCTS_UNMAPPED` ведёт именно в этот раздел; payroll form остаётся
отдельным контуром.

После аналитического импорта backend повторно классифицирует только активные позиции со снимком
`UNMAPPED`, только для канонических external product IDs из импорта и только внутри указанного
integration connection. Уже классифицированные позиции и товары других подключений не меняются.
Quality issue товара закрывается только когда все найденные активные позиции этого товара получили
аналитическую категорию. Assignment учитывается только начиная с его `validFrom`; автоматическое
правило не используется для обхода этой даты. Связанный возврат наследует классификацию исходной
продажи.

Backend возвращает точный набор затронутых магазинов. Frontend после успешного аналитического
импорта запрашивает пересборку текущего Weekly Review для каждого из них и сбрасывает связанные
query caches. Новая immutable revision создаётся только при изменении content hash. Ошибка этого
дополнительного обновления не откатывает сохранённую категорию, показывается отдельно и допускает
повтор запроса.

Автоматические правила `livesklad-product-rules-v9` распознают кабели, название которых состоит
только из пар разъёмов `USB-C/Type-C` и `Lightning`, как `CHARGER_CABLE`. Оба признака обязательны,
чтобы слово `Lightning` в названии самостоятельной техники не превращало её в аксессуар.
Написания `картхолдер`, `кардхолдер` и `cardholder` относятся к
`OTHER_ACCESSORY_PRODUCT`. Эти категории имеют зарплатную категорию `ACCESSORY`; отдельное
payroll-назначение не создаётся.

Для `SERVICE|WARRANTY|PROTECTION` ожидаемый ноль себестоимости допустим. В других категориях
`ZERO_UNEXPECTED` — возможная ошибка, которую readiness пока не всегда блокирует.
