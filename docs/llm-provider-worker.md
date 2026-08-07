# LLM Provider Call Worker

Статус на 2026-08-06: provider-neutral request preparation, preflight budgets, bounded provider-call
worker, отдельный heartbeat и handoff сохранённого ответа в `VALIDATE_RESPONSE` реализованы и
проверены. Validation, publication, dashboard read projection и notification fanout также
реализованы. Реальный YandexGPT 5.1 call успешно прошёл весь lifecycle на псевдонимизированных
локальных метриках. Production flags остаются выключенными до server-side staging и fault drills.
Детали: [yandexgpt-adapter.md](yandexgpt-adapter.md)
и [llm-response-validation.md](llm-response-validation.md).

## Граница ответственности

Provider-call worker выполняет только сетевую фазу:

```text
PENDING/PREPARE, WAITING_RETRY/CALL_PROVIDER или validation retry
→ claim одного job через FOR UPDATE SKIP LOCKED
→ собрать request только из immutable analytics_snapshot
→ schema и budget preflight
→ durable attempt STARTED
→ LlmProviderClient.generate
→ durable RESPONSE_RECEIVED
→ WAITING_RETRY/VALIDATE_RESPONSE
```

Claim store выбирает `PREPARE`, `CALL_PROVIDER` и только тот `VALIDATE_RESPONSE`, где предыдущая
невалидная attempt уже закрыта и зарезервирован validation retry. Job с открытым
`RESPONSE_RECEIVED` выбирается исключительно validation worker и не может попасть в provider call.

За один scheduler tick обрабатывается не более одного job. Execution и heartbeat используют разные
single-thread scheduler, поэтому lease продлевается даже во время блокирующего HTTP-вызова. После
выхода из provider call runner перестаёт продлевать lease.

## Provider-neutral port

`LlmProviderClient` предоставляет две операции:

- `preflight` — оценка входных токенов, context window и верхней оценки стоимости;
- `generate` — один внешний вызов, обязанный соблюдать `callDeadline` из request.

Request содержит provider/model, versioned system prompt, schema-valid pseudonymized input,
response JSON Schema, temperature, output-token limit и абсолютный deadline. SHA-256 exact request
material сохраняется в `llm_analysis_attempt` до вызова.

Registry запрещает два adapter с одинаковым `providerCode`. Для `WORKER`/`COMBINED`
зарегистрирован YandexGPT adapter с кодом `YANDEX`; API role сетевого adapter не создаёт.

## Input и приватность

Request factory читает snapshot через integrity-checking `WeeklySnapshotStore`, собирает
`WeeklyInterpretationInput v1`, валидирует его packaged JSON Schema и использует `storeRef=S01`.
Имена сотрудников, телефоны, переписки, зарплата и credentials в provider request не попадают.

Перед сетевым вызовом `LlmProviderInputCompactor` строит ограниченную provider-проекцию:

- для магазина передаются до трёх значимых категорий и двух attach-групп;
- для сотрудника — core metrics и до двух категорий/двух attach-групп;
- для `INSUFFICIENT` остаётся только `WORKLOAD_STATUS`;
- нулевые категории и backend-owned limitations во внешний payload не включаются.

Response schema динамически фиксирует точное число сотрудников и enum разрешённых
`employeeRef`/`evidenceRef`, что ограничивает выдуманные ссылки ещё на уровне provider output.

System prompt и output schema читаются из versioned packaged resources. Секреты не входят ни в
request hash, ни в БД, ни в сообщения worker log.

## Preflight

До создания `STARTED` проверяются:

- общий размер prompt/input/schema в UTF-8;
- `estimatedInputTokens + maxOutputTokens <= contextWindowTokens`;
- валюта оценки `RUB`;
- верхняя оценка стоимости одного вызова.

Provider adapter отвечает за корректную консервативную оценку токенов и стоимости своей модели.
Базовые лимиты задаются environment variables и должны калиброваться по staging-наблюдениям.
Known provider/preflight rejection до внешнего вызова немедленно завершает job без создания attempt
и сохраняет `LLM_PROVIDER_*` либо `LLM_PREFLIGHT_*` reason code. Counter
`storeanalytics.interpretation.llm.preflight.rejections{reason}` использует только фиксированный
набор технических причин и не содержит бизнес-данных.

## Durable provider failures

Provider adapter возвращает только provider-neutral `LlmProviderException`: стабильный error code,
безопасное summary, retryability, HTTP status, `Retry-After` и certainty `NOT_SENT`,
`RESPONSE_RECEIVED` либо `UNKNOWN`. После `STARTED` execution worker перехватывает только этот
контракт. Обычный `RuntimeException` считается crash/programming failure и остаётся для lease
recovery, чтобы случайно не замаскировать дефект кода как штатную ошибку провайдера.

Typed failure закрывает attempt и меняет job в одной транзакции:

- `401/403`, invalid request, incompatible или malformed response — `PERMANENT_FAILED/FAILED`;
- `429`, `408`, поддерживаемый `5xx` — `TRANSIENT_FAILED/WAITING_RETRY`;
- timeout/reset/I/O с недоказанным исходом — `UNKNOWN_OUTCOME/WAITING_RETRY`;
- retry резервируется только при свободных `max_transport_retries` и `maxProviderCalls`;
- задержка равна максимуму из `recovery-delay` и provider `Retry-After`;
- retry, который не помещается до общего job deadline, не запускается.

Exactly-once для ambiguous outcome без idempotency key провайдера недостижим. Поэтому такой повтор
явно виден как `UNKNOWN_OUTCOME` и ограничен одним общим provider-call budget.

## Настройки

```dotenv
INTERPRETATION_GENERATION_WORKER_ENABLED=false
INTERPRETATION_GENERATION_WORKER_DELAY=5s
INTERPRETATION_GENERATION_LEASE_DURATION=4m
INTERPRETATION_GENERATION_HEARTBEAT_INTERVAL=15s
INTERPRETATION_GENERATION_RECOVERY_DELAY=30s
INTERPRETATION_GENERATION_PROVIDER_CALL_TIMEOUT=180s
INTERPRETATION_GENERATION_MAX_REQUEST_BYTES=524288
INTERPRETATION_GENERATION_MAX_ESTIMATED_COST_RUB=50.00
```

Heartbeat interval обязан быть короче lease duration. Provider timeout ограничен десятью минутами;
фактический call deadline дополнительно обрезается общим deadline job.

## Текущая rollout-граница

Локальная внешняя приёмка уже пройдена, но `INTERPRETATION_GENERATION_WORKER_ENABLED` остаётся
`false` до server-side staging, offline evaluation на обезличенных примерах и fault drills
timeout/crash/quota/malformed JSON.
Порядок включения: [llm-production-operations.md](llm-production-operations.md).
