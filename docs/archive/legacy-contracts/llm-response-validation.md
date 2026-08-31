---
doc_schema: 1
doc_type: archive
status: archived
owner: ai
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/current/ai/README.md"
original_content_sha256: a4ccb28385c9239787cb1d1d21883e14997a4f55d49eb143b2cbd326a8b59aef
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/README.md`.

# LLM Response Validation Worker

Статус на 2026-08-06: structural/fact/safety validation, отдельные claim/execution и heartbeat
scheduler, атомарные PostgreSQL transitions, crash recovery и одна полная validation-retry
реализованы. Реальный YandexGPT 5.1 response прошёл валидацию и атомарную публикацию без retry;
`INTERPRETATION_GENERATION_WORKER_ENABLED` в production остаётся `false` до server-side gate.

## Поток

```text
WAITING_RETRY / VALIDATE_RESPONSE + open RESPONSE_RECEIVED
→ claim через FOR UPDATE SKIP LOCKED
→ RUNNING + bounded lease
→ прочитать immutable snapshot и сохранённый raw response_body
→ structural JSON Schema validation
→ semantic validation относительно manifest того же snapshot
├─ valid: сохранить отдельный canonical validated body/hash
│  → attempt SUCCEEDED → WAITING_RETRY / PUBLISH
└─ invalid:
   ├─ validation retry доступен: закрыть attempt как *_INVALID
   │  → увеличить validation_retry_count
   │  → полный provider call типа VALIDATION_RETRY с безопасными violation codes
   └─ retry исчерпан: attempt *_INVALID → job VALIDATION_FAILED
```

Ответ валидируется после durable commit `RESPONSE_RECEIVED`; validation worker не повторяет сетевой
вызов при обычном claim или после crash. Если процесс падает до terminal transition, lease recovery
сохраняет открытую attempt и повторно ставит тот же body в `VALIDATE_RESPONSE`.

## Что проверяет backend

Structural layer использует packaged Draft 2020-12 schema и проверяет форму: required properties,
типы, enum, nullability, размеры коллекций и `additionalProperties=false`.

Semantic layer проверяет только доверенные границы фактов и контракта:

- точное множество `employeeRef`, отсутствие дублей и совпадение `analysisStatus`;
- membership всех employee/candidate/category/competency references;
- допустимость fact `evidenceRef`: ссылка может идти прямо из provider facts, а явно недоступная
  manifest evidence остаётся запрещённой для доказательства вывода;
- запрет evidence другого сотрудника внутри персональной карточки;
- точное backend-owned множество data limitations;
- пустые неподдержанные блоки для `INSUFFICIENT`;
- корректный target персональной рекомендации;
- отсутствие двух структурно одинаковых управленческих действий с изменённой только
  формулировкой;
- соответствие измерения риска его evidence: вывод о выручке или прибыльности требует хотя бы
  одной связанной метрики того же измерения;
- отсутствие неподтверждённых чисел, процентов и денежных символов в опубликованном narrative.

Backend не определяет, что считать сильной стороной, риском или лучшей рекомендацией, не ранжирует
выводы и не требует заранее заведённый `candidateRef` для каждого нового синтеза. Эти свойства
остаются ответственностью модели, prompt и offline quality evaluation.

## Safety-нормализация перед публикацией

Backend-owned поля не делегируются модели и восстанавливаются из immutable snapshot:

- `dataLimitations` заменяются точным backend-набором до structural validation и повторно перед
  semantic validation;
- legacy affected sections приводятся к каноническим
  `CATEGORY_PERFORMANCE`, `ADDITIONAL_SALES`, `PROFITABILITY`, `TEAM_COMPARISON`;
- неподдержанные блоки сотрудников с `INSUFFICIENT` очищаются;
- narrative с числовыми утверждениями не переписывается в общую нейтральную фразу, а отклоняется
  кодом `FORBIDDEN_NARRATIVE_LITERAL`: первый такой ответ получает один полный validation retry,
  повторный невалидный ответ не публикуется; псевдонимы вида `E01` нарушением не считаются;
- точные повторы narrative считаются quality-сигналом для evaluation, но сами по себе не блокируют
  фактически корректный результат.

Это защитная граница, а не перенос аналитики в backend: выбор значимых фактов, сильных/слабых сторон,
рисков и действий остаётся за LLM.

## Validation retry

Первая невалидная attempt сохраняется целиком для ограниченного технического аудита и закрывается
как `STRUCTURAL_INVALID` либо `SEMANTIC_INVALID`. В новый prompt добавляются только уникальные
машинные `violation.code` (не response body, пути, значения фактов или персональные данные).
Повтор генерирует полный атомарный response; backend не исправляет JSON по частям.

Количество повторов ограничено `max_validation_retries` job (схема разрешает не более одного),
общим `maxProviderCalls`, preflight budget и абсолютным deadline. Второй невалидный ответ переводит
job в `VALIDATION_FAILED` и требует технического уведомления разработчику.

## Состояния и атомарность

Успешная проверка одной транзакцией:

- сохраняет каноническое валидированное представление в `validated_response_body` и его SHA-256 в
  `validated_response_hash`, не изменяя raw provider body в `response_body`;
- публикация читает только validated body и повторно проверяет его hash;
- переводит attempt `RESPONSE_RECEIVED → SUCCEEDED`;
- записывает пустой массив violations;
- переводит job `RUNNING/VALIDATE_RESPONSE → WAITING_RETRY/PUBLISH`;
- освобождает lease.

Невалидный terminal outcome одной транзакцией закрывает attempt, сохраняет bounded safe violations,
ставит terminal reason `LLM_RESPONSE_STRUCTURAL_INVALID` или `LLM_RESPONSE_SEMANTIC_INVALID`,
заполняет `finished_at` и освобождает lease. Частичной публикации нет.

## Наблюдаемость

- `storeanalytics.interpretation.llm.validation.results{result=valid|structural_invalid|semantic_invalid}`;
- `storeanalytics.interpretation.llm.validation.duration` — histogram времени проверки;
- общий gauge `storeanalytics.interpretation.llm.jobs{status="validation_failed"}`;
- существующий alert `LlmAnalysisFailedJobsPresent` направляется разработчику.

Метрики и logs не содержат prompt, response body, narrative или значения фактов. В attempt
`validation_violations` хранятся только code/path/reference; пользовательский API их не публикует.
Raw и validated body доступны только внутреннему worker-контуру и подлежат ограниченной retention.

## Scheduler isolation и эксплуатация

Provider call, provider heartbeat, validation и validation heartbeat работают на четырёх отдельных
single-thread scheduler. Оба worker включаются общим feature flag, но выбирают взаимоисключающие
durable состояния. Настройки lease/heartbeat/recovery общие и описаны в
[llm-provider-worker.md](llm-provider-worker.md).

До включения production flag остаются обязательными внешние gates:

1. staging fault injection для timeout, quota/rate limit, malformed response и restart;
2. offline evaluation на обезличенных примерах заказчика;
3. проверка Alertmanager delivery в технический канал и утверждение бюджета.
