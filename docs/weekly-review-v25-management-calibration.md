# Weekly review v25/schema4 — управленческая калибровка

Дата: 2026-08-30
Статус: полный local/paid gate и независимые review пройдены; candidate допущен к canary, production-canary не выполнен

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

Общий разрешённый бюджет — 20 ₽. Использовано 16,016 ₽: v23 — 14,104 ₽, один v24 case —
1,036 ₽, один v25 case — 0,876 ₽. Остаток — 3,984 ₽. Дополнительные paid calls не требуются;
любой новый вызов всё равно потребует отдельного явного разрешения и нового hard cap.

V25 допущен к default-off production-canary, но это решение само по себе не выполняет deploy,
не включает planner/worker flags и не публикует enrichment.
