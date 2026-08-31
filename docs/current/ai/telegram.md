---
doc_schema: 1
doc_type: current
status: current
owner: ai
audience:
  - developer
  - operator
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/llm-notifications-design.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/integration/telegram/web/TelegramWebhookAuthenticationFilter.java
  - backend/src/main/java/com/storeanalytics/notification/linking/TelegramWebhookService.java
  - backend/src/main/java/com/storeanalytics/notification/fanout/NotificationEventFanoutStore.java
  - backend/src/main/java/com/storeanalytics/notification/fanout/WeeklyTelegramMessageRenderer.java
  - backend/src/main/java/com/storeanalytics/notification/daily/DailyStorePulsePlanner.java
  - backend/src/main/java/com/storeanalytics/notification/delivery/NotificationDeliveryWorker.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/integration/telegram/web/TelegramWebhookSecurityIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/notification/linking/TelegramLinkingIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/notification/fanout/NotificationEventFanoutIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/notification/fanout/WeeklyTelegramMessageRendererTest.java
  - backend/src/test/java/com/storeanalytics/notification/fanout/DailyTelegramMessageSanitizationTest.java
runtime_evidence: []
required_reviewers:
  - ai-semantic
  - security-privacy
  - operations
review_triggers:
  - telegram-webhook-change
  - telegram-linking-change
  - notification-publication-change
  - notification-retention-change
supersedes: []
superseded_by: null
---

# Telegram notifications

## Назначение и границы

Telegram-контур связывает manager account с private chat, принимает защищённый webhook, создаёт
fanout deliveries и отправляет weekly legacy reports, service messages и daily store pulse.
Фактические production flags не копируются сюда; они принадлежат
[`project-state.md`](../project-state.md).

## Linking

Пользователь получает одноразовый link token через authenticated API, передаёт его боту командой
`/start`, а затем подтверждает pending subscription в приложении с ETag/If-Match.

Реализованные защиты:

- token хранится как hash и имеет ограниченный срок/число применений;
- принимаются только private chats и сообщения не от bot user;
- Telegram destination блокируется транзакционно и не может принадлежать другому app user;
- `update_id` идемпотентен;
- lifecycle `my_chat_member` переводит подписку при block/unblock;
- audit не сохраняет исходную `/start` command или link token.

## Webhook ingress

Ingress доступен только при разрешённой конфигурации, проверяет exact bot code и заголовок
`X-Telegram-Bot-Api-Secret-Token`. Current и previous secret сравниваются constant-time. Request
должен быть JSON и не превышать bounded body size.

Receipt сохраняет тип update, hash payload и outcome, но не полный command text. Повторный
`update_id` возвращает idempotent duplicate outcome.

## Weekly notifications

Weekly fanout принадлежит legacy LLM:

```text
legacy llm_interpretation
        ↓
WEEKLY_REPORT_READY / WEEKLY_REPORT_REVISED event
        ↓
schema1–3 Telegram renderer
        ↓
notification_delivery
        ↓
Telegram Bot API
```

V25/schema4 enrichment не создаёт weekly event, а renderer не поддерживает schema4. Прямого
bridge нет.

## Daily store pulse

Daily pulse — отдельный детерминированный контур, не использующий LLM:

```text
sync coverage → deterministic KPI projection → DAILY_STORE_PULSE event
→ daily fanout → notification_delivery → Telegram
```

Planner выбирает вчерашний business date в timezone магазина, работает только в send window и
требует coverage по `SALES`, `RETURNS` и `ORDERS`. Deduplication key включает store, business date
и policy version.

Текущий coverage gate принимает sync runs `SUCCESS` и `PARTIAL_SUCCESS`, затем проверяет лишь
максимальный `period_end`. Он не доказывает gap-free покрытие внутри периода. Поэтому наличие event
не следует трактовать как доказательство полной синхронизации без дополнительного invariant.

## Fanout и delivery

Fanout выбирает активные manager subscriptions с доступом к магазину, учитывает quiet hours,
создаёт idempotent deliveries и receipt. Delivery worker использует lease, retry/backoff,
expiration и terminal outcomes. Manual resend создаёт отдельную delivery с обязательным
idempotency key и audit trail.

Если явной notification preference нет, SQL использует `COALESCE(preference.enabled, true)`. То
есть weekly и daily события считаются разрешёнными по умолчанию для подходящей активной подписки.
Это реализованное поведение, но его согласие/privacy semantics ещё нужно утвердить.

## Schema4 poison-event gap

Weekly renderer выбрасывает исключение для schema, отличной от 1–3. Receipt при этом не создаётся.
Общий worker сначала обрабатывает weekly event и только при пустой weekly queue переходит к daily;
исключение перехватывается снаружи. Поэтому неподдерживаемый weekly event может снова выбираться и
задерживать последующие weekly и daily события. Требуются schema filter/dead-letter isolation и
poison-event integration test до schema4 bridge.

## Данные и retention

Контур хранит app user/subscription linkage, Telegram user/chat IDs, preferences, webhook receipts,
notification events, rendered text, delivery attempts и provider response metadata. Явной полной
retention/deletion policy для этих таблиц в текущем коде не найдено. Revoke прекращает активную
подписку, но не является доказательством удаления всей истории.

## Расхождения и открытые решения

1. Нет schema4 weekly publication bridge.
2. Нет poison-event isolation для неподдерживаемой weekly schema.
3. `PARTIAL_SUCCESS` daily coverage не доказывает отсутствие gaps.
4. Default-enabled preference требует явного product/privacy решения.
5. Нужна retention/delete policy для identifiers, receipts, rendered text и deliveries.
6. Production secret rotation требует подтверждения, что current/previous secret реально доступны
   runtime; кодовая поддержка сама по себе это не доказывает.

## Проверка

Security integration tests проверяют secret/content-type/body limits. Linking integration tests —
token lifecycle, ownership и ETag. Fanout/delivery tests — idempotency, quiet hours, retries и
manual resend. Daily tests проверяют обычный coverage path, но нужен отдельный gap-in-period test.

## Триггеры пересмотра

Изменение webhook auth, linking, preferences, event schemas, renderer, fanout ordering, daily
coverage, delivery retention или Bot API client обновляет документ.
