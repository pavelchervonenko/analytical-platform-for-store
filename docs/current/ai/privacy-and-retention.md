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
  - docs/maintenance/documentation-policy.md
  - docs/data-retention.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiInputCompactor.java
  - backend/src/main/java/com/storeanalytics/interpretation/generation/LlmProviderInputCompactor.java
  - backend/src/main/java/com/storeanalytics/notification/linking/TelegramWebhookService.java
  - backend/src/main/java/com/storeanalytics/notification/fanout/NotificationEventFanoutStore.java
  - backend/src/main/resources/db/migration/V23__add_telegram_notification_outbox.sql
  - backend/src/main/resources/db/migration/V45__add_weekly_review_snapshots.sql
  - backend/src/main/resources/db/migration/V46__add_weekly_review_ai_enrichments.sql
  - backend/src/main/resources/db/migration/V47__add_weekly_review_ai_generation_jobs.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiInputCompactorTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/generation/LlmProviderInputCompactorTest.java
  - backend/src/test/java/com/storeanalytics/notification/linking/TelegramLinkingIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/notification/fanout/DailyTelegramMessageSanitizationTest.java
runtime_evidence: []
required_reviewers:
  - security-privacy
  - ai-semantic
review_triggers:
  - provider-payload-change
  - telegram-data-change
  - retention-policy-change
  - logging-change
supersedes: []
superseded_by: null
---

# Privacy и retention для AI/Telegram

## Назначение

Документ фиксирует фактически реализованные ограничения и незакрытые privacy/retention gates. Он
не является юридическим основанием обработки и не подменяет утверждённую security policy.

## Категории данных

| Контур | Сохраняемые/передаваемые данные | Текущая граница |
|---|---|---|
| Weekly Review v25 input | store-level factors, actions, labels, numeric evidence | employee scope запрещён, текстового PII scrubber нет |
| Weekly Review AI attempts | полный canonical `input_payload` JSON, полный raw `response_payload` text, hashes, provider outcome, token/cost metadata и validation codes | final attempts immutable; PII scrubber и retention не утверждены |
| AI enrichment | rendered schema4 content, input/content hashes, timestamps | immutable, delete/update запрещены DB trigger |
| Legacy LLM | snapshots, provider payload, interpretations, employee references | privacy-reduced prompts существуют, но legacy остаётся compatibility-контуром |
| Telegram linking | app user, Telegram user/chat IDs, hashed link token, subscription state | private chat/ownership/expiry checks |
| Telegram delivery | event payload, recipient relation, rendered text, attempts/outcomes | delivery history сохраняется; срок удаления не определён |
| Webhook receipts | bot code, update ID, update type, payload hash, outcome | command/link token не сохраняются в receipt |

## Реализованная минимизация

- V25 AI input допускает только store-level evidence и запрещает employee IDs.
- Provider response ограничен selector schema и не становится пользовательским текстом напрямую.
- Link token хранится как hash; raw token имеет срок и одноразовый lifecycle.
- Telegram webhook receipt хранит hash и outcome, а не исходную команду.
- Daily message renderer имеет sanitization tests.
- Runtime evidence должно содержать только versions, hashes, counts, costs, outcomes и timestamps.

## Незакрытые gaps

### Provider input

Store-only scope снижает риск, но не гарантирует отсутствие PII. Текстовые `title`, `label` и
`check` проходят в provider input без отдельного allowlist/scrubber. Категория, заметка или
будущее пользовательское поле могут содержать имя, телефон, email, токен или свободный текст.

V47 сохраняет точный outbound `input_payload` и полный raw provider `response_payload` в
`weekly_review_ai_attempts`. Кроме того, `weekly_review_snapshots.report_payload` хранит полный
deterministic report, а `weekly_review_ai_enrichments.content_payload` — опубликованный content.
Это не только hashes/metadata: DB становится хранилищем полного обмена и сформированных документов.
Immutable terminal state не ограничивает срок хранения и не заменяет PII gate.

### Telegram

Нет единой утверждённой retention policy для `telegram_subscriptions`, webhook receipts,
`notification_events`, `notification_deliveries.rendered_text` и attempts. Revoke/blocked state
не доказывает физическое удаление identifiers и message history.

При отсутствии preference уведомления считаются включёнными. Требуется решение, является ли факт
linking достаточным согласием на weekly и daily notifications, и как пользователь отключает каждый
тип события.

### Legacy

Legacy snapshots/interpretations могут содержать employee display data. Пока frontend fallback и
weekly Telegram зависят от legacy, эти таблицы нельзя удалить без migration/reconciliation, но это
не оправдывает бессрочное хранение автоматически.

## Обязательные gates до расширения

1. Полный data inventory: поле, таблица/provider, purpose, owner, lawful basis, retention,
   deletion/anonymization path.
2. PII allowlist или scrubber перед outbound AI request.
3. Negative tests на токены, телефоны, email, имена и произвольные заметки в provider input,
   logs и operator evidence.
4. Явный продуктовый контракт notification consent/defaults.
5. Delete/revoke flow для Telegram identifiers с audit и referential-integrity strategy.
6. Retention job с dry-run, holds, before/after counts и restore evidence.
7. Проверка store access для любого сообщения с employee/store data.
8. Запрет full payload/message text в logs, alerts, tickets и release evidence.

## Допустимое evidence

Можно сохранять:

- prompt/schema/model versions без credential URI components;
- input/content/request hashes;
- token counts, стоимость и валюту;
- outcome/validation codes и timestamps;
- агрегированные queue/delivery counts.

Нельзя сохранять в Git/evidence:

- API keys, webhook secrets, bot tokens;
- raw environment dumps;
- Telegram user/chat IDs;
- link tokens или `/start` commands;
- полный provider input/response без отдельной обезличенной evaluation-процедуры;
- rendered message с именами или другой персональной информацией.

## Проверка

Текущие tests подтверждают отдельные minimization/ownership/sanitization invariants, но не полную
retention policy и не отсутствие PII во всех текстовых полях. Поэтому новые outbound поля или
автоматизация платных вызовов требуют независимого `security-privacy` review.

## Триггеры пересмотра

Добавление AI input field, provider, Telegram event/message, identifier, log field, retention job
или consent setting обновляет документ до merge.
