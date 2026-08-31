---
doc_schema: 1
doc_type: evidence
status: historical
owner: ai
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved legacy evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/current/ai/README.md"
original_content_sha256: c63738874cd47811ad256b3547fc4ea45499850347befa7487d542ef69d51c1b
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/ai/README.md`.

# ИИ-интерпретация v19: production handoff

Дата фиксации: 2026-08-17
Статус: локальный release candidate прошёл полную автоматическую и слепую ручную матрицу; разрешён
только отдельный canary. Production default, публикация и Telegram не переключались.

Этот документ заменяет
[AI_INTERPRETATION_V15_RELEASE_HANDOFF_2026-08-17.md](AI_INTERPRETATION_V15_RELEASE_HANDOFF_2026-08-17.md)
как текущая точка продолжения. V15–v18 остаются immutable-историей обнаруженных дефектов.

## 1. Итоговое решение

Кандидат `weekly-interpretation-v19` / content schema `3` получил
`CANDIDATE_ELIGIBLE_FOR_CANARY`.

- automatic gate: 26/26 ответов v19 прошли, 0 нарушений;
- blinded review: 26/26 ответов v19 прошли;
- средняя ручная оценка: 4,8/5;
- required findings: 0 пропусков;
- forbidden findings: 0;
- critical errors: 0;
- provider refusals и moderation blocks: 0;
- baseline v4: 0/26 automatic и 4/26 manual pass, 131 automatic violation.

Допуск не означает автоматическую активацию. До production default нужен отдельный end-to-end canary
с exact release candidate, проверкой publication/read path и явным решением о rollout.

## 2. Почему v15 не принят

Полный платный прогон v4/v15 был необходим и выполнен. V15 успешно вернул 26 provider responses,
но прошёл только 18 из 26 сценариев и получил 43 автоматических нарушения. Основные классы дефектов:

- модель свободно формулировала персональные выводы и могла неверно интерпретировать сотрудника;
- командные сравнения и relationships оставались слишком зависимыми от генерации;
- нестабильные или общие narrative/actions;
- недостаточно надёжная обработка ограниченной базы, равенства сотрудников и граничных KPI;
- повторяемость и смысловая точность зависели от prompt сильнее, чем допустимо для production.

V15 не следует активировать или повторно использовать как release candidate.

## 3. Архитектура v19

LLM больше не получает данные отдельных сотрудников и не отвечает за персональные выводы.

### Provider input

Для v19 compactor передаёт только:

- агрегированные STORE facts;
- разрешённые STORE candidate signals;
- безопасные category labels;
- агрегированный `TEAM.RATING.ELIGIBLE_COUNT`, необходимый для оценки сопоставимости.

Из provider input исключены:

- employee facts и employee refs;
- employee candidate signals;
- backend relationship candidates;
- персональные headline/status payloads.

### Provider output

Модель формирует только ограниченную магазинную интерпретацию:

- один разрешённый `primarySignal` или `null`;
- необязательные distinct store insights;
- детерминированный enum для team overview;
- максимум одно строго ограниченное действие;
- `backendEmployeeHeadlines=true`;
- пустой `teamRelationships`.

Provider schema ограничивает candidate refs, evidence refs, kind/theme/scope и пользовательский текст
точными backend-разрешёнными значениями.

### Backend-owned presentation

Backend после ответа провайдера детерминированно создаёт и проверяет:

- персональные employee headlines;
- team overview и подтверждённые team relationships;
- нейтральные выводы при недостаточной базе;
- точные limitations;
- пользовательские названия категорий;
- формулировки про чистую выручку, план, прибыль/маржинальность и нулевую выручку;
- STORE result при отсутствии material candidate;
- canonical content schema v3 для API/UI/Telegram.

Таким образом LLM выбирает только допустимый агрегированный сигнал, а факты, люди, связи,
направление KPI и критичные пользовательские формулировки принадлежат backend.

### Двухконтекстная production-валидация

Production path сохраняет два независимых контекста:

- exact provider input — обезличенный документ, который действительно был отправлен модели;
- full snapshot input — полный проверенный snapshot для детерминированной backend-презентации.

Сырой ответ v19 сначала проверяется только против exact provider input: нельзя сослаться на скрытого
сотрудника, candidate, evidence или категорию. Лишь после этой проверки backend добавляет персональные
headlines, командные связи и limitations из full snapshot и валидирует итоговый canonical schema 3.
Offline evaluator зеркалит тот же порядок и имеет отдельные regression-тесты на утечку скрытых refs.

## 4. Усиленные инварианты

Production validator и offline evaluator независимо проверяют:

- согласованную пару prompt/content schema;
- точное множество доступных candidate/evidence refs;
- exact соответствие candidate kind/theme/scope/category/evidence;
- запрет персональных полей в provider payload v19;
- запрет свободных employee conclusions и relationships;
- отсутствие чисел, процентов, валют, дат и внутренних идентификаторов в narrative;
- соответствие измерения тексту: revenue, profit и margin не подменяют друг друга;
- отсутствие причинных домыслов и управленческих директив в insights;
- отсутствие повторов и близких повторов;
- достаточную базу attach-rate;
- отдельные glass screen/glass camera категории через backend classification;
- exact limitations при неполной себестоимости и ограниченной классификации;
- canonical response, реально показываемый пользователю, в blinded review.

Проверка целостности review связывает SHA-256 сырого provider response с assignments и одновременно
сравнивает пакет с backend-canonical представлением. Это позволяет оценивать именно production
presentation, не теряя доказательство неизменности сырого ответа.

## 5. Результаты платной матрицы

Артефакты находятся локально в ignored-каталоге:

```text
build/llm-eval/v4-v15-full-20260817/
build/llm-eval/v4-v19-full-20260817/
```

Финальные v19-артефакты:

```text
responses/
automatic-report-final.json
review-final/packet.json
review-final/scores.json
review-final/assignments.json
blinded-decision-final.json
blinded-decision-final.md
```

Контрольные суммы и exact request/evaluation hashes сохранены в shadow receipts. API key, folder ID,
prompt body, raw responses и receipts не добавляются в git.

Стоимость:

- полная учётная матрица v4/v15: 149,1096 ₽;
- новые вызовы полного v4/v15 этапа с учётом переиспользованной контрольной пары: 143,0880 ₽;
- успешные итерационные вызовы v16–v18: 60,9368 ₽;
- все 26 ответов v19: 26,9848 ₽;
- суммарные новые расходы текущего полного этапа: 231,0096 ₽.

V19 заметно дешевле кандидатов, которым передавались персональные и командные данные: его provider
input и output существенно меньше.

## 6. Проверки кода

Перед фиксацией выполнены:

- полный backend `./gradlew check`;
- frontend `npm run check`;
- Python evaluation/review suite;
- полный automatic gate на 52 ответах v4/v19;
- blinded manual review всех 52 вариантов;
- integrity/hash gate финального review-пакета;
- проверка immutable prompts v16–v19 и production request path.

Финальный commit candidate проверен повторно после двухконтекстной production-правки:

- backend: 859 тестов, 0 failures, полный `./gradlew check` успешен;
- frontend: 123 теста, contracts/lint/test/build успешны;
- Python evaluator/review: 57 тестов, 0 failures;
- сохранённые 52 provider responses: v19 26/26 и 0 нарушений, v4 0/26 и 131 нарушение;
- raw-provider allowlist и canonical full-snapshot presentation покрыты отдельными regression-тестами.

Числа относятся к этому release candidate и не считаются вечной характеристикой репозитория.

## 7. Что не выполнено

- v19 не является application/production default;
- production env и Compose не менялись;
- production deployment не выполнялся;
- свежий end-to-end canary v19/schema 3 не выполнялся;
- публикация v19, dashboard read path и Telegram fanout на реальном canary не проверялись;
- server-side secret/model configuration в рамках этого этапа не изменялась.

## 8. Следующий безопасный шаг

1. Собрать exact release commit без временных audit/build artifacts.
2. На staging или контролируемом production-equivalent окружении задать:
   - `LLM_PROMPT_VERSION=weekly-interpretation-v19`;
   - `LLM_CONTENT_SCHEMA_VERSION=3`;
   - `weekly-snapshot-v6`;
   - зафиксированный versioned Yandex model URI;
   - строгие call/token/cost limits.
3. Выполнить один end-to-end canary периода без Telegram fanout.
4. Проверить snapshot → provider input → response → validation → immutable publication →
   consumer API → отдельный раздел ИИ.
5. Проверить evidence projection, employee presentation, стоимость, идемпотентность и rollback.
6. Только после приёмки canary отдельно решить вопрос о production default и Telegram.

Rollback пары prompt/schema атомарный: вернуть `weekly-interpretation-v4` вместе со schema `2`.
Нельзя смешивать v19 со schema 2 или v4 со schema 3.
