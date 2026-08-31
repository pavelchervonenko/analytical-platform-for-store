---
doc_schema: 1
doc_type: evidence
status: historical
owner: ai
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved legacy evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/current/ai/telegram.md"
original_content_sha256: da11cbce746472bd3d0a5442cf71a5c8dc5910195aec08b040ff0869e1c8fa59
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/ai/telegram.md`.

# Telegram staging acceptance

Статус: автоматический preflight и безопасная процедура ротации подготовлены. Реальный прогон
возможен только после появления отдельного staging-домена, staging-бота и HTTPS deployment.

## Подтверждённый локальный delivery — 2026-08-06

Без домена полный webhook acceptance не выполнялся. Для локальной проверки использован polling
bridge, который передавал update в тот же backend webhook-контур, а отправка шла через официальный
Telegram Bot API.

Контролируемое business event прошло `fanout → delivery`: receipt получил
`DELIVERIES_CREATED`, создана одна delivery, итоговый статус `SENT`, `attempt_count=1`, provider
error отсутствует. Временный store access удалён, роль связанного пользователя восстановлена в
`ADMIN`; рабочая схема прав production не ослаблялась. Событие и terminal receipts оставлены только
в disposable integration-БД как evidence прогона. Эта проверка подтверждает Bot API delivery, но
не заменяет TLS/webhook, reverse-proxy, fault-injection и alert-route staging acceptance ниже.

## MANAGER canary с interpretation revision 7 — 2026-08-07

Повторный контролируемый прогон использовал опубликованную interpretation
`367f6db8-7843-4906-9148-6e96955850fe` и актуальный phone-first renderer. Для уже подтверждённой
приватной Telegram-подписки связанный тестовый пользователь был временно переведён из `ADMIN` в
`MANAGER` и получил доступ только к магазину события. Отдельное событие
`4e63724e-0433-4bfc-b3ce-ae8382a5d650` прошло полный путь `fanout → delivery`:

- receipt: `DELIVERIES_CREATED`, `recipient_count=1`, `delivery_count=1`;
- delivery: `2ae83dfa-7232-4398-aa85-5475e0e23c77`;
- terminal status: `SENT`, `attempt_count=1`, provider message id `4`;
- provider error отсутствует, повторная delivery не создана;
- текст сформирован из той же immutable interpretation, которая показывается в кабинете.

После terminal commit временный store access удалён, роль пользователя восстановлена в `ADMIN`,
изолированный worker остановлен, основной backend возвращён в состояние `UP`. Canary подтверждает
MANAGER eligibility gate, новый renderer и реальный Bot API send, но не заменяет production
onboarding отдельного руководителя и публичный HTTPS webhook acceptance.

## Цель и граница

Acceptance подтверждает не только доступность Telegram API, но и полный пользовательский путь:

```text
dashboard link → /start webhook → pending confirmation → dashboard confirm
→ durable fanout/outbox → Bot API send → admin monitoring/alerts
```

Unit и PostgreSQL integration tests уже проверяют классификацию `429`, `403`, timeout,
`UNKNOWN_OUTCOME`, lease recovery и идемпотентность. На staging повторно проверяются сетевые,
TLS, reverse-proxy и secret-management границы. Нагрузкой на реальный Telegram искусственно
вызывать `429` запрещено.

## Предусловия

- отдельные staging PostgreSQL, домен и бот, не используемые production;
- валидный публичный TLS без обхода certificate verification;
- `TELEGRAM_NOTIFICATIONS_ENABLED=true`, `TELEGRAM_LINKING_ENABLED=true` и
  `TELEGRAM_WEBHOOK_ENABLED=true`;
- на первом этапе `TELEGRAM_FANOUT_ENABLED=false` и `TELEGRAM_DELIVERY_ENABLED=false`;
- Bot API token и webhook secret находятся в secret files с доступом только оператору;
- в admin UI нет необработанных старых Telegram-инцидентов.

## Автоматический preflight

Скрипт [telegram-staging-acceptance.sh](../../../../../scripts/telegram-staging-acceptance.sh):

- проверяет `getMe` и точное совпадение username;
- проверяет URL и `allowed_updates` через `getWebhookInfo`;
- проверяет `/readyz` и ожидаемый `401` на webhook с заведомо неверным secret;
- выводит только allowlisted сведения, но не provider description и не секреты;
- передаёт Bot API URL с токеном через временный curl config режима `0600`, а не через argv;
- по умолчанию ничего не изменяет.

Первичная установка webhook — единственная изменяющая команда:

```bash
APP_BASE_URL=https://staging.example.com \
TELEGRAM_BOT_USERNAME=store_analytics_staging_bot \
TELEGRAM_BOT_TOKEN_FILE=/run/secrets/telegram_bot_token \
TELEGRAM_WEBHOOK_SECRET_FILE=/run/secrets/telegram_webhook_secret \
CONFIRM_TELEGRAM_WEBHOOK_CHANGE=SET_STAGING_WEBHOOK \
scripts/telegram-staging-acceptance.sh configure
```

Повторная read-only проверка:

```bash
APP_BASE_URL=https://staging.example.com \
TELEGRAM_BOT_USERNAME=store_analytics_staging_bot \
TELEGRAM_BOT_TOKEN_FILE=/run/secrets/telegram_bot_token \
scripts/telegram-staging-acceptance.sh verify
```

Скрипт не должен запускаться с `set -x`, через команду, сохраняющую environment в CI artifacts,
или с секретами, записанными в shell history.

## Ручная acceptance-матрица

| Сценарий | Действие | Ожидаемый результат |
|---|---|---|
| Link | Руководитель выпускает ссылку и нажимает Start | Один `PENDING_CONFIRMATION`, одно service message, повторный update ничего не дублирует |
| Confirm | Руководитель подтверждает связь в кабинете | Subscription становится `ACTIVE`; устаревший ETag возвращает `412` |
| Delivery | Включить delivery, затем fanout и сформировать одно тестовое событие | Одна delivery проходит `PENDING → RUNNING → SENT`; текст совпадает с dashboard interpretation |
| Block | Получатель блокирует бота | `BOT_BLOCKED`, ожидающие delivery отменены, admin и alerting показывают событие |
| Unblock | Получатель разблокирует и нажимает Start | Подтверждённая связь возвращается в `ACTIVE`, новая связь не создаётся |
| Duplicate webhook | Повторить тот же `update_id` | HTTP `200`, но один inbox receipt и один бизнес-переход |
| Manual resend | Создать контролируемый terminal outcome | Только ADMIN, причина и duplicate-risk confirmation; исходная delivery неизменна |

Между включением `delivery` и `fanout` нужно дождаться как минимум двух успешных worker polls.
После каждого сценария сохраняются время, версия deployment, delivery reference, статус и снимок
метрик. Telegram chat ID, Bot API token, webhook secret и текст сообщения в evidence не входят.

## Fault injection

`429`, timeout и malformed success проверяются только через изолированный HTTPS Bot API stub:

1. staging worker останавливается;
2. `TELEGRAM_API_BASE_URL` переводится на контролируемый HTTPS stub;
3. stub по одному разу возвращает `429 + retry_after`, затем timeout после принятия request;
4. worker запускается, а оператор проверяет соответственно `WAITING_RETRY` и
   `UNKNOWN_OUTCOME` без автоматического повтора;
5. API base URL возвращается на `https://api.telegram.org`, credentials перезапускаются из secret
   storage, затем выполняется автоматический `verify`.

