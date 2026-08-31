---
doc_schema: 1
doc_type: runbook
status: draft
owner: ai
audience:
  - operator
last_verified: 2026-08-31
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - local
operation_type: reversible-write
environments:
  - local
  - test
  - staging
risk_level: medium
source_of_truth:
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiShadowRunner.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiOfflineEvaluator.java
  - scripts/weekly-review-ai-eval/review.py
  - scripts/weekly-review-ai-eval/manifest-v6.json
verification_evidence:
  - level: static
    scope: offline, paid-shadow, manifest and blind-review controls reviewed
    verified_at: 2026-08-31
    evidence: docs/current/ai/runtime-artifacts.md
required_reviewers:
  - ai-semantic
  - security-privacy
review_triggers:
  - evaluation-runner-change
  - evaluation-manifest-change
  - provider-pricing-change
  - paid-call-policy-change
supersedes: []
superseded_by: null
---

# AI evaluation: offline и платный semantic shadow

## Цель и область

Процедура проверяет новый AI candidate до canary. Offline этап не использует сеть и деньги. Paid
shadow разрешён только для exact обезличенного case с отдельной явной авторизацией и cap. Результат
`CANDIDATE_ELIGIBLE_FOR_CANARY` не включает production flags и не является deploy approval.

## Влияние и требуемая авторизация

Offline — local build artifacts. Paid shadow передаёт outbound payload provider-у, расходует деньги
и сохраняет provider response в локальный build directory. Нужны ai-semantic и privacy review, а
также явная авторизация exact case, model, payload hash, max calls и max cost.

## Предусловия

- Рабочее дерево и exact commit зафиксированы.
- Manifest hashes совпадают.
- Payload проверен как обезличенный; employee scope/PII отсутствуют.
- Model URI versioned и не оканчивается на `/latest`.
- Output directory новый, находится только под `build/weekly-review-ai-eval/` и не tracked.
- В authorisation указан верхний cap; исторический cap/остаток не переносится.

## Секреты и безопасный вывод

Нельзя вводить значение в `export YANDEX_AI_API_KEY='<value>'`: команда остаётся в shell history,
а secret environment доступен процессам того же пользователя. Текущий runner принимает только
`YANDEX_AI_API_KEY`; безопасный file-input/wrapper, аналогичный `scripts/llm-eval/shadow.sh`, для
него не реализован. Поэтому платный запуск остаётся NO-GO. Целевой wrapper должен принимать путь к
root/user-owned `0600` файлу, работать с `set +x`, передавать key только дочернему Java-процессу
и очищать переменную. Provider JSON остаётся локальным audit artifact и не добавляется в Git.

## Критерии остановки

- Manifest drift, dirty runtime artifact или stale build.
- Нет отдельной авторизации либо cap не выражен числом/валютой.
- Payload содержит имя, телефон, email, token, employee data или свободную заметку.
- Exact outbound payload нельзя получить и проверить до платного вызова.
- Model mutable, price zero/unknown или preflight не рассчитывает максимум.
- Runner планирует больше разрешённых calls либо output directory уже использован.
- Blind reviewer получил доступ к assignments/provider selectors до фиксации score.

## Offline preflight

```bash
./gradlew :backend:test --tests '*WeeklyReviewAiOfflineEvaluatorTest'
./gradlew :backend:weeklyReviewAiShadow
python3 -m unittest scripts/weekly-review-ai-eval/test_review.py
```

Проверить plan: cases, request hashes, model version, estimated maximum per case и total. В plan
режиме сеть и платные вызовы запрещены.

Текущий plan печатает только hashes/cost и не материализует exact outbound input. Поэтому privacy
review не может доказать содержимое по одному plan output. До добавления read-only protected
`plan-output` либо независимой сборки exact payload с совпадающим hash paid execute по этому
runbook имеет статус **NO-GO**.

## Точный target и авторизация

Перед execute change record должен содержать:

- exact commit и manifest path/hash;
- case ID/offset и input hash;
- provider/model version;
- `maxPaidCalls`;
- `maxCostRub` для этого запуска;
- output directory;
- privacy verdict;
- явную фразу approver-а, разрешающую этот exact scope.

## Платная процедура

Исполняемой команды сейчас нет: exact-payload preflight и secret-file wrapper не реализованы.
После их появления процедура должна принимать approved values через wrapper, сверять exact payload
hash и только затем выставлять execute confirmation дочернему процессу. До обновления
`source_of_truth`, static tests и этого runbook запуск `WEEKLY_REVIEW_AI_EVAL_MODE=execute`
напрямую запрещён.

Не расширять case count/cap после approval. При preflight отказе нельзя обходить guard изменением
manifest, цены, output path или ручной передачей API key.

## Blind review

1. Выполнить `review.py status` с exact manifest и responses directory.
2. Выполнить `review.py prepare` в новый `blind-review` directory.
3. Reviewer открывает только `packet.json` и фиксирует `scores.json`.
4. `assignments.DO_NOT_OPEN.json` не открывать до завершения scores.
5. Выполнить `review.py finalize` и сохранить immutable decision artifact.

Gate: каждый критерий не ниже 3/5, средняя не ниже 4/5, все required findings, ни одного forbidden
finding и critical error.

## Проверка результата

Сверить receipt: exact versions/hashes, token counts, фактическую стоимость и validation outcome.
Фактическая стоимость не превышает cap, calls — approved count, renderer output прошёл schema4 и
blind gate.

## Повторный запуск и конкурентность

Каждый execute получает новый output directory и новую authorization. Нельзя объединять остаток cap
двух approvals. Retry после uncertain provider outcome считается новым платным вызовом и требует
доступного approved call count; иначе stop.

## Rollback или forward-fix

Provider call необратим по стоимости и раскрытию payload. Локальные build artifacts можно удалить
после retention decision, но immutable evaluation evidence, использованное для canary, сохраняется
в обезличенном виде. Неудачный candidate исправляется новой versioned итерацией.

## Evidence

В Git можно поместить только sanitized decision: commit, manifest/hash, case IDs, aggregate
scores, token/cost totals и verdict. Provider payload/response, secrets и reviewer assignment map
не публиковать.

## Репетиция

- Достигнут только `static`.
- До `current` нужен clean local offline run и один полностью задокументированный paid shadow с
  exact authorization/cap, privacy PASS и blind review.
