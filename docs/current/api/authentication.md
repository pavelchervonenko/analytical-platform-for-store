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
  - docs/authentication-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/auth
  - backend/src/main/java/com/storeanalytics/common/security
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/auth/AuthIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/auth/SecurityHardeningIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - security-privacy
review_triggers:
  - authentication-change
  - session-change
  - authorization-change
supersedes:
  - docs/authentication-api.md
superseded_by: null
---

# Authentication API

## Контракт

Browser-клиент использует server-side `JSESSIONID` и CSRF double-submit cookie/header. До login и
после ротации authentication нужно заново получить `GET /api/auth/csrf`. Unsafe requests передают
cookie и `X-XSRF-TOKEN`.

OpenAPI v10 публикует:

- `GET /api/auth/csrf`, `POST /api/auth/login`, `GET /api/auth/me`;
- `GET /api/auth/sessions`, удаление одной другой или всех других sessions;
- `POST /api/auth/change-password`.

`POST /api/auth/logout` обслуживается Spring Security, но отсутствует в OpenAPI v10 — это
зафиксированный transport gap, а не разрешение менять method/path в клиенте без contract update.

## Безопасность и состояния

- Session ID ротируется при login; наружу список sessions отдаёт opaque HMAC reference, timestamp
  и `current`, но не cookie, IP или User-Agent.
- Temporary password ограничивает доступ auth/session endpoints до успешной смены пароля. Смена
  инвалидирует текущую session.
- Role/store-access/credential change увеличивает security version; устаревшая session отклоняется
  при следующем запросе.
- `ADMIN` имеет доступ ко всем магазинам, `MANAGER` — только к назначенным. Публичной регистрации
  нет.
- Registry process-local; multi-replica API без общего session store не поддерживается.

## Ошибки

Основные коды: `INVALID_CREDENTIALS`, `AUTHENTICATION_REQUIRED`, `SESSION_EXPIRED`,
`ACCESS_DENIED`, `LOGIN_THROTTLED`, `CURRENT_SESSION_REQUIRES_LOGOUT`. Точный общий error shape —
в [`../architecture/error-handling.md`](../architecture/error-handling.md).

OpenAPI v10 не содержит полноценного security scheme и общих 401/403 responses. Фактическая
security semantics подтверждается security configuration и integration tests; baseline необходимо
дополнить отдельно.
