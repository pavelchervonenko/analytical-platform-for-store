# Weekly Snapshot Execution

Статус: execution service, runner и operational lifecycle реализованы и проверены unit- и
PostgreSQL integration-тестами, включая полный planner → worker → immutable snapshot сценарий,
2026-08-02. Scheduled worker и reconciliation planner реализованы, но выключены по умолчанию;
вызов LLM пока не включён.
Эксплуатационный контракт: [weekly-snapshot-operations.md](weekly-snapshot-operations.md).
Worker contract: [weekly-snapshot-worker.md](weekly-snapshot-worker.md).
Planner contract: [weekly-snapshot-planner.md](weekly-snapshot-planner.md).

## Граница одного выполнения

`WeeklySnapshotJobExecutionService` принимает уже claimed job со статусом `RUNNING` и проверяет
lease owner. Далее он выполняет одну последовательность:

1. строит `WeeklyAnalyticsFactsQuery` для целевой недели и непосредственно предшествующей недели;
2. получает типизированные факты через `WeeklyAnalyticsFactsSource`;
3. строит `WeeklySnapshotDraft`;
4. проверяет store, query и все contract/policy versions относительно job;
5. повторно читает `finished_at` успешной исходной синхронизации того же connection;
6. сохраняет immutable snapshot;
7. переводит job в `SUCCESS` с `CREATED` или `UNCHANGED`.

LLM не участвует в этом сервисе. Это сохраняет границу: сначала backend фиксирует доказуемый
набор фактов, и только отдельный следующий job передаёт зафиксированный snapshot модели.

## Транзакционные границы

Чтение аналитических фактов выполняется в `REPEATABLE_READ`, запись snapshot — в отдельной короткой
транзакции с блокировкой store, terminal transition job — ещё в одной короткой транзакции. Долгая
транзакция на всю сборку не удерживается.

Если процесс завершится после вставки snapshot, но до `job.complete`, повторное выполнение узнает
тот же write attempt по `source_sync_job_id`, `source_sync_completed_at` и `source_data_cutoff`.
Хранилище возвращает прежний snapshot с outcome `CREATED`, не создавая дополнительную ревизию. Это
позволяет безопасно завершить исходный job после restart.

Если payload совпал, но provenance отличается, результат остаётся `UNCHANGED`. Для auto revision
он обязан указывать на `base_snapshot_id`, что дополнительно защищается backend и CHECK constraint.

## Следующая граница

1. Подключить готовые operator alert rules и провести staging rollout worker/planner.
2. Добавить audit ручной отмены/requeue.
3. Реализовать lifecycle автоматически созданного `llm_analysis_job` и provider attempts.
