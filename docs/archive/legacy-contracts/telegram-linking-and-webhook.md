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
original_content_sha256: 65d767dbbdbfd9499ac0981a8b0f5380753f53300dce820dcd7b6d21ca2266f6
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/telegram.md`.

# Telegram linking and webhook

Статус на 2026-08-03: безопасная привязка Telegram-аккаунта к пользователю кабинета и приём
Telegram webhook реализованы. Пользовательский UI привязки в профиле, typed Bot API adapter и
фактический delivery worker также реализованы;
см. [telegram-delivery-worker.md](telegram-delivery-worker.md). Все Telegram feature flags по
умолчанию выключены.

## Граница этапа

Реализованный поток:

```text
авторизованный руководитель
  │ POST /api/notifications/channels/telegram/link
  ▼
одноразовая deep link https://t.me/<bot>?start=<token>
  │ пользователь нажимает Start в личном чате с ботом
  ▼
Telegram POST /api/integrations/telegram/<botCode>/webhook
  │ token валиден и Telegram destination свободен
  ▼
PENDING_CONFIRMATION
  │ руководитель подтверждает в кабинете с If-Match
  ▼
ACTIVE
```

Нажатия `Start` недостаточно для активации доставки: пользователь должен вернуться в защищённый
кабинет и подтвердить найденный Telegram-аккаунт. Так перехваченная deep link не даёт возможности
незаметно перенаправить уведомления на чужой chat ID.

## Dashboard API

Все маршруты требуют действующую browser session, завершённую смену временного пароля и обычную
CSRF-защиту.

| Метод | Маршрут | Назначение |
|---|---|---|
| `GET` | `/api/notifications/channels/telegram` | Текущее состояние и допустимые действия |
| `POST` | `/api/notifications/channels/telegram/link` | Создать одноразовую deep link; ответ `201` |
| `POST` | `/api/notifications/channels/telegram/confirm` | Активировать pending subscription |
| `POST` | `/api/notifications/channels/telegram/revoke` | Отозвать subscription и отменить ожидающие доставки |
| `PUT` | `/api/notifications/channels/telegram/settings` | Атомарно заменить timezone и quiet hours активной подписки |

`GET`, `confirm` и `revoke` возвращают `Cache-Control: no-store`. Для изменяемого subscription они
также возвращают strong `ETag`; `confirm` и `revoke` требуют его в `If-Match`. Отсутствующий
`If-Match` даёт `428`, устаревший — `412`.

`settings` также возвращает `Cache-Control: no-store`, требует strong `ETag` в `If-Match`
и изменяет только `ACTIVE` subscription. Идентичный `PUT` не создаёт новую версию и audit event.

Состояния клиентской модели: `NOT_LINKED`, `LINK_ISSUED`, `PENDING_CONFIRMATION`, `ACTIVE`,
`BOT_BLOCKED`. Backend также возвращает allowlist `allowedActions`; frontend не должен выводить
действия, отсутствующие в этом списке.

## Dashboard UI

Карточка «Уведомления в Telegram» находится в профиле. Она создаёт одноразовую deep link,
показывает только маскированный destination, требует явного подтверждения pending-связи и
позволяет отозвать её даже после отключения feature flags. Deep-link token существует только
в памяти вкладки после ответа `link`: после перезагрузки получить plaintext повторно нельзя,
пользователь выпускает новую ссылку после истечения текущей.

Для активной связи руководитель выбирает поддерживаемый российский IANA timezone
и настраивает тихие часы с точностью до минуты.

Клиент опрашивает состояние каждые 2 секунды для `LINK_ISSUED`, каждые 3 секунды для
`PENDING_CONFIRMATION` и каждые 10 секунд для `BOT_BLOCKED`; для terminal/unknown состояний
polling остановлен. Mutations отправляют opaque strong ETag без разбора. На `412` ресурс
перечитывается, а опасное действие не повторяется автоматически.

## Одноразовый токен

- токен содержит 192 бита криптографической случайности и помещается в Telegram `start` parameter;
- PostgreSQL хранит только SHA-256 токена, plaintext существует лишь в одном ответе `link`;
- срок действия по умолчанию — 10 минут;
- токен используется один раз и атомарно связывается с pending subscription;
- повторная выдача отзывает прежний неиспользованный токен;
- ограничение выдачи: не чаще одного раза в 30 секунд и не более пяти раз в час на пользователя;
- Telegram user/chat не может одновременно принадлежать другому active, pending или blocked пользователю.

Формат соответствует официальному механизму bot deep links: параметр `start` ограничен 64
символами и безопасным URL-набором. См. [Telegram deep links](https://core.telegram.org/bots/features#deep-linking).

## Webhook boundary

Внешний маршрут:

```text
POST /api/integrations/telegram/{botCode}/webhook
X-Telegram-Bot-Api-Secret-Token: <secret>
Content-Type: application/json
```

Это отдельная stateless security chain: без browser session, cookies, CSRF, CORS и request cache.
До JSON-десериализации backend проверяет:

- глобальный Telegram flag и webhook flag;
- точное совпадение `botCode`;
- секретный заголовок constant-time сравнением;
- совместимый `application/json` content type;
- объявленный и фактически прочитанный размер body, максимум 64 KiB по умолчанию.

Выключенный webhook или неизвестный `botCode` получает `404`, неверный секрет — `401`, неверный
content type — `415`, слишком большой body — `413`.

Каждый `update_id` фиксируется в `telegram_update_receipts` с unique `(bot_code, update_id)`.
Telegram повторяет webhook при неуспешном HTTP-ответе, поэтому duplicate update возвращает `200`,
но не повторяет бизнес-переход. Поддержан `/start <token>` только от человека в private chat.
Успешный `/start` в той же транзакции создаёт pending subscription и одну durable delivery типа
`LINK_CONFIRMATION`. У неё нет `notification_event`: это lifecycle-сообщение, а не новость магазина.
Exact text и SHA-256 фиксируются в outbox до вызова Telegram. Частичный unique index не допускает
двух таких сообщений для одной subscription, а duplicate webhook отсекается inbox receipt. Worker
отправляет сообщение только пока subscription остаётся `PENDING_CONFIRMATION`; подтверждение в
кабинете, отзыв или блокировка отменяют ещё не отправленную delivery; истечение общего `expires_at`
завершает её как `EXPIRED` без provider attempt.


Private `my_chat_member` обрабатывает lifecycle уже созданной связи: `kicked`/`left` переводит
`ACTIVE` в `BOT_BLOCKED`, отменяет ожидающие deliveries и просит остановить `RUNNING`; `member`
возвращает ранее подтверждённый `BOT_BLOCKED` в `ACTIVE`. Pending-связь событием `member` не
подтверждается, а `REVOKED` не восстанавливается. Для защиты от запоздавших updates subscription
хранит последний membership `update_id` и время его получения. Меньший ID в течение семи дней
игнорируется; после недельной паузы допускается документированный Telegram reset последовательности.
Остальные message сохраняются как проигнорированные. Официальные поля и семантика webhook описаны в
[Telegram Bot API](https://core.telegram.org/bots/api#setwebhook).

## Конфигурация

```text
TELEGRAM_NOTIFICATIONS_ENABLED=false
TELEGRAM_LINKING_ENABLED=false
TELEGRAM_WEBHOOK_ENABLED=false
TELEGRAM_BOT_CODE=store-analytics-primary
TELEGRAM_BOT_USERNAME=
TELEGRAM_LINK_TOKEN_TTL=10m
TELEGRAM_PENDING_CONFIRMATION_TTL=10m
TELEGRAM_LINK_ISSUE_MIN_INTERVAL=30s
TELEGRAM_LINK_MAX_PER_HOUR=5
TELEGRAM_WEBHOOK_SECRET_FILE=/secure/path/telegram-webhook-secret
TELEGRAM_BOT_TOKEN_FILE=/secure/path/telegram-bot-token
# Direct value is allowed only for an explicitly controlled local process.
TELEGRAM_WEBHOOK_SECRET=
TELEGRAM_WEBHOOK_PREVIOUS_SECRET=
TELEGRAM_WEBHOOK_MAX_BODY_BYTES=65536
```

`TELEGRAM_WEBHOOK_SECRET` — отдельный случайный секрет длиной минимум 16 символов из
`A-Z a-z 0-9 _ -`; это не BotFather token. Рекомендуется не менее 32 случайных байт в base64url.
Production Compose читает webhook secret и Bot API token только из указанных secret files.
`TELEGRAM_WEBHOOK_PREVIOUS_SECRET` задаётся только на короткое окно безопасной ротации: backend
принимает current и previous, но запрещает их совпадение. После переключения Telegram previous
обязательно удаляется. Секреты и Bot API token должны находиться в secret storage, а не в Git.

## Production setup

1. Создать бота через официальный `@BotFather`, сохранить username и Bot API token в аккаунте
   инфраструктуры заказчика.
2. Развернуть backend за публичным HTTPS-доменом; не открывать приложение напрямую по IP.
3. Сгенерировать независимый webhook secret и подключить Telegram secret files.
4. Сначала включить `TELEGRAM_NOTIFICATIONS_ENABLED`, `TELEGRAM_LINKING_ENABLED` и
   `TELEGRAM_WEBHOOK_ENABLED`, оставив fanout/delivery выключенными.
5. Установить webhook и проверить `getMe`, `getWebhookInfo`, readiness и authentication boundary
   скриптом `scripts/telegram-staging-acceptance.sh`.
6. Пройти ручную acceptance-матрицу и безопасную ротацию credentials из
   [telegram-staging-acceptance.md](../../history/canaries/2026/08/telegram-staging-acceptance.md).
7. После staging-проверки отдельно включать fanout и фактическую доставку.

Telegram принимает `secret_token` и присылает его в заголовке
`X-Telegram-Bot-Api-Secret-Token`; этот заголовок является обязательной частью текущего boundary.
Не логировать URL с BotFather token, webhook secret, deep-link token или необработанный update body.

## Аудит и отзыв

В общий неизменяемый security audit записываются `TELEGRAM_LINK_ISSUED`,
`TELEGRAM_LINK_PENDING`, `TELEGRAM_LINK_CONFIRMED`, `TELEGRAM_LINK_REVOKED`,
`TELEGRAM_BOT_BLOCKED` и `TELEGRAM_BOT_UNBLOCKED`. Применяется единая настраиваемая retention
policy security-аудита.
Изменения timezone и quiet hours записываются как `TELEGRAM_DELIVERY_SETTINGS_CHANGED`
с безопасными before/after без chat ID и токенов.

При отзыве subscription:

- `PENDING` и `WAITING_RETRY` deliveries становятся `CANCELLED`;
- у `RUNNING` delivery выставляется `cancel_requested`, который обязан проверить sender перед
  внешним вызовом;
- открытые link tokens отзываются.

## Проверки и оставшиеся задачи

PostgreSQL integration tests покрывают полный lifecycle, хранение только hash токена, запрет
подтверждения другим пользователем, обязательный ETag, duplicate/out-of-order и недельный reset
`update_id`, block/unblock, неверный secret/content type, body limit и отсутствие browser
session/CSRF на webhook boundary. Дополнительно проверены единственность durable link confirmation,
отсутствие business event и отмена ожидающего сообщения после dashboard-confirmation.

До production-отправки остаются:

1. развернуть отдельные staging PostgreSQL, HTTPS-домен и staging-бота;
2. пройти и зафиксировать [staging acceptance](../../history/canaries/2026/08/telegram-staging-acceptance.md) на release candidate.
