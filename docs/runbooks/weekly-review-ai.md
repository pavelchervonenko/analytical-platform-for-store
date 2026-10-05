---
doc_schema: 1
doc_type: runbook
status: draft
owner: ai
audience:
  - operator
last_verified: 2026-09-20
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
  - backend/src/main/java/com/storeanalytics/interpretation/web/SellerWeeklyReviewController.java
  - backend/src/main/java/com/storeanalytics/interpretation/web/WeeklyReviewAiOperationsController.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiOperatorService.java
  - deploy/bin/weekly-review-ai-release-safety.sh
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationConfiguration.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyPreparationScheduler.java
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
production deployment. Перед любым canary проверить фактические generation/planner/worker
flags по новой sanitized runtime-проверке и сверить её с
[project-state](../current/project-state.md); старый POST нельзя считать защищённым
новым contract.

Статус остаётся `draft`: локальная статическая проверка не заменяет reusable staging rehearsal,
production read-only evidence и отдельное разрешение exact canary с известной стоимостью.

## Влияние и требуемая авторизация

Потерявший lease worker не может начать provider attempt; recovery выполняет штатный lifecycle,
а не ручная правка статуса. Heartbeat после истечения lease не является восстановлением права
на запрос. При диагностике повторного запуска сначала сверить job/attempt/receipt, не выполнять
enqueue заново из-за отсутствия немедленного результата. Уточнение будущего автоматического
режима и необходимых retries: [ADR-0005](../decisions/ADR-0005-weekly-ai-activation-and-retries.md),
пакеты rollout: [production-план](../maintenance/weekly-ai-production-automation-plan.md).

При UNKNOWN provider outcome или LEASE_EXPIRED после первой attempt сохранять terminal job
и проверять receipt/стоимость. Не создавать новый job для обхода остановки. Retry cap применяется
к известному retryable отказу и semantic rejection внутри той же задачи, с backoff; исходное
exact approval продолжает ограничивать число разрешённых attempts.

Для кандидата с отдельным `weekly_review_ai_response_receipts` проверять также receipt по
exact attempt ID: terminal UNKNOWN attempt может иметь позднюю RESPONSE receipt без enrichment.
Это свидетельство уже полученного ответа, не право оживить job или вызвать ИИ заново.
Известная RUB-стоимость учитывается однократно; при отсутствующей стоимости сохраняется резерв
оценки. Диагностика выводит только IDs, outcome/validation codes, times и billing metadata —
никогда response/input payload. `VALIDATION_EXECUTION_FAILED` требует исправления валидатора,
а не автоматического платного повторения. До выпуска новой migration нужны rehearsal на
заполненной БД, grants для runtime role и проверка несовместимости старого executable со схемой.
Runtime table/contract availability проверяется отдельно; старую production-базу этими запросами
не считать уже обновлённой.

- Операция пишет job/attempt/enrichment; enrichment после публикации не удаляется.
- Внешний эффект — платный outbound request в YandexGPT.
- Нужны operator approval точного snapshot и отдельное явное approval exact case/payload hash,
  максимального числа вызовов и верхней стоимости.
- Privacy reviewer подтверждает store-only schema verdict и принимает остаточное ограничение:
  общего PII scrubber для backend-owned подписей пока нет.

## Предусловия

### Дополнительные проверки seller-контракта

Исторический candidate reader сам по себе не даёт разрешения публикации. До temporal cutover
проверить v4 attach policy: исторический путь не поддерживает legacy v3 fallback. Новые views
`seller_attach_item_facts_v1`, `seller_attach_ordinary_facts_v1` и `seller_attach_reviewed_facts_v1`
требуют runtime SELECT grants и rehearsal на заполненной БД; прежние attach views/allocations
должны дать прежние значения. Проверить readiness старого executable после миграции: добавление
view не доказывает совместимость schema-version guard. Для обычных позиций membership берётся
на дату их операции, для гарантийных allocations/base — целевой продажи. UNKNOWN истории/автора
не обходить включением текущего roster. Baseline должен покрывать обе сравниваемые недели.
Combined facts, historical identity и opt-in snapshot writer соединены free runner. Additive
period read и внутренний free planner реализованы; отдельный opt-in free scheduler подключён
к backlog, но historical paid planner ещё не подключён:
atomic paid-attempt/publication fence реализован в локальном candidate; перед включением нужны
его filled-DB concurrency/release acceptance и scheduler wiring по плану. Codec/frontend parsing поддерживают temporal basis; runtime activation
это не доказывает. Для старого периода проверять отсутствие future actions; departed card должна
сохранить исторические суммы и `actionableNow=false`.

