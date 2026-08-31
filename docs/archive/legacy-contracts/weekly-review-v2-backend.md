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
original_content_sha256: 4b1dcc3cca1ab05fe86f699290fecdc7f5aa3a4a25ca3b16502b8e7ef7046e8a
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/README.md`.

# Weekly review v2 — backend implementation

Дата: 2026-08-30
Статус: этапы 3–6 реализованы локально; v25 local gate пройден, paid/full gate и production rollout не выполнялись

## Версии

| Контур | Версия |
| --- | --- |
| Public contract | `weekly-review-contract-v2` (`contractVersion = 2`) |
| Facts schema | `2` |
| Metrics policy | `weekly-metrics-v4` |
| Snapshot policy | `weekly-snapshot-v7` |
| Quality policy | `weekly-quality-v4` |
| AI prompt | `weekly-interpretation-v25` |
| AI provider input | `weekly-review-ai-input-v4` |
| AI provider output | `weekly-review-ai-selection-v1` |
| AI schema | `4` |

Номера `v4/v7/v4` выбраны после проверки production baseline: в старом контуре уже используются
`weekly-metrics-v3`, `weekly-snapshot-v6` и `weekly-quality-v3`. Новый номер не пересекается с
историческими snapshot.

## Архитектура

```text
две последние завершённые недели (пн–вс)
  ├─ store KPI
  ├─ sales / returns decomposition
  ├─ category hierarchy + attach
  ├─ raw employee sales + shifts + attach
  └─ period-scoped quality / source coverage
            ↓
WeeklyReviewAssembler (deterministic)
            ↓
weekly_review_snapshots (immutable revision)
            ↓
