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

## Платный semantic shadow: NO-GO

Прямой execute сейчас запрещён. Runner принимает API key только через environment, а plan не
материализует exact outbound payload для privacy-проверки. Поэтому README намеренно не содержит
исполняемой paid-команды. Не вводить API key через `export` и не восстанавливать команду по
историческому evidence.

Разблокирующие условия, модель авторизации и безопасный secret-file wrapper определены только в
[`AI evaluation runbook`](../../docs/runbooks/ai-evaluation.md). До выполнения всех его gates
разрешены лишь offline plan и работа с уже созданными обезличенными artifacts. Датированный
результат прежнего отдельно разрешённого вызова сохранён как история, а не как инструкция:
[`weekly-review-ai-v25-paid-evaluation.md`](../../docs/history/audits/2026/08/ai-evaluations/weekly-review-ai-v25-paid-evaluation.md).

После будущего разрешённого run для каждого case должны сохраняться:

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