Opt-in preparation backlog требует grants для `seller_weekly_backlog_state` и
`seller_weekly_preparation_jobs`, upgrade rehearsal и отдельного согласованного подключения
scheduler. Migration не создаёт baseline, задания, snapshots или provider calls. Discovery
запускается только после явного per-store baseline и ждёт полноты обеих недель; отсутствие
baseline не обходить подстановкой даты старой синхронизации. Cursor ограниченно продвигается,
задания прошлых недель остаются в БД независимо от новых границ календаря.
При изменении timezone получить `BACKLOG_CONFIGURATION_CHANGED` — ожидаемая безопасная
остановка. Не переписывать cursor/job timezone вручную: требуется отдельная reviewed операция
перехода, сохраняющая старые границы периодов. Для read-only диагностики достаточно store/week,
status, next_evaluation_at, lease_until, preparation_attempt_count, last_reason_code и snapshot_id;
не выводить report/provider payloads. `WAITING_SOURCES`/`WAITING_HISTORY` — бесплатное ожидание,
`FAILED` — technical intervention, `SUCCEEDED` — подготовка snapshot, не оплата или публикация AI.
Истёкший lease можно только reclaim с новым token; старый owner/token не должен завершать задачу.
Устаревший `SUCCEEDED` возвращается в бесплатную подготовку bounded refresh (1–100 jobs),
с тем же ID/store/week. Это не разрешение повторять provider job и не удаление опубликованного
snapshot/enrichment; paid counters остаются независимыми.
Существующая canary-команда не подключает backlog. Free runner подготавливает одну claimed неделю,
не создаёт baseline/AI job и не вызывает provider. Отдельный free scheduler вызывает runner
только по dedicated opt-in flag. Он использует historical
assembler с отдельной identity, перепроверяет source/membership revision под locks и exact binding.
Source change даёт отложенную бесплатную подготовку; contract/technical failure требует вмешательства.
Проверять sanitized reason/state, а не raw exception/provider payload. При повторном выполнении
равный semantic content сохраняет прежний snapshot ID/payload и обновляет совместимый checkpoint.
Free cutover использует `SELLER_WEEKLY_PREPARATION_ENABLED` (default false); parent и seller
weekly features обязательны. На API/MIGRATION scheduler не создаётся. При free cutover legacy
current-roster snapshot planner исключён; комбинация с действующим automatic current-roster paid
planner запрещена release preflight и startup. Не обходить эту защиту: historical paid planning
и UI/current reader ещё требуют reviewed подключения. Free flag не включает AI worker и не
разрешает платный вызов. Перед включением нужны локальные gates, filled-DB rehearsal, forward-only
baseline approval и отдельное production решение; документ не утверждает выполненный cutover.

Параметры free contour: `SELLER_WEEKLY_PREPARATION_SCAN_DELAY`, `_STORE_BATCH_SIZE`,
`_DISCOVERY_WEEKS`, `_REFRESH_BATCH_SIZE`, `_BATCH_SIZE`, `_TIME_BUDGET` с полным префиксом
`SELLER_WEEKLY_PREPARATION`. Defaults и безопасные bounds задаются properties/compose example,
не копируются из runtime state. Budget cooperative: не прерывает начатую подготовку.
Перед migration release preflight проверяет и числовые/временные bounds, даже при выключенном
free флаге. Для duration environment values применять целое число с единицей `ms`, `s`, `m` или
`h`; bare integers, ISO duration и выражения не поддерживаются operator preflight.
Short queue transactions ограничены 30 seconds. Перезапуск теряет лишь store sweep cursor в памяти, не
durable cursor/jobs. `SUCCEEDED` остаётся успехом бесплатной подготовки, не AI publication.
При новой неделе free refresh удаляет future-action horizon посредством новой immutable revision,
а не UPDATE старого payload. Отчёт без future actions не меняется только из-за новой недели.
До historical paid planner и runtime acceptance не выдавать free scheduler за готовый автообзор.

Candidate различает `EXACT` и `AUTOMATIC` jobs. Все прежние rows остаются `EXACT`; не менять их
origin вручную и не переносить exact approval на новую revision. Только неоплаченная automatic
job без active lease может принять CURRENT snapshot того же store/week: сохраняются ID, deadline,
provider/model и retry cap. Наличие любого attempt/receipt, UNKNOWN или published enrichment
запрещает такой refresh. Для zero-attempt source failure допускается только `SNAPSHOT_NOT_CURRENT`,
не прочие terminal failures. Deadline не продлевается; истёкшая job требует operator diagnosis.
Перед cutover проверить filled upgrade с сохранением old rows/receipts, DB unique key/trigger,
старый claim после rebind и гонку с startAttempt. Этот механизм ещё не включает historical paid
planner, baseline или production scheduler.

