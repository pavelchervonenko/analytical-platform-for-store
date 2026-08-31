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
  - staging
  - production-read-only
operation_type: reversible-write
environments:
  - test
  - staging
  - production
risk_level: high
source_of_truth:
  - backend/src/main/java/com/storeanalytics/interpretation/web/WeeklyReviewOperationsController.java
  - backend/src/main/java/com/storeanalytics/interpretation/web/WeeklyReviewAiOperationsController.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiOperatorService.java
  - deploy/bin/weekly-review-ai-release-safety.sh
verification_evidence:
  - level: static
    scope: API, lifecycle, budget, validation and immutable publication paths reviewed
    verified_at: 2026-08-31
    evidence: docs/current/ai/weekly-review.md
required_reviewers:
  - ai-semantic
  - security-privacy
  - operations
review_triggers:
  - ai-contract-change
  - weekly-review-operations-change
  - provider-budget-change
  - production-flag-change
supersedes: []
superseded_by: null
---

# Генерация и canary Weekly Review AI

## Цель и область

Процедура предназначена для одного exact weekly-review snapshot. Она создаёт durable AI job,
выполняет ограниченный provider-вызов и проверяет опубликованный immutable enrichment. Процедура не
включает массовый planner, не меняет KPI и не разрешает платные вызовы без отдельной авторизации.

**Текущий authorization status: NO-GO для платного canary.** Admin API не предоставляет read-only
preview exact compacted input/hash и отсутствие конфликтующего job до enqueue: POST сразу создаёт
job. Поэтому privacy/cost approval до записи и будущего provider-вызова сейчас недоказуем.

Статус остаётся `draft`: кроме этого NO-GO gap, нет сохранённого reusable staging rehearsal и
production read-only evidence для этой версии процедуры.

## Влияние и требуемая авторизация

- Операция пишет job/attempt/enrichment; enrichment после публикации не удаляется.
- Внешний эффект — платный outbound request в YandexGPT.
- Нужны operator approval точного snapshot и отдельное явное approval exact case/payload hash,
  максимального числа вызовов и верхней стоимости.
- Privacy reviewer подтверждает, что input обезличен и не содержит employee scope/PII.

## Предусловия

- Exact release/runtime state прочитан из [project-state](../current/project-state.md), а не из
  старого rollout-документа.
- Snapshot принадлежит нужному магазину и завершённой неделе, имеет `READY` или `PARTIAL`.
- Content hash snapshot сохранён до enqueue.
- Нет existing enrichment с другой семантикой для той же пары snapshot/prompt/schema.
- Offline contract/evaluation gates зелёные для exact commit.
- Provider model version фиксирована; mutable `/latest` запрещён.

## Секреты и безопасный вывод

Provider credential читается только из secret store/config tree. Не печатать environment, model
credential components, provider input/response, store/employee payload. Evidence ограничить IDs,
versions, hashes, status, attempt count, validation codes, token counts, cost и timestamps.

## Критерии остановки

- Snapshot `BLOCKED`, неизвестен его store/period или content hash изменился.
- Нет явной авторизации стоимости/обезличенного payload.
- Exact compacted input/hash и конфликтующий job нельзя проверить read-only до enqueue.
- Provider preflight, budget, context, schema или privacy gate не прошёл.
- Для snapshot уже существует несовместимый job/enrichment.
- Worker обрабатывает не активную пару v25/schema4.
- Появился любой неожиданный notification event: v25 не должен создавать weekly Telegram event.

## Preflight

```bash
./gradlew :backend:test --tests '*WeeklyReviewAi*'
python3 -m unittest scripts/weekly-review-ai-eval/test_review.py
./gradlew :backend:weeklyReviewAiShadow
```

Последняя команда выполняется только в network-free plan mode. Затем operator через authenticated
admin read path фиксирует exact snapshot ID/hash/report state. Текущий API не показывает compacted
input и не предоставляет pre-enqueue job check; поэтому preflight останавливается здесь.

## Точный target

До записи сохранить:

- environment и release evidence link;
- store ID без названия/персональных данных;
- period start/end;
- snapshot ID, revision, content hash и report state;
- prompt/input/selection/content versions;
- approved max calls и cost cap;
- approver и timestamp в закрытом change record.

## Процедура

Исполняемой paid-процедуры сейчас нет. До её появления нужно реализовать authenticated read-only
preflight, который для exact snapshot возвращает canonical input hash, безопасный preview/privacy
verdict, active version pair, budget maximum и отсутствие conflicting job без enqueue. После этого
runbook должен быть дополнен проверенным request wrapper, staging rehearsal и только затем
операциями enqueue/status/readback.

`POST /api/admin/weekly-review-ai/snapshots/{snapshotId}/generate` до закрытия gate вручную не
вызывать: он сразу создаёт job. Raw cookie/token нельзя помещать в команды, shell history или
evidence.

## Проверка результата

Успех требует одновременно:

- job `SUCCEEDED` в допустимом числе attempts;
- validation violations пусты;
- enrichment hash стабилен при повторном чтении;
- deterministic facts/evidence/actions не изменились, менялось только разрешённое wording;
- cost не превысила exact approval;
- в event queue нет созданного этой операцией weekly Telegram event.

Для `PARTIAL` итог обязан содержать ограничение доступности данных.

## Повторный запуск и конкурентность

Enqueue по snapshot/prompt/schema идемпотентен на уровне job uniqueness. Не создавать параллельные
jobs вручную. Повторная запись того же enrichment допустима только при совпадении input/content
hashes; конфликт означает stop и расследование.

## Rollback или forward-fix

Immutable enrichment не откатывается и не удаляется. Безопасный operational fallback — отключить
использование AI-layer по утверждённому release-процессу; deterministic weekly-review останется.
Исправление семантики создаёт новую prompt/version или новый snapshot/enrichment.

## Evidence

Сохранить отдельный immutable canary record: exact commit/release, snapshot/job IDs, hashes,
versions, statuses, validation codes, token/cost totals, до/после backend-owned comparison и scope
вердикта. Не сохранять provider payload или секреты.

## Репетиция

- Достигнут только `static`.
- До `current` обязательны staging rehearsal платного вызова и production read-only preflight.
- Canary одного магазина/недели не доказывает массовую автоматизацию.
