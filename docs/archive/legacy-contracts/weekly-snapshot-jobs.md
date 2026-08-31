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
original_content_sha256: 24201389e7f662dd27a87fa9692f1fbc73b8b9ddd2ee7b5c289fcbb6e0a7866b
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/README.md`.

# Weekly Snapshot Jobs

Статус: durable job store, planner, execution service, runner, operational lifecycle и
reconciliation handoff в `llm_analysis_jobs` реализованы и проверены, 2026-08-02. Все workers и
planners выключены по умолчанию. Детали исполнения и эксплуатации:
[weekly-snapshot-execution.md](weekly-snapshot-execution.md),
[weekly-snapshot-operations.md](weekly-snapshot-operations.md),
[weekly-snapshot-planner.md](weekly-snapshot-planner.md).

## Enqueue

`WeeklySnapshotJobStore.enqueue` выполняется транзакционно и блокирует строку магазина. До вставки
backend проверяет:

- магазин существует;
- source sync job имеет `SUCCESS` и относится к integration connection магазина;
- `AUTO_REVISION` имеет `baseSnapshotId`, равный последней ревизии того же магазина и периода;
- `MANUAL_BACKFILL` при существующем snapshot также ссылается на последнюю ревизию;
- `INITIAL` не имеет base snapshot, и snapshot за эту неделю ещё не существует;
- max attempts находится в диапазоне 1–20.

Повтор одного request identity из unique index V22 возвращает существующий job. Если metadata
идемпотентного запроса отличается, возникает conflict. Другой active job для той же недели также
отклоняется, поэтому уникальный partial index не используется как штатный механизм обработки ошибки.

## Claim и lease

Claim выбирает один `PENDING/WAITING_RETRY` job:

```sql
FOR UPDATE SKIP LOCKED
```

После claim:

- status становится `RUNNING`;
- attempt count увеличивается;
- записываются owner и lease deadline;
- started time устанавливается только при первом claim;
- старые error fields очищаются.

Завершать или переводить job в retry может только текущий lease owner.

## Retry и terminal state

Retry разрешён только если:

- ошибка помечена retryable;
- attempt count меньше max attempts;
- cancellation не запрошена;
- следующий запуск находится в будущем.

Иначе job переходит в `FAILED`; при cancellation — в `CANCELLED`. Lease очищается во всех переходах
из `RUNNING`. Error code ограничен 80 символами, summary — 500 символами.

Успешное завершение проверяет, что result snapshot относится к тем же store/period. Outcome
`UNCHANGED` дополнительно обязан указывать ровно на `baseSnapshotId`; это исключает ложное завершение
старым или посторонним snapshot.

## Оставшаяся граница

1. Production Alertmanager, staging rollout и audit trail ручной отмены.
2. Claim/lease/attempt lifecycle созданных `llm_analysis_jobs`.
3. Provider adapter, validation и publication.
