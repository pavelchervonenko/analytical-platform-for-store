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
  - backend/src/main/java/com/storeanalytics/notification/daily/DailyStorePulsePlanner.java
  - backend/src/main/java/com/storeanalytics/notification/daily/DailyStorePulseEventStore.java
  - backend/src/main/java/com/storeanalytics/notification/fanout/DailyNotificationEventFanoutService.java
  - backend/src/main/java/com/storeanalytics/notification/fanout/DailyTelegramMessageRenderer.java
verification_evidence:
  - level: static
    scope: daily coverage, projection, deduplication, fanout and rendering paths reviewed
    verified_at: 2026-08-31
    evidence: docs/current/ai/telegram.md
required_reviewers:
  - operations
  - security-privacy
  - backend-data
review_triggers:
  - daily-pulse-change
  - sync-coverage-change
  - notification-policy-change
  - timezone-change
supersedes: []
superseded_by: null
---

# Daily store pulse: canary и контроль покрытия

## Цель и область

Процедура проверяет один daily pulse за exact store/business date. Daily pulse детерминирован и не
использует AI. Статус `draft`, потому что текущий readiness gate допускает `PARTIAL_SUCCESS` и не
доказывает отсутствие внутренних gaps.

## Влияние и требуемая авторизация

Planner создаёт immutable-by-convention notification event, fanout — deliveries, Bot API —
необратимое внешнее сообщение. Нужны exact target, test recipient, operator approval и privacy
review.

## Предусловия

- Store timezone и вчерашний business date вычислены явно.
- Coverage подтверждено отдельно для `SALES`, `RETURNS`, `ORDERS`.
- Помимо максимального `period_end`, read-only reconciliation доказывает отсутствие gaps и failed
  windows за target date.
- Quality/classification issues не делают сообщение вводящим в заблуждение.
- Weekly queue не содержит poison event, способный не допустить daily fanout.
- Recipient count до записи равен `1`.

## Секреты и безопасный вывод

Использовать правила [Telegram runbook](telegram.md). Evidence содержит только store/date,
coverage statuses, aggregate KPI/hash, event/delivery outcomes и timestamps.

## Критерии остановки

- Любой scope не покрывает полный business date.
- Найден `PARTIAL_SUCCESS` без отдельной проверки внутренних окон.
- Timezone неизвестна/изменилась либо local send window уже закрыт.
- Projection содержит персональные данные вне согласованного message contract.
- Deduplication key уже существует с другим payload hash.
- Recipient count не равен exact canary scope.

## Preflight

```bash
./gradlew :backend:test --tests '*DailyStorePulse*'
./gradlew :backend:test --tests '*DailyTelegramMessage*'
./gradlew :backend:test --tests '*DailyNotification*'
```

Для staging дополнительно смоделировать: полный coverage, missing scope, gap внутри
`PARTIAL_SUCCESS`, duplicate planner run, expired send window и poison weekly event.

## Точный target

Зафиксировать environment/release evidence, store ID/timezone, business date, три coverage
интервала/statuses, reconciliation result, policy/render versions, expected payload hash,
test subscription и expected recipient count.

## Процедура

1. Выполнить read-only coverage/gap reconciliation для exact date.
2. Снять deterministic KPI projection и сверить с dashboard за тот же business date.
3. Подтвердить send window и один recipient.
4. Разрешить один scheduler cycle в staging/canary scope; прямого production trigger без
   проверенного wrapper-а не использовать.
5. Проверить ровно один `DAILY_STORE_PULSE` event и deduplication key.
6. Проверить daily fanout receipt и одну delivery.
7. Дождаться terminal outcome; повторный planner cycle не должен создавать второе событие.
8. После canary вернуть конфигурацию в заранее зафиксированное состояние через общий release
   процесс; фактические значения не копировать в этот документ.

## Проверка результата

Event business date/timezone/payload hash совпадают с target, projection совпадает с dashboard,
создан один event/receipt/delivery, повторный cycle идемпотентен, message sanitization соблюдена.

## Повторный запуск и конкурентность

Deduplication key включает store/date/policy version. Не менять policy version для обхода duplicate.
При неизвестном результате сначала читать event/receipt/delivery state. Параллельные planner cycles
должны свестись к одной записи.

## Rollback или forward-fix

Отправленное сообщение не откатывается. До delivery event можно остановить дальнейший fanout по
утверждённой release-процедуре; ручное удаление событий запрещено. Неверный расчёт исправляется
новой policy version после incident review, а не повторной отправкой без согласования.

## Evidence

Сохранить coverage/reconciliation без payload, event hash, dedup outcome, recipient/delivery counts,
provider outcome и timestamps. Не сохранять message text/Telegram IDs.

## Репетиция

- Достигнут только `static`.
- Требуется staging gap-in-period и poison-event rehearsal.
- Production read-only evidence должно подтвердить queues/coverage до любого canary.
