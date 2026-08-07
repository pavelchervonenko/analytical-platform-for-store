# Prometheus Operator Alerts

`weekly-snapshot-alerts.yml`, `llm-analysis-alerts.yml` и `telegram-delivery-alerts.yml`
содержат transport-agnostic правила для технических уведомлений
разработчику. Они не относятся к Telegram-уведомлениям руководителей и не выполняют внешние вызовы
из backend job-транзакций.

Подключение в production Prometheus:

```yaml
rule_files:
  - /etc/prometheus/rules/weekly-snapshot-alerts.yml
  - /etc/prometheus/rules/llm-analysis-alerts.yml
  - /etc/prometheus/rules/telegram-delivery-alerts.yml
```

Перед rollout файл проверяется той же версией Prometheus, которая используется в production:

```bash
promtool check rules /etc/prometheus/rules/weekly-snapshot-alerts.yml \
  /etc/prometheus/rules/llm-analysis-alerts.yml \
  /etc/prometheus/rules/telegram-delivery-alerts.yml
```

Alertmanager route с label `owner=developer` должен вести в отдельный технический канал заказчика и
разработчика. Адрес, bot token, SMTP credentials или webhook URL являются deployment secrets и не
хранятся в Git. Сначала рекомендуется email или отдельный технический Telegram-чат; канал
руководителей магазина для этих событий не используется.

Два источника дополняют друг друга:

- gauges показывают durable состояние PostgreSQL и не теряются при рестарте backend;
- event counters и structured logs фиксируют короткое восстановление истёкшего lease, которое может
  исчезнуть до следующего scrape.

После первого месяца пороги и `for` калибруются по фактической длительности snapshot jobs. Alert
закрывается только после устранения причины; terminal failed job нельзя удалять только ради
погашения сигнала.
