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
original_content_sha256: e92570c687693dfcf87b31cacda28ad9ed40b8635d514373bf74202b18b98825
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/README.md`.

# LLM Analysis Planner

Статус на 2026-08-06: durable enqueue, reconciliation planner, provider attempts,
YandexGPT adapter, validation, publication и dashboard projection реализованы. Полный путь прошёл
реальный acceptance на агрегированных псевдонимизированных метриках. Все generation flags
по-прежнему выключены по умолчанию. Lifecycle contract:
[llm-analysis-lifecycle.md](llm-analysis-lifecycle.md).

## Назначение

Planner создаёт provider-neutral `llm_analysis_job` после появления нового immutable weekly
snapshot. Он не вызывает YandexGPT и не публикует пользовательский текст.

Надёжная последовательность сейчас выглядит так:

```text
snapshot planner
→ analytics_snapshot_job
→ immutable analytics_snapshot
→ snapshot job SUCCESS / CREATED
→ LLM reconciliation planner
→ llm_analysis_job PENDING / PREPARE
```

## Почему reconciliation

Прямой вызов enqueue из snapshot worker создал бы crash-gap: snapshot и его terminal job state
фиксируются короткими отдельными транзакциями. Процесс мог бы завершиться после `CREATED`, но до
постановки LLM job.

Reconciliation периодически читает зафиксированное состояние PostgreSQL. Поэтому restart или
временная ошибка planner не теряет генерацию, а сбой LLM-контура не откатывает готовый snapshot.

## Eligibility

Автоматически выбирается snapshot, который одновременно:

- относится к активному магазину и типу `WEEKLY`;
- имеет quality status `READY` или `PARTIAL`;
- является последней snapshot revision этого магазина и недели;
- подтверждён `analytics_snapshot_job` со статусом `SUCCESS` и outcome `CREATED`;
- ещё не имеет generation revision 1.

`BLOCKED`, `UNCHANGED`, незавершённые snapshot jobs и устаревшие snapshot revisions не создают
LLM job. Для `BLOCKED` dashboard позже должен показать backend-owned объяснение quality gate.

## Идемпотентность и конкуренция

`LlmAnalysisJobStore.enqueue` блокирует строку snapshot через `SELECT ... FOR UPDATE`, после чего
проверяет `(snapshot_id, generation_revision)`. Два WORKER-инстанса могут одновременно увидеть
кандидата, но в PostgreSQL останется одна строка.

Существующая job повторно используется только при совпадении provider/model, contract/policy
versions, retry limits и `input_hash`. Deadline не входит в identity: разные инстансы вычисляют его
в немного разное время, а победившая транзакция фиксирует единственное значение.

## Воспроизводимый request metadata

Job хранит:

- `provider_code=YANDEX` и requested model URI;
- provider configuration, prompt, content schema, analysis policy и budget policy versions;
- canonical generation parameters: temperature, output-token limit и общий provider-call limit;
- SHA-256 от snapshot ID/facts hash и всей versioned request metadata;
- независимые transport/validation retry limits;
- deadline и начальное состояние `PENDING/PREPARE`.

API key в БД, hash material, параметрах или логах не сохраняется. Полный prompt также не
сохраняется: он должен воспроизводиться из immutable snapshot и packaged prompt version.

## Feature flags

```dotenv
INTERPRETATION_GENERATION_ENABLED=false
INTERPRETATION_GENERATION_PLANNER_ENABLED=false
INTERPRETATION_GENERATION_PLANNER_SCAN_DELAY=1m
INTERPRETATION_GENERATION_PLANNER_BATCH_SIZE=25
INTERPRETATION_GENERATION_JOB_DEADLINE=5m
INTERPRETATION_GENERATION_WORKER_ENABLED=false
```

Planner создаётся только на runtime role `WORKER` или `COMBINED`. Startup завершается ошибкой, если
planner включён без generation feature flag. Включённая generation также требует полного набора
Yandex credentials/model и packaged prompt/schema versions.

В production planner/worker включаются только после server-side staging, fault drills, проверки
алертов и бюджетных лимитов. Безопасный rollout — развернуть код со всеми generation flags
`false`, затем включать этапы в порядке из `llm-production-operations.md`.

## Следующая граница

1. Server-side staging с теми же prompt/schema/model versions.
2. Fault drills: 401/403, 429, 5xx, timeout, malformed JSON и restart после send.
3. Offline quality evaluation на обезличенных примерах заказчика.
4. Проверка billing/technical alerts и утверждённого месячного бюджета.
