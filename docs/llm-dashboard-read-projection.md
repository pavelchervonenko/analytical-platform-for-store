# LLM Dashboard Read Projection

Статус на 2026-08-03: read-only backend projection и защищённый API для опубликованных недельных
интерпретаций реализованы и проверены через PostgreSQL end-to-end pipeline.

## Назначение

Dashboard читает только immutable `llm_interpretations`. Он не читает provider response из
`llm_analysis_attempts`, незавершённые job или notification outbox. Поэтому пользователь никогда
не увидит невалидированный либо ещё не опубликованный результат.

На текущем объёме отдельная mutable projection table не нужна. `WeeklyInterpretationQueryRepository`
собирает read model из индексированных immutable таблиц:

```text
llm_interpretations
├─ analytics_snapshots: timezone, snapshot revision, quality status
├─ llm_analysis_jobs: content schema version
└─ analytics_snapshot_employees: immutable employeeRef/name mapping
```

Это и есть отдельная backend-проекция: controller не отдаёт строки БД напрямую, а получает
versioned summary/detail view. При существенном росте объёма тот же API можно перевести на cache или
materialized projection без изменения frontend-контракта.

## API

Все маршруты требуют аутентификацию, сменённый initial password и доступ пользователя к `storeId`
через `StoreAccessAuthorization`:

- `GET /api/stores/{storeId}/interpretations/weekly` — история, только максимальная revision каждой
  недели;
- `GET /api/stores/{storeId}/interpretations/weekly/latest` — максимальная business week, затем её
  максимальная revision;
- `GET /api/stores/{storeId}/interpretations/weekly/{interpretationId}` — конкретная immutable
  revision, включая superseded.

История поддерживает `periodStartFrom`, `periodEndTo`, `page` и `size`. Default size — 12 недель,
общий предел стандартный для API: 100. Обратный диапазон и некорректная pagination возвращают
`INVALID_ARGUMENT`. Отсутствующая или принадлежащая другому магазину запись возвращает одинаковый
`WEEKLY_INTERPRETATION_NOT_FOUND`, не раскрывая существование чужого ID.

## Response envelope

Summary содержит:

- interpretation/snapshot/store IDs и период;
- timezone, snapshot revision и interpretation revision;
- `currentRevision`, supersession ID и publication reason;
- `contentSchemaVersion`, `contentHash` и snapshot quality status;
- число сотрудников, `validatedAt` и `publishedAt`.

Detail добавляет два независимых блока:

- `content` — исходный schema-versioned LLM JSON без переписывания текстов и evidence refs;
- `employees` — `employeeRef`, internal employee ID и `displayNameSnapshot`.

Имя берётся из момента формирования snapshot, а не из текущей карточки сотрудника. Это сохраняет
историческую воспроизводимость. Frontend разрешает `E01` через directory, но не заменяет refs внутри
canonical content.

## Целостность и revision semantics

Publication и read path используют общий `LlmCanonicalJsonCodec`. При detail-чтении backend:

1. разбирает JSON object;
2. сортирует object keys, не меняя порядок arrays;
3. повторно считает SHA-256;
4. сравнивает его с immutable `content_hash`.

Несовпадение считается нарушением server invariant и не возвращает потенциально повреждённый
контент. История группируется по store/type/period и выбирает максимальную revision. Detail по старой
revision остаётся доступен с `currentRevision=false`.

## Нагрузка и дальнейшее развитие

При ожидаемых двух магазинах и десятках недель запросы работают по
`ix_llm_interpretations_store_latest`; список выполняет отдельный exact count и bounded page query.
В detail добавляется один ordered lookup employee directory. N+1 по сотрудникам отсутствует.

Idempotent weekly Telegram fanout, delivery worker, operator UI и ежедневная backend-проекция
реализованы. Актуальный rollout runbook:
[llm-production-operations.md](llm-production-operations.md).
