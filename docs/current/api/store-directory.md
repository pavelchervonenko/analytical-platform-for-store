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
  - docs/store-directory-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/store/web/StoreDirectoryController.java
  - backend/src/main/java/com/storeanalytics/store/service/StoreDirectoryService.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/store/web/StoreDirectoryControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
review_triggers:
  - store-directory-change
  - store-access-change
supersedes:
  - docs/store-directory-api.md
superseded_by: null
---

# Store directory API

`GET /api/stores` возвращает активные магазины, доступные authenticated principal. `ADMIN` видит
все активные магазины, `MANAGER` — активные назначения. Клиент не передаёт user ID или список
разрешённых stores: scope всегда вычисляет backend.

Элементы содержат identity, name, nullable address, timezone, business-day/store-hours settings и
active state согласно OpenAPI v10. Список сортируется по name case-insensitively и UUID. Session с
`PASSWORD_CHANGE_REQUIRED` не получает directory.

Endpoint является единственным backend-owned источником store switcher. Frontend не сохраняет
доступ к магазину после исчезновения его из ответа и не угадывает timezone из locale браузера.
