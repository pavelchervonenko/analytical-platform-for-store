---
doc_schema: 1
doc_type: archive
status: archived
owner: ai
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/current/ai/README.md"
original_content_sha256: 98195f65a87af6961d74f2caf1889b5170c91777dd1e39ac66cf35629b9d02b7
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/README.md`.

# Weekly Snapshot Worker

Статус: production-shaped scheduled worker, feature flags, dedicated schedulers, heartbeat и
cancellation checkpoints реализованы и проверены, включая PostgreSQL e2e planner → worker →
snapshot, 2026-08-02. Worker и automatic planner выключены по умолчанию. Planner contract:
[weekly-snapshot-planner.md](weekly-snapshot-planner.md). Operator signals:
[weekly-snapshot-alerting.md](weekly-snapshot-alerting.md).

## Включение

Worker создаётся только для runtime role `WORKER` или `COMBINED` и только при:

```dotenv
INTERPRETATION_SNAPSHOT_ENABLED=true
INTERPRETATION_SNAPSHOT_WORKER_ENABLED=true
```

Если worker flag включён без snapshot feature flag, приложение worker-роли завершает startup с
ошибкой. На API-only роли worker, readiness validator и его scheduler beans не создаются.

Безопасный rollout:

1. применить миграции и развернуть код с обоими flags `false`;
2. проверить PostgreSQL, worker metrics и отсутствие старых активных jobs;
3. включить `INTERPRETATION_SNAPSHOT_ENABLED=true`;
4. создать один тестовый job вручную;
5. включить `INTERPRETATION_SNAPSHOT_WORKER_ENABLED=true` только на worker/combined instance;
6. проверить terminal result, gauges и structured logs;
7. только после этого включить `INTERPRETATION_SNAPSHOT_PLANNER_ENABLED=true` и проверить
   идемпотентный повтор planner tick.

## Настройки

| Переменная | Default | Назначение |
|---|---:|---|
| `INTERPRETATION_SNAPSHOT_WORKER_DELAY` | `5s` | Пауза между завершением iteration и следующим claim |
| `INTERPRETATION_SNAPSHOT_LEASE_DURATION` | `10m` | Срок владения claimed job |
| `INTERPRETATION_SNAPSHOT_HEARTBEAT_INTERVAL` | `1m` | Частота продления живого lease |
| `INTERPRETATION_SNAPSHOT_RETRY_INITIAL_DELAY` | `30s` | Начальная задержка retry/recovery |
| `INTERPRETATION_SNAPSHOT_RETRY_MAX_DELAY` | `15m` | Верхняя граница exponential backoff |

Startup validation требует положительные durations, heartbeat короче lease, lease минимум на два
heartbeat interval и `retryMaxDelay >= retryInitialDelay`.

## Scheduler isolation

Выполнение и heartbeat используют разные однопоточные scheduler beans:

- `weeklySnapshotWorkerScheduler`;
- `weeklySnapshotHeartbeatScheduler`.

Поэтому блокирующее чтение facts не останавливает heartbeat. Shutdown прекращает delayed/periodic
задачи, ожидает текущую работу до 30 секунд, а незавершённую job затем подберёт expired-lease recovery.

## Heartbeat и cancellation

Heartbeat выполняет один атомарный PostgreSQL update по `lease_owner`. Он продлевает только
`RUNNING` job с ещё не истёкшим lease и не способен оживить уже потерянное владение.

Execution service проверяет `cancel_requested`:

1. до чтения facts;
2. после чтения facts;
3. после построения и проверки draft;
4. после чтения source sync metadata, непосредственно перед persistence.

При cancellation runner завершает job как `CANCELLED` без error fields. Остаётся узкое допустимое
race window между последним checkpoint и атомарной записью snapshot: если persistence/complete уже
победили, terminal `SUCCESS` имеет приоритет над поздней отменой.

## Следующая граница

1. Подключить готовые operator alert rules к Alertmanager и провести staging fire/recovery test.
2. Провести staging observation planner → worker → snapshot.
3. Реализовать lifecycle и provider attempts автоматически созданного `llm_analysis_job`.
