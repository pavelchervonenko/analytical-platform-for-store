# Telegram delivery worker

Статус на 2026-08-03: durable delivery state machine и typed `sendMessage` adapter реализованы и
проверены unit- и PostgreSQL integration-тестами. Реальные запросы в Telegram при разработке и
тестах не выполнялись. `TELEGRAM_DELIVERY_ENABLED` по умолчанию равен `false`.

## Транзакционная граница

```text
PENDING / WAITING_RETRY
  │ короткая transaction: FOR UPDATE SKIP LOCKED + lease
  ▼
RUNNING
  │ отдельная transaction: повторная eligibility/hash проверка + STARTED attempt
  ▼
HTTP sendMessage вне DB transaction
  │
  ├─ однозначный success ───────────────► SENT
  ├─ 429 / 5xx ─────────────────────────► WAITING_RETRY
  ├─ 403 ───────────────────────────────► PERMANENT_FAILED + BOT_BLOCKED
  ├─ другой однозначный reject ─────────► PERMANENT_FAILED
  └─ timeout / transport ambiguity ─────► UNKNOWN_OUTCOME
```

Attempt `STARTED` фиксируется commit-ом до внешнего вызова. Результат фиксируется отдельным
commit-ом только владельцем актуального lease. Внешний HTTP никогда не выполняется внутри
PostgreSQL-транзакции.

## Crash recovery и защита от дублей

- Падение после claim, но до создания attempt: истёкший lease возвращает delivery в
  `WAITING_RETRY`.
- Падение после commit `STARTED`: истёкший lease переводит attempt и delivery в
  `UNKNOWN_OUTCOME`.
- `UNKNOWN_OUTCOME` никогда не выбирается автоматическим worker повторно.
- Успешный result требует `ok=true` и integral `result.message_id`; message ID сохраняется вместе с
  `SENT` attempt/delivery одной транзакцией.
- Lease duration обязана быть больше HTTP read timeout; startup validation контролирует это.

Telegram Bot API не предоставляет idempotency key для `sendMessage`, поэтому эта модель сознательно
не обещает exactly-once side effect. При неоднозначном исходе безопаснее возможная недоставка и
ручное решение оператора, чем автоматический дубль.

## Проверки перед каждым send

Worker повторно проверяет не только данные момента fanout:

- delivery ещё `RUNNING`, lease не истёк и принадлежит текущему worker;
- нет `cancel_requested`, не истёк `expires_at`, не исчерпаны attempts;
- SHA-256 сохранённого `rendered_text` совпадает с `content_hash`;
- app user активен и совпадает с владельцем subscription;
- `NOTIFICATION` delivery имеет `event_id`, а subscription всё ещё `ACTIVE`; затем проверяются
  audience, роль и актуальный store access;
- `LINK_CONFIRMATION` delivery не имеет `event_id`, уникальна для subscription и допустима только
  пока subscription остаётся `PENDING_CONFIRMATION`;
- подтверждение в кабинете атомарно отменяет ожидающую `LINK_CONFIRMATION`, поэтому запоздавший
  worker не отправляет устаревшую подсказку;
- manager всё ещё имеет роль `MANAGER` и доступ к магазину события;
- для будущего operator event получатель имеет роль `ADMIN`.

Отзыв прав, subscription или уведомления до начала вызова завершает delivery как `CANCELLED` без
создания provider attempt. Просроченный текст становится `EXPIRED`.

## Bot API adapter

Adapter отправляет один plain-text `sendMessage`:

- `chat_id` берётся только из subscription, прошедшей type-specific eligibility перед send;
- `text` — точная сохранённая строка, без повторного LLM-вызова;
- `protect_content=true`;
- `link_preview_options.is_disabled=true`;
- `allow_paid_broadcast=false`;
- `parse_mode` не задаётся, поэтому сохранённый LLM-текст не интерпретируется как HTML/Markdown.

Ответ читается bounded stream-ом (64 KiB по умолчанию). Provider response body, `description`, Bot
token, request URI и Telegram IDs не логируются и не сохраняются. Сохраняются только safe category,
HTTP status, `retry_after`, latency и provider message ID при успехе.

