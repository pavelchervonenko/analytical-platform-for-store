---
doc_schema: 1
doc_type: runbook
status: draft
owner: ai
audience:
  - operator
last_verified: 2026-08-31
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - staging
  - production-read-only
operation_type: reversible-write
environments:
  - test
  - staging
  - production
risk_level: high
source_of_truth:
  - backend/src/main/java/com/storeanalytics/interpretation/web/LlmOperationsController.java
  - backend/src/main/java/com/storeanalytics/interpretation/web/LlmOperationsControlController.java
  - backend/src/main/java/com/storeanalytics/interpretation/publication/LlmPublicationStore.java
  - backend/src/main/java/com/storeanalytics/notification/fanout/NotificationEventFanoutService.java
verification_evidence:
  - level: static
    scope: legacy operations, publication transaction, read fallback and weekly fanout reviewed
    verified_at: 2026-08-31
    evidence: docs/current/ai/legacy-llm.md
required_reviewers:
  - ai-semantic
  - operations
  - security-privacy
review_triggers:
  - legacy-llm-change
  - manual-regeneration-change
  - weekly-notification-change
  - legacy-retirement-change
supersedes: []
superseded_by: null
---

# Legacy LLM: диагностика и ручное восстановление

## Цель и область

Процедура помогает диагностировать legacy snapshot/job/interpretation и при необходимости выполнить
один idempotent manual regenerate или cancel. Она не является штатным способом создавать v25
weekly-review и не разрешает массовую регенерацию.

## Влияние и требуемая авторизация

Regenerate создаёт новый legacy job, может вызвать платный provider и после publication создать
immutable interpretation вместе с weekly Telegram event. External message после отправки
необратимо. Нужны exact target approval, cost authorization и operator/privacy review.

## Предусловия

- Подтверждено, что проблема относится именно к legacy fallback/Telegram, а не к v25 enrichment.
- Snapshot ID, store, period, quality status и hashes определены.
- Известно состояние active/failed job, interpretation revision, event и deliveries.
- До regenerate проверено, что v25 UI не решает пользовательскую проблему без legacy write.

## Секреты и безопасный вывод

Использовать authenticated admin API без вывода cookie/token. Не сохранять snapshot/provider payload,
employee names, Telegram identifiers или rendered text. Допустимы IDs, hashes, schema/prompt
versions, statuses и агрегированные counts.

## Критерии остановки

- Snapshot blocked/unknown или период не совпадает.
- Есть active job/lease; сначала дождаться или отменить exact job.
- Нет отдельного approval provider cost.
- Regenerate создаст нежелательное weekly уведомление.
- Target content schema не входит в 1–3.
- Queue содержит неподдерживаемый schema4 weekly event: сначала изолировать incident.

## Preflight

Прочитать `GET /api/admin/llm/operations`, current legacy endpoint и delivery operations. Сверить
job/snapshot/interpretation/event chain и отсутствие конкурирующей записи. Локально выполнить:

```bash
./gradlew :backend:test --tests '*Llm*' --tests '*WeeklyInsight*'
./gradlew :backend:test --tests '*NotificationEventFanout*' --tests '*WeeklyTelegramMessageRenderer*'
```

## Точный target

Change record содержит environment/release evidence, store ID, period, snapshot/job IDs, current
interpretation revision, event/delivery counts, idempotency key, reason, approved max calls/cost и
ожидаемый notification impact.

## Процедура

1. Для active ошибочного job вызвать exact cancel:
   `POST /api/admin/llm/jobs/{jobId}/cancel` с новым `Idempotency-Key` и причиной.
2. Дождаться terminal state и повторно прочитать operations view.
3. Только после отдельного regenerate approval вызвать
   `POST /api/admin/llm/snapshots/{snapshotId}/regenerate` с новым `Idempotency-Key`.
4. Наблюдать phases и attempts; не запускать второй job.
5. При publication сверить revision, interpretation hash и ровно один expected weekly event.
6. Проверить fanout/delivery только для согласованных recipients; message content в evidence не
   копировать.
7. Проверить legacy endpoint и убедиться, что новый v25 endpoint не был изменён этой операцией.

## Проверка результата

Успех: один terminal successful job, одна новая immutable revision, один deduplicated event,
ожидаемое число deliveries/receipts, отсутствие poison schema, cost в cap. Если recipients не
должны получать сообщение, event publication означает stop и incident.

## Повторный запуск и конкурентность

`Idempotency-Key` повторяется только для retry того же логического действия. Новый key означает
новую авторизацию. Не запускать regenerate при active job или неизвестной точке обрыва; сначала
прочитать durable state.

## Rollback или forward-fix

Interpretation и отправленное сообщение не удаляются. Forward-fix — новая revision после исправления
контракта/данных. До публикации job можно cancel; после publication можно только остановить дальнейший
fanout и зафиксировать incident.

## Evidence

Сохранить sanitized chain snapshot→job→attempt→interpretation→event→delivery, hashes, versions,
cost/outcomes и явный scope. Не включать payload, names, chat IDs или rendered text.

## Репетиция

- Достигнут только `static`.
- Нужен staging rehearsal cancel/regenerate/publication/fanout и production read-only preflight.
- Пока schema4 poison-event не изолирован, любой неподдерживаемый event является stop condition.