Stub не должен видеть production token или production chat ID. Запрещено создавать rate limit
массовой отправкой в официальный Telegram API.

## Ротация webhook secret без окна 401

Backend принимает `TELEGRAM_WEBHOOK_SECRET` и временный
`TELEGRAM_WEBHOOK_PREVIOUS_SECRET`. Оба проходят одинаковую проверку; previous secret не логируется
и не может совпадать с current.

1. Сгенерировать новый независимый secret.
2. Развернуть backend с `current=new`, `previous=old`.
3. Запустить `configure`, передав новый secret file; Telegram начнёт присылать новый заголовок.
4. Выполнить `verify` и дождаться нулевого либо стабильного `pending_update_count`.
5. Удалить `TELEGRAM_WEBHOOK_PREVIOUS_SECRET` и повторно развернуть backend.
6. Повторить `verify`. Старый secret должен давать `401`.

Previous secret — только краткоживущий migration bridge. Его нельзя оставлять до следующей
плановой ротации.

## Ротация Bot API token

1. Остановить fanout, дождаться отсутствия `RUNNING`, затем остановить delivery worker.
2. Выпустить новый token через официальный BotFather и атомарно обновить secret storage.
3. Перезапустить worker и выполнить `verify`; старый token отозвать, если BotFather не сделал это
   автоматически.
4. Включить delivery, отправить одно service/test message, затем включить fanout.

Неизвестный исход операции непосредственно перед остановкой остаётся `UNKNOWN_OUTCOME`; ротация
credentials не является основанием для автоматического resend.

## Go / no-go

Production enablement разрешён, только если:

- все строки acceptance-матрицы прошли на одном release candidate;
- alert route дошёл до разработчика и заказчика на тестовом инциденте;
- `UNKNOWN_OUTCOME` не повторился автоматически;
- предыдущий webhook secret удалён;
- backup/restore и rollback release candidate проверены;
- зафиксированы оператор, дата, commit SHA и ссылки на обезличенные evidence.
