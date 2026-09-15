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
  - backend/src/test/java/com/storeanalytics/auth/UserAdministrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/store/web/StoreDataStatusSecurityIntegrationTest.java
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
- Новый пароль после NFC-нормализации содержит 12–128 Unicode code points, не более 72 UTF-8 bytes,
  не содержит управляющих символов, отличается от текущего и отсутствует в локальном списке
  распространённых/скомпрометированных паролей. UI проверяет измеримые ограничения до запроса, а
  backend остаётся авторитетной границей полной policy.
- Role/store/feature-access/credential change увеличивает security version; устаревшая session
  отклоняется при следующем запросе. Повторное сохранение того же набора прав version не меняет.
- `ADMIN` имеет неявный доступ ко всем магазинам и всем функциям. `MANAGER` видит только назначенные
  магазины и получает независимый глобальный набор `features`: `PLAN`, `SHIFTS`, `PAYROLL`.
  Пустой набор допустим и означает «только аналитика». Публичной регистрации нет.
- Без `PLAN`, `SHIFTS` или `PAYROLL` backend возвращает `403` для прямых API соответствующего
  раздела, а frontend скрывает пункт меню и перенаправляет прямую ссылку на обзор. `PAYROLL` также
  удаляет зарплатный блок из карточки сотрудника. Архив отчётов намеренно остаётся доступным и может
  содержать зарплатные значения, а аналитические агрегаты не считаются прямым управлением сменами.
- `GET /api/auth/me` и admin user responses возвращают эффективный список `features`; для
  администратора он всегда содержит все известные функции, хотя явные строки доступа не хранятся.
- Создание руководителя требует явных `storeIds` и `features`. Единый
  `PUT /api/admin/users/{userId}` атомарно обновляет профиль, роль, active, магазины и функции и
  требует `version`; устаревшая форма получает `409 CONCURRENT_MODIFICATION` без частичной записи.
  Legacy endpoint замены магазинов также требует `version`.
- Раздел и API качества данных доступны только `ADMIN`, даже если магазин назначен менеджеру.
- Registry process-local; multi-replica API без общего session store не поддерживается.

## Ошибки

Основные коды: `INVALID_CREDENTIALS`, `AUTHENTICATION_REQUIRED`, `SESSION_EXPIRED`,
`ACCESS_DENIED`, `PASSWORD_POLICY_VIOLATION`, `LOGIN_THROTTLED`,
`CURRENT_SESSION_REQUIRES_LOGOUT`. Точный общий error shape —
в [`../architecture/error-handling.md`](../architecture/error-handling.md).

OpenAPI v12 не содержит полноценного security scheme и общих 401/403 responses. Фактическая
security semantics подтверждается security configuration и integration tests; baseline необходимо
дополнить отдельно.
