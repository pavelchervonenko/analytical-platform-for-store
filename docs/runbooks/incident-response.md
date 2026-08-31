---
doc_schema: 1
doc_type: runbook
status: draft
owner: operations
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
  - docs/security/threat-model-and-risk-register.md
  - deploy/bin
  - deploy/compose.production.yml
verification_evidence:
  - level: static
    scope: repository recovery boundaries and known operational gaps
    verified_at: 2026-08-31
    evidence: docs/security/threat-model-and-risk-register.md
required_reviewers:
  - operations
  - security-privacy
  - backend
review_triggers:
  - incident
  - topology-change
  - recovery-change
supersedes: []
superseded_by: null
---

# Реакция на incident

## Цель и область

Организовать технический response при outage, corruption, credential compromise или ошибочном
release. Конкретные migration/restore/rotation действия выполняются специализированными runbooks.

## Влияние и требуемая авторизация

Incident commander определяет severity, владельцев operations/backend/security/customer comms и
разрешает stop-writes. Любая destructive/recovery операция получает отдельную exact-target
авторизацию.

## Предусловия и точный target

Создать incident ID; записать start/detection time, environment, affected stores/services/data
periods, release/digests/schema и known change. Назначить evidence custodian и communication owner.

## Критерии остановки

- Нельзя установить exact target/authority.
- Предлагаемое действие уничтожает evidence, очищает queue/logs или перезаписывает backup/DB.
- Recovery plan не содержит before/after invariants и rollback/forward-fix.

## Процедура

1. Классифицировать severity по безопасности данных, доступности и финансовой достоверности.
2. Сохранить volatile evidence: bounded logs, health, metrics, queues, release/schema/flags; не
   копировать secrets/payload без privacy approval.
3. Containment: отключить точный ingress/worker/flag или writers минимально необходимым способом.
4. Выбрать ветку: application rollback, migration forward-fix, backup restore, secret rotation,
   access recovery или provider degradation.
5. Выполнить recovery после independent review, затем technical и business reconciliation.
6. Наблюдать agreed window, сообщить customer impact и закрыть только после owner acceptance.

## Проверка результата

Health/schema/queues стабильны, безопасность восстановлена, data period/store scope reconciled,
temporary access/flags/silences удалены. Unresolved impact остаётся в incident status.

## Повторный запуск и rollback

Каждый recovery step имеет собственный retry/rollback. Не объединять несколько writes без
checkpoint. При неизвестной точке сбоя возвращаться к read-only inspection.

## Evidence и известные пробелы

Сохранить timeline, decisions/authorizers, before/after facts, customer impact, recovery и follow-up.
Нет принятой severity matrix, communication SLA, credential compromise matrix, quarterly tabletop
или DR exercise evidence; runbook остаётся draft.
