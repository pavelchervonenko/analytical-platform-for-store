# Weekly review v25/input-v4/selection-v1/schema4 — production rollout

Дата: 2026-08-30
Статус: `v0.1.0-pilot.27` развёрнут default-off; первый production-canary PASS, planners выключены

## Граница релиза

V25 меняет только optional AI enrichment. Расчёты, deterministic snapshot, периоды, evidence,
factor/action IDs, team/employees и публичный `schema4` не меняются. Новых миграций БД нет.

Provider возвращает только selectors. Backend fail-closed проверяет их и формирует финальный текст.
Worker исполняет только active `weekly-interpretation-v25/schema4` jobs. Read-path выбирает первый
валидный enrichment в порядке `v25 → v24 → v23 → v22`.

## Обязательный допуск

1. Checkstyle, полный backend regression, OpenAPI compatibility, security/release/supply-chain.
2. Полный frontend test, ESLint и production build.
3. Offline corpus `weekly-review-ai-eval-v6` и blind-review unit tests.
4. Один отдельно разрешённый paid case в пределах общего бюджета 20 ₽.
5. Semantic validation и blind review финального `RENDERED_SCHEMA4` без hard-gate нарушений.
6. Независимое code/release review без P0/P1.
7. Default-off deploy и один ручной store canary.

## Текущий статус gates

- пункты 1–3 — PASS: backend 1072 теста, frontend 175 тестов, Checkstyle, OpenAPI, security,
  release-safety, supply-chain, offline corpus и blind-review tooling зелёные;
- пункт 6 — PASS: независимое финальное review не нашло P0/P1/P2;
- пункты 4–5 — PASS: один `balanced-strength-risk` стоил 0,876 ₽, semantic violations — 0,
  blind review — 4,5/5, решение `CANDIDATE_ELIGIBLE_FOR_CANARY`;
- пункт 7 — PASS: `v0.1.0-pilot.27` развёрнут default-off и exact v25 job для «МобиСферы»
  завершился `SUCCEEDED` с первой попытки; semantic validation — `VALID`, violations — `[]`.

## Production evidence

- release: `v0.1.0-pilot.27`, commit `ea90ec81c3c33729e86d515e937bd9d82c39e636`;
- backend image: `sha256:ca73220219b27c1aa0b738dedfb19b4d6c3caf2086bb5f146ce214b6769c6feb`;
- web image: `sha256:1953f68c53755a0390ffe79233ce6fae7b7deea21c96fa3a3e51b050766b595c`;
- schema до/после — `48`; Flyway проверил 49 migrations и не применял новых изменений;
- release preflight, Compose config, ACL repair, public smoke, external liveness/readiness и
  post-deploy log filter — PASS;
- `backend-api`, `backend-worker` и `web` — `healthy`;
- `WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=false` и
  `WEEKLY_REVIEW_AI_PLANNER_ENABLED=false` сохранены.

## Deploy до canary

```dotenv
WEEKLY_REVIEW_ENABLED=true
WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_ENABLED=true
WEEKLY_REVIEW_AI_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_WORKER_ENABLED=true
```

До migrator обязательны release preflight и `docker compose config --quiet`. После deploy нужно
проверить image digests, schema 48, health всех сервисов и отсутствие contract mismatch,
validation loop и provider errors. Никакой автоматический planner до ручного canary не включается.

## Ручной canary

Для одного магазина создаётся или переиспользуется deterministic snapshot последней завершённой
недели и ровно один exact v25 job. Допуск требует `SUCCEEDED`, пустые validation codes, фактическую
версию v25, неизменность всех backend-owned полей, отсутствие PII/новых чисел и blind score по
rubric. Только после этого можно отдельно решать вопрос второго магазина и planners.

Canary выполнен для «МобиСферы»:

- snapshot `31d55acb-5276-4145-b7a5-827c04adaae5`, revision 1, `PARTIAL`, период
  `2026-08-17..2026-08-23`, content hash
  `9e8e6f4458d4453a1366b91734a445afe94878b55ca7438b27d91f182797403c`;
- job `6cebdcdd-7380-4357-865b-a1fafa266212` — `SUCCEEDED`, attempt 1/2,
  `weekly-interpretation-v25/schema4`;
- provider outcome — `RESPONSE_RECEIVED`, HTTP 200, 1390 input + 114 output = 1504 tokens,
  actual cost `1,203200 ₽`;
- validation outcome — `VALID`, validation violations и job validation codes пусты;
- enrichment immutable, content hash
  `8c7e930a15c6ed59d3c79b6969c24019494dc924b12769ac54b04b630e635b59`;
- browser-side exact comparison после нормализации только разрешённых AI-owned полей подтвердил
  `backendOwnedStable=true`, unexpected difference paths — `[]`;
- PII, новых чисел, месячного плана, текущей неполной недели и кадровых оценок в AI-тексте нет;
- независимый blind reviewer — PASS, среднее `3,5/5`, минимум `3/5`, hard-gate нарушений нет.
  Неблокирующие замечания: шаблонность факторов и недостаточно операциональное слово «разобрать».

Первый canary допускает v25 как безопасное optional enrichment для выбранного магазина, но не
разрешает автоматический массовый rollout. Оба planner остаются выключенными до отдельного решения
о втором магазине и режиме автоматизации.

## Rollback

```dotenv
WEEKLY_REVIEW_AI_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_WORKER_ENABLED=false
WEEKLY_REVIEW_AI_ENABLED=false
```

Это мгновенно возвращает deterministic weekly review без удаления immutable snapshots,
enrichments, jobs или attempts. Повреждённый/невалидный v25 enrichment также автоматически
игнорируется; read path продолжает использовать безопасный fallback.
