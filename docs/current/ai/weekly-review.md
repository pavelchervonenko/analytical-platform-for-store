---
doc_schema: 1
doc_type: current
status: current
owner: ai
audience:
  - developer
  - operator
  - manager
last_verified: 2026-09-15
requirement_sources:
  - docs/archive/legacy-contracts/AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md
  - docs/archive/legacy-contracts/weekly-review-ai-management-rubric.md
implementation_sources:
  - frontend/src/insights/InsightsPreviewPage.tsx
  - frontend/src/insights/WeeklyReviewView.tsx
  - frontend/src/insights/weekly-review-presentation.ts
  - frontend/src/insights/weekly-review/WeeklyReviewContent.tsx
  - frontend/src/insights/weekly-review/ReviewDetailPanel.tsx
  - frontend/src/insights/weekly-review/weeklyReviewViewModel.ts
  - frontend/src/insights/weekly-review/weekly-review.css
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewAssembler.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewCoreProjector.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewQualityPolicyV1.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewSummaryPresenter.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewSnapshotStore.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewTeamEmployeeProjector.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiContract.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiOperatorService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiPreflightView.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiInputCompactor.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiSemanticValidator.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiRendererV25.java
  - backend/src/main/resources/db/migration/V46__add_weekly_review_ai_enrichments.sql
  - backend/src/main/resources/db/migration/V47__add_weekly_review_ai_generation_jobs.sql
  - backend/src/main/resources/db/migration/V48__harden_weekly_review_rollout.sql
verification_sources:
  - frontend/src/insights/WeeklyReviewView.test.tsx
  - frontend/src/insights/weekly-review/weeklyReviewViewModel.test.ts
  - frontend/src/insights/weekly-review-presentation.test.ts
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewAssemblerTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewSnapshotStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewTeamEmployeeProjectorTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewResponseContractTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiSchemaContractTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiSemanticValidatorTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiRendererV25Test.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiCompletionServiceIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiOperatorServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiJobStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/web/WeeklyReviewAiOperationsSecurityIntegrationTest.java
runtime_evidence:
  - docs/history/audits/2026/09/WEEKLY_REVIEW_LOCAL_PRERELEASE_2026-09-14.md
  - docs/history/releases/2026/09/v0.1.0-pilot.33-production-verification.md
required_reviewers:
  - ai-semantic
  - backend-data
  - security-privacy
review_triggers:
  - ai-contract-change
  - weekly-review-schema-change
  - weekly-review-publication-change
  - provider-payload-change
supersedes: []
superseded_by: null
---

# Weekly Review v25/schema4

## Назначение и границы

Weekly Review — основной контракт страницы «ИИ-разбор». Он отделяет расчёт фактов от AI:
детерминированная часть строит полный отчёт и evidence, а optional AI-layer выбирает только
редакционные selector-ы. AI не пересчитывает KPI и не создаёт новые действия или числа.

Документ не утверждает, что planner или worker включены в конкретном окружении. Это проверяется по
[`project-state.md`](../project-state.md).

## Контур

```text
weekly-review facts
        ↓
deterministic weekly_review_snapshot
        ↓
bounded store-only provider input
        ↓
YandexGPT selector response
        ↓
structural + semantic validation
        ↓
backend-owned schema4 rendering
        ↓
immutable weekly_review_ai_enrichment
        ↓
GET /api/stores/{storeId}/weekly-reviews/current
```

Snapshot формируется отдельно от AI. Отчёт остаётся доступным в детерминированном виде, если AI
выключен, задержан, недоступен или ответ не прошёл проверку.

Персональный блок сотрудников использует тот же roster продавцов, что и рейтинг: активный
сотрудник, активное назначение и `participatesInRanking=true`. Сотрудники вне рейтинга не попадают
ни в персональные карточки Weekly Review, ни в командный benchmark. Для появления сотрудника в
карточках также нужна активность хотя бы в одном из двух сравниваемых недельных периодов.

### Roster и исторические snapshots

Roster вычисляется во время формирования snapshot, а не при каждом открытии страницы. Активностью
считается хотя бы одна завершённая продажа, ненулевая чистая выручка или смена в текущей либо
предыдущей неделе. Сотрудник, добавленный после отчётной недели и не имеющий активности в обеих
неделях, в такой отчёт не попадает даже после включения флага рейтинга.

