---
doc_schema: 1
doc_type: evidence
status: historical
owner: ai
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved legacy evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/current/ai/README.md"
original_content_sha256: 279ba1cb4402ed4cf55fe5d559fd431bf39ba4a513fd45c4dace868439d59fe7
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/ai/README.md`.

# Weekly review v25/schema4 — управленческая калибровка

Дата: 2026-08-30
Статус: local/paid gates и первый production-canary пройдены; v25 развёрнут default-off, planners выключены

## Причина v25

V24 прошёл factual hard gates, но blind review оценил управленческую полезность в 2/5: модель почти
дословно повторяла подготовленный backend текст. Immutable v24 оставлен со статусом `REJECT`.

V25 отделяет редакционный выбор от пользовательского текста:

1. backend формирует обезличенные facts, разрешённые selector-токены, exact actions и evidence;
2. YandexGPT выбирает только summary/factor selectors и factor IDs;
3. structural и semantic validator проверяют точный набор, порядок, allowlist и effect;
4. versioned `WeeklyReviewAiRendererV25` формирует итоговый `schema4`;
5. в enrichment сохраняется только финальный текст, raw selector остаётся в audit attempt.

Так модель сохраняет полезный выбор фокуса и framing, но не может придумать число, причину,
действие, скрыть известный риск или изменить backend-owned данные.

## Контракты

- prompt: `weekly-interpretation-v25`;
- provider input: `weekly-review-ai-input-v4`;
- provider output: `weekly-review-ai-selection-v1`;
- public/enrichment content: неизменный `weekly-review-ai-content-v4`;
- read fallback: `v25 → v24 → v23 → v22`;
- миграции БД: отсутствуют.

Summary selector теперь ограничен составом факторов: без факторов — только outcome, только positive
— strength, только negative — risk, одновременно positive и negative — balanced. Поэтому модель не
может выбрать общий итог и скрыть уже известную проблему.

## Проверки на текущем этапе

- полный backend regression: 1072 теста, 0 failures/errors/skipped;
- пакет `com.storeanalytics.interpretation.review.ai.*`: 85 тестов, 0 failures/errors/skipped;
- Checkstyle main/test, security и release-safety — PASS;
- OpenAPI compatibility — PASS; Gradle supply-chain — PASS, 449 компонентов и 840 артефактов;
- frontend: 41 test file, 175 тестов, ESLint и production build — PASS;
- blind-review unit tests: 9, PASS;
- network-free shadow plan: 4 case, максимум 12,432800 ₽; `balanced-strength-risk` — максимум 3,296800 ₽;
- manifest v6 фиксирует SHA-256 prompt, input/selection/content schemas, renderer, corpus, fixtures,
  shadow runner и review script; итоговый blind packet принимает только `RENDERED_SCHEMA4`;
- независимое финальное code/release review: P0/P1/P2 не найдено.

## Paid acceptance

Отдельно разрешён один обезличенный case `balanced-strength-risk` с hard cap 3,296800 ₽.
Выполнен ровно один provider call:

- фактическая стоимость — 0,876 ₽; 996 input и 99 output tokens;
- structural/semantic validation — `VALID`, 0 violations;
- модель выбрала `SUMMARY_BALANCED`, strength для accessory attach-rate и risk для возвратов;
- итоговый `RENDERED_SCHEMA4` прошёл integrity-checked blind review;
- blind average — 4,5/5, minimum dimension — 3/5, forbidden/critical findings — 0;
- решение — `CANDIDATE_ELIGIBLE_FOR_CANARY`.

Reviewer отметил один неблокирующий недостаток: factor explanations безопасны, но довольно общие и
частично повторяют summary. Временные локальная и production-копии API key/env удалены после
finalize; исходный production secret не изменялся.

## Платный бюджет

Калибровочный бюджет — 20 ₽. До production-canary использовано 16,016 ₽: v23 — 14,104 ₽,
один v24 case — 1,036 ₽, один v25 case — 0,876 ₽. Отдельно разрешённый production-canary
стоил 1,203200 ₽; совокупная известная стоимость v23–v25 calibration и canary — 17,219200 ₽.
Дополнительные paid calls не требуются; любой новый вызов всё равно потребует отдельного явного
разрешения и нового hard cap.

## Production-canary

Release `v0.1.0-pilot.27` с exact commit
`ea90ec81c3c33729e86d515e937bd9d82c39e636` развёрнут на schema 48. Автоматические snapshot/AI
planners оставлены выключенными, AI worker включён только для ручной очереди.

Для «МобиСферы» создан immutable snapshot завершённой недели `2026-08-17..2026-08-23` со
статусом `PARTIAL`, после чего поставлен ровно один exact `weekly-interpretation-v25/schema4` job.
Job завершился `SUCCEEDED` с первой попытки: provider outcome `RESPONSE_RECEIVED`, HTTP 200,
1390 input и 114 output tokens, actual cost 1,203200 ₽. Structural/semantic validation — `VALID`,
validation violations и job validation codes — пустые.

Публичный read path вернул `AI_ENHANCED/READY` с фактическим v25/schema4. Сравнение полного ответа
до/после после нормализации только разрешённых AI-owned полей подтвердило отсутствие изменений
backend-owned данных. В AI-тексте нет PII, новых чисел, месячного плана, текущей неполной недели
или кадровых оценок. Независимый blind reviewer дал PASS при среднем `3,5/5`, минимуме `3/5` и
нуле hard-gate нарушений. Остаточные неблокирующие замечания — шаблонность factor details и
недостаточно операциональная формулировка действий «разобрать».

Первый store-canary — PASS; решение о втором магазине и автоматических planners остаётся
отдельным change.
