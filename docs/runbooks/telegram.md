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
  - backend/src/main/java/com/storeanalytics/integration/telegram/web/TelegramWebhookAuthenticationFilter.java
  - backend/src/main/java/com/storeanalytics/notification/web/TelegramChannelController.java
  - backend/src/main/java/com/storeanalytics/notification/web/TelegramDeliveryOperationsController.java
  - backend/src/main/java/com/storeanalytics/notification/web/ManualTelegramResendController.java
  - backend/src/main/java/com/storeanalytics/notification/delivery/NotificationDeliveryWorker.java
verification_evidence:
  - level: static
    scope: linking, webhook, fanout, delivery and manual resend paths reviewed
    verified_at: 2026-08-31
    evidence: docs/current/ai/telegram.md
required_reviewers:
  - security-privacy
  - operations
  - ai-semantic
review_triggers:
  - telegram-webhook-change
  - telegram-secret-change
  - notification-delivery-change
  - notification-retention-change
supersedes: []
superseded_by: null
---

# Telegram: linking, webhook и delivery canary

## Цель и область

Процедура проверяет один test manager/private chat от link до delivery и безопасно диагностирует
очередь. Она не разрешает массовый fanout, schema4 weekly event или публикацию персонального
message content в evidence.

## Влияние и требуемая авторизация

Операция создаёт subscription/delivery и отправляет внешнее сообщение, которое нельзя отозвать.
Нужны согласие владельца test chat, exact store/event scope, privacy review и operator approval.
Production canary выполняется только после staging rehearsal.

## Предусловия

- Runtime/release берётся из [project-state](../current/project-state.md).
- Bot code/username, current/previous webhook secret availability и Bot API credential проверены
  без вывода значений.
- Используется отдельный test manager с минимальным store access.
- Weekly canary использует только legacy schema 1–3; schema4 запрещена до появления bridge.
- Delivery/fanout queues не содержат unknown or poison events.

## Секреты и безопасный вывод

Bot token и webhook secrets передаются только через secret files/config tree. Не использовать
`printenv`, shell tracing, query string или command line с secret. Evidence не содержит Telegram
user/chat IDs, link token, `/start` command или rendered message.

## Критерии остановки

- Previous-secret rollout заявлен, но previous secret не смонтирован в runtime.
- Webhook отвечает не ожидаемым auth/content-type/body-limit поведением.
- Destination уже принадлежит другому app user.
- Event schema не поддерживается renderer-ом.
- Есть stuck weekly event без receipt: daily fanout может быть заблокирован.
- Recipient list шире exact test user.
- Нет согласия на отправку или retention условий test data.

## Preflight

```bash
./gradlew :backend:test --tests '*TelegramWebhookSecurityIntegrationTest'
./gradlew :backend:test --tests '*TelegramLinkingIntegrationTest'
./gradlew :backend:test --tests '*NotificationEventFanoutIntegrationTest'
./gradlew :backend:test --tests '*NotificationDelivery*'
```

Read-only operator view: `GET /api/admin/notifications/telegram/deliveries`. Проверить aggregate
queue states, expired leases и incidents без открытия rendered text.

## Точный target

Записать environment/release evidence, bot code, test app user ID, exact store access, event type,
expected schema, policy/render versions, expected recipient count `1`, idempotency key и срок
удаления test linkage/evidence.

## Процедура

1. Test user создаёт link через `POST /api/notifications/channels/telegram/link`.
2. Пользователь самостоятельно отправляет полученную команду в private chat; operator не копирует
   token.
3. Проверить pending subscription через authenticated GET и подтвердить с exact ETag.
4. Повторить duplicate webhook update в staging и подтвердить отсутствие второй subscription.
5. Создать только согласованный test event поддерживаемой schema либо service confirmation.
6. До fanout подтвердить recipient count `1`; при другом count остановиться.
7. Дождаться delivery terminal state и проверить provider outcome/attempts через operations view.
8. Для manual retry использовать
   `POST /api/admin/notifications/telegram/deliveries/{deliveryId}/resend` с новым
   `Idempotency-Key` только после отдельного approval.
9. Revoke test subscription с current ETag и проверить прекращение новых deliveries.

## Проверка результата

Успех: webhook receipt идемпотентен, subscription ownership корректна, одна expected delivery
доставлена или получила объяснимый terminal outcome, retries bounded, secrets/message text не
попали в logs/evidence, после revoke user больше не eligible.

## Повторный запуск и конкурентность

Link token не переиспользовать. Duplicate `update_id` должен быть no-op. Manual resend retry
использует тот же idempotency key; новая отправка — новый approval/key. При неизвестном outcome
сначала читать durable delivery/attempt state.

## Rollback или forward-fix

Subscription можно revoke, pending delivery — cancel/expire по реализованному lifecycle. Уже
отправленное сообщение необратимо. Ошибочный контент исправляется новым message/event только после
incident review; историю БД вручную не удалять.

## Evidence

Сохранить versions, hashed/opaque IDs, receipt/delivery outcomes, attempt counts, latency и
timestamps. Не сохранять secrets, chat/user IDs, token, payload или rendered text.

## Репетиция

- Достигнут только `static`.
- Нужен staging end-to-end webhook/link/fanout/delivery/revoke rehearsal.
- Production scope до `current` ограничивается read-only preflight и одним отдельно одобренным
  canary.
