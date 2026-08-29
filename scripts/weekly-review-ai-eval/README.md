# Weekly review AI v24/input-v3/schema4 evaluation

Контур изолирован от production publication. Baseline — `v0.1.0-pilot.26` с v22/schema4 canary и
v21/schema3 fallback. Offline corpus и `plan` не используют сеть,
не публикуют отчёты и не создают Telegram-события.

## Offline gate и безопасный plan

```bash
./gradlew :backend:test --tests '*WeeklyReviewAiOfflineEvaluatorTest'
./gradlew :backend:weeklyReviewAiShadow
python3 -m unittest scripts/weekly-review-ai-eval/test_review.py
```

Plan использует четыре обезличенных input: положительная динамика с positive factor и
точным числом, отрицательная динамика возвратов, разнонаправленные выручка/прибыль с двумя
факторами и отсутствие материального изменения. Он собирает запрос production factory,
выполняет только локальный Yandex preflight и печатает SHA-256/максимальную стоимость.

## Платный semantic shadow

Выполняется только в новом ignored-каталоге и только после явных четырёх ограничений:

```bash
export WEEKLY_REVIEW_AI_EVAL_MODE=execute
export YANDEX_AI_FOLDER_ID='<folder>'
export YANDEX_AI_MODEL_URI='gpt://<folder>/<versioned-model>'
export YANDEX_AI_API_KEY='<temporary-key>'
export WEEKLY_REVIEW_AI_EVAL_MAX_PAID_CALLS=4
export WEEKLY_REVIEW_AI_EVAL_CASE_OFFSET=0
export WEEKLY_REVIEW_AI_EVAL_MAX_COST_RUB=16.00
export WEEKLY_REVIEW_AI_EVAL_OUTPUT_DIR='build/weekly-review-ai-eval/<new-run>'
export CONFIRM_WEEKLY_REVIEW_AI_SHADOW='CALL_WEEKLY_REVIEW_AI_SHADOW'
./gradlew :backend:weeklyReviewAiShadow
```

Для последовательного прогона в общем бюджете `CASE_OFFSET` выбирает начало из четырёх
фиксированных cases; runner отклоняет выход диапазона за корпус. Runner отклоняет `/latest`, нулевые цены, повторное использование каталога, путь вне
`build/weekly-review-ai-eval`, превышение per-call production budget и общий максимум выше
явного cap. API key и provider response нельзя добавлять в git.

## Слепая проверка

```bash
python3 scripts/weekly-review-ai-eval/review.py status \
  --responses-dir build/weekly-review-ai-eval/<run>
python3 scripts/weekly-review-ai-eval/review.py prepare \
  --responses-dir build/weekly-review-ai-eval/<run> \
  --review-dir build/weekly-review-ai-eval/<run>/blind-review
```

По умолчанию используется `manifest-v5.json`. Reviewer открывает только `packet.json` с
обезличенными provider input/output и заполняет `scores.json`; файл
`assignments.DO_NOT_OPEN.json` не открывается до завершения оценок. Затем:

```bash
python3 scripts/weekly-review-ai-eval/review.py finalize \
  --responses-dir build/weekly-review-ai-eval/<run> \
  --review-dir build/weekly-review-ai-eval/<run>/blind-review \
  --report build/weekly-review-ai-eval/<run>/decision.json
```

Gate требует по каждому ответу оценку не ниже 3/5 по каждому критерию, среднюю не ниже 4/5,
покрытие required findings,
ноль forbidden findings и ноль critical errors. Решение `CANDIDATE_ELIGIBLE_FOR_CANARY`
не включает feature flag, production publication или deployment.
