# YandexGPT Adapter

Статус на 2026-08-06: production adapter для синхронного OpenAI-compatible Chat Completions API
реализован и проверен как contract tests, так и реальным YandexGPT 5.1 call. Агрегированные
псевдонимизированные метрики прошли snapshot v3 → prompt v3 → generation →
structural/fact/safety validation → publication. Generation flags остаются выключенными до
server-side staging, fault drills и калибровки на обезличенных примерах заказчика.

## Transport contract

Adapter доступен только для runtime role `WORKER` или `COMBINED` и реализует provider-neutral
`LlmProviderClient` с кодом `YANDEX`.

Используется фиксированный endpoint:

```text
POST https://ai.api.cloud.yandex.net/v1/chat/completions
```

Фиксированный host исключает SSRF через configuration. Redirect запрещён. Вызов stateless,
не использует streaming, tools, web search, files или provider-side conversation storage.

Обязательные headers:

- `Authorization: Api-Key <secret>`;
- `OpenAI-Project: <folder-id>`;
- `x-data-logging-enabled: false`;
- новый `x-client-request-id` для каждого HTTP attempt;
- JSON `Content-Type`/`Accept` и `Accept-Encoding: identity`.

API key никогда не входит в request hash, БД, exception message или metrics. Folder в model URI
обязан совпадать с настроенным folder, а model URI request — с immutable job configuration.

## Structured output

Request передаёт versioned system prompt, canonical pseudonymized snapshot input и packaged output
schema через `response_format.type=json_schema`. Для текущей полной schema используется
`strict=false`: она содержит `$defs`, `anyOf` и ограничения, которые шире гарантированного
провайдером strict-подмножества. Это transport hint, а не доверенная валидация.

Adapter принимает только один choice с `index=0`, `finish_reason=stop`, без refusal, с ожидаемой
model URI и непустым JSON object в `message.content`. Usage обязан содержать согласованные
неотрицательные input/output/total token counts. Полная structural, evidence и semantic validation
выполняется следующим backend worker по сохранённому `RESPONSE_RECEIVED` без повторного вызова LLM.

## Preflight и стоимость

Preflight не обращается в сеть. Консервативная локальная оценка — один token на два Unicode code
points плюс transport overhead. Она используется только как верхний safety estimate; фактический
usage берётся из ответа провайдера.

Тарифы и context window вынесены в deployment configuration, потому что меняются независимо от
кода. Defaults сверены 2026-08-06 с официальной документацией YandexGPT Pro 5.1: context 32,768
tokens и 0.8 RUB за 1,000 input/cached-input/output tokens в синхронном режиме. Перед каждым
production rollout значения проверяются повторно по страницам моделей и тарифов, указанным ниже.

Фактическая стоимость attempt рассчитывается по usage с точностью до 0.000001 RUB и сохраняется
в `llm_analysis_attempt` вместе с tokens, provider request ID, model, latency и HTTP status.

## Timeout и response bounds

Фактический request timeout равен минимуму из provider read timeout и остатка абсолютного job call
deadline. Истёкший deadline отклоняется до отправки. Connect/read timeout или I/O failure после
начала `HttpClient.send` маркируется как `UNKNOWN`, потому что нельзя доказать, обработал ли
провайдер запрос.

Response читается как stream не более `max-response-bytes + 1`. Проверяются JSON content type и
отсутствие compression. Raw error body не включается в exception. Максимальный response limit не
может превышать 1 MiB — тот же предел, что у durable response receipt.

## Error classification

| Событие | Kind | Retryable | Outcome certainty |
| --- | --- | --- | --- |
| local config/schema/deadline | `AUTHENTICATION`, `INVALID_REQUEST`, `DEADLINE_EXCEEDED` | нет | `NOT_SENT` |
| HTTP 401/403 | `AUTHENTICATION` | нет | `RESPONSE_RECEIVED` |
| HTTP 408 | `DEADLINE_EXCEEDED` | да | `RESPONSE_RECEIVED` |
| HTTP 429 | `RATE_LIMITED` | да | `RESPONSE_RECEIVED` |
| HTTP 500/502/503/504 | `TRANSIENT_PROVIDER` | да | `RESPONSE_RECEIVED` |
| HTTP 501 | `PROVIDER_INCOMPATIBLE` | нет | `RESPONSE_RECEIVED` |
| timeout/reset/I/O | `DEADLINE_EXCEEDED` или `TRANSPORT` | да | `UNKNOWN` |
| oversized/malformed/truncated/refusal | typed content failure | нет | `RESPONSE_RECEIVED` |

