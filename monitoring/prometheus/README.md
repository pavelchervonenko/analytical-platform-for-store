# Prometheus Operator Alerts

`weekly-snapshot-alerts.yml`, `llm-analysis-alerts.yml` и `telegram-delivery-alerts.yml`
содержат transport-agnostic правила для технических уведомлений
разработчику. Они не относятся к Telegram-уведомлениям руководителей и не выполняют внешние вызовы
из backend job-транзакций.

## Статус

Это repository examples, а не доказательство подключённого production monitoring. В репозитории
нет подтверждения, что production Prometheus загружает эти файлы, Alertmanager маршрутизирует
события, а конечный канал принимает fire/recovery. Текущая граница доказанного поведения описана в
[`observability.md`](../../docs/current/architecture/observability.md).

Ниже приведён только пример будущего wiring; его нельзя копировать в production или считать
действующей конфигурацией без отдельного runbook, runtime verification и change approval:

```yaml
rule_files:
  - /etc/prometheus/rules/weekly-snapshot-alerts.yml
  - /etc/prometheus/rules/llm-analysis-alerts.yml
  - /etc/prometheus/rules/telegram-delivery-alerts.yml
```

До rollout необходимо как минимум проверить repository-файлы совместимым `promtool`, подтвердить
фактическую загрузку rules, безопасный Alertmanager route с `owner=developer`, тестовый fire,
delivery и recovery. Адрес, bot token, SMTP credentials и webhook URL являются deployment secrets
и не хранятся в Git. Канал руководителей магазина для этих технических событий не используется.

Два источника дополняют друг друга:

- gauges показывают durable состояние PostgreSQL и не теряются при рестарте backend;
- event counters и structured logs фиксируют короткое восстановление истёкшего lease, которое может
  исчезнуть до следующего scrape.

Если wiring будет введён и подтверждён evidence, после первого месяца пороги и `for` следует
калибровать по фактической длительности jobs. Alert закрывается только после устранения причины;
terminal failed job нельзя удалять только ради погашения сигнала.
