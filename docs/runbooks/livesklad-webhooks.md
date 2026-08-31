---
doc_schema: 1
doc_type: runbook
status: draft
owner: integrations
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
  - staging
  - production
risk_level: medium
source_of_truth:
  - backend/src/main/java/com/storeanalytics/integration/livesklad/webhook
  - backend/src/main/resources/application.yml
  - deploy/compose.production.yml
verification_evidence:
  - level: static
    scope: ingress authentication, dedupe, worker retry and configuration contract
    verified_at: 2026-08-31
    evidence: backend/src/test/java/com/storeanalytics/integration/livesklad/webhook
required_reviewers:
  - integration
  - operations
  - security-privacy
review_triggers:
  - webhook-contract-change
  - webhook-secret-change
  - compose-change
supersedes: []
superseded_by: null
---

# Активация LiveSklad webhooks

## Статус процедуры

Runbook остаётся `draft`: static code/test review выполнен, но repository evidence не доказывает
полный staging rehearsal и свежий production read-only preflight. Он не является разрешением
изменять production прямо сейчас.

## Цель и влияние

Процедура подключает два endpoint-specific webhook secret, проверяет URL verification, принимает
первое реальное событие и canary-включает worker. Изменение обратимо выключением worker/receiver;
уже сохранённые receipts не удаляются.

Авторизация: владелец production и независимый operations reviewer. В change record фиксируются
окружение, store, webhook kind, время и sanitized receipt state — без token/payload.

## Предусловия

- Exact release/schema/runtime role подтверждены отдельным sanitized production evidence.
- Оба current secrets подготовлены в secret store; sale/order используют разные значения.
- Compose preflight проходит до database migration/restart.
- Scheduled overlap настроен как fallback missed events; нет конфликтующей migration/recovery.
- Известен exact webhook kind; первый real ORDER_RETURN ещё не считается provider contract без
  проверки `data.id`.

## Секреты и безопасный вывод

Никогда не печатать secret file, environment целиком, headers, session cookie или payload. Допустимо
сохранять только enabled/disabled flags, receipt ID/hash/state, delivery count, mismatch boolean,
attempt count, safe error code и timestamps.

## Критерии остановки

- URL verification не возвращает exact challenge/HTTP 200.
- Event не создаёт одну receipt с `delivery_count=1` и `payload_mismatch=false`.
- `data.id` не scalar или не открывает ожидаемый detail resource.
- Возникают payload mismatch, terminal failure, stale queue, health regression или неожиданное
  изменение фактов.

## Preflight

1. Проверить exact target и sanitized flags, не выводя их секретные значения.
2. Выполнить repository release preflight для полного Compose model.
3. Проверить public health и worker health.
4. Read-only проверить отсутствие stale/terminal/mismatch receipts и конфликтующей job.

Ожидаемый результат: точный target подтверждён, receiver/worker flags известны, секреты существуют,
очередь не содержит необъяснённых отказов.

## Процедура

1. Создать в LiveSklad отдельные sale-return и order-return webhooks с минимальным payload.
2. Передать endpoint-specific token только через custom header; verification challenge — через
   `X-LiveSklad-Verification`.
3. Проверить URL каждого endpoint при выключенном соответствующем worker.
4. После первого реального события read-only подтвердить `eventId`, kind, `data.id`, delivery
   count и mismatch state. `action.id` не использовать.
5. Для ORDER_RETURN отдельно подтвердить, что `data.id` идентифицирует exact order detail.
6. Canary-включить только нужный worker и перезапустить только worker service.
7. Дождаться `PROCESSED`, сверить exact document/order facts и проверить очередь после следующего
   overlap cycle.

## Повтор и rollback

Redelivery с тем же payload идемпотентна. Другой payload под тем же event ID — stop condition.
При failure выключить worker, сохранить receipt, вернуть предыдущий flag и выполнить forward-fix;
не удалять/переписывать payload или terminal state вручную. Receiver можно оставить включённым для
durable накопления только после privacy/retention review.

## Evidence и ограничения

Historical record должен содержать exact kind, timestamps, receipt state, safe error codes,
reconciliation и reviewer verdict. Для перевода runbook в `current` обязательны staging и свежий
production-read-only evidence для каждого webhook kind.
