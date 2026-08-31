---
doc_schema: 1
doc_type: archive
status: archived
owner: backend
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/current/architecture/error-handling.md"
original_content_sha256: 998fb8c8767ae1e093e8c5e5bacadb60b0ff214dd724669835c581bbef914657
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/architecture/error-handling.md`.

# Production error handling

Status: implemented and revalidated on 2026-08-01.

This document is the source of truth for the backend error boundary. Feature API documents may
describe additional business scenarios, but they must not define another response shape or expose
exception messages.

## Public response contract

Every JSON API error has the same shape:

```json
{
  "timestamp": "2026-07-24T10:00:00Z",
  "status": 409,
  "code": "PAYROLL_STATE_CONFLICT",
  "message": "Payroll state does not allow this operation",
  "path": "/api/stores/00000000-0000-0000-0000-000000000000/payroll/runs/...",
  "correlationId": "8e392b03-22cc-4d6c-a37f-d39bfc955462"
}
```

The `code` is the machine-readable contract. Frontend behavior must depend on `status` and
`code`, not on `message`. The message is a safe, stable explanation suitable for display, but it
is not a localization key and must not contain entity dumps, SQL, upstream bodies, credentials,
paths on the server, or exception details.

## Correlation ID

`CorrelationIdFilter` runs before the security chain. The backend owns request identity and applies
the following policy to every request:

- it always creates an authoritative UUID and stores it as the server request ID;
- it returns that UUID in `X-Correlation-ID` and includes the same value in every `ApiError`
  `correlationId` field;
- it places the UUID in SLF4J MDC as `request.id` for the duration of the request;
- an incoming `X-Correlation-ID` is only an optional client correlation hint. A single value is
  retained only when it is 1-64 ASCII characters and matches
  `[A-Za-z0-9][A-Za-z0-9._-]*`;
- a valid client hint is stored separately on the request and in MDC as
  `client.correlation_id`. It is never copied to the response or error body;
- invalid, empty, overlong or ambiguous duplicate client values are ignored;
- both MDC values are removed after request processing, including exceptional completion.

The public JSON field remains named `correlationId` for API compatibility, but its value is the
authoritative server request ID. Operations and support should use it to join a client report with
server logs. `client.correlation_id` may help join logs to a separately instrumented client or
trusted proxy, but it is untrusted diagnostic metadata. It must never affect authentication,
authorization, throttling, idempotency, data access or any other business decision.

## Exception taxonomy

Expected failures extend `BusinessException`. Every concrete exception selects exactly one
`BusinessErrorCode`; the catalog owns its stable string code, semantic type and safe client
message.

| Business type | HTTP | Meaning |
| --- | ---: | --- |
| `INVALID_REQUEST` | 400 | A syntactically valid request violates an input rule. |
| `UNPROCESSABLE` | 422 | A valid operation cannot be processed within a documented business limit. |
| `NOT_FOUND` | 404 | A requested domain object does not exist in the allowed scope. |
| `CONFLICT` | 409 | Current domain state or version conflicts with the requested transition. |
| `RATE_LIMITED` | 429 | A documented rate limit rejected the operation. |
| `UPSTREAM_FAILURE` | 502 | A dependency failed while processing the request. |

Feature code throws a concrete typed exception such as `PayrollStateConflictException`,
`RatingSchemeConflictException` or `ProductNotFoundException`. It may keep precise IDs, state and
causes in the internal exception message for diagnostics. The HTTP boundary never serializes
`exception.getMessage()`; it uses only the safe message from `BusinessErrorCode`.

`409 IDEMPOTENCY_KEY_CONFLICT` means an authenticated actor reused an unexpired opaque key for a
different action, resource, canonical request body or response contract. The client must not invent
a retry with altered content under that key; it should create a new user intent and key.

Payroll mappings verified at both service and HTTP boundaries:

- missing or blank `revisionReason` when a new revision is required returns
  `400 INVALID_ARGUMENT`;
- a mutation forbidden by the payroll lifecycle, including an adjustment after approval/payment,
  returns `409 PAYROLL_STATE_CONFLICT`;
- a stale optimistic `run.version` returns `409 PAYROLL_STATE_CONFLICT`;
- changed calculation inputs before approval/payment return `409 PAYROLL_SOURCE_DATA_CHANGED`.

These are expected business outcomes and must never be converted to `500 INTERNAL_ERROR`.

`409 CURRENT_SESSION_REQUIRES_LOGOUT` prevents an opaque session-revoke command from expiring its
own current request context ambiguously. The client refreshes the session list and uses the ordinary
logout endpoint for the row marked `current=true`; the public response contains neither the raw
session ID nor the cookie value.

Framework and security failures use the centralized `ApiErrorCode` catalog. Current codes include
`VALIDATION_ERROR`, `MALFORMED_REQUEST`, `MISSING_PARAMETER`, `METHOD_NOT_ALLOWED`,
`UNSUPPORTED_MEDIA_TYPE`, `PAYLOAD_TOO_LARGE`, `RESOURCE_NOT_FOUND`,
`PRECONDITION_REQUIRED`, `PRECONDITION_FAILED`, `CONCURRENT_MODIFICATION`,
`INVALID_CREDENTIALS`, `AUTHENTICATION_REQUIRED`, `ACCESS_DENIED` and `SESSION_EXPIRED`.

`413 PAYLOAD_TOO_LARGE` means the encoded API request body exceeded the validated global byte
budget. It is returned both when a trustworthy declared length is already too large and when an
unknown, chunked or understated stream crosses the boundary while being consumed. The payload and
headers retain the ordinary `ApiError`/correlation contract and never disclose request content or
the configured limit. This transport boundary is separate from domain cardinality/complexity
errors, which continue to use their documented validation or business codes.

Условные mutation используют HTTP preconditions отдельно от доменных конфликтов `409`:

- `428 PRECONDITION_REQUIRED` — plan/schedule mutation не указала состояние, на котором основана;
- `412 PRECONDITION_FAILED` — strong ETag устарел либо условие создания оказалось ложным.

После `412` клиент обязан перечитать ресурс. Автоматический слепой retry с новым токеном может
незаметно перезаписать изменение другого пользователя. Отсутствующий или устаревший токен
обрабатывается на HTTP boundary, а публичное сообщение никогда не раскрывает полученный ETag.

## Unexpected failures

Any exception not explicitly recognized as an expected business, validation, security or HTTP
failure is an internal defect or an unclassified infrastructure failure:

- the client receives HTTP 500, code `INTERNAL_ERROR` and message
  `An unexpected error occurred`;
- the response includes no exception class, original message, cause or stack trace;
- the server logs one ERROR event containing the correlation ID, method, request path and the full
  exception stack trace.

There is deliberately no global handler for `IllegalStateException` or
`IllegalArgumentException`. Mapping either class wholesale to 409 or 400 would hide programming
errors and turn accidental internal messages into a public API. An expected outcome must instead be
represented by a typed `BusinessException`.

## Implementation boundaries

- `common.exception`: taxonomy, stable catalog and shared request exception.
- feature `exception` packages: concrete business meanings owned by that feature.
- `common.web.ApiExceptionHandler`: the single MVC translation boundary.
- `common.web.ApiErrorFactory`: construction of the public payload.
- `common.web.CorrelationIdFilter`: request identity and MDC lifecycle.
- `common.web.RequestBodyLimitFilter`: pre-security declared and actual-byte API body boundary.
- `common.web.RequestBodyTooLargeException`: neutral streaming overflow signal; the MVC handler
  recognizes it through converter exception cause chains without exposing parser or transport
  details.
- `common.security`: authentication, authorization and expired-session writers using the same
  factory and correlation contract.

Feature-specific `@RestControllerAdvice` classes must not duplicate the global mapping. A new
business scenario is added by introducing or reusing a concrete business exception, assigning a
stable catalog entry, and testing both its HTTP mapping and safe payload.

## Review checklist

When adding an endpoint or failure path:

1. Decide whether the failure is expected domain behavior or unexpected failure.
2. For expected behavior, use a typed business exception and a stable, documented code.
3. Keep the safe public message independent from the internal diagnostic message.
4. Assert status, code and correlation ID at the HTTP boundary.
5. Assert that secrets or the original exception message are absent from 500 responses.
6. Do not log credentials, tokens, request bodies or sensitive before/after values.
7. Preserve the generic catch-all handler and full stack trace logging for unexpected failures.
