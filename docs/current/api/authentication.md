---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - operator
last_verified: 2026-09-14
requirement_sources:
  - docs/archive/legacy-contracts/authentication-api.md
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
  - docs/archive/legacy-contracts/authentication-api.md
superseded_by: null
---

# Authentication API

## Контракт

Browser-клиент использует server-side `JSESSIONID` и CSRF double-submit cookie/header. До login и
после ротации authentication нужно заново получить `GET /api/auth/csrf`. Unsafe requests передают
cookie и `X-XSRF-TOKEN`.

OpenAPI v12 публикует:

- `GET /api/auth/csrf`, `POST /api/auth/login`, `GET /api/auth/me`;
- `GET /api/auth/sessions`, удаление одной другой или всех других sessions;
- `POST /api/auth/change-password`.

`POST /api/auth/logout` обслуживается Spring Security, но отсутствует в OpenAPI v12 — это
зафиксированный transport gap, а не разрешение менять method/path в клиенте без contract update.

## Безопасность и состояния

- Session ID ротируется при login; наружу список sessions отдаёт opaque HMAC reference, timestamp
  и `current`, но не cookie, IP или User-Agent.
- Temporary password ограничивает доступ auth/session endpoints до успешной смены пароля. Смена
  инвалидирует текущую session.
- Role/store/feature-access/credential change увеличивает security version; устаревшая session
  отклоняется при следующем запросе. Повторное сохранение того же набора прав version не меняет.
- `ADMIN` имеет неявный доступ ко всем магазинам и функциям. `MANAGER` видит только назначенные
  магазины и получает независимый глобальный набор `features`: `PLAN`, `SHIFTS`, `PAYROLL`.
  Пустой набор означает «только аналитика».
- Без соответствующей функции backend возвращает `403` для прямых API, frontend скрывает пункт
  меню и перенаправляет прямую ссылку на обзор. Без `PAYROLL` карточка сотрудника не содержит
  зарплатный блок; архив отчётов остаётся доступным и может содержать зарплатные значения.
- Создание руководителя принимает явные `storeIds` и `features`. Единый
  `PUT /api/admin/users/{userId}` атомарно обновляет профиль, роль, active, магазины и функции и
  требует `version`; устаревшая форма получает `409 CONCURRENT_MODIFICATION` без частичной записи.
  Публичной регистрации нет.
- Раздел и API качества данных доступны только `ADMIN`, даже если магазин назначен менеджеру.
- Registry process-local; multi-replica API без общего session store не поддерживается.

## Ошибки

Основные коды: `INVALID_CREDENTIALS`, `AUTHENTICATION_REQUIRED`, `SESSION_EXPIRED`,
`ACCESS_DENIED`, `LOGIN_THROTTLED`, `CURRENT_SESSION_REQUIRES_LOGOUT`. Точный общий error shape —
в [`../architecture/error-handling.md`](../architecture/error-handling.md).

OpenAPI v12 не содержит полноценного security scheme и общих 401/403 responses. Фактическая
security semantics подтверждается security configuration и integration tests; baseline необходимо
дополнить отдельно.
