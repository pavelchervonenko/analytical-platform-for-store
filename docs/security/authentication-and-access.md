---
doc_schema: 1
doc_type: current
status: current
owner: security
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/security-hardening.md
  - docs/bootstrap-and-break-glass.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/auth
  - backend/src/main/java/com/storeanalytics/common/config/SecurityConfig.java
  - backend/src/main/resources/db/migration/V2__add_application_authentication.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/auth
  - backend/src/test/java/com/storeanalytics/common/security/SessionRevocationSecurityAuditTest.java
runtime_evidence: []
required_reviewers:
  - security-privacy
  - backend
review_triggers:
  - authentication-change
  - role-change
  - session-change
  - break-glass-change
supersedes: []
superseded_by: null
---

# Аутентификация и управление доступом

## Назначение и границы

Документ описывает application authentication, authorization и emergency-access boundaries. Cloud,
SSH, database-provider IAM и customer identity governance находятся вне application runtime.

## Действующий контракт

- Passwords нормализуются NFC, хешируются bcrypt cost 12 и проверяются policy/offline compromised
  blocklist. Login имеет email/IP throttling и bounded retention.
- Browser auth хранится в server-side HTTP session. Login меняет session ID; cookies HttpOnly,
  Secure в prod profile и SameSite Lax; state changes требуют CSRF token.
- Idle timeout, absolute timeout, security-version invalidation и ограничение concurrent sessions
  уменьшают lifetime доступа. Пользователь видит псевдонимные references и может отозвать другие
  sessions.
- Roles и store assignments проверяются на service/controller boundaries. Admin operation требует
  изменённый после bootstrap пароль.
- Bootstrap admin создаётся только при пустой user table, под PostgreSQL advisory lock, и обязан
  сменить пароль. Break-glass user IDs создают persistent audit и structured alert на login.

## Инварианты

- Bootstrap secret не является повторным reset-механизмом.
- Current session отзывается через logout, а не через endpoint удаления другой session.
- Raw session IDs и passwords не возвращаются и не логируются.
- Нельзя увеличивать API replicas: `SessionRegistryImpl` и locks process-local.

## Расхождения и открытые решения

- Application MFA отсутствует; до её появления нельзя заявлять MFA recovery.
- Нет репозиторного доказательства customer-owned break-glass rehearsal и подписанного exception.
- Утрата всех admin/break-glass credentials требует отдельной customer-authorized incident
  procedure; публичного reset endpoint нет.

## Проверка

Auth/security integration tests проверяют login, password change, CSRF, role/store scope, session
revoke, concurrency и bootstrap. Runtime user inventory и emergency custody не выводятся из кода.

## Триггеры пересмотра

Изменение password/MFA/session policy, ролей, store scope, replica topology, bootstrap или
break-glass процесса требует security review.