Payload snapshot, включая состав и имена сотрудников, хранится неизменяемо. Изменение назначения,
активности или `participatesInRanking` не переписывает уже сохранённый отчёт и не фильтрует его на
read path. Поэтому историческая revision может содержать сотрудника, который сейчас исключён из
рейтинга, либо не содержать сотрудника, добавленного позднее.

Повторная генерация той же завершённой недели создаёт следующую immutable revision только если
содержимое изменилось; при том же content hash возвращается существующая revision. После начала
новой недели обычная генерация нацелена уже на последнюю завершённую неделю — произвольный
исторический период endpoint не принимает. Включён ли автоматический planner в production,
фиксируется только в [`project-state.md`](../project-state.md).

## Активный контракт

| Элемент | Версия | Источник |
|---|---|---|
| Prompt | `weekly-interpretation-v25` | [`weekly-interpretation-v25.md`](../../prompts/weekly-interpretation-v25.md) |
| Provider input | schema 4 | [`weekly-review-ai-input-v4.schema.json`](../../schemas/weekly-review-ai-input-v4.schema.json) |
| Provider output | selection schema 1 | [`weekly-review-ai-selection-v1.schema.json`](../../schemas/weekly-review-ai-selection-v1.schema.json) |
| Published content | schema 4 | [`weekly-review-ai-content-v4.schema.json`](../../schemas/weekly-review-ai-content-v4.schema.json) |
| Deterministic metrics policy | `weekly-metrics-v7` | `WeeklyReviewPolicyV1` |
| Deterministic snapshot policy | `weekly-snapshot-v13` | `WeeklyReviewPolicyV1` |
| Data-quality policy | `weekly-quality-v7` | `WeeklyReviewPolicyV1` |

Backend читает опубликованные schema4 enrichments в порядке `v25`, `v24`, `v23`, `v22`. Worker
создаёт только активную пару `v25/schema4`. Read compatibility не означает, что старые версии
снова допустимы для генерации.

### Детерминированное управленческое представление

`WeeklyReviewSummaryPresenter` формирует один категориальный итог: неделя лучше, слабее, изменилась
разнонаправленно либо существенно не изменилась. Категория учитывает все материальные KPI со
состоянием `READY`; ограниченные и недоступные KPI не меняют направление вывода. Итог не повторяет
точные значения четырёх KPI; при наличии материального фактора он добавляет одну главную зону
внимания, а при её отсутствии — один положительный сигнал. `summary.effect` описывает общий
результат, а не тон добавленного фактора.

Корневое действие берётся из первого элемента backend-списка и должно однозначно соответствовать
фактору по `metricCode` и evidence. Оно остаётся проверкой, а не сохраняемой задачей: API уже
передаёт операцию, числовой ориентир, горизонт и способ проверки.

Presentation model не повторяет однозначно связанный с корневым действием фактор второй
равноправной карточкой в `Что изменилось`: числа и evidence остаются доступны через основание
действия, а остальные материальные факторы сохраняются в списке. Если связь неоднозначна, frontend
ничего не скрывает. Полное отсутствие исходных факторов и отсутствие только вторичных факторов —
разные состояния: во втором случае секция скрывается без ложного сообщения о спокойной неделе.
Deterministic действие по росту возвратов сформулировано как операция менеджера —
`Проверить чеки и причины возвратов`. Изменение persisted wording выпущено новой policy
`weekly-snapshot-v12`. Бизнес-правила по нулевой себестоимости и возвратам без доступной исходной
продажи выпущены новой policy `weekly-snapshot-v13`; прежние immutable snapshots не
переписываются и остаются читаемыми.

В персональном блоке чистая выручка означает вклад сотрудника. `peerComparison` означает только
сравнение выручки в час с медианой минимум трёх сотрудников, у которых достаточно продаж, смен и
часов в обеих сравниваемых неделях. Старый сохранённый payload с
`peerComparison.metricCode=NET_REVENUE` принимается frontend для совместимости, но не показывается
как сравнение эффективности. Frontend дополнительно требует `participatesInBenchmark=true` и
готовую достаточную метрику `REVENUE_PER_HOUR`; несовместимый peer payload не отображается.

