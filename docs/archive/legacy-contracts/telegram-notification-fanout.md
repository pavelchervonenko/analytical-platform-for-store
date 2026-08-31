---
doc_schema: 1
doc_type: archive
status: archived
owner: ai
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/current/ai/telegram.md"
original_content_sha256: 288b73043ad82cebc73a6c9374d810d5bb9cd26e743fc83c7918d272a7520a87
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/telegram.md`.

# Telegram notification fanout

Статус на 2026-08-03: внутренняя проекция weekly notification event в durable Telegram delivery
реализована и проверена unit- и PostgreSQL integration-тестами. Безопасные link/confirmation/revoke
и Telegram webhook также реализованы; см. [telegram-linking-and-webhook.md](telegram-linking-and-webhook.md).
Typed Telegram Bot API adapter и delivery state machine также реализованы; см.
[telegram-delivery-worker.md](telegram-delivery-worker.md). Все feature flags по умолчанию
выключены.

## Назначение этапа

Публикация weekly-интерпретации и доставка сообщения разделены двумя транзакциями:

```text
llm_interpretation + immutable notification_event
                      │
                      ▼
NotificationEventFanoutWorker
  ├─ выбирает event через FOR UPDATE SKIP LOCKED
  ├─ проверяет SHA-256 опубликованной интерпретации
  ├─ разрешает получателей и preferences
  ├─ рассчитывает quiet hours
  ├─ фиксирует точный rendered_text и content_hash
  └─ создаёт terminal fanout receipt
                      │
                      ▼
notification_delivery (без внешнего HTTP-вызова)
```

Недоступность Telegram либо отсутствие подписки не влияют на publication и dashboard. В fanout-
транзакции нет внешнего I/O.

## Получатели

Для manager weekly event delivery создаётся только когда одновременно выполняются условия:

- `app_user` активен и имеет роль `MANAGER`;
- сохранён текущий `user_store_access` к магазину события;
- Telegram subscription имеет статус `ACTIVE` и совпадающий `bot_code`;
- preference для `store/channel/event_type` отсутствует или имеет `enabled=true`.

Отсутствующая preference означает versioned default `enabled`. Явный `enabled=false` исключает
получателя. Перед фактической отправкой delivery worker должен повторить проверки user access и
subscription state, потому что они могли измениться после fanout.

## Идемпотентность и crash recovery

V26 добавляет immutable `notification_event_fanout_receipts`. Receipt имеет один из результатов:

- `DELIVERIES_CREATED`;
- `NO_RECIPIENTS`;
- `EVENT_EXPIRED`.

Event, для которого receipt уже существует, больше не claim-ится. Receipt и все deliveries
фиксируются одной короткой транзакцией. Падение до commit не оставляет частичный terminal результат,
а повторный worker безопасно обработает event заново. Unique
`(event_id, channel, subscription_id)` дополнительно исключает дубль на одного получателя.

Отсутствие получателей является успешным terminal outcome, а не ошибкой и не бесконечным backlog.
Просроченный event закрывается без чтения/рендеринга пользовательского текста.

## Рендеринг weekly-сообщения

Renderer `weekly-telegram-v1` использует только опубликованный, повторно проверенный по SHA-256
content и backend-owned metadata. В сообщение входят:

- тип отчёта, магазин и период;
- headline, результат и динамика;
- продажи по категориям и дополнительные продажи;
- outlook по плану и главный риск;
- командный вывод;
- компактная строка по каждому сотруднику с именем из immutable snapshot membership;
- первый фокус недели и ссылка-подсказка на кабинет без access token.

Renderer не просит LLM повторно сформировать Telegram-текст. Поэтому dashboard и Telegram показывают
одну опубликованную интерпретацию, а channel formatting остаётся задачей backend. Exact text и его
SHA-256 сохраняются до enqueue; смена renderer не изменяет уже ожидающие deliveries.

Сообщение ограничено 4096 Unicode code points и отправляется как одна атомарная delivery. Длинные
поля ограничиваются детерминированно; полный отчёт остаётся в кабинете. Telegram HTML/Markdown пока
не используется, поэтому LLM-текст не становится markup-инъекцией.

## Тихие часы и срок актуальности

Расчёт выполняется в timezone подписки и поддерживает интервалы, пересекающие полночь. Weekly event
имеет TTL 24 часа. Если ближайшее разрешённое время наступает после `expires_at`, delivery сразу
фиксируется как `EXPIRED`; устаревшее сообщение не отправляется позднее.

## Конфигурация

```text
TELEGRAM_NOTIFICATIONS_ENABLED=false
TELEGRAM_FANOUT_ENABLED=false
TELEGRAM_BOT_CODE=store-analytics-primary
TELEGRAM_FANOUT_DELAY=5s
TELEGRAM_DELIVERY_MAX_ATTEMPTS=5
TELEGRAM_WEEKLY_RENDER_VERSION=weekly-telegram-v1
```

Worker создаётся только для application role `WORKER`/`COMBINED` и только когда оба флага
`TELEGRAM_NOTIFICATIONS_ENABLED` и `TELEGRAM_FANOUT_ENABLED` равны `true`. Scheduler выделен в
отдельный single-thread bulkhead. На текущем этапе флаги не включать.

## Метрики и проверка

Counter `storeanalytics.notification.fanout.total{outcome=...}` считает terminal fanout outcomes,
не включая текст, Telegram IDs и другие персональные данные.

Покрыты:

- перенос через ночные quiet hours и expiry до конца quiet hours;
- ready/revised headings и bounded rendering;
- `NO_RECIPIENTS`, `EVENT_EXPIRED` и создание delivery;
- реальная Flyway V26/PostgreSQL 16 транзакция и отсутствие дубля при повторном вызове.

## Следующий этап

1. Добавить durable подтверждение привязки в чате и UI кабинета.
2. Добавить operator alerts/admin actions и audited ручной resend для terminal deliveries.
3. Провести staging Bot API/webhook и fault-injection acceptance tests.
4. Только после этого отдельно включать linking, webhook, fanout и delivery флаги в staging.
