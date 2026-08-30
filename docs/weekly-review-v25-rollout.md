# Weekly review v25/input-v4/selection-v1/schema4 — production rollout

Дата: 2026-08-30
Статус: candidate; production deploy и canary запрещены до завершения всех gates

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
4. Один отдельно разрешённый paid case в оставшемся бюджете 4,860 ₽.
5. Semantic validation и blind review финального `RENDERED_SCHEMA4` без hard-gate нарушений.
6. Независимое code/release review без P0/P1.
7. Default-off deploy и один ручной store canary.

## Текущий статус gates

- пункты 1–3 — PASS: backend 1072 теста, frontend 175 тестов, Checkstyle, OpenAPI, security,
  release-safety, supply-chain, offline corpus и blind-review tooling зелёные;
- пункт 6 — PASS: независимое финальное review не нашло P0/P1/P2;
- пункты 4–5 — WAIT: платный `balanced-strength-risk` ещё не разрешён и не выполнен;
- пункт 7 — WAIT: deploy и production-canary запрещены до успешных пунктов 4–5.

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

## Rollback

```dotenv
WEEKLY_REVIEW_AI_PLANNER_ENABLED=false
WEEKLY_REVIEW_AI_WORKER_ENABLED=false
WEEKLY_REVIEW_AI_ENABLED=false
```

Это мгновенно возвращает deterministic weekly review без удаления immutable snapshots,
enrichments, jobs или attempts. Повреждённый/невалидный v25 enrichment также автоматически
игнорируется; read path продолжает использовать безопасный fallback.