Контракт `sendMessage`, `ResponseParameters.retry_after` и структура ответа основаны на
[официальном Telegram Bot API](https://core.telegram.org/bots/api#sendmessage).

## Классификация исходов

| Provider outcome | Delivery result | Автоповтор |
|---|---|---|
| `ok=true` + `message_id` | `SENT` | нет |
| `429`, optional `retry_after` | `WAITING_RETRY` | да, bounded |
| `5xx` с полученным ответом | `WAITING_RETRY` | да, bounded |
| `401` / неверный Bot token | `WAITING_RETRY` + auth circuit open | после cooldown |
| `403` | `PERMANENT_FAILED`, subscription `BOT_BLOCKED` | нет |
| другой однозначный `4xx` | `PERMANENT_FAILED` | нет |
| read timeout, transport loss, malformed success | `UNKNOWN_OUTCOME` | нет |

Retry использует exponential backoff с детерминированным jitter; официальный `retry_after` имеет
приоритет, если он позже. Попытка не планируется после `expires_at` и не превышает `max_attempts`.

Authentication failure открывает локальный circuit breaker на максимальный retry interval. Это
предотвращает быстрое расходование attempts остальной очереди в текущем worker-процессе. Delivery
остаётся retryable, subscriptions не блокируются и не отзываются. При нескольких worker replicas
нужен общий distributed circuit state до горизонтального масштабирования этого контура.

## Конфигурация

```text
TELEGRAM_NOTIFICATIONS_ENABLED=false
TELEGRAM_DELIVERY_ENABLED=false
TELEGRAM_BOT_TOKEN=
TELEGRAM_API_BASE_URL=https://api.telegram.org
TELEGRAM_CONNECT_TIMEOUT=5s
TELEGRAM_READ_TIMEOUT=15s
TELEGRAM_DELIVERY_DELAY=5s
TELEGRAM_DELIVERY_LEASE_DURATION=1m
TELEGRAM_DELIVERY_RETRY_INITIAL_DELAY=15s
TELEGRAM_DELIVERY_RETRY_MAX_DELAY=5m
TELEGRAM_DELIVERY_MAX_ATTEMPTS=5
TELEGRAM_MAX_RESPONSE_BYTES=65536
```

При `delivery-enabled=true` startup требует глобальный Telegram flag, HTTPS API base URL и BotFather
token допустимого формата. `toString()` typed properties всегда редактирует Bot token и webhook
secret. Token передаётся только через secret storage/environment и не коммитится в Git.

Worker создаётся только для application role `WORKER`/`COMBINED`, когда одновременно включены
`enabled` и `delivery-enabled`. У него отдельный single-thread scheduler. При интервале 5 секунд
исходный масштаб существенно ниже Telegram rate limits; перед переходом к batch/parallel sending
обязателен отдельный global/per-chat limiter.

## Наблюдаемость и проверка

- `storeanalytics.notification.delivery.total{channel="TELEGRAM",outcome=...}`;
- `storeanalytics.notification.delivery.latency{channel="TELEGRAM"}`.

Метрики не содержат business text, chat/user ID или secrets. Тесты покрывают request contract,
success, `429 + retry_after`, `403`, read timeout, committed attempt, success transition, retry,
отзыв store access до вызова и lease recovery в `UNKNOWN_OUTCOME`.


## Административный обзор доставки

Read-only endpoint `GET /api/admin/notifications/telegram/deliveries?incidentLimit=50`
доступен только роли `ADMIN`. Он строит backend-проекцию непосредственно из durable outbox:

- готовые `PENDING` и `WAITING_RETRY`, активные и просроченные `RUNNING`;
- все `PERMANENT_FAILED` и `UNKNOWN_OUTCOME`;
- число `ACTIVE` и `BOT_BLOCKED` subscriptions;
- время самой старой готовой delivery;
- приоритетный bounded-список инцидентов.

`CRITICAL` означает наличие `UNKNOWN_OUTCOME` или просроченного lease. `WARNING` означает
готовый backlog, permanent failure или заблокированную subscription. Это операционные правила
доставки, а не бизнес-правила LLM.

Ответ не содержит Telegram chat/user ID, `rendered_text`, content hash, provider message ID,
payload события или secret. Показываются только имя получателя, магазин, тип события, status,
счётчики attempts, безопасные error code/summary и timestamps.

В admin UI раздел «Telegram» обновляется раз в 60 секунд при штатной работе и раз в 15 секунд,
если требуется внимание. Для допустимых терминальных бизнес-доставок доступна отдельная
кнопка ручного повтора.

### Audited ручной повтор

`POST /api/admin/notifications/telegram/deliveries/{deliveryId}/resend` доступен только `ADMIN` и
требует `Idempotency-Key`. Backend допускает команду только при выполнении всех условий:

- исходная delivery имеет kind `NOTIFICATION`;
- status равен `PERMANENT_FAILED` или `UNKNOWN_OUTCOME`;
- срок `expires_at` ещё не наступил;
- body содержит reason длиной 10–500 символов и `acknowledgeDuplicateRisk=true`;
- у исходной delivery нет активного или уже успешного ручного повтора.

Команда не меняет исходную запись и не запускает LLM повторно. Она атомарно копирует получателя,
subscription, event, готовый текст, markup, content hash, TTL и лимит попыток в новую `PENDING`
delivery. Любой иной набор данных отклоняет database trigger. Причина, actor и связь с исходной
delivery сохраняются в БД и в immutable audit log без Telegram ID и текста сообщения.

## До production enablement

1. Административный обзор и независимые Prometheus rules для authentication failure, backlog и
   `UNKNOWN_OUTCOME` реализованы; при деплое подключить production Prometheus/Alertmanager route
   по [runbook](telegram-delivery-alerting.md).
2. Audited ручной resend с явным подтверждением риска дубля реализован; проверить его в staging.
3. Выполнить [staging acceptance](telegram-staging-acceptance.md) с отдельным ботом: реальный
   webhook, send, block/unblock и credential rotation. `429` и timeout проверять через
   изолированный HTTPS stub, а не нагрузкой на официальный Telegram API.
4. Только после этого включать `TELEGRAM_DELIVERY_ENABLED` в production.
