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
original_content_sha256: c3b2a9f4bbdbfaf824a5980fc47906a3cc787ec17274a4876e217ea377e85736
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/ai/telegram.md`.

# Test contour rollout: Yandex AI and Telegram

Этот runbook используется для контура, который по конфигурации и данным близок к production.
Первый запуск выполняется fail-closed: миграции и чтение опубликованных данных проверяются до
включения платной генерации и внешних отправок.

## Release contract

Release candidate использует только следующую подтверждённую связку:

- prompt `weekly-interpretation-v4`;
- content schema `2`;
- snapshot calculation `weekly-snapshot-v4`;
- `LLM_MAX_OUTPUT_TOKENS=8000`;
- не более одного initial call и одного validation retry;
- явный model URI `gpt://<folder-id>/yandexgpt-5.1`; URI с `/latest` запрещён;
- weekly renderer `weekly-telegram-v1`;
- daily policy `daily-store-pulse-v1`, renderer `daily-store-pulse-v2`.

## Secrets

На хосте создаются отдельные файлы с mode `0600`; значения не помещаются в `.env`, Compose
environment, command line или CI artifacts:

- `POSTGRES_PASSWORD_FILE`;
- `LIVESKLAD_LOGIN_FILE`;
- `LIVESKLAD_PASSWORD_FILE`;
- `YANDEX_AI_API_KEY_FILE`;
- `TELEGRAM_BOT_TOKEN_FILE`;
- `TELEGRAM_WEBHOOK_SECRET_FILE`;
- `SECURITY_TELEMETRY_PSEUDONYM_KEY_FILE`.

Telegram webhook secret должен содержать 16–256 символов из `A-Z`, `a-z`, `0-9`, `_`, `-`.
Telemetry pseudonym key должен быть случайным, уникальным для окружения и не короче 32 символов.

## Первый запуск

1. Оставить выключенными generation, Telegram fanout/delivery и daily pulse.
2. Выполнить `docker compose --env-file <release-env> -f docker-compose.prod.yml config --quiet`.
3. Запустить миграции и backend, дождаться `UP` на `/actuator/health/readiness`.
4. Проверить schema version, вход администратора, магазин, sync status и состав сотрудников.
5. Убедиться, что у сотрудников корректно выставлен `participates_in_ranking`.
6. Проверить dashboard на сохранённых данных без внешних вызовов.

## Включение Yandex AI

Флаги включаются в таком порядке:

1. snapshot feature и worker;
2. snapshot planner;
3. generation feature и worker при выключенном generation planner;
4. publication;
5. один контролируемый manual generation;
6. после проверки стоимости, validation и публикации — generation planner.

Перед пунктом 5 ADMIN проверяет pinned model URI, prompt/schema versions и
`INTERPRETATION_GENERATION_MAX_ESTIMATED_COST_RUB`. После выполнения должны существовать одна
immutable interpretation и одно недельное notification event. Технические идентификаторы,
prompt и provider response не передаются заказчику или в Telegram.

## Включение Telegram

1. Установить публичный HTTPS webhook и выполнить `scripts/telegram-staging-acceptance.sh verify`.
2. Включить notifications, linking и webhook при выключенных fanout и daily pulse.
3. Включить delivery и дождаться двух успешных worker polls.
4. Создать постоянного пользователя `MANAGER`, выдать доступ только к нужному магазину.
5. MANAGER проходит dashboard link → Start → dashboard confirmation.
6. Создать одно контролируемое недельное событие, затем включить fanout.
7. Проверить одну delivery со статусом `SENT`, совпадение текста с dashboard и отсутствие дубля.
8. Только после этого включить daily pulse в безопасном временном окне.

`ADMIN` не является получателем бизнес-уведомлений. Не изменять audience ради теста: технические
и бизнес-роли должны оставаться разделёнными.

## Обязательные проверки до доступа заказчика

- неверный Yandex key останавливает generation и создаёт operator incident;
- Yandex timeout/429 не создаёт повторную публикацию;
- invalid model response проходит не более одного validation retry;
- Telegram duplicate webhook не создаёт повторный переход;
- blocked bot переводит delivery в terminal outcome и отменяет ожидающие отправки;
- ambiguous Telegram timeout остаётся `UNKNOWN_OUTCOME` без автоматического resend;
- quiet hours и expiry работают в timezone подписки;
- Prometheus rules загружены, тестовый alert дошёл до разработчика;
- backup и rollback release candidate проверены.

## Rollback

Сначала выключить daily pulse и fanout, затем generation planner. Не удалять события, attempts или
deliveries: они являются audit evidence. При `UNKNOWN_OUTCOME` запрещён автоматический resend.
Dashboard продолжает показывать последнюю опубликованную immutable interpretation или понятный
fallback без повторного provider call.
