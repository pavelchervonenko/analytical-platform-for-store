---
doc_schema: 1
doc_type: archive
status: archived
owner: frontend
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/current/frontend/README.md"
original_content_sha256: 6c9bd31e5a8656605755f013aeb3ddf7c939c269863bc8f7d7b68eb1c6b0fffb
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/frontend/README.md`.

# Стабильный frontend-контракт backend

Дата базовой фиксации: 2026-07-26. Текущая contract version: 9 (2026-07-29).

Карта экранов и действий: `docs/frontend-actions.md`.

## Источник истины

Канонический машинный контракт — `contracts/openapi/current.json`. Backend генерирует его задачей
`generateOpenApi` через Testcontainers и временного test-admin, поэтому CI не использует production
login или production secrets. Runtime OpenAPI доступен администратору по `GET /v3/api-docs`, но не
заменяет проверяемый артефакт релиза.

`contracts/openapi/baselines/v1.json`–`v8.json` сохранены как неизменяемая история. Version 2
добавила обязательный `Idempotency-Key` для финансовых payroll-команд; version 3 — strong ETag и
обязательные conditional requests для plan/schedule; version 4 — resource-bound request schemas
для work-schedule и payroll bulk classification (`maxItems`/`maxLength`); version 5 публикует
`format: email` для уже валидируемых login/admin email-полей после миграции генератора на
springdoc 3; version 6 добавляет self-service active-session list, точечный revoke и
revoke-all-others с opaque `sessionReference`, без raw cookie/session ID; version 7 делает
`Idempotency-Key` обязательным в report-backfill OpenAPI; version 8 публикует nullable
`PeriodPlanQualityView.formulaVersion` и исправляет OpenAPI 3.1 compatibility checker.

Текущий immutable baseline — `contracts/openapi/baselines/v9.json`. Version 9 — согласованное
breaking-изменение до первого production rollout: удалены четыре синхронных endpoint
`/api/sync/stores`, `/api/sync/employees`, `/api/sync/sales`, `/api/sync/returns` и их transport
schemas. Единственная публичная команда запуска синхронизации — durable
`POST /api/sync/jobs/backfill`; frontend одновременно переведён на одну кнопку и job polling.
`checkOpenApiCompatibility` проверяет точное соответствие backend-генерации `current.json`, а
затем запрещает удаления path/operation/schema/field/response, новые обязательные параметры и поля,
а также изменения type/format/required/enum относительно v9.

Frontend генерирует transport-типы в `frontend/src/api/generated/` из `current.json`. Ручные
Zod-схемы остаются runtime-границей и формируют безопасные business view-model.


Bootstrap readiness GET /api/sync/jobs/backfill-readiness и
SyncClassificationReadinessView добавлены в current.json как совместимое расширение version 9.
Frontend использует endpoint до создания backfill; backend независимо возвращает
409 SYNC_CLASSIFICATION_REQUIRED, если на начало периода нет действующего назначения.

Telegram channel API добавлен в `current.json` как совместимое расширение v9. `GET` может не
иметь ETag в состояниях без subscription; при наличии `subscriptionId` strong ETag обязателен.
`confirm` и `revoke` принимают его в `If-Match`. Frontend использует `allowedActions` как
server-side capability allowlist и безопасно обрабатывает неизвестные состояния.
Атомарный `PUT /api/notifications/channels/telegram/settings` также требует текущий ETag
и возвращает новую версию channel resource.

Weekly insight evidence bundle добавлен в `current.json` как совместимое расширение v9.
`WeeklyInsightContentView.evidence` содержит безопасные backend-форматированные значения только
для фактов, процитированных опубликованной интерпретацией. Вложенные `evidenceRefs` используют
response-local коды `EV001`, ... и разрешаются через `evidenceCode`; технические snapshot refs
и псевдонимы сотрудников наружу не передаются. Frontend не разбирает код, не ищет исходный факт и
не пересчитывает current/previous/delta: он отображает `formattedValue`,
`previousFormattedValue`, `absoluteDeltaFormatted`, `relativeDeltaFormatted` и
`comparisonText` как подготовленную backend presentation.

## Правила совместимости

До первой production-версии текущий контракт считается frontend baseline:

- переименование или удаление endpoint, поля либо enum требует явного согласования с frontend;
- новое nullable-поле и новый endpoint считаются обратно совместимым расширением;
- новый вариант response enum считается потенциально breaking и останавливает contract gate;
  подготовленный frontend отображает неизвестную строку как `UNKNOWN`, не назначает ей права,
  terminal status или бизнес-смысл;
- изменение формулы не должно менять смысл существующего поля молча: используется новая версия
  формулы или новое поле;
- JPA-сущности и upstream LiveSklad payload никогда не возвращаются наружу.

URL пока сохраняют префикс `/api` без `/v1`. Номер версии появится при первой внешней production
фиксации, если действительно понадобится параллельная поддержка несовместимых контрактов.

## Жизненный цикл и N/N-1

`apiContractVersion` — целое строковое поколение API-контракта, независимое от версии сборки
приложения. Backend публикует его в `/api/system/status`; SPA показывает рядом собственную build
version из `package.json`.

Порядок совместимого rollout:

1. backend N выпускается первым и остаётся совместимым с immutable SPA N-1;
2. после проверки backend разворачивается SPA N с content-hashed assets;
3. `index.html` обязан отдаваться с `Cache-Control: no-store`, а content-hashed assets могут быть
   immutable; фактические Caddy headers проверяются в P1-06;
4. удаление старого поведения допустимо только после завершения окна N-1 и отдельного повышения
   contract version/baseline.

Ни один опубликованный baseline нельзя перезаписывать для прохождения gate. При осознанном
несовместимом релизе создаётся новый baseline `vN.json`, меняется
`ApiContractVersion.CURRENT`, документируется миграция и только затем переключается проверка.
Contract versions 2–9 созданы до первого production rollout; живого окна совместимости со старой SPA пока нет.

Production `VITE_API_BASE_URL` принимает только пустое значение, `/` или нормализованный
same-origin root-relative prefix. Absolute/protocol-relative URL, backslash, query, fragment,
dot-segment, повторный slash и необычные символы завершают сборку ошибкой.

## Общие типы

```ts
interface ApiError {
  timestamp: string; // ISO-8601 instant
  status: number;
  code: string;
  message: string;
  path: string;
  correlationId: string;
}
```

- UUID передается строкой, календарная дата — `YYYY-MM-DD`, месяц — первое число месяца;
- instant всегда содержит timezone/offset;
- в weekly insight evidence деньги, проценты и сравнения приходят готовыми display-строками;
  это сознательное исключение, чтобы LLM и frontend не формировали бизнес-числа;
- деньги, количества и проценты приходят JSON number; frontend их не пересчитывает;
- `null` означает «значение отсутствует или ненадежно», а `0` — рассчитанный ноль;
- списки всегда возвращаются массивом, включая пустой `[]`, а не `null`;
- enum передается строкой в верхнем регистре;
- порядок значим только там, где он описан тематическим API.

## Сессия и ошибки

- API client всегда использует `credentials: "include"`;
- bearer token не используется;
- mutation передает CSRF header;
- payroll calculate/adjustment/void/approve/paid передают `Idempotency-Key`; transport повторно
  использует ключ после timeout/network/5xx и удаляет его только после достоверного результата;
- `401` завершает локальную сессию, кроме локальной ошибки login;
- `403` означает отсутствие роли/доступа к магазину либо обязательную смену пароля;
- `409` означает ожидаемый конфликт; точная реакция определяется стабильным `code`;
- `412 PRECONDITION_FAILED` означает stale ETag: перечитать resource до следующей mutation;
- `428 PRECONDITION_REQUIRED` означает, что mutation отправлена без обязательной precondition;
- `429 LOGIN_THROTTLED` учитывает `Retry-After`;
- `500 INTERNAL_ERROR` показывает нейтральное сообщение и идентификатор обращения;
- frontend сохраняет выданный сервером `X-Correlation-ID`/`correlationId` для диагностики,
  сам идентификатор не генерирует и не формирует бизнес-поведение из текста `message`;
- неизвестный ответ proxy или HTML не отображается пользователю как backend message.

Полный контракт и каталог правил: `docs/error-handling.md`.

## Изменение контракта

При изменении backend нужно одновременно:

1. изменить отдельный response/request DTO, не JPA-сущность;
2. обновить controller/serialization и consumer contract tests;
3. выполнить `./gradlew -p backend generateOpenApi` и перенести полученный
   `backend/build/openapi/current.json` в `contracts/openapi/current.json`;
4. выполнить `npm run contracts:generate` в `frontend/`;
5. выполнить `./gradlew -p backend checkOpenApiCompatibility` и `npm run check`;
6. обновить тематический API-документ и `FRONTEND_HANDOFF.md`;
7. не менять released baseline без явно спланированной новой contract version.
