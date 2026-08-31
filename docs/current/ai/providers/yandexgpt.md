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
  - scripts/weekly-review-ai-eval/README.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/integration/llm/yandex/YandexLlmProviderClient.java
  - backend/src/main/java/com/storeanalytics/integration/llm/yandex/YandexLlmPolicyProperties.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiBudgetGuard.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiProviderRequestFactory.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/integration/llm/yandex/YandexLlmProviderClientTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiBudgetGuardTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiProviderRequestFactoryTest.java
runtime_evidence: []
required_reviewers:
  - ai-semantic
  - security-privacy
review_triggers:
  - provider-change
  - provider-payload-change
  - budget-policy-change
  - secret-handling-change
supersedes: []
superseded_by: null
---

# YandexGPT provider boundary

## Назначение и границы

YandexGPT — внешний provider, а не источник бизнес-фактов. Provider получает только bounded input,
а результат допускается в приложение после structural, semantic и budget gates. Production model,
flags и последний вызов указываются только через [`project-state.md`](../../project-state.md) и
sanitized evidence.

## Запрос

`WeeklyReviewAiProviderRequestFactory` формирует request из:

- versioned system prompt;
- канонического bounded input JSON;
- selection response schema;
- exact versioned model URI;
- temperature, output-token limit и deadline.

Request material получает SHA-256. Retry prompt добавляет только проверенные violation codes.
Provider API key не входит в request hash, логи, документацию или evidence.

## Ответ и fail-closed поведение

Provider response не публикуется напрямую. Для v25 это selector JSON, который должен пройти
selection schema, semantic allowlist и backend rendering. HTTP/transport outcomes классифицируются
по retryability и certainty; неизвестный или невалидный результат не становится enrichment.

## Budget и ограничения

`WeeklyReviewAiBudgetGuard` до вызова проверяет:

- суммарный размер prompt/input/response schema;
- estimated input + max output относительно context window;
- валюту стоимости;
- максимальную оценку одного вызова;
- projected daily cost.

Runtime limits из конфигурации являются техническими предохранителями, но не заменяют человеческое
разрешение. Каждый платный evaluation/canary вызов требует явной авторизации точного case,
обезличенного payload, числа вызовов и верхнего лимита стоимости. Исторический cap нельзя
автоматически переносить на новый запуск.

## Privacy boundary

V25 compactor запрещает employee scope и передаёт только store evidence. При этом отдельного
PII scrubber/allowlist для текстовых `factor.title`, `evidence.label`, `action.title` и
`action.check` нет. До расширения входа или автоматизации вызовов обязательны negative tests на
имена, телефоны, email, токены и произвольный пользовательский текст.

Payload и полный provider response не должны попадать в production runtime evidence. Допустимы
версии, hashes, token counts, cost, outcome codes, validation codes и timestamps без секретов и
персональных данных.

## Секреты

API key передаётся только через secret/config tree. Запрещено:

- помещать ключ в shell history, command line, Markdown или Git;
- сохранять полный environment dump;
- копировать provider request/response в issue без privacy review;
- использовать mutable `/latest` model для воспроизводимого evaluation.

## Расхождения и открытые решения

- Нет общего outbound PII scrubber перед YandexGPT.
- Retention provider attempt payload/response требует отдельной утверждённой политики.
- Code tests не доказывают фактическую provider availability, quota и production credentials.

## Проверка

Client tests проверяют transport parsing, bounded responses и error mapping. Request factory tests
проверяют schema/resources/hash/deadline. Budget tests проверяют request, context, currency,
per-call и daily limits. Платная semantic проверка выполняется только по draft-runbook
[AI evaluation](../../../runbooks/ai-evaluation.md).

## Триггеры пересмотра

Смена provider/model policy, endpoint, request/response shape, budget, retry classification,
secret source или privacy boundary требует обновления документа.
