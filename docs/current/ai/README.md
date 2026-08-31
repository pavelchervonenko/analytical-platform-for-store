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
  - docs/maintenance/documentation-policy.md
  - docs/archive/legacy-contracts/AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewService.java
  - backend/src/main/java/com/storeanalytics/interpretation/query/WeeklyInsightQueryService.java
  - frontend/src/insights/InsightsPreviewPage.tsx
verification_sources:
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/query/WeeklyInsightQueryServiceTest.java
  - frontend/src/insights/WeeklyReviewView.test.tsx
runtime_evidence: []
required_reviewers:
  - ai-semantic
  - security-privacy
review_triggers:
  - ai-contract-change
  - weekly-review-read-path-change
  - notification-publication-change
  - provider-payload-change
supersedes: []
superseded_by: null
---

# AI и Telegram: действующий контур

## Назначение и границы

Этот раздел описывает реализованные AI- и Telegram-контуры без утверждений о том, какие feature
flags включены в production. Фактическое состояние окружения публикуется только в
[`project-state.md`](../project-state.md).

Два weekly-контура существуют параллельно и пока решают разные задачи:

| Контур | Роль сейчас | Пользовательское чтение | Telegram |
|---|---|---|---|
| Weekly Review `v25/schema4` | Основной контракт страницы «ИИ-разбор» | `/weekly-reviews/current` | Прямого publication bridge нет |
| Legacy LLM `schema1–3` | Compatibility fallback для старых snapshots и interpretations | `/insights/weekly/current` | Владеет weekly `notification_events` и renderer-ом |

Legacy нельзя удалить только потому, что weekly review стал основным UI. До удаления нужны замена
fallback-чтения и отдельное решение по weekly Telegram.

## Карта документов

- [Weekly Review v25/schema4](weekly-review.md) — deterministic snapshot, selector-only AI и
  backend-owned rendering.
- [Legacy LLM](legacy-llm.md) — старый generation/publication/read lifecycle и его совместимость.
- [Runtime-артефакты](runtime-artifacts.md) — immutable prompts, schemas, packaging и hashes.
- [YandexGPT](providers/yandexgpt.md) — provider boundary, budget и fail-closed проверки.
- [Telegram](telegram.md) — linking, webhook, fanout, delivery и daily pulse.
- [Privacy и retention](privacy-and-retention.md) — сохраняемые данные, реализованные ограничения
  и незакрытые gates.

Операторские действия отделены от контракта:

- [Weekly Review AI](../../runbooks/weekly-review-ai.md);
- [Legacy LLM](../../runbooks/legacy-llm.md);
- [Telegram](../../runbooks/telegram.md);
- [Daily store pulse](../../runbooks/daily-store-pulse.md);
- [AI evaluation](../../runbooks/ai-evaluation.md).

Все эти runbook пока имеют статус `draft`: статическая проверка кода не заменяет rehearsal,
sanitized staging evidence и production read-only preflight.

## Иерархия чтения

Для нового weekly-review backend ищет опубликованное совместимое enrichment в порядке
`v25 → v24 → v23 → v22`, всегда с content schema 4. Если enrichment отсутствует или отклонён,
backend возвращает детерминированный weekly-review и отдельное состояние AI, а не legacy-ответ.

Frontend переходит к legacy-представлению только если новый endpoint вернул `404`/`null` либо
запрос завершился ошибкой. Отсутствие AI-enrichment внутри существующего weekly-review само по
себе не включает frontend fallback.

## Критические границы

- YandexGPT в `v25` выбирает только разрешённые selector-токены; итоговый текст формирует backend.
- Facts, evidence references, action IDs и checks остаются backend-owned.
- `weekly_review_ai_enrichments` публикуются как immutable записи; jobs являются изменяемым
  lifecycle-состоянием.
- Legacy publication атомарно создаёт `llm_interpretations` и weekly `notification_events`.
- Weekly Telegram renderer поддерживает только content schemas 1–3. Schema4 bridge отсутствует.
- Daily store pulse — отдельный детерминированный контур без AI.
- Текущая daily-readiness принимает `PARTIAL_SUCCESS` и проверяет конечную дату покрытия, но не
  доказывает отсутствие внутренних пропусков периода.

## Известные незакрытые решения

1. Спроектировать безопасный `schema4 → notification event → Telegram` bridge либо явно отказаться
   от weekly Telegram для нового контура.
2. Добавить PII allowlist/scrubber перед provider-вызовом для текстовых `title`, `label` и `check`.
3. Утвердить retention для AI payload, Telegram identifiers, webhook receipts и rendered text.
4. Решить, должно ли отсутствие preference означать согласие на weekly/daily уведомления.
5. Усилить daily coverage gate проверкой gap-free покрытия, а не только максимального `period_end`.

## Триггеры пересмотра

Раздел обновляется в том же изменении, которое меняет prompt/schema, weekly API/read fallback,
AI input, provider budget, publication transaction, Telegram renderer/fanout, daily coverage или
retention персональных данных.