Незаполненные или недостаточные смены ограничивают только `SHIFT_COUNT`, `WORKED_HOURS` и
`REVENUE_PER_HOUR`. Такие workload-метрики получают адресное состояние `UNAVAILABLE`/`LIMITED`, но
сами по себе не переводят сотрудника, команду или весь отчёт в `LIMITED`/`PARTIAL`, не создают
сотруднику приоритет и не порождают действие. Достаточные sales-выводы при этом сохраняются.

## Provider boundary

`WeeklyReviewAiInputCompactor` принимает только `READY` или `PARTIAL` report и проецирует:

- store-level summary outcome;
- store-level factors и список допустимых selector-ов;
- store-level actions с backend-owned `title`, `check` и evidence references;
- только доступные store-level evidence values.

Employee scope и employee public IDs в input запрещены. Модель возвращает selector-ы для summary и
каждого фактора. Она не возвращает свободный пользовательский текст, KPI, action title/check или
новые evidence references.

### Operator preflight и exact approval

Authenticated admin endpoint
`GET /api/admin/weekly-review-ai/snapshots/{snapshotId}/preflight` строит exact provider request
network-free и без enqueue. Он доступен при выключенных generation/worker flags, чтобы решение о
включении не требовало предварительной записи. Ответ содержит только:

- snapshot/store IDs, revision, завершённый период, report state и content hash;
- активные prompt/input/selection/content versions;
- provider code и конечный сегмент versioned model URI, но не folder ID или полный URI;
- canonical input/request hashes, размеры bounded input и верхнюю оценку tokens/cost;
- текущий daily cost, технические limits и состояние exact job/enrichment;
- структурный privacy verdict `PASS_STORE_ONLY_SCHEMA`.

Ответ не содержит compacted input, названия магазина, employee scope, имена, provider response или
credentials. Privacy verdict доказывает только store-only форму schema4: отдельного общего scrubber
для backend-owned подписей всё ещё нет, поэтому verdict не заменяет security/privacy approval.

API runtime намеренно не получает provider API key. Поле `providerCredentialCheck=WORKER_ONLY`
означает, что preflight проверил schema, versioned model, context и budget, а наличие credential
проверяется отдельным root read-only audit и повторно worker-ом перед outbound request.

`POST /api/admin/weekly-review-ai/snapshots/{snapshotId}/generate` требует body с exact snapshot,
input и request hashes из свежего preflight, одобренным числом provider calls, точной верхней
стоимостью и валютой. Пустой body возвращает `428`; устаревшее или конфликтующее approval — `412`.
Snapshot row lock и уникальность job закрывают гонку между повторными enqueue. Для первого canary
разрешается только один provider call, даже если технический предел конфигурации выше.

## Validation и rendering

1. Input сериализуется канонически, проверяется packaged input schema и получает SHA-256.
2. Provider response проверяется selection schema.
3. Semantic validator требует точный набор факторов, разрешённые selector-ы и корректные роли
   positive/negative focus.
4. `WeeklyReviewAiRendererV25` формирует итоговый schema4 текст из backend-owned facts.
5. Итог снова проходит content schema и semantic checks.
6. Completion в одной транзакции сохраняет enrichment и завершает attempt/job.

Для `PARTIAL` backend явно добавляет ограничение, что вывод основан только на доступной части
данных. Каждая quality-проблема привязана к конкретным block IDs и metric codes. Неполная
аналитическая классификация ограничивает только структуру продаж и attach, но не чистую выручку,
валовую прибыль, команду или сотрудников. Проблема согласованности продаж/возвратов ограничивает
чистую выручку, её разложение и основанный на ней главный вывод, но не переносится на независимые
метрики. Действительно отсутствующая себестоимость ограничивает валовую прибыль, маржу и зависящий
от них главный вывод. Нулевая себестоимость является допустимым бизнес-значением: её diagnostic
counter сохраняется в исходных фактах, но она не создаёт limitation и не понижает состояние
прибыли, маржи или всего отчёта. Отсутствующая исходная продажа либо позиция у части возвратов также
не считается нарушением согласованности итогов магазина; сумма возврата уже входит в чистую
выручку. Если из-за этого невозможно определить продавца, блок команды показывает нейтральную
оговорку о доступной связи, не создавая page-level warning и не предлагая исправить нормальное
состояние данных.
Если `PARTIAL` возник только внутри структуры или команды, assembler добавляет такой блок в общую
сводку качества даже при отсутствии корневого quality limitation. Frontend объединяет корневые и
локальные тексты в одной панели ограничений, а у затронутого главного вывода показывает короткий
маркер доверия вместо повторения полного предупреждения.
Несовместимый enrichment игнорируется; детерминированный отчёт остаётся источником ответа.

