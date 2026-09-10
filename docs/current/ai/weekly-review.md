---
doc_schema: 1
doc_type: current
status: current
owner: ai
audience:
  - developer
  - operator
  - manager
last_verified: 2026-09-10
requirement_sources:
  - docs/archive/legacy-contracts/AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md
  - docs/archive/legacy-contracts/weekly-review-ai-management-rubric.md
implementation_sources:
  - frontend/src/insights/InsightsPreviewPage.tsx
  - frontend/src/insights/WeeklyReviewView.tsx
  - frontend/src/insights/weekly-review-presentation.ts
  - frontend/src/insights/weekly-review.css
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewAssembler.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewCoreProjector.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewQualityPolicyV1.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewSnapshotStore.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewTeamEmployeeProjector.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiContract.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiInputCompactor.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiSemanticValidator.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiRendererV25.java
  - backend/src/main/resources/db/migration/V46__add_weekly_review_ai_enrichments.sql
  - backend/src/main/resources/db/migration/V47__add_weekly_review_ai_generation_jobs.sql
  - backend/src/main/resources/db/migration/V48__harden_weekly_review_rollout.sql
verification_sources:
  - frontend/src/insights/WeeklyReviewView.test.tsx
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
runtime_evidence: []
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

Backend читает опубликованные schema4 enrichments в порядке `v25`, `v24`, `v23`, `v22`. Worker
создаёт только активную пару `v25/schema4`. Read compatibility не означает, что старые версии
снова допустимы для генерации.

## Provider boundary

`WeeklyReviewAiInputCompactor` принимает только `READY` или `PARTIAL` report и проецирует:

- store-level summary outcome;
- store-level factors и список допустимых selector-ов;
- store-level actions с backend-owned `title`, `check` и evidence references;
- только доступные store-level evidence values.

Employee scope и employee public IDs в input запрещены. Модель возвращает selector-ы для summary и
каждого фактора. Она не возвращает свободный пользовательский текст, KPI, action title/check или
новые evidence references.

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
метрики. Если включённая в главный вывод валовая прибыль ограничена качеством себестоимости,
главный вывод также получает состояние `LIMITED`.
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

Страница сохраняет manager-first порядок: главный вывод, ключевые результаты, изменения и шаги,
затем структура продаж, команда и сотрудники. Evidence остаётся доступным по раскрытию рядом с
соответствующим выводом, но не конкурирует с управленческим уровнем.

Frontend показывает `Дополнено ИИ` только когда опубликованный summary действительно имеет
`generatedBy=AI_ENHANCED` и `aiEnhancement.state=READY`. Для детерминированного отчёта отдельная
подпись источника не показывается; отсутствие AI enrichment не маскирует детерминированный отчёт
как ошибку и не меняет порядок бизнес-блоков.

Карточка `Главное` занимает всю ширину и не дублирует store action или отдельный сигнал риска:
риск остаётся в `Основных изменениях`, а действия — в соседнем разделе. `Основные изменения` и
`Шаги на следующую неделю` используют равные колонки на широком экране. В действиях отображаются
только названия без номера, цели и способа проверки; заголовок содержит календарный диапазон полной
недели, следующей за отчетной.

Для `PARTIAL` нейтральный статус «Разбор по доступным данным» показывается один раз в верхней панели
над временем обновления. Нормальный `READY` не получает отдельную success-плашку. Локальные подписи
`Данные ограничены`, inline limitations и отдельный нижний блок ограничений не повторяются;
`INSUFFICIENT`, `NOT_APPLICABLE` и блокирующее состояние сохраняют явные объяснения, потому что
значения в этих состояниях недоступны.

Текущее значение маржи приходит из backend по формуле `grossProfit / netRevenue × 100%`.
Изменение маржи показывается как абсолютная разница в процентных пунктах, а не как относительный
процент между двумя значениями маржи.

Пользовательский текст раздела использует только букву `е` в спорных написаниях, включая состояния,
подписи и резервный legacy-экран.

На desktop блок сотрудников использует master–detail: компактный список с одним главным показателем
слева и единая область выбранного сотрудника справа. Дополнительные метрики, динамика и сравнение с
командой образуют один плоский аналитический уровень; вложенные карточки и одновременное раскрытие
нескольких сотрудников не создают конкурирующую визуальную иерархию.

На tablet и mobile список сотрудников становится горизонтальным селектором над выбранным
сотрудником. На mobile ключевые результаты остаются сеткой 2×2, статистика команды — строкой из трех
показателей, а сигналы недели объединяются в один контейнер. Страница не создает горизонтальный
overflow и сохраняет доступные области нажатия.

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
