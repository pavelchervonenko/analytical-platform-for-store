# Weekly review v24/input-v3/schema4 — production rollout

Дата: 2026-08-29
Статус: локальный regression gate пройден; paid calibration и production-canary ещё не выполнены

## 1. Граница релиза

`weekly-interpretation-v24` разрешает модели только выбрать один полный итог из backend allowlist. Factor, `Action.title` и `Action.check` возвращаются по точным backend-owned формулам. `Action.title` и `Action.check` формирует backend, а модель обязана вернуть их дословно. Публичный response/API остаётся
`schema4`; расчёты, периоды, evidence, factor/action IDs, action target/check/horizon и
персональные блоки не меняются.

Provider input получает immutable `input-v3` с `summary.allowedNarratives`, `summary.outcomeEffect` и
`factor.managementMeaning`. Публичный API остаётся schema4; расчёты, периоды, evidence, ID,
targets, horizon и персональные блоки не меняются.

## 2. Обязательный допуск до deploy

1. Полный backend test/checkstyle/OpenAPI/security gate и frontend regression gate.
2. Network-free corpus `weekly-review-ai-eval-v5`.
3. Обезличенные paid shadow-вызовы только в пределах отдельно подтверждённого агрегатного лимита.
4. Semantic validation всех ответов без hard-gate нарушений.
5. Blind review: каждый критерий не ниже 3, средняя оценка не ниже 4,0.
6. Независимое code/release review без P0/P1.

Низкая оценка управленческой полезности блокирует rollout, но сама по себе не запускает ещё один
платный запрос. Повторная калибровка требует нового immutable prompt/corpus revision.

## 3. Version-isolation

- worker выбирает, восстанавливает и учитывает в operational-метриках только
  `weekly-interpretation-v24/schema4` jobs;
- несовпадение версии прекращает выполнение до подготовки provider request;
- незавершённые v22/v23 jobs не исполняются новым worker и не меняют v24 telemetry;
- read-path использует опубликованные enrichment в порядке v24, v23, затем v22;
- API показывает фактическую версию применённого enrichment;
- v22/v23/v24 job state machines изолированы по versioned key; enrichments и завершённые attempts остаются immutable.
- v24 input копирует `Action.title` из exact immutable snapshot, поэтому существующие безопасные
  snapshots не требуют пересборки;
- summary принимается только при exact membership в `allowedNarratives`; factors и actions — только при точном backend-owned тексте;

Это позволяет проверить «МобиСферу», не скрывая опубликованный v22-результат магазина
«МАГАЗИН».

## 4. Deploy до canary

Release выполняется с выключенными автоматическими planner:

```dotenv
WEEKLY_REVIEW_ENABLED=true
WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_ENABLED=true
WEEKLY_REVIEW_AI_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_WORKER_ENABLED=true
```

До migrator обязательны `preflight-release.sh` и
`docker compose config --quiet`. Новых миграций в v24-калибровке нет; runtime по-прежнему
требует schema 48.

После deploy проверить digest/tag, schema, health backend-api/backend-worker/web и отсутствие
`JOB_CONTRACT_MISMATCH`, validation loop и provider ошибок в логах.

## 5. Ручной canary «МобиСферы»

1. Создать или переиспользовать deterministic snapshot последней завершённой недели только для
   «МобиСферы».
2. Зафиксировать snapshot ID, period, revision, state и content hash.
3. Создать один exact v24 AI job.
4. Дождаться terminal status без автоматического planner.
5. Сравнить результат с deterministic snapshot и оценить по management rubric.

```http
POST /api/admin/weekly-reviews/stores/{storeId}/generate
POST /api/admin/weekly-review-ai/snapshots/{snapshotId}/generate
GET  /api/admin/weekly-review-ai/jobs/{jobId}
GET  /api/stores/{storeId}/weekly-reviews/current
```

Canary допускается только при `SUCCEEDED`, пустых validation codes, неизменности всех
backend-owned полей, фактической версии `weekly-interpretation-v24`, отсутствии PII/новых чисел
и blind-оценке не ниже установленного порога. Отдельно проверить, что «МАГАЗИН» продолжает
показывать опубликованный v22 fallback.

## 6. Rollback

Быстрый функциональный rollback AI:

```dotenv
WEEKLY_REVIEW_AI_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_WORKER_ENABLED=false
WEEKLY_REVIEW_AI_ENABLED=false
```

Это возвращает deterministic weekly review без удаления истории. Если нужно вернуть именно
генерацию v22, требуется redeploy V48-compatible v22 image; переключение prompt ID через env не
поддерживается. Таблицы, v22/v23/v24 enrichments, jobs и attempts не удаляются и не изменяются.

До двух успешных store-canary `WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED` и
`WEEKLY_REVIEW_AI_PLANNER_ENABLED` остаются `false`.


## 7. Итоговый gate 2026-08-30

- offline corpus `weekly-review-ai-eval-v5`: 41 сценарий, PASS;
- targeted contract/validator/compactor и Checkstyle: PASS;
- network-free maximum: 14,9472 ₽ для четырёх cases;
- общий paid budget: 20 ₽; v23 использовал 14,104 ₽, один v24 case — 1,036 ₽;
- backend: 1052 теста, Checkstyle, генерация и compatibility-проверка OpenAPI — PASS;
- frontend: 41 test file / 175 тестов, ESLint и production build — PASS;
- release safety, operator security и supply-chain gates — PASS;
- предварительный code review не выявил P0/P1;
- paid-output `positive-growth` прошёл semantic validation, но blind review отклонил его:
  средняя оценка 4,4/5, `manager usefulness` 2/5;
- v24 rollout закрыт со статусом **REJECT**. Production-canary и дополнительные v24 paid calls
  запрещены; продолжение требует новой immutable версии.
