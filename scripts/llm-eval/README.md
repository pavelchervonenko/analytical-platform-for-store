# Offline evaluation недельной ИИ-интерпретации

Evaluation-контур сравнивает production baseline `weekly-interpretation-v4` / schema 2 и
bounded privacy-reduced candidate `weekly-interpretation-v21` / schema 3
на 26 обезличенных сценариях.
Dry-run не обращается к YandexGPT, production, публикации или Telegram. Платный режим доступен
только через явную confirmation-фразу и два бюджетных лимита.

Актуальный результат на 2026-08-21:

- v21: 26/26 automatic pass, 0 violations;
- v21: 26/26 blinded manual pass, средняя оценка 4,8/5;
- решение: `CANDIDATE_ELIGIBLE_FOR_CANARY`;
- production default по-прежнему v4/schema 2.

Подробный handoff:
[`AI_INTERPRETATION_V21_WEEKLY_CANARY_2026-08-17.md`](../../docs/history/canaries/2026/08/AI_INTERPRETATION_V21_WEEKLY_CANARY_2026-08-17.md).

## Состав

- `dataset-v2.json` — 26 сценариев, конфигурации v4/v21 и экспертные ожидания;
- `dataset-v2.schema.json` — схема dataset;
- `evaluate.py` — сбор provider input, автоматический gate и canonical backend presentation;
- `review.py` — completeness/integrity gate и слепая A/B-оценка;
- `shadow.sh` — безопасный plan/run wrapper;
- `LlmEvalShadowRunner` — production compactor/request factory/client без включения runner в JAR;
- `test_evaluate.py`, `test_review.py` — регрессионные тесты gate.

Dataset фиксирует facts, sufficiency, materiality, candidates, limitations, required/acceptable/
forbidden findings и critical errors. Смысл required/forbidden findings оценивается вручную;
автоматический gate проверяет контрактные и доказательные инварианты.

## Архитектура v21

V4 получает legacy compact input. V21 наследует privacy-reduced path и ограничивает
provider input максимум двумя разными по теме store candidates:

- provider получает STORE facts/candidates и только агрегированный `TEAM.RATING_ELIGIBLE_COUNT`;
- employee facts, employee refs, employee candidates и relationship candidates не отправляются;
- модель выбирает только разрешённые STORE candidates и enum-тексты;
- employee headlines, team relationships, neutral results и limitations формирует backend;
- сырой provider output проверяется только против exact privacy-reduced input;
- после raw-gate canonical presentation достраивается из полного trusted snapshot и проверяется повторно;
- evaluation и blinded review проверяют backend-canonical документ, показываемый пользователю;
- SHA-256 raw response остаётся связан с packet/assignments и проверяется отдельно.

Различие provider input hash между v4 и v21 ожидаемо и разрешено только для этого versioned
privacy-reduced path. Внутри каждой конфигурации input, prompt, schema и generation parameters
включены в immutable evaluation hash.

## Локальная проверка без сети

Из корня репозитория:

```bash
python3 -m pip install -r scripts/llm-eval/requirements.txt
python3 scripts/llm-eval/evaluate.py
python3 -m unittest   scripts/llm-eval/test_evaluate.py   scripts/llm-eval/test_review.py
```

Экспорт полных scenario inputs:

```bash
python3 scripts/llm-eval/evaluate.py   --export-inputs build/llm-eval/inputs
```

Dry-run production request path:

```bash
LLM_EVAL_RESPONSES_DIR=build/llm-eval/v4-v21-offline/responses LLM_EVAL_ARTIFACTS_DIR=build/llm-eval/v4-v21-offline/shadow   scripts/llm-eval/shadow.sh plan
```

Plan создаёт request/evaluation hashes, token estimates и консервативный максимум стоимости.
API key для plan не нужен.

## Платный shadow-run

Обязательные условия:

```bash
export YANDEX_AI_FOLDER_ID='<folder-id>'
export YANDEX_AI_MODEL_URI='gpt://<folder-id>/<versioned-model>'
export YANDEX_AI_API_KEY_FILE='/secure/path/yandex-api-key'
export LLM_EVAL_RESPONSES_DIR='build/llm-eval/<immutable-run>/responses'
export LLM_EVAL_ARTIFACTS_DIR='build/llm-eval/<immutable-run>/shadow'
export CONFIRM_YANDEX_LLM_SHADOW='CALL_YANDEX_SHADOW'
scripts/llm-eval/shadow.sh run <max-calls> <max-rub>
```

