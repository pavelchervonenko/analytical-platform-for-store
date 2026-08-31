---
doc_schema: 1
doc_type: runbook
status: draft
owner: security
audience:
  - operator
last_verified: 2026-08-31
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - staging
  - production-read-only
  - production-drill
operation_type: recovery
environments:
  - staging
  - production
risk_level: critical
source_of_truth:
  - backend/src/main/java/com/storeanalytics/auth/bootstrap
  - backend/src/main/java/com/storeanalytics/auth/security/BreakGlassAccessMonitor.java
  - backend/src/main/java/com/storeanalytics/auth/service/UserAdministrationService.java
verification_evidence:
  - level: static
    scope: bootstrap single-use guard and break-glass audit/alert behavior
    verified_at: 2026-08-31
    evidence: backend/src/test/java/com/storeanalytics/auth
required_reviewers:
  - security-privacy
  - operations
review_triggers:
  - authentication-change
  - break-glass-change
  - access-incident
supersedes: []
superseded_by: null
---

# Доступ и break-glass

## Цель и область

Восстановить административный доступ через заранее подготовленный named break-glass account.
Bootstrap используется только при первоначально пустой user table и не является reset path.

## Влияние и требуемая авторизация

Каждый emergency login — security incident/change с customer authorization, independent witness и
обязательным audit review. Database-level recovery не разрешён этим draft.

## Предусловия и точный target

Зафиксировать incident ID, environment/domain, user UUID/reference, owner/witness, reason, allowed
actions и expiry. Credential извлекается customer custodian вне чата/репозитория.

## Критерии остановки

- Нет out-of-band customer authorization или exact account UUID.
- Account не заранее provisioned/marked, audit/alert path не готов либо credential shared.
- Предлагается повторно включить bootstrap при непустой user table или использовать hardcoded
  defaults из исторического activation script.

## Процедура

1. Предпочесть другого named admin. Если невозможно — подтвердить break-glass account и scope.
2. Выполнить один login, проверить persistent/structured break-glass event.
3. Сделать только разрешённое действие: создать/восстановить named admin, reset password, revoke
   compromised sessions/access.
4. Выйти, проверить revoke/expiry и вернуть credential в sealed custody либо rotate по решению.
5. Проверить audit trail и конечную alert delivery.

## Проверка результата

Обычный named admin доступен, emergency session закрыта, unintended store/role access отсутствует,
audit/alert и authorizer подтверждены.

## Повторный запуск и rollback

Повтор требует новой авторизации. Rollback — удалить временно выданный access/role или disable
compromised account через named admin с audit; audit events не удаляются.

## Evidence и известные пробелы

Сохранить incident/account pseudonymous ref, authorizers, actions, timestamps и audit/alert verdict.
Application MFA отсутствует; customer custody и break-glass rehearsal не доказаны. Скрипт
`activate-bootstrap-admin.sh` содержит environment-specific defaults и не является универсальным
runbook. Статус остаётся draft.
