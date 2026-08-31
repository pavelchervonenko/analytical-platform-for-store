---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/archive/legacy-contracts/daily-store-pulse.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/notification/daily/DailyStorePulsePlanner.java
  - backend/src/main/java/com/storeanalytics/notification/daily/DailyStorePulseEventStore.java
  - backend/src/main/java/com/storeanalytics/notification/fanout/DailyNotificationEventFanoutService.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/notification/daily
  - backend/src/test/java/com/storeanalytics/notification/fanout/DailyTelegramMessageSanitizationTest.java
runtime_evidence: []
required_reviewers:
  - product
  - backend-data
  - security-privacy
review_triggers:
  - daily-pulse-change
  - sync-coverage-change
  - notification-policy-change
supersedes: []
superseded_by: null
---

# Ежедневная сводка магазина

Daily store pulse — детерминированная сводка за вчерашний business date магазина. Она не
использует LLM и не является доказательством полной синхронизации сама по себе.

Сообщение включает store-level KPI, изменения, дополнительные продажи, категории, короткий
командный блок и ограничение качества. Оно не должно раскрывать внутренние job-коды или технические
ошибки руководителю.

Planner работает только в send window, требует coverage `SALES`, `RETURNS`, `ORDERS` и
дедуплицирует по store/date/policy version. Текущий gate принимает `PARTIAL_SUCCESS` и проверяет
максимальный `period_end`, но не отсутствие внутренних gaps. Поэтому до gap-free invariant
сообщение может быть создано на неполном периоде.

Notification preference при отсутствии строки сейчас default-enabled. Consent semantics,
retention Telegram identifiers/rendered text и poison-event isolation не утверждены. Операционный
canary описан в [draft runbook](../../runbooks/daily-store-pulse.md), который не разрешает
production write до staging и read-only gates.
