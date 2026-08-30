# Weekly review AI v25/input-v4/selection-v1/schema4 evaluation

Контур изолирован от production publication. V25 не меняет расчёты weekly review и публичный
`schema4`: YandexGPT возвращает только selector-токены, backend валидирует их и сам формирует
пользовательский текст.

## Offline gate и безопасный plan

```bash
./gradlew :backend:test --tests '*WeeklyReviewAiOfflineEvaluatorTest'
./gradlew :backend:weeklyReviewAiShadow
python3 -m unittest scripts/weekly-review-ai-eval/test_review.py
```

Plan использует четыре обезличенных input: итог без факторов, положительный итог с риском
возвратов, разнонаправленный итог с положительным и отрицательным факторами и отсутствие
существенного изменения. Сеть и деньги в режиме `plan` не используются. Команда собирает
production request, проверяет provider preflight и печатает SHA-256 и максимальную стоимость.
Текущий network-free plan: полный corpus — 12,432800 ₽; `balanced-strength-risk` — 3,296800 ₽.

## Платный semantic shadow

Каждый платный вызов требует отдельного явного разрешения на конкретный case, обезличенный payload
и максимальную стоимость. Общий исторический лимит — 20 ₽; уже использовано 16,016 ₽, остаток —
3,984 ₽. V25 `balanced-strength-risk` стоил 0,876 ₽ и прошёл semantic/blind gates.
Нельзя переносить старый cap или автоматически выполнять следующий case.

```bash
export WEEKLY_REVIEW_AI_EVAL_MODE=execute
export YANDEX_AI_FOLDER_ID='<folder>'
export YANDEX_AI_MODEL_URI='gpt://<folder>/<versioned-model>'
export YANDEX_AI_API_KEY='<temporary-key>'
export WEEKLY_REVIEW_AI_EVAL_MAX_PAID_CALLS=1
export WEEKLY_REVIEW_AI_EVAL_CASE_OFFSET=<approved-case-offset>
export WEEKLY_REVIEW_AI_EVAL_MAX_COST_RUB=<approved-call-cap>
export WEEKLY_REVIEW_AI_EVAL_OUTPUT_DIR='build/weekly-review-ai-eval/<new-run>'
export CONFIRM_WEEKLY_REVIEW_AI_SHADOW='CALL_WEEKLY_REVIEW_AI_SHADOW'
./gradlew :backend:weeklyReviewAiShadow
```

Runner запрещает `/latest`, нулевые цены, повторное использование каталога, путь вне
`build/weekly-review-ai-eval`, более одного разрешённого вызова и превышение hard cap. API key и
ответы provider не добавляются в git.

Для каждого case сохраняются:

- `*.provider.json` — исходный selector-ответ модели, только для audit;
- `*.json` — итоговый текст `schema4`, сформированный backend;
- `*.receipt.json` — версии, стоимость и результат semantic validation.

Blind reviewer оценивает только итоговый `*.json`, а не служебные selector-токены. Manifest
проверяет SHA-256 prompt, input/selection/content schemas, renderer, corpus, fixtures, shadow
runner и самого review script до формирования пакета.

## Слепая проверка

Для полного корпуса используется `manifest-v6.json`. Для отдельно разрешённого вызова
`balanced-strength-risk` используется `manifest-v6-balanced.json`.

```bash
python3 scripts/weekly-review-ai-eval/review.py status \
  --manifest scripts/weekly-review-ai-eval/manifest-v6-balanced.json \
  --responses-dir build/weekly-review-ai-eval/<run>
python3 scripts/weekly-review-ai-eval/review.py prepare \
  --manifest scripts/weekly-review-ai-eval/manifest-v6-balanced.json \
  --responses-dir build/weekly-review-ai-eval/<run> \
  --review-dir build/weekly-review-ai-eval/<run>/blind-review
```

Reviewer открывает только `packet.json` и заполняет `scores.json`.
`assignments.DO_NOT_OPEN.json` нельзя открывать до завершения оценок. Затем:

```bash
python3 scripts/weekly-review-ai-eval/review.py finalize \
  --manifest scripts/weekly-review-ai-eval/manifest-v6-balanced.json \
  --responses-dir build/weekly-review-ai-eval/<run> \
  --review-dir build/weekly-review-ai-eval/<run>/blind-review \
  --report build/weekly-review-ai-eval/<run>/decision.json
```

Gate требует по каждому ответу: каждый критерий не ниже 3/5, средняя не ниже 4/5, все required
findings, ноль forbidden findings и ноль critical errors. Решение
`CANDIDATE_ELIGIBLE_FOR_CANARY` само по себе не включает flags, publication или deployment.
