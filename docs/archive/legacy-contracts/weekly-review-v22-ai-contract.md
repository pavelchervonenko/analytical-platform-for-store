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
original_content_sha256: 16ef2423ea2436b41c4ad993b8cfcd2e51524f55850e5323ee2653566fd6a19f
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/README.md`.

# Weekly review v22/schema4 — AI contract and generation lifecycle

Дата: 2026-08-29
Статус: этап 4 завершён; CANDIDATE_ELIGIBLE_FOR_CANARY, production activation не выполнялась

## Назначение

`weekly-interpretation-v22` — необязательный store-level слой формулировок поверх
детерминированного `weekly-review-contract-v2`. Это отдельный контур, а не изменение production
v21/schema3. Старые jobs, payload, endpoint и projector не меняются.

Провайдер не рассчитывает показатели и не анализирует сотрудников. При его недоступности
руководитель получает полный детерминированный отчёт; меняется только состояние optional AI.

## Версии

| Ресурс | Версия / путь |
| --- | --- |
| Prompt | `weekly-interpretation-v22` |
| Provider input | `weekly-review-ai-input-v1.schema.json` |
| Provider output | `weekly-review-ai-content-v4.schema.json` |
| Output schema version | `4` |
| Prompt resource | `docs/prompts/weekly-interpretation-v22.md` |
| Snapshot storage | Flyway V45 |
| Validated enrichment | Flyway V46 |
| Generation lifecycle | Flyway V47 |
| Integrity and budget hardening | Flyway V48 |

## Privacy-reduced provider input

`WeeklyReviewAiInputCompactor` принимает только `READY` или `PARTIAL` report и передаёт:

- детерминированный store `summary.outcome`;
- не более трёх уже выбранных backend factors;
- не более трёх уже выбранных store-level actions;
- только доступные store evidence этих объектов;
- объектные allowlist допустимых числовых литералов.

В payload отсутствуют сотрудники, ФИО, employee/store/snapshot IDs, UUID, team block, shifts,
benchmark, месячный план, forecast, текущая незавершённая неделя, provenance, raw documents и source
payload. Compactor отклоняет `BLOCKED` report, employee action/evidence, недоступную или
неразрешённую ссылку и дубликат evidence ref.

## Provider output и semantic ownership

Schema4 содержит ровно четыре root-поля:

```json
{
  "schemaVersion": 4,
  "summary": {"text": "...", "evidenceRefs": ["STORE.NET_REVENUE"]},
  "factorExplanations": [],
  "actionWordings": []
}
```

Structural validator не ремонтирует ответ: malformed JSON, unknown/missing fields, wrong version и
duplicate IDs отклоняются целиком. Semantic validator проверяет:

- exact ordered factor/action IDs;
- exact evidence allowlist каждого объекта;
- дословное совпадение backend-owned `action.check`;
- отсутствие нового числового литерала;
- отсутствие неподтверждённой причинности;
- отсутствие month/plan/current-week формулировок;
- отсутствие кадровых оценок, generic employee fallback, UUID и повторяющегося текста.

AI может изменить только `summary.outcome.text`, `Factor.detail` и `Action.title`. Числа, периоды,
effects, targets, checks, team, employees, limitations, evidence и provenance остаются из V45.
Structural-valid без semantic marker не применяется и не сохраняется.

## Хранение и атомарная публикация

V46 хранит одну immutable validated формулировку для exact
`snapshot_id/prompt_version/content_schema_version`: input/content SHA-256, canonical content,
validated/published timestamps. Trigger запрещает update/delete.

V47 добавляет:

- `weekly_review_ai_jobs` — durable state `PENDING/RUNNING/RETRY_WAIT/SUCCEEDED/FAILED`;
- `weekly_review_ai_attempts` — exact privacy-reduced input, request/input hash, provider receipt,
  validation outcome, токены, стоимость и sanitized failure;
- lease/heartbeat/recovery, deadline и максимум две provider attempts;
- immutable finalized attempt и idempotent unique job для exact snapshot/prompt/schema.

V48 связывает JSON headers с immutable колонками snapshot/enrichment и добавляет
`provider_outcome = NOT_SENT/UNKNOWN/RESPONSE_RECEIVED`. Поэтому отсутствие ответа после отправки
не освобождает оценочный резерв и не позволяет следующему worker-вызову незаметно превысить
суточный cap. Upgrade с заполненной V47 выполняется транзакционно: immutable-trigger attempts
отключается только на время backfill нового поля и включается до завершения миграции.

Validated V46 enrichment и успешное завершение V47 job фиксируются в одной транзакции. Потеря
lease/job transition откатывает и enrichment, и attempt result. Истёкший lease сначала закрывает
`STARTED` attempt, затем переводит job в retry/failed. Planner рассматривает только самый новый
snapshot магазина и не откатывается к старому READY, если новый snapshot `BLOCKED`.

## Бюджет и readiness

До provider call проверяются размер request, context window, RUB currency, per-call cap и дневной
cap. Непосредственно перед созданием attempt V47 атомарно резервирует estimated cost под DB lock;
поэтому два worker-инстанса не могут одновременно пройти дневной лимит на устаревшем значении.

Defaults:

- максимум 2 calls/job;
- максимум 10 RUB/call;
- максимум 100 RUB UTC/day;
- provider timeout 180s, lease 4m, heartbeat 30s, job deadline 2h;
- batch 10, request 128 KiB.

Startup и release preflight fail closed: дочерние flags требуют parent flag, разрешён только
`YANDEX`, обязательны credentials и versioned model URI, `/latest` запрещён. Production Compose
явно передаёт read/snapshot/AI flags, timeouts и budgets; первый deploy выполняется со всеми
новыми flags `false`.

## Read states и rollback switch

- `BLOCKED` → `NOT_APPLICABLE`;
- parent flag `false` → `DISABLED` и только V45 report;
- нет job и planner выключен → `UNAVAILABLE`;
- pending/running/retry до SLA → `PREPARING`, после SLA → `DELAYED`;
- failed → `UNAVAILABLE`;
- published V46 → `READY` с validated wording.

`WEEKLY_REVIEW_AI_ENABLED=false` отключает применение сохранённого V46 enrichment, оставляя
детерминированный V45. `WEEKLY_REVIEW_ENABLED=false` отключает весь новый read endpoint: frontend
получает 404 и возвращается на v21/schema3. Данные V45–V48 не удаляются.

## Операторский контур

Все endpoints находятся под `/api/admin/**`:

```http
POST /api/admin/weekly-reviews/stores/{storeId}/generate
POST /api/admin/weekly-review-ai/snapshots/{snapshotId}/generate
GET  /api/admin/weekly-review-ai/jobs/{jobId}
```

Первый POST синхронно создаёт/переиспользует immutable deterministic snapshot и возвращает его ID.
Второй идемпотентно ставит exact snapshot в AI queue. GET показывает bounded lifecycle без raw
secret. Default-off `WeeklyReviewSnapshotPlanner` создаёт V45 snapshot последней завершённой
недели только после достаточного совокупного непрерывного sync coverage; release preflight не
разрешает AI planner без этого deterministic planner.

Метрика `storeanalytics.interpretation.weekly.review.ai.jobs` имеет status tags `pending`,
`running`, `retry_wait`, `succeeded`, `failed`, `delayed`, `expired_lease`. Provider tokens/cost/
latency продолжают учитываться общими Yandex metrics.

## Evaluation gates

Offline corpus содержит 17 positive/negative сценариев: READY/PARTIAL, no material change, exact
numeric literal, malformed/schema mismatch, factor/action/evidence/check mismatch, новое число,
неподтверждённая причинность, month-plan leak, generic employee text, UUID и duplicate narrative.

Network-free `:backend:weeklyReviewAiShadow` собирает exact production prompt/input/schema и считает
максимальную стоимость четырёх обезличенных online cases. Execute mode требует одновременно
versioned model, 1–4 paid calls, явный RUB cap, новый каталог под `build/`, confirmation phrase и
credentials. Blind-review packet содержит integrity-checked privacy-reduced input и output, но
скрывает case assignment до завершения оценок.

Исторический plan v2: 4 cases, maximum `13.648000 RUB`. Provider run завершён
`4/4 semanticValidated=true`, actual `3.380800 RUB`; всего за калибровку v2 выполнено 18 вызовов
стоимостью `14.716800 RUB`. Независимый blind review затем отклонил пакет из-за несогласованного
synthetic input: `110000 -> 120000` не соответствует разрешённым `5,9%`. Corpus исправлен на
`113315 -> 120000`, версионирован как v3 и прошёл offline plan: 4 cases, maximum `13.648000 RUB`.
Production publication и flags не затрагивались.

## Итог semantic gate

Corpus v3 прошёл paid run `4/4 semanticValidated=true`, actual `3.382400 RUB`. Независимый blind
review: average `4.75/5`, все cases passed, forbidden/critical findings — 0. Integrity-finalize выдал
`CANDIDATE_ELIGIBLE_FOR_CANARY`. Общая калибровка v2+v3: 22 calls, `18.099200 RUB`. Следующий gate —
отдельное решение о canary/rollout; production flags, frontend и publication здесь не включаются.

Решение `CANDIDATE_ELIGIBLE_FOR_CANARY` не включает flags, deployment, frontend или Telegram.