Каждая новая матрица обязана использовать новый каталог. Runner:

- не перезаписывает успешный ответ;
- не повторяет ошибку автоматически;
- требует `LLM_EVAL_RETRY_FAILURES=RETRY` для отдельного retry;
- сохраняет response, receipt и безопасный failure artifact раздельно;
- проверяет evaluation hash перед признанием ответа завершённым.

API key, folder ID, prompt body, responses, receipts и failures нельзя добавлять в git.

## Автоматический gate

```bash
python3 scripts/llm-eval/evaluate.py   --responses-dir build/llm-eval/<immutable-run>/responses   --require-responses   --report build/llm-eval/<immutable-run>/automatic-report.json
```

Gate проверяет:

- полноту всех 52 ответов;
- schema v2 для v4 и schema v3/provider transport v21;
- exact candidates/evidence/scope/category;
- backend-owned employees, team relationships и limitations;
- absence person-level provider output для v21;
- sufficient/limited/insufficient semantics;
- revenue/profit/margin dimension guards;
- запрет чисел, identifiers, unsupported causes и directives;
- повторы, близкие повторы и action quality;
- required candidate coverage;
- canonical пользовательский result/headlines/relationships.

Baseline violations измеряются, но не блокируют review кандидата. Кандидат обязан иметь ноль
blocking violations.

## Слепая ручная оценка

Статус без записи:

```bash
python3 scripts/llm-eval/review.py status \
  --manifest scripts/llm-eval/dataset-v2.json \
  --responses-dir build/llm-eval/<immutable-run>/responses \
  --baseline v4 \
  --candidate v21
```

Подготовка immutable A/B packet:

```bash
python3 scripts/llm-eval/review.py prepare   --manifest scripts/llm-eval/dataset-v2.json   --responses-dir build/llm-eval/<immutable-run>/responses   --review-dir build/llm-eval/<immutable-run>/review
```

Reviewer не открывает `assignments.json` до полного заполнения `scores.json`. Для каждого ответа
выставляются 1–5 по пяти dimensions, required/forbidden findings и critical errors.

Финализация:

```bash
python3 scripts/llm-eval/review.py finalize   --manifest scripts/llm-eval/dataset-v2.json   --responses-dir build/llm-eval/<immutable-run>/responses   --review-dir build/llm-eval/<immutable-run>/review   --baseline v4   --candidate v21   --report build/llm-eval/<immutable-run>/decision-report.json   --markdown build/llm-eval/<immutable-run>/decision-report.md
```

Integrity gate отдельно проверяет raw response SHA и соответствие packet backend-canonical response.
Кандидат проходит, только если каждый его ответ достигает `passAverage`, покрывает required
findings и не содержит forbidden findings/critical errors. Он также не должен регрессировать к
baseline по ручным и автоматическим метрикам.

`CANDIDATE_ELIGIBLE_FOR_CANARY` разрешает только отдельный canary одного периода. Он не включает
смену default prompt, publication, Telegram fanout или production deployment.

## Финальный сохранённый прогон v21

Локальные ignored-артефакты:

```text
build/llm-eval/v4-v21-full-20260819/responses/
build/llm-eval/v4-v21-full-20260819/automatic-report-final.json
build/llm-eval/v4-v21-full-20260819/review-final/
build/llm-eval/v4-v21-full-20260819/FINAL-v21-schema3-decision.json
build/llm-eval/v4-v21-full-20260819/FINAL-v21-schema3-decision.md
```

Файлы `blinded-decision-final.*` относятся к более ранней неуспешной ручной оценке и не являются
источником финального решения.

Итог: v21 26/26 automatic, 26/26 manual, 0 violations, 0 missing/forbidden/critical findings.
V4 сохранил 110 automatic violations и 11/26 manual pass как контрольный baseline.

## Добавление сценария

Новый случай добавляется только вместе с:

- обезличенным входом без имён, UUID и production identifiers;
- required, acceptable и forbidden findings;
- тематическими tags;
- candidate/relationship/limitation expectation;
- regression-тестом, если вводится новое автоматическое правило.

Dataset не содержит «идеальный ответ модели»: источником истины являются facts, backend policy и
экспертные смысловые ожидания.
