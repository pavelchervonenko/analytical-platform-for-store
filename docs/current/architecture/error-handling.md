---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - operator
  - manager
last_verified: 2026-09-09
requirement_sources:
  - docs/archive/legacy-contracts/error-handling.md
implementation_sources:
  - frontend/src/shared/QueryState.tsx
  - backend/src/main/java/com/storeanalytics/common/web
  - backend/src/main/java/com/storeanalytics/common/exception
  - backend/src/main/java/com/storeanalytics/common/security
verification_sources:
  - frontend/src/shared/QueryState.test.tsx
  - backend/src/test/java/com/storeanalytics/common/web/ApiExceptionHandlerTest.java
  - backend/src/test/java/com/storeanalytics/common/web/CorrelationIdFilterTest.java
  - backend/src/test/java/com/storeanalytics/common/web/RequestBodyLimitMvcTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - frontend
  - product
  - security-privacy
review_triggers:
  - api-error-change
  - frontend-error-presentation-change
  - security-writer-change
  - request-filter-change
supersedes:
  - docs/archive/legacy-contracts/error-handling.md
superseded_by: null
---

# HTTP error boundary

## Публичный формат

JSON API error имеет единый shape:

```json
{
  "timestamp": "2026-08-31T10:00:00Z",
  "status": 409,
  "code": "BUSINESS_ERROR_CODE",
  "message": "Safe public message",
  "path": "/api/resource",
  "correlationId": "server-generated-uuid"
}
```

Клиент принимает решения по `status` и стабильному `code`, но не парсит `message`. Public message
не содержит exception text, SQL, provider body, credentials, filesystem paths или entity dump.

## Correlation ID

Backend всегда создаёт authoritative server UUID, возвращает его в `X-Correlation-ID`, добавляет в
`ApiError` и MDC `request.id`. Входной `X-Correlation-ID` — только ограниченный untrusted hint в
`client.correlation_id`; он не становится public ID и не влияет на auth, rate limit, idempotency или
data access.

## Taxonomy

Ожидаемые domain failures представлены concrete `BusinessException` и stable catalog code:

| Тип | HTTP |
|---|---:|
| invalid request | 400 |
| not found | 404 |
| conflict | 409 |
| unprocessable | 422 |
| rate limited | 429 |
| upstream failure | 502 |

Framework/security boundary использует тот же `ApiErrorFactory`. Precondition errors отделены от
domain conflict: отсутствующий ETag — 428, stale ETag — 412. Payload byte limit возвращает 413 и
не раскрывает лимит или тело.

Неизвестное исключение возвращает только `500 INTERNAL_ERROR` с общим сообщением; stack trace и
correlation ID остаются в server log. Глобального преобразования всех `IllegalArgumentException`
или `IllegalStateException` в 4xx нет, чтобы не скрывать дефекты и не публиковать внутренний текст.

## Представление ошибки в интерфейсе

Frontend различает три уровня. Блокирующая красная ошибка используется только когда локальный
экран или основной источник данных невозможно показать. Ошибка вспомогательного блока остаётся
внутри этого блока и не скрывает остальную страницу. Если фоновое обновление завершилось ошибкой,
последние успешные данные сохраняются, а пользователь видит компактную нейтральную заметку с
повтором запроса.

`correlationId` не конкурирует с пользовательским объяснением: код обращения находится под
раскрываемыми «Подробностями». Нормальное успешное состояние не требует отдельного баннера.
Предупреждение показывается только для понятного бизнес-риска или действия, которое менеджер может
выполнить; техническая диагностика качества данных принадлежит администраторскому разделу.

## Известный transport gap

OpenAPI v11 пока не описывает общий `ApiError`, 401/403 responses и security schemes полностью.
Фактическая защита реализована в Spring Security и тестах, но generated client не должен считать
отсутствие этих элементов отсутствием auth/error contract. Gap должен быть закрыт изменением
OpenAPI и consumer checks, а не копированием другого response shape в feature-документы.

## Проверка и триггеры

`ApiExceptionHandlerTest`, security writer tests, `CorrelationIdFilterTest` и request-body tests
проверяют форму, безопасный catch-all и отсутствие утечки internal message. Новый endpoint обязан
использовать эту границу и добавить HTTP-тест для новых business codes.
