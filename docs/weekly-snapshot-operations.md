# Weekly Snapshot Operations

Статус: heartbeat, recovery истёкших lease, cooperative cancellation, coordinator и operational
metrics, operator signals и alert rules реализованы и проверены на PostgreSQL 16, 2026-08-02.
Scheduled worker и planner реализованы, но выключены по умолчанию. Детали:
[weekly-snapshot-worker.md](weekly-snapshot-worker.md),
[weekly-snapshot-planner.md](weekly-snapshot-planner.md) и
[weekly-snapshot-alerting.md](weekly-snapshot-alerting.md).

## Lifecycle contract

`WeeklySnapshotJobLifecycleStore` отвечает только за эксплуатационные переходы очереди:

- heartbeat продлевает только живой `RUNNING` lease и только для его текущего owner;
- истёкший lease выбирается через `FOR UPDATE SKIP LOCKED`;
- recovery возвращает job в `WAITING_RETRY`, а при исчерпании attempts переводит в `FAILED`;
- отменённый job при recovery переходит в `CANCELLED`, а не в retry/failure;
- `PENDING` и `WAITING_RETRY` отменяются сразу;
- для `RUNNING` cancellation сначала записывает `cancel_requested=true` и не отбирает lease у
  работающего процесса;
- terminal cancellation идемпотентна и не записывает error code/summary.

Heartbeat после истечения lease отклоняется. Это не позволяет старому worker «оживить» владение,
когда другой процесс уже мог начать recovery.

## Coordinator

`WeeklySnapshotJobCoordinator.runNext` перед каждым новым claim восстанавливает максимум один
истёкший job, после чего передаёт управление проверенному runner. Ограничение «один recovery за
итерацию» не даёт большой очереди истёкших jobs надолго заблокировать обычную обработку.

Coordinator также является внутренней точкой входа для heartbeat и cancellation. HTTP/admin API
на этом этапе намеренно не добавлен.

Cancellation является cooperative. Если работа уже завершилась и terminal `SUCCESS` успел
зафиксироваться, поздняя отмена ничего не меняет. Если запрос замечен через failure/recovery, job
закрывается как `CANCELLED`. Scheduled worker проверяет cancellation между длительными фазами до
persistence; поздний `SUCCESS` сохраняет приоритет в узком race window после последнего checkpoint.

## Метрики

`storeanalytics.interpretation.snapshot.jobs{status=...}` публикует cached gauges:

- `pending`;
- `running`;
- `retrying`;
- `failed`;
- `expired_lease`.

Обновление использует общий metrics scheduler и не выполняет SQL при каждом Prometheus scrape.
Ошибка refresh сохраняет предыдущий cache и попадает в технический log.

`storeanalytics.interpretation.snapshot.job.events{event=...}` считает terminal failures и
восстановления истёкших lease. На обоих переходах также пишется structured log с безопасными
идентификаторами. Состояние PostgreSQL остаётся источником истины, event counter — вспомогательным
диагностическим сигналом.

Готовые Prometheus rules находятся в
[monitoring/prometheus/weekly-snapshot-alerts.yml](../monitoring/prometheus/weekly-snapshot-alerts.yml).
Фактический канал доставки настраивается в Alertmanager deployment secrets, отдельно от
Telegram-уведомлений руководителям.

## Следующая граница

1. Подключение rules к production Prometheus/Alertmanager и staging fire/recovery test.
2. Audit trail ручной отмены/requeue.
3. Lifecycle и provider attempts автоматически созданного `llm_analysis_job`.
