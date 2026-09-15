---
doc_schema: 1
doc_type: runbook
status: draft
owner: ai
audience:
  - operator
last_verified: 2026-09-15
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
    scope: Exact preflight, approval, lifecycle, budget, validation and immutable publication paths reviewed
    verified_at: 2026-09-15
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

**Текущий authorization status: NO-GO для платного canary.** В candidate реализован read-only
preflight и exact approval contract, но они ещё не прошли release review, staging rehearsal и
production deployment. До этого production pilot.32 остаётся с выключенными generation/planner/
worker flags, а старый POST нельзя считать защищённым новым contract.

Статус остаётся `draft`: локальная статическая проверка не заменяет reusable staging rehearsal,
production read-only evidence и отдельное разрешение exact canary с известной стоимостью.

## Влияние и требуемая авторизация

- Операция пишет job/attempt/enrichment; enrichment после публикации не удаляется.
- Внешний эффект — платный outbound request в YandexGPT.
- Нужны operator approval точного snapshot и отдельное явное approval exact case/payload hash,
  максимального числа вызовов и верхней стоимости.
- Privacy reviewer подтверждает store-only schema verdict и принимает остаточное ограничение:
  общего PII scrubber для backend-owned подписей пока нет.

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
- Preflight не возвращает exact input/request hashes либо сообщает existing job/enrichment.
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
admin read path вызывает:

```text
GET /api/admin/weekly-review-ai/snapshots/{snapshotId}/preflight
```

Endpoint не делает provider-вызов и не создаёт job/enrichment. Он должен вернуть:

- exact snapshot ID/revision/period/content hash и `READY` либо `PARTIAL`;
- active prompt/input/selection/content versions;
- `privacy.verdict=PASS_STORE_ONLY_SCHEMA`, `employeeScopeIncluded=false` и
  `rawInputIncluded=false`;
- provider code, безопасную model version, canonical input/request hashes, token/context limits и
  верхнюю стоимость;
- `existing.jobStatus=NONE`, пустой enrichment и `approvalEligible=true`;
- `providerCredentialCheck=WORKER_ONLY`.

Последнее значение ожидаемо: API container не получает provider key. Его наличие проверяется
отдельным root read-only runtime audit, а worker повторно валидирует credential перед outbound
request. Preflight не является доказательством provider availability или quota.

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

1. Сохранить sanitized preflight response в закрытый change record и подтвердить, что exact
   snapshot/hash всё ещё соответствуют выбранному магазину и периоду.
2. Получить отдельное явное approval, в котором указаны snapshot ID, snapshot/input/request hashes,
   `approvedMaximumProviderCalls=1`, exact `approvedMaximumTotalCost` из preflight и `RUB`.
3. Через authenticated admin client отправить:

   ```text
   POST /api/admin/weekly-review-ai/snapshots/{snapshotId}/generate
   Content-Type: application/json

   {
     "snapshotContentHash": "<preflight snapshot.contentHash>",
     "inputHash": "<preflight request.inputHash>",
     "requestHash": "<preflight request.requestHash>",
     "approvedMaximumProviderCalls": 1,
     "approvedMaximumTotalCost": <preflight budget.estimatedMaximumCostPerCall>,
     "costCurrency": "RUB"
   }
   ```

4. Сохранить возвращённый job ID и читать его через
   `GET /api/admin/weekly-review-ai/jobs/{jobId}` до terminal state, не создавая второй job.
5. После `SUCCEEDED` прочитать Weekly Review штатным пользовательским endpoint и выполнить проверки
   ниже.

Пустой body должен вернуть `428 Precondition Required`. Любое изменение snapshot/request/budget
между preflight и POST должно вернуть `412 Precondition Failed` без enqueue. Пока candidate не
выпущен и не отрепетирован, production POST вручную не вызывать. Raw cookie/token, полный provider
input/response и credentials нельзя помещать в команды, shell history или evidence.

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

Approved enqueue блокирует exact snapshot row и повторно проверяет отсутствие job/enrichment;
уникальность snapshot/prompt/schema закрывает оставшуюся гонку. Не создавать параллельные jobs
вручную. Повторная запись того же enrichment допустима только при совпадении input/content hashes;
конфликт означает stop и расследование.

## Rollback или forward-fix

Immutable enrichment не откатывается и не удаляется. Безопасный operational fallback — отключить
использование AI-layer по утверждённому release-процессу; deterministic weekly-review останется.
Исправление семантики создаёт новую prompt/version или новый snapshot/enrichment.

## Evidence

Сохранить отдельный immutable canary record: exact commit/release, snapshot/job IDs, hashes,
versions, statuses, validation codes, token/cost totals, до/после backend-owned comparison и scope
вердикта. Не сохранять provider payload или секреты.

## Репетиция

- Достигнут только `static`: candidate preflight/approval contract прошёл локальные unit,
  authorization, PostgreSQL concurrency и OpenAPI compatibility проверки.
- До `current` обязательны release review, staging rehearsal платного вызова и production
  read-only preflight exact deployed commit.
- Canary одного магазина/недели не доказывает массовую автоматизацию.
