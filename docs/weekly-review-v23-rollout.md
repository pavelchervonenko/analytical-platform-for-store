# Weekly review v23/input-v2/schema4 — production rollout

Дата: 2026-08-29
Статус: исторический immutable v23 runbook; активный candidate описан в `weekly-review-v25-rollout.md`

## 1. Граница релиза

`weekly-interpretation-v23` меняет только разрешённые формулировки
`summary.outcome.text` и `Factor.detail`. `Action.title` и `Action.check` формирует backend, а модель обязана вернуть их дословно. Публичный response/API остаётся
`schema4`; расчёты, периоды, evidence, factor/action IDs, action target/check/horizon и
персональные блоки не меняются.

Provider input получает отдельную immutable версию `input-v2`. Она сохраняет store-only
privacy boundary и добавляет backend-owned `summary.outcomeEffect`, чтобы модель не определяла
направление недели самостоятельно; `MIXED` фиксирует разнонаправленные материальные изменения
чистой выручки и валовой прибыли. `factor.managementMeaning` передаёт безопасное предметное
объяснение показателя и не разрешает модели придумывать причины.

## 2. Обязательный допуск до deploy

1. Полный backend test/checkstyle/OpenAPI/security gate и frontend regression gate.
2. Network-free corpus `weekly-review-ai-eval-v4`.
3. Обезличенные paid shadow-вызовы только в пределах отдельно подтверждённого агрегатного лимита.
4. Semantic validation всех ответов без hard-gate нарушений.
5. Blind review: каждый критерий не ниже 3, средняя оценка не ниже 4,0.
6. Независимое code/release review без P0/P1.

Низкая оценка управленческой полезности блокирует rollout, но сама по себе не запускает ещё один
платный запрос. Повторная калибровка требует нового immutable prompt/corpus revision.

## 3. Version-isolation

- worker выбирает, восстанавливает и учитывает в operational-метриках только
  `weekly-interpretation-v23/schema4` jobs;
- несовпадение версии прекращает выполнение до подготовки provider request;
- незавершённые v22 jobs не исполняются новым worker и не меняют v23 telemetry;
- read-path сначала использует опубликованный v23 enrichment, затем
  `weekly-interpretation-v22/schema4` fallback;
- API показывает фактическую версию применённого enrichment;
- v22/v23 job state machines изолированы по versioned key; enrichments и завершённые attempts остаются immutable.
- v23 input копирует `Action.title` из exact immutable snapshot, поэтому существующие безопасные
  snapshots не требуют пересборки;
- summary и factors с отрицаниями, сомнениями или оговорками отклоняются fail-closed; разрешена
  только две canonical neutral-конструкции: «существенных изменений нет» и «существенных изменений относительно предыдущей недели нет»;

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
`docker compose config --quiet`. Новых миграций в v23-калибровке нет; runtime по-прежнему
требует schema 48.

После deploy проверить digest/tag, schema, health backend-api/backend-worker/web и отсутствие
`JOB_CONTRACT_MISMATCH`, validation loop и provider ошибок в логах.

## 5. Ручной canary «МобиСферы»

1. Создать или переиспользовать deterministic snapshot последней завершённой недели только для
   «МобиСферы».
2. Зафиксировать snapshot ID, period, revision, state и content hash.
3. Создать один exact v23 AI job.
4. Дождаться terminal status без автоматического planner.
5. Сравнить результат с deterministic snapshot и оценить по management rubric.

```http
POST /api/admin/weekly-reviews/stores/{storeId}/generate
POST /api/admin/weekly-review-ai/snapshots/{snapshotId}/generate
GET  /api/admin/weekly-review-ai/jobs/{jobId}
GET  /api/stores/{storeId}/weekly-reviews/current
```

Canary допускается только при `SUCCEEDED`, пустых validation codes, неизменности всех
backend-owned полей, фактической версии `weekly-interpretation-v23`, отсутствии PII/новых чисел
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
поддерживается. Таблицы, v22/v23 enrichments, jobs и attempts не удаляются и не изменяются.

До двух успешных store-canary `WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED` и
`WEEKLY_REVIEW_AI_PLANNER_ENABLED` остаются `false`.


## 7. Подтверждённый локальный gate 2026-08-29

- offline corpus `weekly-review-ai-eval-v4`: 41 сценарий, PASS;
- paid calibration: 14,104 ₽ из разрешённых 20 ₽; финальный blind review — 4,8/5, hard gates — 0;
- backend: 1045 тестов, 0 failures, 0 errors; Checkstyle main/test и OpenAPI compatibility — PASS;
- frontend: 41 test files, 175 tests; generated contracts, ESLint и production build — PASS;
- security/release-safety, supply-chain integrity и `git diff --check` — PASS;
- snapshot terminal transitions защищены от редкого отката системных часов; targeted и full regression — PASS.

До canary остаются независимое финальное code/release review, immutable commit/release и default-off deploy.
