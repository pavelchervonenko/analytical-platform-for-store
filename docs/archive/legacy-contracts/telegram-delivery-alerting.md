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
original_content_sha256: 3498863eb25a45841b71e58c1b1d2a515db597009bc109b5310d253cfa559231
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/telegram.md`.

# Telegram Delivery Alerting

Статус: durable backend gauges, Prometheus rules и административный read-only экран реализованы
2026-08-03. Production Prometheus, Alertmanager и конкретный адрес доставки настраиваются при
деплое; credentials и recipients не хранятся в репозитории.

## Граница ответственности

Технические алерты принадлежат разработчику и ответственному представителю заказчика. Они не идут
через тот же Telegram delivery worker, который контролируют. Иначе ошибка Bot API, токена или
очереди могла бы одновременно скрыть уведомление о самой себе.

Backend выполняет только три действия:

- сохраняет delivery/attempt state в PostgreSQL;
- раз в минуту обновляет in-memory snapshot низкокардинальных Micrometer gauges;
- публикует безопасные event counters и структурированные логи.

Prometheus scrape читает только cached snapshot и никогда не запускает SQL. Alertmanager отвечает
за группировку, подавление повторов и доставку во внешний технический канал.

## Метрики

`storeanalytics.notification.delivery.state{channel="TELEGRAM",status=...}` содержит:

- `ready_pending` — готовые к claim новые delivery;
- `ready_retry` — готовые повторные попытки;
- `authentication_retry` — delivery, ожидающие повтора после ошибки Bot API credentials;
- `running` — активные leases;
- `expired_lease` — просроченные активные leases;
- `permanent_failed` — terminal однозначные отказы;
- `unknown_outcome` — неоднозначный результат provider call;
- `blocked_subscription` — пользователи, заблокировавшие бота.

Значения равны `NaN` до первого успешного refresh. При временной ошибке PostgreSQL сохраняется
последний успешный snapshot, а backend пишет stack trace без Telegram ID и текста сообщения.

## Готовые правила

[telegram-delivery-alerts.yml](../../../monitoring/prometheus/telegram-delivery-alerts.yml) содержит:

- critical при отсутствии worker gauges дольше пяти минут;
- critical для `UNKNOWN_OUTCOME`, expired lease и authentication failure;
- warning для permanent failure и `BOT_BLOCKED` subscription;
- warning для pending backlog дольше десяти минут;
- warning для retry backlog дольше тридцати минут;
- warning при открытии authentication circuit breaker.

Все series используют только фиксированные labels `channel`, `status`, `outcome`, `application` и
`role`. Delivery, store, user, chat, event и correlation ID никогда не становятся metric labels.

## Production rollout

1. Подключить rules-файл к production Prometheus.
2. Проверить файл командой `promtool check rules` той же версии, что используется в production.
3. Настроить Alertmanager route `owner=developer` в отдельный email, инфраструктурный Telegram-чат
   или другой независимый on-call канал.
4. Хранить SMTP/webhook/token credentials только в customer-owned deployment secrets.
5. На staging искусственно остановить worker и проверить `MetricsMissing` от scrape до получения.
6. Отдельно создать контролируемый invalid-token сценарий и проверить authentication alert без
   попадания токена или URI в alert/log.
7. Создать timeout fault после начала provider attempt и убедиться, что `UNKNOWN_OUTCOME` не
   повторяется автоматически.
8. После восстановления проверить погашение transient alerts; terminal delivery не удалять ради
   зеленого dashboard.

Пороговые интервалы калибруются после месяца эксплуатации. Снижение порога не должно создавать
alert fatigue: terminal ambiguity и credentials остаются critical независимо от объема магазина.
