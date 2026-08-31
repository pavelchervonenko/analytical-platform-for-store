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
implementation_sources:
  - backend/build.gradle.kts
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiContract.java
  - backend/src/main/java/com/storeanalytics/interpretation/contract/LlmContractResources.java
  - scripts/weekly-review-ai-eval/manifest-v6.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiSchemaContractTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/validation/WeeklyInterpretationSchemaContractTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/validation/WeeklyInterpretationV2SchemaContractTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/validation/WeeklyInterpretationV3SchemaContractTest.java
  - scripts/weekly-review-ai-eval/test_review.py
runtime_evidence: []
required_reviewers:
  - ai-semantic
  - security-privacy
review_triggers:
  - ai-contract-change
  - resource-packaging-change
  - evaluation-manifest-change
supersedes: []
superseded_by: null
---

# Runtime-артефакты AI

## Назначение

Prompts, JSON Schemas и examples в `docs/prompts/` и `docs/schemas/` являются не обычной prose-
документацией, а versioned runtime-контрактами. Они остаются на текущих путях, копируются в backend
artifact и учитываются отдельными manifest hashes.

## Активный набор Weekly Review

| Роль | Source path | Packaged path |
|---|---|---|
| Prompt v25 | [`docs/prompts/weekly-interpretation-v25.md`](../../prompts/weekly-interpretation-v25.md) | `prompts/llm/weekly-interpretation-v25.md` |
| Input schema 4 | [`docs/schemas/weekly-review-ai-input-v4.schema.json`](../../schemas/weekly-review-ai-input-v4.schema.json) | `contracts/llm/weekly-review-ai-input-v4.schema.json` |
| Selection schema 1 | [`docs/schemas/weekly-review-ai-selection-v1.schema.json`](../../schemas/weekly-review-ai-selection-v1.schema.json) | `contracts/llm/weekly-review-ai-selection-v1.schema.json` |
| Content schema 4 | [`docs/schemas/weekly-review-ai-content-v4.schema.json`](../../schemas/weekly-review-ai-content-v4.schema.json) | `contracts/llm/weekly-review-ai-content-v4.schema.json` |

Legacy prompts `v1–v21`, input schema 1, content schemas 1–3 и examples также остаются runtime-
артефактами, пока legacy read/publication/Telegram совместимость не удалена.

## Packaging

`backend/build.gradle.kts` копирует весь `docs/schemas` в `contracts/llm` и весь `docs/prompts` в
`prompts/llm` во время `processResources`. Код загружает ресурсы через classloader и завершает
операцию fail-closed, если обязательный файл отсутствует или JSON schema не может быть прочитана.

Локальный `backend/build/resources` может быть stale и не является evidence. Подтверждение release-
artifact требует clean `processResources`/`bootJar` и проверки файлов внутри полученного JAR.

## Hash manifests

[`manifest-v6.json`](../../../scripts/weekly-review-ai-eval/manifest-v6.json) фиксирует SHA-256 для
v25 prompt, input/selection/content schemas, renderer, evaluation corpus, fixtures, shadow runner
и review script. Narrow manifest для одного case не заменяет полный manifest.

Manifest доказывает целостность только указанного набора файлов. Он не доказывает:

- что собранный production JAR содержит те же bytes;
- что конкретные feature flags включены;
- что provider вернул качественный ответ;
- что canary прошёл для всех магазинов.

## Правила изменения

1. Опубликованная versioned версия не переписывается.
2. Изменение поведения создаёт новый prompt/schema version и обновляет code routing.
3. Examples, validators, manifests и offline corpus обновляются атомарно.
4. Старый runtime-файл удаляется только после доказанного отсутствия build/read/history references.
5. Paid evaluation и rollout evidence хранятся отдельно; они не встраиваются в prompt/schema.
6. Hash в manifest обновляется только после содержательного review, а не для обхода проверки.

## Проверка

Минимальный gate для нового runtime-контракта:

- schema contract tests для source и packaged resource;
- clean resource/JAR packaging;
- manifest hash verification;
- structural и semantic negative tests;
- offline evaluation;
- отдельная явная авторизация перед любым платным provider-вызовом;
- immutable canary evidence перед расширением rollout.

## Триггеры пересмотра

Изменение Gradle resource mapping, resource path, prompt/schema/example, manifest или classloader
routing обновляет документ в том же изменении.
