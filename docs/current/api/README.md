---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - contracts/openapi/current.json
  - backend/src/main/java/com/storeanalytics
verification_sources:
  - frontend/src/api/consumerContract.test.ts
  - scripts/check-openapi-compatibility.py
runtime_evidence: []
required_reviewers:
  - backend-data
  - frontend-product
review_triggers:
  - api-change
  - openapi-version-change
  - authentication-change
supersedes: []
superseded_by: null
---

# HTTP API

## Источник истины

[`contracts/openapi/current.json`](../../../contracts/openapi/current.json), версия `10`, —
transport authority для публичных paths, methods, parameters и schemas. Документы этого каталога
описывают semantics, access, null/partial behaviour и stable errors; они не заменяют OpenAPI.

Простое отсутствие отдельного prose-файла не удаляет endpoint из OpenAPI. Provider ingress,
намеренно скрытый от клиентского контракта, описывается в integration-документации.

## Общие правила

- Browser API использует server-side session и CSRF; store scope вычисляется по authenticated
  principal.
- Даты периода передаются как ISO `YYYY-MM-DD`; границы `periodStart`/`periodEnd` включительные,
  если feature-документ не определяет специальную месячную семантику.
- Frontend не пересчитывает backend-owned status, share, quality или readiness и не заменяет `null`
  нулём.
- Ошибки используют единый контракт из
  [`../architecture/error-handling.md`](../architecture/error-handling.md).

## Подтверждённые gaps OpenAPI v10

В baseline отсутствуют полноценные `securitySchemes`, общие 401/403 responses и reusable
`ApiError`. Spring Security и backend tests обеспечивают фактическую защиту, но transport baseline
ещё не выражает её полностью. `POST /api/auth/logout`, обслуживаемый security filter, также не
представлен как path в OpenAPI v10.

До исправления gap:

- backend tests остаются доказательством access/error semantics;
- generated consumer не может делать вывод «endpoint публичный» из отсутствия security metadata;
- новый prose не должен придумывать shape, противоречащий реальному `ApiError`.

## Тематические контракты

- [`authentication.md`](authentication.md), [`store-directory.md`](store-directory.md),
  [`store-data-status.md`](store-data-status.md)
- [`data-quality.md`](data-quality.md), [`period-quality.md`](period-quality.md)
- [`store-kpi.md`](store-kpi.md), [`employee-kpi.md`](employee-kpi.md),
  [`category-kpi.md`](category-kpi.md),
  [`employee-category-kpi.md`](employee-category-kpi.md), [`average-kpi.md`](average-kpi.md),
  [`attach-rate.md`](attach-rate.md)
- [`employee-rating.md`](employee-rating.md),
  [`store-plan-progress.md`](store-plan-progress.md), [`payroll.md`](payroll.md)
- [`reports.md`](reports.md), [`product-category-import.md`](product-category-import.md)

Каждое изменение DTO/path сначала обновляет generated OpenAPI и compatibility checks, затем
соответствующий semantic contract.
