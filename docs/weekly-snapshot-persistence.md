# Weekly Snapshot Persistence

Статус: persistence, durable jobs, execution worker и reconciliation planner реализованы и
проверены на PostgreSQL 16, 2026-08-02. Все runtime flags выключены по умолчанию.

## Почему JDBC

Таблицы `analytics_snapshots` и `analytics_snapshot_employees` являются append-only storage с
database triggers, revision chain и JSONB payload. Они намеренно остаются вне JPA mapping:
`WeeklySnapshotStore` выполняет небольшой явный набор SQL-команд, соответствующий V22.

## Атомарный алгоритм записи

`persist(command)` выполняется в одной транзакции:

1. Блокирует строку магазина через `SELECT ... FOR UPDATE`.
2. Читает и проверяет последнюю ревизию этой недели.
3. Сравнивает `factsHash`, versions, quality status и timezone.
4. При полном совпадении возвращает `UNCHANGED` и существующий snapshot.
5. При изменении вставляет новую ревизию и immutable employee membership.
6. Повторно читает созданную запись и проверяет её целостность до commit.

Блокировка магазина не позволяет двум worker’ам параллельно создать одинаковый номер ревизии.
Нагрузка проекта мала, поэтому store-level serialization проще и надёжнее advisory key math.

Первая ревизия всегда имеет:

- `revision=1`;
- `revision_reason_code=INITIAL`;
- `supersedes_snapshot_id=null`.

Следующая ревизия ссылается на непосредственно предыдущую и использует закрытый backend enum
`AUTO_REVISION` или `MANUAL_BACKFILL`. Отсутствующая/пустая revision note превращается в `null`,
максимальная длина — 500 символов.

## Источник данных

Persistence command обязательно содержит:

- UUID завершённого sync job;
- фактическое время его завершения;
- source data cutoff;
- revision reason.

V22 trigger отклоняет snapshot, если sync job не имеет статус `SUCCESS`, относится к другой
integration connection или магазин больше не связан с этой connection. Backend не обходит эту
проверку.

## Проверка чтения

Каждый `findById/findLatest`:

- декодирует JSONB обратно в typed `WeeklySnapshotPayload`;
- читает membership в порядке `employeeRef`;
- повторно вычисляет canonical SHA-256 по payload и membership;
- сравнивает hash в constant time;
- проверяет точное совпадение manifest employee refs и membership;
- проверяет соответствие contract version заголовку snapshot.

Нечитаемый JSON, поддельный hash или рассинхронизированный membership приводят к
`WeeklySnapshotIntegrityException`; повреждённый snapshot не может стать входом LLM.

Decimal JSON values читаются как `BigDecimal`. Это исключает преобразование денег через `Double` и
сохраняет воспроизводимость hash после JSONB round-trip.

## Следующая граница

- production Alertmanager и staging observation window;
- lifecycle автоматически созданных `llm_analysis_jobs`;
- validation и публикация (token preflight и YandexGPT provider adapter уже реализованы).