## Неизменяемость и повторный запуск

`weekly_review_ai_enrichments` имеет уникальность по snapshot/prompt/schema и DB trigger против
update/delete. Повторная запись с теми же input/content hashes идемпотентна; другое содержимое для
того же ключа отклоняется.

Завершённые attempts защищены от изменения. `weekly_review_ai_jobs` остаются изменяемыми
lifecycle-записями для lease, retry и terminal state. Новая редакция отчёта создаёт новый snapshot
и новый immutable enrichment, а не переписывает старый.

## Read path и frontend fallback

`WeeklyReviewService` сначала читает latest snapshot завершённой недели, затем пытается применить
первый совместимый опубликованный enrichment. При отсутствии enrichment возвращается тот же
deterministic response с состоянием AI: `DISABLED`, `PREPARING`, `DELAYED`, `UNAVAILABLE` или
`NOT_APPLICABLE`.

Frontend показывает legacy weekly insight только когда новый endpoint не имеет сохранённого ответа
и вернул `404`/`null`. Legacy явно помечается как предыдущий формат, чтобы пользователь не принял
его за новый Weekly Review. Ошибка transport/schema/server не включает legacy: frontend показывает
ошибку загрузки и действие повтора.

Ошибка фонового обновления уже показанного v25 snapshot не переключает пользователя на legacy:
сохраняется последняя версия с компактной заметкой. Это compatibility fallback всего weekly-review,
а не fallback отдельного AI слоя.

### Presentation contract

Первый содержательный экран имеет фиксированный manager-first порядок:

1. компактный header с последней завершённой неделей, периодом сравнения и временем обновления;
2. общий вывод и одна приоритетная проверка рядом на desktop и друг под другом на узких экранах;
3. четыре KPI: чистая выручка, валовая прибыль, маржа и средняя продажа.

На desktop карточки итога и приоритетной проверки растягиваются до одной высоты текущей grid-строки
без фиксированной высоты; их действия выровнены по нижней границе. На tablet/mobile они идут друг
под другом и сохраняют естественную высоту.

Проверка показывает backend-owned название, числовой ориентир, критерий и следующую полную неделю.
Дополнительные проверки свёрнуты внутри того же action-блока. Название «Что проверить на этой
неделе» сохраняется, пока в продукте нет task-state с назначением и выполнением.

Frontend показывает `Дополнено ИИ` только когда опубликованный summary действительно имеет
`generatedBy=AI_ENHANCED` и `aiEnhancement.state=READY`. Для детерминированного отчёта отдельная
подпись источника не показывается; отсутствие AI enrichment не маскирует детерминированный отчёт
как ошибку и не меняет порядок бизнес-блоков.

После KPI расположены независимые вторичные секции: до трёх факторов в «Что изменилось», свёрнутая
«Структура продаж» без графика и «Команда». Связанный с primary action фактор принадлежит верхнему
decision-блоку и не повторяется здесь. В спокойном READY вместо пустых больших блоков показывается
короткое нейтральное сообщение.

Заголовок команды показывает одно сообщение `N из M требуют проверки`, после него без отдельной
серой сводной плашки расположены максимум три карточки с реальным `ATTENTION`. Карточка не повторяет
общий статус: она содержит конкретную причину, ориентир и короткие действия `Почему сотрудник в
списке` и `Открыть сотрудника`; доступные имена действий включают имя сотрудника. Frontend не
выбирает первого сотрудника автоматически. Метрики, evidence и сравнение эффективности открываются
по запросу. Сотрудники `LIMITED`, `POSITIVE` и `STABLE` не занимают основной экран.

Evidence, формула чистой выручки, ограничения и подробность сотрудника открываются единым
`ReviewDetailPanel`: правой панелью на desktop и bottom sheet на mobile. Одновременно существует
один detail context. Панель закрывается кнопкой, `Escape` или backdrop, удерживает фокус, скрывает
фон от accessibility tree и возвращает фокус на исходный триггер.

