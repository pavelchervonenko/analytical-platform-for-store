# Weekly Snapshot Alerting

Статус: backend-сигналы и transport-agnostic Prometheus rules реализованы и проверены,
2026-08-02. Фактическая доставка уведомлений появится после подключения production Prometheus и
Alertmanager; адреса каналов и credentials в репозитории не хранятся.

## Граница ответственности

Это технические уведомления разработчику и ответственному со стороны заказчика. Они отделены от
Telegram-уведомлений руководителям магазина и не используют бизнес-тексты LLM.

Backend не обращается к Telegram, email или webhook из транзакции job. Он только:

- сохраняет состояние job в PostgreSQL;
- публикует Micrometer-метрики;
- пишет структурированные события без payload фактов и персональных данных.

Prometheus обнаруживает проблему, а Alertmanager отвечает за маршрутизацию и повторную доставку.

## Источники сигналов

### Durable gauges

`storeanalytics.interpretation.snapshot.jobs{status=...}` строится по PostgreSQL и содержит
`pending`, `running`, `retrying`, `failed`, `expired_lease`. Это основной источник для состояния,
которое должно пережить рестарт backend.

### Event counters

`storeanalytics.interpretation.snapshot.job.events{event=...}` содержит:

- `terminal_failure` — job достигла `FAILED` при обычном выполнении либо recovery;
- `expired_lease_recovered` — coordinator обработал просроченный lease.

Counter и log являются диагностикой короткого события, но не заменяют состояние в PostgreSQL:
между commit перехода и публикацией сигнала процесс теоретически может завершиться.

### Structured logs

События используют стабильные `event_code`:

- `weekly_snapshot_job_terminal_failure`;
- `weekly_snapshot_lease_terminal_failure`;
- `weekly_snapshot_lease_recovered`.

Для корреляции записываются безопасные поля `job_id`, `store_id`, `job_type`, attempts и
`error_code`, когда он существует. Полные exception, prompt, snapshot payload и данные сотрудников
в операторский alert не включаются.

## Готовые правила

Файл [weekly-snapshot-alerts.yml](../monitoring/prometheus/weekly-snapshot-alerts.yml) содержит:

- critical при наличии terminal `FAILED`;
- critical при живом `RUNNING` job с истёкшим lease;
- warning при факте recovery просроченного lease;
- warning, если retry-очередь не опустела за 30 минут.

Все правила фильтруют runtime role `WORKER|COMBINED` и имеют label `owner=developer`. Порог retry и
время `for` калибруются после месяца наблюдения, но terminal failure остаётся обязательным alert.

## Production rollout

1. Подключить rules-файл к Prometheus.
2. Проверить его production-версией `promtool check rules`.
3. Настроить Alertmanager route для `owner=developer` в отдельный технический канал.
4. Хранить recipient, bot token, SMTP/Webhook credentials только в deployment secrets.
5. На staging создать контролируемый failed job и проверить цепочку metric → alert → delivery.
6. Восстановить причину, безопасно повторить job и убедиться, что durable alert погас.
7. Смоделировать истёкший lease и проверить warning recovery без дублирования snapshot.

Удалять failed job ради погашения alert нельзя. Сначала сохраняются `job_id`, `error_code` и
диагностический контекст, затем устраняется причина и выполняется предусмотренный retry/requeue.

## Что ещё не реализовано

- production Prometheus/Alertmanager и конкретный транспорт доставки;
- dashboard по SLO snapshot pipeline;
- staging-проверка фактического маршрута alert → технический канал.

Audit ручной регенерации/отмены, provider worker и метрики `llm_analysis_job` реализованы в
приложении; production readiness требует подключить их к внешнему Prometheus/Alertmanager.