Исторический GET `/api/stores/{storeId}/weekly-reviews/seller-period?periodStart=YYYY-MM-DD`
проверяет закрытый Monday-start period и store authorization под прежними parent/seller feature
gates. Он не пишет данные и не вызывает ИИ. Нет historical snapshot — PREPARING, без fallback;
изменившаяся identity/coverage/stability — STALE с прежним immutable payload. Для CURRENT нужны
compatible/latest ID и актуальный checkpoint; после бесплатного semantic reuse embedded identity
старого payload не сравнивается напрямую с новой revision. Смена дня не требует переписывать
старый отчёт; истечение горизонта future actions требует free revision. Не считать ответ CURRENT
atomic разрешением provider call: worker повторяет проверку под отдельным source fence.
Free exact refresh historical snapshot не меняет одобренные ID/хеши и не открывает новый paid job.
Новая revision требует нового exact review, не повторного запуска старой команды. Endpoint/free
planner не активируют scheduler или baseline; локальный UI ещё не переключён на historical read.

Source fence candidate удерживает store/source locks только в коротких READ_COMMITTED операциях
startAttempt и completion, никогда во время provider network/free generation. Fence использует
transaction-only lock/statement timeouts, не меняет глобальные настройки БД. Worker execution
не наследует внешнюю транзакцию. Старый caller timestamp
не продлевает seller lease после ожидания budget/job locks. Stale source до start не создаёт attempt;
stale source после ответа даёт terminal SNAPSHOT_NOT_CURRENT без enrichment/automatic retry.
Receipt и известный расход сохраняются независимо. Не повторять canary из-за отсутствия enrichment:
сначала read-only проверить job/attempt/receipt и exact freshness. Технический сбой fence безопасно
запрещает публикацию, а не означает нулевую стоимость. CURRENT read остаётся диагностикой,
не заменяет runtime/release acceptance или точное разрешение.

При seller cutover проверять version/scope exact snapshot, а не считать прежний STORE preflight
разрешением нового input. Seller API: GET `/api/stores/{storeId}/weekly-reviews/seller-current`,
ADMIN POST `/api/admin/seller-weekly-reviews/stores/{storeId}/generate`. Parent weekly-review и
`app.interpretation.seller-weekly-review.enabled` должны быть согласованы; недопустимая комбинация
отвергается на startup. Production/staging activation и платный provider canary требуют отдельной
авторизации независимо от доступности candidate-кода.

- В seller mode exact snapshot имеет contract3/scope SELLERS и `CURRENT`; PREPARING/STALE/BLOCKED
  не дают разрешения на AI job. Дополнительно проверить continuous SUCCESS coverage
  SALES/RETURNS/ORDERS за обе недели и отсутствие незавершённых/не reconciled source writes.
- После upgrade до V87 прежний seller checkpoint намеренно становится STALE: V86 изменила
  attach-проекцию. Дождаться штатного seller planner либо через локально/операторски разрешённый
  ADMIN generate получить новую проверенную snapshot; не запускать AI по старой revision и не
  считать STALE подтверждением порчи данных. Если coverage неполный, сохранить ожидание и
  восстановить источник вместо обхода gate.
- Seller preflight возвращает prompt v26/input5, `PASS_SELLER_ONLY_SCHEMA`, без employee scope/raw
  input. Legacy v2 сохраняет prompt v25/input4 и прежний STORE verdict. Чужой cache не применяется.
- Backend оставляет immutable provider receipt при stale-after-response, но не enrichment;
  `SNAPSHOT_NOT_CURRENT` не повторяет платный вызов для того же устаревшего input.
- Rollback выполняется на совместимом executable выключением seller read/write preference;
  v3 snapshots/enrichments/checkpoints не удалять. Старый endpoint читает только v2 с явной STORE
  подписью; общая revision chain сохраняется. После rollback worker выбирает legacy prompt jobs,
  а seller jobs не переименовываются. Повторное включение должно снова пройти freshness gates.
- Local fixture visual review не заменяет authenticated backend parity, полный backend check,
  offline seller AI/privacy review и отдельное canary решение. Не записывать real provider payload,
  имена, финансовые значения или business screenshots в evidence.

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
- Worker пытается обработать job вне активной пары prompt/schema: v25/schema4 в legacy mode
  или v26/schema4 в seller mode. Переключение режима не переименовывает ожидающие jobs.
- Появился любой неожиданный notification event: v25 не должен создавать weekly Telegram event.

## Preflight

После изменения quality-семантики сначала сформировать новую deterministic snapshot revision без
provider-вызова и проверить `qualityPolicy=weekly-quality-v8`, report state и limitations. Старую
revision/enrichment не удалять и не переписывать. Для payment/cash событий дополнительно проверить:

- payment mismatch остаётся видимым в quality read path, но отсутствует в limitations
  `NET_REVENUE`;
- cash mismatch исключён из limitations только при точном равенстве active app payments и latest
  raw `detail.cash`; отсутствие raw evidence сохраняет limitation;
- unexpected zero-cost виден как `INFO` и не переводит snapshot в `PARTIAL`.

Только после этой network-free проверки разрешено строить exact AI preflight для новой revision.

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