Для `PARTIAL` header показывает нейтральный статус, а под ним находится одна сводка качества.
Надёжные KPI и секции сохраняются; адресная ссылка у затронутой метрики объясняет только её
ограничение. Локальные ограничения структуры и команды входят в ту же сводку и доступны из своей
секции. Если backend не сформировал проверяемое действие, `PARTIAL` не обещает, что дополнительная
проверка не нужна: экран предлагает сначала уточнить ограничения. Нормальный `READY` не получает
success-плашку. `BLOCKED` скрывает длинный недостоверный
отчёт и показывает причину с исправлением; для недоступного менеджеру исправления панель называет
администратора или ответственного за загрузку данных без ложной кнопки действия. `PREPARING`
остаётся отдельным состоянием прогресса.

Текущее значение маржи приходит из backend по формуле `grossProfit / netRevenue × 100%`.
Изменение маржи показывается как абсолютная разница в процентных пунктах, а не как относительный
процент между двумя значениями маржи.

На mobile KPI остаются сеткой 2×2, decision-блоки идут последовательно, структура превращается в
двухколоночные строки, а detail panel становится нижним листом. В плотном сценарии сначала видны
один вторичный фактор и один сотрудник; остальные из уже ограниченных трёх раскрываются кнопками
`Ещё N изменений` и `Ещё N сотрудников` в том же DOM. На tablet/desktop эти элементы сразу видны
полностью. Интерактивные элементы сохраняют доступную область нажатия; страница не создаёт
горизонтальный overflow. Графиков в этой версии нет, поскольку weekly-review endpoint не
предоставляет согласованный временной ряд.

## Telegram boundary

Публикация schema4 enrichment не создаёт `notification_events`. Текущий weekly Telegram fanout
читает legacy `llm_interpretations` и поддерживает schemas 1–3. Прямого schema4 bridge нет; нельзя
объявлять weekly Telegram частью v25 до отдельной реализации и E2E/poison-event tests.

## Ошибки и неполные данные

- Quality counters для Weekly Review вычисляются только по текущей и предыдущей сравниваемым
  неделям. Открытая store-wide проблема вне этих периодов не переводит отчёт в `PARTIAL`.
- `BLOCKED` snapshot не передаётся AI и получает `NOT_APPLICABLE`.
- Невалидный provider response не публикуется.
- Budget, deadline, request-size и context-window violations завершаются fail-closed.
- Ошибка чтения weekly-review endpoint не маскируется legacy-представлением.
- Ошибка чтения отдельного enrichment логируется без раскрытия payload; следующий candidate может
  быть проверен, после чего остаётся deterministic fallback.
- `PARTIAL` допускает AI только при наличии deterministic outcome и явно сохраняет ограничение.
- Отсутствие смен не является store-wide quality issue и не должно скрывать доступные sales-выводы.
- Нулевая себестоимость не является quality limitation Weekly Review; отсутствие себестоимости
  остаётся ограничением.
- Отсутствие исходной продажи или позиции у части возвратов не входит в store-level consistency
  count; недоступная связь с сотрудником объясняется только внутри блока команды.
- У сотрудника, уже попавшего в список по независимому sales-сигналу, отсутствие time-оценки
  объясняется локально: `Часть смен не заполнена — оценка по часам недоступна`. Эта подпись не
  меняет attention, action или report state.

## Расхождения и открытые решения

- В input нет employee scope, но текстовые `factor.title`, `evidence.label`, `action.title` и
  `action.check` не проходят отдельный PII scrubber/allowlist.
- Schema4 Telegram publication bridge отсутствует.
- Production enablement, очередь и последний successful enrichment нельзя выводить из кода;
  требуется sanitized runtime evidence.

## Проверка

Contract tests проверяют resource versions, input/selection/content schemas, semantic selector
rules и renderer. Integration tests проверяют immutable persistence, budget reservation,
job lifecycle и атомарное завершение. Полноценное подтверждение сборки требует clean `bootJar` и
сверки packaged hashes с manifest; локальный `build/resources` не является доказательством.

## Триггеры пересмотра

Новая версия prompt/schema, изменение selector vocabulary, compactor, renderer, read-order,
enrichment immutability, AI state, frontend fallback или Telegram publication обновляет этот
документ в том же PR.
