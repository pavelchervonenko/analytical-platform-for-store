---
doc_schema: 1
doc_type: current
status: current
owner: frontend
audience:
  - developer
last_verified: 2026-09-14
requirement_sources:
  - contracts/openapi/current.json
implementation_sources:
  - frontend/src/api/client.ts
  - frontend/src/api/contracts.ts
  - frontend/src/api/queries.ts
verification_sources:
  - frontend/src/api/consumerContract.test.ts
  - contracts/openapi/current.json
runtime_evidence: []
required_reviewers:
  - frontend
  - backend
review_triggers:
  - api-change
  - zod-schema-change
  - error-contract-change
supersedes: []
superseded_by: null
---

# Transport-контракты frontend

OpenAPI — transport shape, Zod — runtime validation потребляемых ответов, product docs — semantics.
Client использует same-origin production path, JSON headers, CSRF для unsafe methods, optional
idempotency, timeout и structured error с correlation ID/Retry-After.

`404` становится empty state только по явному endpoint contract (например, plan absent). Frontend
не заменяет nullable cost/GP/margin/rate/score нулём и не пересчитывает achievement из округлённого
текста.
Общий `QueryError` различает отсутствие доступа/данных, конфликт версии, временный transport
failure и несовместимый response contract. Для 403/404 бесполезный retry не показывается;
correlation ID выводится только как ссылка для обращения в поддержку.

OpenAPI v12 делает `features` обязательным в session/admin user responses и в create/update user
commands; combined update также требует `active`, `storeIds` и optimistic `version`. Runtime-схема
при чтении допускает неизвестное enum-значение как `UNKNOWN`, но fail-closed админка не сохраняет
такого пользователя старым набором полей.

## Gaps

1. OpenAPI неполно описывает security schemes, 401/403 и общий `ApiError`.
2. Consumer test проверяет paths и часть schemas, не полное совпадение всех KPI DTO.
3. Backend/UI precision различается; возможно presentation-отличие `0,1 п. п.`.
4. Provider ingress намеренно отсутствует в public OpenAPI и не вызывается UI.

API change требует OpenAPI baseline, Zod, query consumer и screen test; nullable finance требует
negative-test, запрещающий zero substitution.
