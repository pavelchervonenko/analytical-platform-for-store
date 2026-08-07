# LLM Analysis Job Lifecycle

Статус на 2026-08-06: durable enqueue, claim/lease/heartbeat, deadline, cancellation,
`llm_analysis_attempt`, crash recovery, YandexGPT provider-call, response validation, publication и
weekly notification fanout workers реализованы и проверены на PostgreSQL 16. Worker contract:
[llm-provider-worker.md](llm-provider-worker.md), validation contract:
[llm-response-validation.md](llm-response-validation.md).

## Job state transitions

```text
PENDING / WAITING_RETRY
→ claim via FOR UPDATE SKIP LOCKED
→ RUNNING + lease owner/until
→ heartbeat или cooperative cancellation
→ WAITING_RETRY после recoverable crash
→ FAILED после исчерпания retry budget или deadline
→ CANCELLED после cancellation

PENDING / WAITING_RETRY за deadline
→ SKIPPED / LLM_GENERATION_DEADLINE_EXCEEDED
```

Claim разрешён только до deadline. `lease_until` всегда ограничивается deadline, даже если
настроенная lease duration длиннее. Старый owner не может продлить уже истёкший lease или lease
чужого worker.

## Durable provider attempt

Перед внешним вызовом worker обязан вызвать `LlmAnalysisAttemptStore.startProviderCall`. В одной
транзакции создаётся строка `STARTED`, фиксируются номер и тип попытки, provider/model и SHA-256
точного outbound request, а job переводится в `CALL_PROVIDER`. Частичный уникальный индекс V25
гарантирует не более одной незавершённой попытки на job.

После получения HTTP-ответа worker сначала вызывает `recordProviderResponse` и только после commit
переходит к разбору и валидации. В `RESPONSE_RECEIVED` сохраняются ограниченный 1 MiB response body,
его SHA-256, request ID, фактически использованная модель, latency, HTTP status, токены и стоимость.
Повторная запись того же response идемпотентна; другая identity отклоняется.

Секреты, Authorization headers и полный outbound prompt в attempt не сохраняются. Prompt должен
воспроизводиться из immutable snapshot и versioned prompt/config metadata job.

## Known provider failure

Когда adapter возвращает typed `LlmProviderException`, worker не ждёт окончания lease. Он под
row lock сразу закрывает `STARTED` attempt, освобождает lease и атомарно переводит job либо в
`WAITING_RETRY/CALL_PROVIDER`, либо в `FAILED`/`CANCELLED`. Known response failure хранится как
`TRANSIENT_FAILED` или `PERMANENT_FAILED`; timeout/reset с неясным внешним результатом — как
`UNKNOWN_OUTCOME`. HTTP status, стабильный error code и только safe summary сохраняются в attempt.

`Retry-After` является нижней границей задержки. Повтор запрещён после исчерпания transport или
provider-call budget и когда рассчитанный retry не помещается до общего generation deadline.

## Crash recovery

Coordinator перед claim обрабатывает максимум один истёкший pending deadline и один expired lease.
Это не позволяет maintenance полностью вытеснить обычную очередь.

Recovery различает durable-состояние внешнего вызова:

- без открытой attempt job возвращается в `WAITING_RETRY`;
- `RESPONSE_RECEIVED` возвращается в `WAITING_RETRY/VALIDATE_RESPONSE` без нового provider call и
  без расходования transport retry;
- `STARTED` означает неизвестный внешний исход: attempt закрывается как `UNKNOWN_OUTCOME`, после
  чего резервируется ровно один transport retry;
- если transport retry budget исчерпан, job завершается `FAILED` с
  `LLM_TRANSPORT_RETRIES_EXHAUSTED`;
- если recovery уже не помещается до deadline, job завершается `FAILED` с
  `LLM_GENERATION_DEADLINE_EXCEEDED`.

Exactly-once для внешнего API без provider idempotency key недостижим. Этот контракт устраняет
повторный платный вызов, когда ответ уже зафиксирован, и делает неизвестный исход явным и
ограниченным бюджетом.

Для RUNNING job отмена кооперативная. PENDING и WAITING_RETRY отменяются сразу. Открытый
`RESPONSE_RECEIVED` закрывается как `CANCELLED`; `STARTED` закрывается как `UNKNOWN_OUTCOME`, потому
что backend не может доказать, был ли запрос обработан провайдером.

## Validation и полный повтор

Сохранённый `RESPONSE_RECEIVED` валидируется без повторного provider call. Успешная attempt
закрывается как `SUCCEEDED`, а job передаётся в `WAITING_RETRY/PUBLISH`. Первая structural либо
semantic ошибка закрывает attempt, резервирует один `validation_retry_count` и возвращает полный
job в provider worker. Retry prompt получает только уникальные machine violation codes. После
исчерпания budget job атомарно завершается `VALIDATION_FAILED`.

## Наблюдаемость

Gauge `storeanalytics.interpretation.llm.jobs{status=...}` публикует cached состояния:

- `pending`, `running`, `retrying`, `success`;
- `failed`, `validation_failed`, `skipped`;
- durable `deadline_exceeded` по terminal reason;
- `expired_lease`.

Counter `storeanalytics.interpretation.llm.job.events` содержит события
`expired_lease_recovered` и `deadline_exceeded`. Structured logs используют стабильные event codes
`llm_analysis_lease_recovered` и `llm_analysis_deadline_exceeded` без prompt/response payload.

Prometheus rules: [llm-analysis-alerts.yml](../monitoring/prometheus/llm-analysis-alerts.yml).
Фактическая доставка настраивается через Alertmanager secrets в технический канал разработчика.

## Текущая rollout-граница

Provider-call worker, preflight, YandexGPT adapter, validation, publication, dashboard read projection
и weekly notification fanout готовы. Production feature flags по-прежнему должны оставаться
выключенными.

Локальный реальный end-to-end acceptance пройден. Оставшаяся production-граница: повторить smoke
на серверном staging, выполнить fault injection и offline evaluation на обезличенных примерах
заказчика, проверить alerts/budget controls. Порядок включения зафиксирован в
[llm-production-operations.md](llm-production-operations.md).
