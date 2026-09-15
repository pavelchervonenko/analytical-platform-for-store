---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - operator
last_verified: 2026-09-10
requirement_sources:
  - docs/archive/legacy-contracts/product-category-import-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/product/service/ProductCategoryImportService.java
  - backend/src/main/java/com/storeanalytics/product/service/ProductClassificationReconciliationService.java
  - backend/src/main/java/com/storeanalytics/sales/repository/SalesDocumentItemRepository.java
  - backend/src/main/java/com/storeanalytics/product/web/ProductCategoryImportController.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/product/service/ProductCategoryImportIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ProductClassificationReconciliationServiceTest.java
  - backend/src/test/java/com/storeanalytics/product/web/ProductCategoryImportControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
review_triggers:
  - classification-import-change
  - category-code-change
  - integration-connection-change
supersedes:
  - docs/archive/legacy-contracts/product-category-import-api.md
superseded_by: null
---

# Product category import API

`POST /api/integration-connections/{connectionKey}/product-category-imports` импортирует
versioned/effective-dated assignments для одной integration connection. Route ADMIN-only по
security configuration, хотя controller не содержит method-level `@PreAuthorize`.

Команда передаёт `validFrom`, rule version, change reason и bounded assignments с external product
identity, name, category и condition. Backend валидирует connection ownership, category values,
duplicates и effective-date invariants до атомарной записи. Ошибка одного элемента откатывает весь
batch; partial import не является успешным результатом.

В той же транзакции import повторно классифицирует активные sale items, которые всё ещё имеют
analytics category `UNMAPPED`. Scope ограничен текущим integration connection и каноническими
external product IDs, разрешёнными импортом. Уже классифицированные items, другие товары и другие
connections не изменяются. Assignment применяется к позиции только если он действует на её
`occurredAt`; иначе позиция остаётся `UNMAPPED`. Связанный возврат наследует аналитический
snapshot исходной продажи и не классифицируется независимо от неё.

Ответ содержит `affectedStoreIds` — точный набор магазинов, в которых реально изменились позиции.
Опубликованные rating/report/weekly snapshots не переписываются. Admin frontend после успешного
импорта запрашивает пересборку текущего Weekly Review для каждого затронутого магазина; новая
immutable revision появляется только при изменившемся content hash, иначе возвращается существующая.
Сам import API не запускает массовую регенерацию и не создаёт AI job.

Повтор команды должен следовать документированной идемпотентности API/сервиса; изменённый набор
нельзя выдавать за retry того же пользовательского намерения.
