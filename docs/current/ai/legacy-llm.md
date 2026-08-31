---
doc_schema: 1
doc_type: current
status: current
owner: ai
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/llm-notifications-design.md
  - docs/llm-interpretation-publication.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/interpretation/contract/LlmContractResources.java
  - backend/src/main/java/com/storeanalytics/interpretation/generation/LlmAnalysisJobWorker.java
  - backend/src/main/java/com/storeanalytics/interpretation/publication/LlmPublicationStore.java
  - backend/src/main/java/com/storeanalytics/interpretation/query/WeeklyInsightQueryService.java
  - backend/src/main/java/com/storeanalytics/notification/fanout/WeeklyTelegramMessageRenderer.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/interpretation/generation/LlmProviderCallPipelineIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/validation/LlmResponseValidationPipelineIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/query/WeeklyInsightQueryServiceTest.java
  - backend/src/test/java/com/storeanalytics/notification/fanout/NotificationEventFanoutIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/notification/fanout/WeeklyTelegramMessageRendererTest.java
runtime_evidence: []
required_reviewers:
  - ai-semantic
  - backend-data
review_triggers:
  - legacy-llm-change
  - weekly-fallback-change
  - notification-publication-change
  - telegram-renderer-change
supersedes: []
superseded_by: null
---

# Legacy weekly LLM

## Назначение и границы

Legacy LLM — отдельный compatibility-контур. Он не является AI-layer нового weekly review, но всё
ещё обслуживает legacy endpoint, старые snapshots/interpretations и weekly Telegram publication.
Поэтому он остаётся действующей частью системы до явной миграции потребителей.

## Lifecycle

```text
analytics snapshot
      ↓
llm_analysis_job / attempts
      ↓
provider response
      ↓
schema1–3 validation
      ↓
immutable llm_interpretation
      +
weekly notification_event
      ↓
GET /api/stores/{storeId}/insights/weekly/current
```

Generation, validation и publication разбиты на durable phases с lease/retry/deadline. Publication
в одной транзакции создаёт новую revision interpretation, weekly notification event и завершает
job.

## Поддерживаемые контракты

Legacy input использует `weekly-interpretation-input-v1`. Prompt versions `v3–v21` маршрутизируются
в content schema 1, 2 или 3 через `LlmContractResources`. Поддерживаемая пара определяется кодом;
номер prompt сам по себе не задаёт schema.

Prompt/schema файлы остаются versioned runtime-артефактами. Старую версию нельзя переписывать для
исправления текущей семантики: создаётся новая версия с отдельными validation/evaluation gates.

## Immutable publication

`llm_interpretations` хранит revision, hashes, validated/published timestamps и ссылку на successful
attempt. Новая публикация supersedes предыдущую, не изменяя её. Notification event получает
deduplication key и ограниченный срок жизни.

Legacy publication и v25 enrichment различаются:

| Свойство | Legacy | Weekly Review v25 |
|---|---|---|
| Основная запись | `llm_interpretations` | `weekly_review_ai_enrichments` |
| Provider output | пользовательский content schema 1–3 | selector schema 1 |
| Финальный текст | provider + validation/projector | backend renderer |
| Weekly notification event | создаётся при publication | не создаётся |

## Read compatibility

`WeeklyInsightQueryService` возвращает latest interpretation завершённой недели либо явное
`PREPARING`/`DELAYED`/`UNAVAILABLE` состояние. Frontend использует этот endpoint как fallback,
когда новый weekly-review отсутствует или его запрос завершился ошибкой.

Legacy fallback может отличаться от v25 по структуре и семантике. Это известная compatibility
граница, а не доказательство эквивалентности двух отчётов.

## Weekly Telegram ownership

`NotificationEventFanoutStore` выбирает weekly events через joins с `llm_interpretations` и
`llm_analysis_jobs`. `WeeklyTelegramMessageRenderer` понимает только schemas 1–3. Таким образом,
weekly Telegram принадлежит legacy publication pipeline.

Если schema4 event искусственно направить в этот fanout, renderer выбросит ошибку. Receipt не будет
создан, тот же event снова станет первым кандидатом, а worker не перейдёт к daily pulse в этой
итерации. До реализации poison-event isolation это потенциально блокирует последующие weekly и
daily fanout.

## Условия будущего удаления

Legacy можно отключать по частям только после выполнения всех условий:

1. frontend больше не использует `/insights/weekly/current`;
2. historical interpretations остаются читаемыми либо имеют подтверждённую миграцию;
3. weekly Telegram получает schema4 bridge или официально выводится из продукта;
4. pending legacy jobs/events/deliveries обработаны или закрыты с reconciliation;
5. retention и privacy для legacy payload утверждены;
6. staging/production-read-only evidence подтверждает отсутствие потребителей.

## Ошибки и неполные данные

- Blocked snapshot не публикуется как готовая интерпретация.
- Невалидный provider response проходит ограниченные retry и не становится interpretation.
- Отсутствие interpretation не скрывается: endpoint возвращает явное состояние и reason code.
- Ошибка renderer/fanout сейчас не изолирует poison weekly event — это открытый P1 gap.

## Проверка

Lifecycle и publication покрыты integration tests; read projection — query tests; schemas 1–3 —
schema/validator tests; weekly fanout и renderer — Telegram integration/unit tests. Эти тесты не
доказывают фактические production flags или отсутствие pending событий.

## Триггеры пересмотра

Изменение legacy prompt/schema routing, publication transaction, endpoint fallback, notification
event, weekly renderer или retirement plan требует обновления документа.