GET /api/stores/{storeId}/weekly-reviews/current
```

Новый источник не зависит от monthly plan, month forecast, current incomplete week или
`EmployeeRatingService`. Последний намеренно не используется, потому что рассчитывает score с
месячным plan context. Для weekly review читаются сырые показатели сотрудника без score/rank.

## Расчёты и состояния

- `NET_REVENUE = SALES_REVENUE - RETURN_REVENUE` проверяется до публикации.
- `AVERAGE_SALE = SALES_REVENUE / completedSaleDocumentCount`; возвраты не уменьшают numerator.
- При отсутствии себестоимости недоступны только gross profit и margin.
- Неожиданная нулевая себестоимость оставляет profit/margin видимыми как `LIMITED`, без factor/action.
- Orders не являются источником v2 и не влияют на готовность отчёта.
- Ошибка последнего sync job не блокирует закрытую неделю при полном покрытии sales и returns.
- Глобальный `openQualityIssueCount` не превращается в предупреждение классификации.
- Сравнение и materiality выполняются только при достаточной базе обеих недель.
- `previous = 0` даёт `NO_BASE`, а не бесконечный процент и не нулевое изменение.
- Возврат без продавца исходной продажи остаётся в итогах магазина, но переводит только блоки
  команды/сотрудников в `LIMITED`; точное количество публикуется в limitation и evidence.

## Структура продаж

Backend отдаёт готовое дерево. Только три верхних узла взаимно исключаются:

```text
Чистая выручка
├── Техника
│   ├── Телефоны
│   └── Другая техника
├── Дополнительная выручка
│   ├── Аксессуары
│   ├── Услуги, гарантии и защита
│   └── Прочие дополнительные категории
└── Остальное
```

Пересечение верхних групп и отрицательный residual переводят только этот блок в `LIMITED` и
создают конкретное объяснение. Frontend не должен повторно суммировать дочерние строки.

## Граница команды и сотрудников

`TeamBlock` содержит только:

- агрегированные roster counts;
- не более двух наблюдений минимум по двум сотрудникам;
- количество карточек, требующих внимания;
- policy медианы и агрегированные ограничения.

В `TeamBlock` отсутствуют ФИО, employee IDs, персональные метрики и actions. Их единственный владелец
— `EmployeeCard`. OpenAPI integration test отдельно проверяет эту границу.

Peer benchmark рассчитывается как медиана current week отдельно по метрике. Для net revenue в него
попадают только active assignment, `participatesInRanking = true` и сотрудники с минимум шестью
продажами. При `eligibleCount < 3` peer comparison не создаётся.

## Immutable revision

Миграция `V45__add_weekly_review_snapshots.sql` добавляет отдельную таблицу
`weekly_review_snapshots`. Она не меняет `analytics_snapshots`, `llm_interpretations` или старые
endpoint.

- одинаковое детерминированное содержимое переиспользует существующую revision;
- изменение содержимого создаёт `revision + 1` и `supersedes_snapshot_id`;
- update/delete запрещены trigger;
- при чтении проверяются header, версии, период, provenance и SHA-256 content hash;
- provenance не входит в content hash, поэтому другое время повторного расчёта само по себе не
  создаёт новую revision.

## Optional AI enrichment V46

Миграция `V46__add_weekly_review_ai_enrichments.sql` не изменяет V45 payload. Она хранит только
семантически проверенные формулировки для exact `snapshot_id`:

- AI может заменить только `summary.outcome.text` и `Factor.detail`; в v23/v24/v25 `Action.title` и `Action.check` остаются backend-owned, legacy v22 fallback сохраняет прежний контракт;
- `summary.positive/risk`, facts, periods, targets, checks, team/employees, limitations, evidence и
  provenance остаются из V45;
- structural-valid без semantic marker не применяется и не сохраняется;
- invalid/mismatch возвращает исходный V45 report;
- input/content SHA-256 проверяются при записи/чтении;
- unique key и trigger запрещают конфликтующую версию, update и delete.

`WeeklyReviewService.current` объединяет V45 с optional V46 при чтении только после наступления
`published_at` и только при `WEEKLY_REVIEW_AI_ENABLED=true`. Выключенный parent flag заставляет
read path игнорировать даже ранее опубликованный enrichment и всегда возвращать V45.

## Durable AI lifecycle V47

Миграция `V47__add_weekly_review_ai_generation_jobs.sql` добавляет отдельные durable jobs и
immutable attempts для exact tuple snapshot/prompt/schema/input hash.

- planner выбирает только последнюю revision магазина и не откатывается к более старому READY,
  если последняя revision BLOCKED;
- worker использует lease, bounded retry и не более двух provider calls;
- provider response проходит structural и semantic validation до атомарной публикации V46;
- STARTED attempt резервирует оценочную стоимость под database lock, поэтому параллельные workers
  не могут превысить дневной бюджет из-за stale read;
- terminal attempt хранит request/response hashes, token/cost receipt и validation/error codes;
- failed exact job не перезапускается бесконечно: требуется исправленная конфигурация либо новая
  snapshot/prompt revision.

Read path, deterministic snapshot planner, AI parent, AI planner и AI worker выключены по
умолчанию независимыми flags. Startup readiness проверяет provider, versioned model URI, secret
file и caps до активации worker.

## Rollout hardening V48

Миграция `V48__harden_weekly_review_rollout.sql`:

- связывает immutable snapshot JSON с колонками contract/version/period/state/provenance;
- связывает enrichment `schemaVersion` с `content_schema_version`;
- сохраняет `provider_outcome` для каждой завершённой попытки;
- продолжает резервировать estimated cost при неопределённом результате provider-вызова;
- сохраняет все ограничения additive, не меняя legacy-таблицы;
- при обновлении уже заполненной V47 временно отключает только immutable-trigger attempts,
  заполняет `provider_outcome` и в той же транзакции обязательно включает trigger обратно.

Отдельный default-off `WeeklyReviewSnapshotPlanner` после успешной синхронизации автоматически
проходит все активные магазины keyset-страницами и создаёт snapshot последней полностью завершённой
недели. Совокупная непрерывная цепочка успешных sync jobs обязана покрывать интервал от начала
предыдущей сравниваемой недели до конца текущей; один incremental job для этого не требуется. При
отсутствии достаточного покрытия ничего не публикуется; при неизменившемся source
повторная revision не создаётся. AI planner может быть включён release preflight только вместе с
deterministic snapshot planner.

## API и backward compatibility

Новый read path:

```http
GET /api/stores/{storeId}/weekly-reviews/current
Cache-Control: private, no-store
```

`WEEKLY_REVIEW_ENABLED=false` заставляет endpoint возвращать `404` без чтения snapshots. До
появления первой v2 revision он также возвращает `404`. Frontend в обоих случаях автоматически
показывает прежний v21/schema3 экран. При наличии semantic-valid V46 row endpoint возвращает
безопасно объединённый report, иначе исходный deterministic V45. Существующий
`GET /api/stores/{storeId}/insights/weekly/current` продолжает обслуживать v21/schema3 без
адаптера и без изменения payload. Это даёт per-store canary: только магазин с вручную созданным deterministic weekly snapshot
переключается на новый экран; остальные остаются на v21.

Frontend не доверяет transport type как runtime-гарантии: ответ проходит строгую Zod-проверку
версий, enum, смежных завершённых недель, metric codes/units/order, revenue identity,
report/block/factor/action invariants, уникальности ID и полного evidence graph.
Golden fixture получена сериализацией реального `WeeklyReviewAssembler`, а не составлена вручную.
Локальные `BlockState` применяются отдельно к summary, структуре продаж и команде, поэтому
ограничение одного блока не скрывает достоверные данные остальных.

Ручные ADMIN endpoints этапа 4:

```http
POST /api/admin/weekly-reviews/stores/{storeId}/generate
POST /api/admin/weekly-review-ai/snapshots/{snapshotId}/generate
GET  /api/admin/weekly-review-ai/jobs/{jobId}
```

Первый endpoint не вызывает provider и создаёт/переиспользует deterministic V45 revision. Второй
идемпотентно ставит один exact AI job, третий возвращает его наблюдаемое состояние. Они нужны для
ограниченного canary; массовый rollout ими не подразумевается.

## Rollback / forward-fix

V45–V48 являются additive migrations, но ранее выпущенные images с меньшим runtime maximum после
V48 запускаться не должны. Безопасный rollback выполняется внутри V48-compatible build:

- `WEEKLY_REVIEW_ENABLED=false` немедленно возвращает frontend на v21/schema3 fallback;
- AI parent/planner/worker и snapshot planner выключаются;
- ранее опубликованный V46 enrichment перестаёт читаться;
- legacy endpoint и projector не обращаются к V45–V48 таблицам;
- таблицы и triggers удалять не требуется и не рекомендуется;
- при backend-дефекте выпускается forward-fix поверх уже применённой V48.

Разрушительный down-migration не является штатным rollback. При дефекте нового контура безопасный
сценарий — оставить старый frontend/endpoint path в V48-compatible image и выпустить forward-fix.
Запуск прежнего image с обходом schema guard запрещён.

## Production status

Реализация, автоматическое недельное создание и rollback-контур готовы локально. Production
activation и canary не выполнялись. Порядок допуска, наблюдения и отката закреплён в
`docs/weekly-review-v22-rollout.md`.
