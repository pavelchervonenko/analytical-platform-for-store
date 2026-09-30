---
doc_schema: 1
doc_type: current
status: current
owner: security
audience:
  - developer
  - operator
last_verified: 2026-09-30
requirement_sources:
  - docs/archive/legacy-contracts/security-hardening.md
  - docs/archive/legacy-contracts/bootstrap-and-break-glass.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/product/service/CatalogLegacyCompatibilityEvidence.java
  - backend/src/main/java/com/storeanalytics/product/service/CatalogCompatibilityService.java
  - backend/src/main/resources/db/migration/V81__store_catalog_compatibility_confirmations.sql
  - backend/src/main/java/com/storeanalytics/auth
  - backend/src/main/java/com/storeanalytics/common/config/SecurityConfig.java
  - backend/src/main/resources/db/migration/V2__add_application_authentication.sql
  - backend/src/main/resources/db/migration/V50__add_user_feature_access.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/product/service/CatalogLegacyCompatibilityEvidenceTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogCompatibilityAuthorizerTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogCompatibilityServiceTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogCompatibilityPersistenceIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/auth
  - backend/src/test/java/com/storeanalytics/common/security/SessionRevocationSecurityAuditTest.java
  - backend/src/test/java/com/storeanalytics/auth/StoreScopedAuthorizationArchitectureTest.java
  - backend/src/test/java/com/storeanalytics/store/web/StoreDataStatusSecurityIntegrationTest.java
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
- Каждый пользовательский endpoint с `/api/stores/{storeId}` обязан содержать явную проверку
  `StoreAccessAuthorization`; архитектурный тест строит effective path из class/method mappings и
  не позволяет добавить endpoint только под общей authenticated-политикой `/api/stores/**`.
- Функции `PLAN`, `SHIFTS`, `PAYROLL` дополняют, но не заменяют store assignment. Управление roster,
  влияющим на смены, требует `SHIFTS`; отчёты остаются доступными без `PAYROLL`, но всегда в границе
  назначенного магазина.
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

## Решения гарантий

`/api/stores/{storeId}/attach-rate/warranties` доступен ADMIN и MANAGER своего назначенного
магазина, без отдельных PLAN/SHIFTS/PAYROLL. Store scope проверяется на очереди, карточке,
поиске, preview и записи; целевое устройство и исходная гарантия дополнительно проверяются
по магазину и подключению. Межмагазинный поиск не раскрывает чужие документы.
Запись требует CSRF, сильный If-Match и Idempotency-Key. История содержит только необходимые
ID, распределения, автора, время и основание; финансовые строки не изменяются решением.

## Внутренние подтверждения совместимости каталога

`CatalogCompatibilityService` пока не имеет публичного endpoint/UI; запись по умолчанию
выключена. Это общий справочник подключения, а не изолированное решение одного магазина.
Внутренняя команда и preview требуют текущего активного ADMIN, сменённого начального пароля
и совпадающего securityVersion. Автор берётся из SecurityContext; повтор идемпотентного
запроса не обходит проверку полномочий. MANAGER не получает это право через store assignment.
SQL дополнительно проверяет активного автора ADMIN, карточку и подключение.
Сохранение требует сильный If-Match и Idempotency-Key, новая ревизия и аудит атомарны.
Отдельное полномочие руководителя на весь затронутый каталог, endpoint и CSRF-контракт
должны быть реализованы до открытия функции в UI. Внутренний сервис не заменяет эти проверки.

Внутренний adoptLegacy требует байты исходного журнала и ожидаемый хеш. Проверка формата,
уникальности и явной совместимости предшествует записи; совпадение карточки проверяется
под блокировкой вместе с ETag. Обычная команда не принимает LEGACY_ADOPTION без документа.
Ожидаемый хеш сверяется оператором с доверенным источником; это не криптографическая
подпись первоначального автора. Повтор проверяет полномочия и возвращает прежний receipt.
