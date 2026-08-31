---
doc_schema: 1
doc_type: current
status: current
owner: ai
audience:
  - developer
  - operator
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/archive/legacy-contracts/AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md
  - docs/archive/legacy-contracts/weekly-review-ai-management-rubric.md
implementation_sources:
  - frontend/src/insights/InsightsPreviewPage.tsx
  - frontend/src/insights/WeeklyReviewView.tsx
  - frontend/src/insights/weekly-review.css
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiContract.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiInputCompactor.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiSemanticValidator.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiRendererV25.java
  - backend/src/main/resources/db/migration/V46__add_weekly_review_ai_enrichments.sql
  - backend/src/main/resources/db/migration/V47__add_weekly_review_ai_generation_jobs.sql
  - backend/src/main/resources/db/migration/V48__harden_weekly_review_rollout.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewServiceTest.java
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
данных. Несовместимый enrichment игнорируется; детерминированный отчёт остаётся источником ответа.

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

Frontend показывает legacy weekly insight только когда новый endpoint вернул `404`/`null` или
завершился ошибкой. Это compatibility fallback всего weekly-review, а не fallback отдельного AI
слоя.

### Presentation contract

Страница сохраняет manager-first порядок: главный вывод и приоритет недели, ключевые результаты,
изменения и действия, затем структура продаж, команда, сотрудники и ограничения. Evidence остаётся
доступным по раскрытию рядом с соответствующим выводом, но не конкурирует с управленческим уровнем.

Frontend показывает `Дополнено ИИ` только когда опубликованный summary действительно имеет
`generatedBy=AI_ENHANCED` и `aiEnhancement.state=READY`. Во всех остальных состояниях интерфейс
показывает `Расчет по данным`; отсутствие AI enrichment не маскирует детерминированный отчёт как
ошибку и не меняет порядок бизнес-блоков.

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

- `BLOCKED` snapshot не передаётся AI и получает `NOT_APPLICABLE`.
- Невалидный provider response не публикуется.
- Budget, deadline, request-size и context-window violations завершаются fail-closed.
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
