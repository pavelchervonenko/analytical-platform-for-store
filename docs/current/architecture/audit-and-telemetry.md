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
  - docs/archive/legacy-contracts/audit-log.md
  - docs/archive/legacy-contracts/observability.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/audit
  - backend/src/main/java/com/storeanalytics/common/observability/SiemAuditEvent.java
  - backend/src/main/java/com/storeanalytics/common/security/SecurityAuditLogger.java
  - backend/src/main/resources/db/migration/V10__make_audit_log_immutable.sql
  - backend/src/main/resources/db/migration/V12__add_data_retention.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/audit
  - backend/src/test/java/com/storeanalytics/common/observability/SiemAuditEventTest.java
  - backend/src/test/java/com/storeanalytics/common/security/SecurityAuditLoggerTest.java
runtime_evidence: []
required_reviewers:
  - security-privacy
  - backend
review_triggers:
  - audit-event-change
  - telemetry-change
  - retention-change
  - pii-boundary-change
supersedes: []
superseded_by: null
---

# Аудит и security-телеметрия

## Назначение и границы

Документ описывает persistent audit и bounded structured telemetry. Он не утверждает, что логи
экспортируются в SIEM или хранятся вне application host.

## Действующий контракт

- Business/security commands записывают audit entry с actor, action, target, optional reason,
  bounded before/after metadata, retention class и временем создания.
- Flyway запрещает обычное изменение/удаление audit rows; retention deletion разрешается только
  специальному transaction-local пути и учитывает holds и retention class.
- Классы `FINANCIAL`, `SECURITY`, `BUSINESS`, `OPERATIONAL` задают разные сроки; финансовый audit
  автоматически не удаляется.
- SIEM envelope принимает только allowlisted поля, bounded symbols/counts/boolean и псевдонимные
  references. Неожиданное поле или значение отклоняется.
- Pseudonym key ID является несекретным идентификатором ротации; сам HMAC key поступает через
  secret file/config tree.

## Инварианты

- Пароли, tokens, cookies, raw session IDs, webhook payloads и персональные provider responses не
  входят в audit metadata или telemetry.
- Audit log не заменяет business facts и не является backup.
- Structured log не считается immutable evidence без off-host append-only storage.
- Ротация pseudonym key меняет correlation boundary и должна фиксироваться без публикации ключа.

## Ошибки и неполные данные

Ошибка обязательной audit-записи в транзакционном command path должна приводить к rollback
команды. Недоступность внешнего log sink не должна заставлять приложение раскрывать payload; при
этом отсутствие off-host копии остаётся operational risk.

## Расхождения и открытые решения

- В репозитории не доказаны off-host collection, immutable storage, retention/legal hold и alert
  routing для structured logs.
- Не существует единой incident correlation procedure через смену pseudonym key.
- Полнота audit coverage должна проверяться при каждом новом privileged/financial command.

## Проверка

Tests подтверждают immutable/retention semantics, allowlisted metadata, bounded SIEM envelope и
security event redaction. Runtime transport и off-host retention требуют отдельного evidence.

## Триггеры пересмотра

Новый audit action, изменение metadata, PII, retention class, log format, pseudonymization или SIEM
sink требует security review и обновления документа.
