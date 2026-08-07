# Weekly Snapshot Planner

Статус: PostgreSQL reconciliation planner, calendar/timezone policy, initial enqueue и 72-часовой
auto-revision контур реализованы и проверены, включая полный planner → worker → snapshot e2e,
2026-08-02. Planner выключен по умолчанию и не вызывает LLM.

## Почему reconciliation

Planner не вызывается напрямую из `SyncJobCoordinator.completeStep`. Он периодически сопоставляет
состояние `stores`, успешных `sync_jobs`, `analytics_snapshots` и `analytics_snapshot_jobs`.
Поэтому ошибка analytics не откатывает успешный sync, а рестарт между sync commit и planner tick не
теряет событие.

## Календарная политика

Для каждого активного LiveSklad-магазина независимо:

1. `now` переводится в IANA timezone магазина.
2. Определяется начало текущей недели — понедельник 00:00.
3. Целевой период — предыдущая полная неделя, понедельник–воскресенье.
4. Подходящим считается только `sync_job=SUCCESS`, который завершён не позднее `now` и имеет
   exclusive `period_end` не раньше начала текущей недели.
5. Из подходящих sync jobs выбирается самый поздно завершённый.

Это не позволяет создать понедельничный snapshot из воскресной синхронизации, которая ещё не
покрывает воскресенье целиком. Для job `source_data_cutoff` равен `finished_at` выбранного sync job.

## Initial и revision

- Если snapshot периода отсутствует, создаётся `INITIAL` после появления подходящего sync.
- Если snapshot существует, `AUTO_REVISION` разрешён только до
  `weekStart + revisionWindow` и только когда новый sync завершён позже сохранённого
  `source_data_cutoff`.
- Production default revision window — 72 часа от понедельника 00:00 в timezone магазина.
- Revision всегда ссылается на последнюю immutable ревизию через `base_snapshot_id`.
- После закрытия окна отсутствие initial snapshot всё ещё может быть восстановлено автоматически;
  окно ограничивает только revision.

Повторный tick сначала проверяет request identity, а `WeeklySnapshotJobStore.enqueue` повторно
защищает запись store-level lock и unique indexes. Поэтому несколько WORKER-инстансов могут
планировать одновременно: в PostgreSQL остаётся одно задание.

## Включение

```dotenv
INTERPRETATION_SNAPSHOT_ENABLED=true
INTERPRETATION_SNAPSHOT_WORKER_ENABLED=true
INTERPRETATION_SNAPSHOT_PLANNER_ENABLED=true
```

Planner создаётся только на runtime role `WORKER` или `COMBINED`. Если planner включён без snapshot
feature flag, worker process завершает startup с ошибкой. Planner и snapshot execution используют
разные scheduler-пулы, поэтому сборка фактов не блокирует reconciliation.

| Переменная | Default | Назначение |
|---|---:|---|
| `INTERPRETATION_SNAPSHOT_PLANNER_SCAN_DELAY` | `1m` | Пауза между reconciliation ticks |
| `INTERPRETATION_SNAPSHOT_REVISION_WINDOW` | `72h` | Окно автоматической ревизии |
| `INTERPRETATION_SNAPSHOT_PLANNER_BATCH_SIZE` | `25` | Число магазинов за tick, максимум 100 |
| `INTERPRETATION_SNAPSHOT_JOB_MAX_ATTEMPTS` | `5` | Max attempts создаваемого snapshot job |

Все значения валидируются при startup. Текущий production scope — до двух магазинов; при росте
выше `batchSize` настройку требуется увеличить до фактического количества активных магазинов.

## Безопасный rollout

1. Развернуть код со всеми interpretation flags `false`.
2. Включить snapshot feature и worker, вручную проверить один job до terminal state.
3. Проверить gauges `pending/running/retrying/failed/expired_lease` и structured logs.
4. Включить planner и убедиться, что создан ровно один request на магазин/неделю/source sync.
5. Повторный planner tick должен вернуть idempotent результат без дополнительной строки.
6. До включения LLM отдельно внедрить operator alerts и staging observation window.

## Следующая граница

1. Подключить готовые operator alert rules к production monitoring и проверить на staging.
2. Провести staging observation автоматического контура.
3. Реализовать lifecycle и provider attempts автоматически созданного `llm_analysis_job`.