`Retry-After` поддерживает delta-seconds и RFC 1123 date, ограничен одним часом. Typed exception применяется worker lifecycle к немедленным durable terminal/retry transitions;
неизвестные RuntimeException по-прежнему обрабатываются только expired-lease recovery.

## Metrics

- `storeanalytics.interpretation.llm.provider.calls{provider,outcome}`;
- `storeanalytics.interpretation.llm.provider.tokens{provider,type}`;
- `storeanalytics.interpretation.llm.provider.cost.rub{provider}`;
- `storeanalytics.interpretation.llm.provider.latency{provider}`;
- `storeanalytics.interpretation.llm.provider.preflights{provider}`.
- `storeanalytics.interpretation.llm.preflight.rejections{reason}`.

Metrics не содержат store, employee, prompt, response, key, folder ID или model URI, поэтому не
создают high-cardinality и privacy risk.

## Configuration

```dotenv
YANDEX_AI_FOLDER_ID=
YANDEX_AI_API_KEY=
YANDEX_AI_MODEL_URI=gpt://<folder-id>/yandexgpt-5.1
YANDEX_AI_CONNECT_TIMEOUT=5s
YANDEX_AI_READ_TIMEOUT=180s
YANDEX_AI_CONTEXT_WINDOW_TOKENS=32768
YANDEX_AI_MAX_RESPONSE_BYTES=1048576
YANDEX_AI_INPUT_RUB_PER_THOUSAND_TOKENS=0.8
YANDEX_AI_CACHED_INPUT_RUB_PER_THOUSAND_TOKENS=0.8
YANDEX_AI_OUTPUT_RUB_PER_THOUSAND_TOKENS=0.8
```

Для service account требуется роль `ai.languageModels.user`, а для API key — scope
`yc.ai.languageModels.execute`. Production key хранится только в secret storage/environment
injection. Рекомендуется explicit versioned model URI, а не плавающий alias.

## Проверенные сценарии

Локальный stub server проверяет:

- точные auth/project/privacy/tracing headers и structured request body;
- mapping content/model/request ID/usage/cost;
- отсутствие сетевого вызова у preflight и истёкшего deadline;
- 429 и bounded `Retry-After` без утечки provider body;
- chunked response больше configured limit;
- read timeout как ambiguous outcome;
- truncation и не-JSON generated content.

Реальный acceptance 2026-08-06 дополнительно подтвердил headers, доступ к модели, structured
response, usage accounting и публикацию. Успешная attempt использовала 4612 input, 4253 output и
8865 total tokens; validation retry не потребовался. См.
[yandexgpt-staging-acceptance.md](yandexgpt-staging-acceptance.md).

## Production gate

Adapter сам по себе не разрешает включать generation worker. До включения нужны:

1. server-side staging с secret injection и точными принятыми model/prompt/schema versions;
2. offline evaluation на обезличенных примерах;
3. drills 401, 403, 429, 5xx, timeout, crash after send и malformed content;
4. billing alerts и утверждённый месячный бюджет;
5. формальный production approval.

Provider data boundary для агрегированных псевдонимизированных недельных метрик подтверждён
2026-08-06.

Официальные источники: [базовый OpenAI-compatible запрос](https://aistudio.yandex.ru/docs/ru/ai-studio/operations/generation/completions-basic.html),
[structured output](https://aistudio.yandex.ru/docs/ru/ai-studio/operations/generation/completions-structured.html),
[отключение server-side logging](https://aistudio.yandex.ru/docs/ru/ai-studio/operations/disable-logging.html),
[модели](https://aistudio.yandex.ru/docs/ru/ai-studio/concepts/generation/models.html),
[квоты и лимиты](https://aistudio.yandex.ru/docs/ru/ai-studio/concepts/limits.html),
[тарифы](https://aistudio.yandex.ru/docs/ru/ai-studio/pricing.html).
