# Передача контекста проекта Store Analytics

Дата полной сверки с кодом и LLM acceptance: 2026-08-06.

Это главная точка входа в проект. Документ описывает фактически реализованный backend и границы
текущего frontend-контракта. Детальная карта экранов, кнопок и условий действий находится в
`docs/frontend-actions.md`, а типы и API — в `docs/FRONTEND_HANDOFF.md` и тематических документах.

Секреты, токены, реальные внешние идентификаторы и персональные данные здесь не хранятся. `.env`
никогда не открывать, не печатать и не добавлять в контекст.

## 1. Текущее состояние

Реализован модульный backend закрытого кабинета руководителей:

- нормализация магазинов, сотрудников, продаж и возвратов LiveSklad в PostgreSQL;
- durable sync jobs, retries, lease, отмена и безопасная диагностика;
- явные runtime-роли `API`, `WORKER`, `COMBINED` и `MIGRATION`: API не запускает фоновые задачи,
  worker владеет scheduler/probe-компонентами, migration выполняет Flyway в минимальном one-shot
  контексте, неизвестная роль останавливает startup;
- DB login, server-side cookie session, CSRF, роли и доступ к магазинам;
- KPI магазина, сотрудников, категорий, средние показатели и attach-rate;
- общий месячный план магазина, фактические смены/часы и progress плана;
- многомерный рейтинг, справочник и карточка сотрудника, неизменяемые finalized snapshots;
- полный payroll workflow: readiness, preview, расчет, удержания, ревизии, сравнение, утверждение
  и выплата;
- неизменяемые месячные отчёты после `PAID`, годовые отчёты из точных месячных ревизий,
  административный backfill и отдельный frontend-раздел архива;
- store-wide и period-specific quality API с машинно-читаемыми действиями;
- административные API пользователей, версий формул, классификации товаров и синхронизации;
- runtime OpenAPI и контрактные/security/integration tests;
- production-контур YandexGPT: immutable snapshots/jobs/attempts, validation, publication, dashboard, operator actions и evaluation tooling;
- Telegram linking/webhook, durable fanout/delivery, недельные LLM-отчёты и ежедневная backend-сводка;
- backend observability: correlation ID, low-cardinality timers, cached operational gauges,
  LiveSklad health, DB/schema-version readiness, Bearer-gated Prometheus scrape на отдельном
  management port и безопасная build/release identity;
- валидируемые Tomcat/Hikari resource budgets, graceful shutdown, отдельные scheduler bulkheads и
  backend cardinality limits, 2 MiB actual-byte API body boundary и стабильный `413`; детали и
  staging k6 acceptance — в `docs/resource-limits.md`;
- one-shot MIGRATION с typed lock/statement timeout, bounded Flyway lock retries, empty/V17→V18
  upgrade test, отдельными V18→V19/V19→V20 regression tests, полным empty-to-V32 schema oracle и
  N-1 additive-write проверкой;
- scheduled technical-data retention: dry-run по умолчанию, status-specific сроки raw/sync,
  exact-to-daily-to-monthly inventory rollups, audit retention classes/holds и защита
  financial/finalized snapshots; LiveSklad raw проходит allowlist до hash/persistence, а deletion
  требует policy/backup/restore evidence и post-purge reconciliation.

В репозитории реализован React + Vite + TypeScript SPA с cookie session, единым CSRF-aware API
client, runtime-валидацией контрактов и lazy-loaded пользовательскими разделами.

Последняя полная backend-проверка 2026-08-05/06: Java 21, PostgreSQL 16/Testcontainers —
`674` теста в `239` suites, `0` failures/errors/skipped; Checkstyle, OpenAPI compatibility,
supply-chain и operator-script security также прошли. Реальный YandexGPT 5.1 acceptance завершён
публикацией валидированной интерпретации. Frontend: 25/25 Vitest files, 90/90 тестов, ESLint,
generated-contract check и production build.

Локальная v2/v4-приёмка read/fan-out 2026-08-07 дополнительно подтверждает persisted snapshot с
integrity hash, HTTP-проекцию кабинета и создание одной durable Telegram delivery из той же
immutable interpretation revision. Внешние YandexGPT/Telegram вызовы в этом gate не выполняются;
production defaults остаются v1/v3 до отдельного платного staging acceptance.

Локальная application acceptance 2026-08-06 дополнительно подтвердила миграцию рабочей тестовой
БД с V29 на V30, восстановление `2/2` исторических report payload hashes, авторизованное чтение
обеих report revisions через API с `200` и переключение «Актуальная ↔ История» в реальном SPA без
HTTP 500, `query-error` и browser runtime failures. Playwright acceptance на desktop, tablet и
mobile подтвердил основные разделы, административные вкладки, интерактивные состояния и полный
жизненный цикл тестового MANAGER. Это доказательство готовности application-кода, но не
разрешение production launch: инфраструктурные gates остаются в `production-deployment-runbook.md`.

## 2. Стек

- Java 21;
- Spring Boot 4.1.0, Spring Framework/MVC 7.0.8, Spring Security 7.1.0 и native Jackson 3.1.4;
- Spring Data JPA/JDBC;
- PostgreSQL 16, Flyway, Hibernate `ddl-auto=validate`;
- springdoc OpenAPI 3.0.3;
- Gradle Kotlin DSL, dependency locking и strict SHA-256 dependency verification;
- JUnit, Testcontainers 2.0.5, Checkstyle 13.3.0, JaCoCo 0.8.14;
- Docker Compose для dev и production-заготовки.

Системная Java в WSL может быть Java 11. Полный check следует запускать на Java 21; актуальная
контейнерная команда приведена в конце документа. Test task выполняет один fork одновременно,
выделяет ему до 768 MiB heap и перезапускает JVM после 50 test-классов, чтобы накопление Spring
contexts/JaCoCo instrumentation не превращало полный CI-прогон в OOM.

## 3. Архитектурная граница

```text
Browser SPA -> Store Analytics API -> PostgreSQL <- sync worker <- LiveSklad
                                      ^          |
                                      |          +-> YandexGPT worker
                                      +------------- Telegram workers
```

Frontend не обращается к LiveSklad. Dashboard API читает нормализованные факты и snapshots из
PostgreSQL. JPA-сущности, raw payload и vendor DTO наружу не возвращаются. Формулы принадлежат
backend; frontend форматирует и объясняет готовые значения, статусы и reason codes.

Основные feature packages:

- `auth` — пользователи, вход, пароль, session lifecycle, права;
- `store` — магазины и свежесть данных;
- `employee` — сотрудники и назначения;
- `product` — товары и аналитическая классификация;
- `sales` — нормализованные продажи/возвраты;
- `sync` и `integration.livesklad` — источник и оркестрация;
- `metrics` — KPI, категории, attach-rate, averages;
- `performance` — планы, смены, рейтинг, карточки;
- `salary` — payroll calculation и lifecycle;
- `report` — finalization, annual aggregation, immutable archive, backfill и API;
- `maintenance` — retention orchestration, bounded SQL batches, rollups и метрики;
- `quality` — общая и периодная композиция качества;
- `interpretation` — weekly snapshots, YandexGPT jobs/attempts, validation, publication и read projection;
- `notification` — Telegram linking, canonical events, fanout, delivery и operator recovery.

## 4. Миграции и данные

Актуальная последовательность Flyway:

1. `V1__create_core_schema.sql` — core, normalized facts, audit/reference tables;
2. `V2__add_application_authentication.sql` — DB users и store access;
3. `V3__add_sync_job_orchestration.sql` — durable jobs;
4. `V4__add_employee_performance_rating.sql` — планы, смены, rating schemes;
5. `V5__add_payroll.sql` — payroll schemes, classification, runs и audit;
6. `V6__add_login_throttling.sql` — DB-backed login throttle;
7. `V7__add_actual_shift_hours_to_payroll.sql` — часы в payroll snapshots;
8. `V8__add_employee_rating_snapshots.sql` — immutable finalized rating;
9. `V9__add_payroll_source_fingerprints.sql` — freshness/recalculation control;
10. `V10__make_audit_log_immutable.sql` — persistent append-only action audit and safe metadata limit;
11. `V11__add_finalized_reports.sql` — immutable monthly/annual report revisions and provenance;
12. `V12__add_data_retention.sql` — inventory rollups, retention metadata/holds and safe provenance cleanup;
13. `V13__bound_raw_record_payload_size.sql` — database boundary for retained raw payload bytes;
14. `V14__add_report_backfill_jobs.sql` — durable report backfill state, leases and queue constraints;
15. `V15__add_list_pagination_indexes.sql` — indexes for bounded report archive and admin-user pages;
16. `V16__add_idempotency_receipts.sql` — durable responses for retry-safe high-risk commands;
17. `V17__add_work_schedule_day_revisions.sql` — aggregate revision and ETag source for a schedule day;
18. `V18__version_retained_raw_payload_policy.sql` — версия privacy allowlist для raw evidence и legacy inventory.
19. `V19__allow_provisional_product_identity_claim.sql` — безопасный одноразовый claim временной
    LiveSklad identity после получения authoritative product/service identity;
20. `V20__repair_orphan_sync_runs.sql` — repair исторических orphan/duplicate `RUNNING` attempts и
    уникальность активной попытки внутри job;
21. `V21__version_payroll_snapshot_recalculations.sql` — монотонное поколение payroll snapshot,
    гарантирующее продвижение optimistic version при каждом пересчете.
22. V22 — weekly snapshots, LLM jobs/attempts и immutable interpretations;
23. V23 — Telegram subscriptions, notification events и deliveries;
24. V24 — индексированный snapshot-to-generation handoff;
25. V25 — invariant единственной незавершённой provider attempt;
26. V26 — terminal fanout receipts;
27. V27 — идемпотентное Telegram membership state;
28. V28 — service/linking delivery kind;
29. V29 — аудируемая ручная повторная Telegram-доставка;
30. V30 — сохранение точного байтового представления report snapshot payload для integrity-проверок.
31. V31 — отдельное хранение raw и канонического validated LLM response с hash-проверкой перед
    публикацией;
32. V32 — подтверждённое разделение CARE на гарантию и протекцию с repair исходной классификации.

Ключевые инварианты:

- sale item хранит category/condition/financial snapshot; будущая переклассификация не меняет
  историю;
- возврат в KPI учитывается датой возврата и относится к продавцу исходной продажи;
- payroll-возврат уменьшает фонд дня и месяца оформления возврата, но использует товарную
  классификацию, действовавшую на дату исходной продажи;
- finalized rating snapshot и approved/paid payroll revisions неизменяемы;
- optimistic `version` используется изменяемыми агрегатами, но frontend передает ее только в тех
  request DTO, где поле действительно предусмотрено;
- raw версии hash-deduplicated по privacy allowlist, dashboard их не читает; legacy full raw имеет
  отдельную policy version и агрегированный inventory;
- cleanup сохраняет последнюю raw identity, последний terminal sync run/job, максимальную успешную
  SALES/RETURNS data-through boundary, evidence открытых quality issues и все финансовые/finalized
  snapshots; exact inventory сначала агрегируется до daily, затем до indefinite monthly;
- физическое удаление по умолчанию отключено; включение fail-fast требует policy approval, backup
  checkpoint и свежий restore test, а результат содержит remaining counts для reconciliation.

Актуальный schema oracle и migration tests покрывают последовательность V1–V32. Текущая локальная
БД использовалась для полного YandexGPT acceptance на агрегированных недельных метриках; история
acceptance-попыток в ней является тестовой и не должна переноситься в production. Фактические
row counts локального volume не являются контрактом проекта и перед релизом проверяются отдельными
reconciliation-командами.

Отдельная disposable E2E-БД 2026-08-01 была мигрирована до V21. На синтетическом зарплатном
графе в ней сохранена неизменяемая оплаченная ревизия и пройдены 39/39 HTTP-проверок формулы,
качества данных, idempotency, concurrency, lifecycle, freshness, store scope и audit trail.

Рабочая тестовая БД 2026-08-06 была обновлена V29→V30 штатным startup/Flyway-процессом. До
миграции обе строки `report_snapshots` с hash воспроизводили найденный JSONB normalization defect;
после миграции тип `payload` равен `text`, оба SHA-256 совпадают с точными UTF-8 байтами документа,
а `trg_report_snapshots_immutable` снова включён. Обе существующие ревизии прочитаны через
авторизованный API и SPA. Эти тестовые данные не переносятся в production.

## 5. Роли и безопасность

Роли:

- `ADMIN` — все активные магазины, все store-business actions, пользователи, схемы, классификация,
  sync, OpenAPI/Swagger и actuator metrics;
- `MANAGER` — все business actions только в назначенных активных магазинах;
- публичной регистрации нет.

Все три руководителя заказчика считаются главными, поэтому plan/rating/payroll mutations доступны
обеим ролям в рамках разрешенного магазина.

Security controls:

- server-side `JSESSIONID`, CSRF cookie/header, rotation session ID at login;
- idle timeout 30 минут, absolute timeout 12 часов, максимум 3 сессии; четвертая вытесняет старую;
- self-service sessions: opaque HMAC reference, last-seen/current без raw ID/IP/User-Agent,
  revoke one/all-other с CSRF и bounded audit signal;
- временный пароль ограничивает session до auth/session endpoints;
- login throttle: по умолчанию 5 email failures за 15 минут и 15-минутная блокировка; IP limit
  выше; ответ `429 LOGIN_THROTTLED` и `Retry-After`;
- client IP: automatic forwarded-header trust выключен, пустой proxy allowlist fail-closed;
  explicit CIDR chain разбирается справа налево, IPv6 throttle агрегируется по `/64`;
- API body: declared и реально прочитанные bytes ограничены 2 MiB; chunked/understated length
  fail-closed возвращает `413 PAYLOAD_TOO_LARGE` без отражения content или лимита;
- пароль: 12–128 Java characters, максимум 72 UTF-8 bytes, no control chars/common denylist,
  bcrypt cost 12;
- store-scoped `@PreAuthorize` и архитектурный тест против IDOR;
- explicit routes и финальный `anyRequest().denyAll()`;
- exact-origin CORS, Secure cookies в prod, SSRF allowlist/HTTPS для LiveSklad;
- webhook и upload endpoints отсутствуют;
- raw passwords/tokens/email/IP не пишутся в security audit.
- `SECURITY_AUDIT` и `BUSINESS_AUDIT` используют общий fail-closed SIEM envelope
  `event_schema_version=1`: точные allowlist полей, bounded outcome/severity и только HMAC references.

## 6. Реализованные пользовательские разделы

### Обзор

Read-only API дают:

- доступные магазины и рабочие часы;
- свежесть продаж/возвратов и lag;
- store KPI и cost-quality;
- категории и группы `PHONES`, `DEVICES`, `ADDITIONAL_REVENUE`;
- средний чек, допвыручку на телефон, цены и динамику;
- 12 attach-rate метрик;
- store-wide и period quality с recommended actions.

### Сотрудники

- список с current/dynamics;
- карточка сотрудника с предыдущим равным периодом и nullable payroll statement за полный месяц;
- подробный рейтинг с местом и четырьмя направлениями;
- включение/исключение сотрудника из будущего live ranking;
- фиксация закрытого периода в immutable snapshot.

Рейтинг и зарплата независимы. Рейтинг нужен руководителю и используется в ИИ-интерпретациях,
но не входит в payroll.

### План и смены

- один план на магазин и календарный месяц; персональных планов нет;
- цели: сумма выручки и доли аксессуаров, услуг, общей допвыручки;
- progress с `asOf`, календарным темпом, прогнозом и четырьмя независимыми status;
- replace-day график: `0.01..11.00` часа, `11.00` — полная смена 10:00–21:00;
- пустой список полностью очищает день.

Plan GET/PUT и отдельный ресурс полного дня графика возвращают strong ETag. Update требует
`If-Match`; создание отсутствующего плана — `If-None-Match: *`. Участие в рейтинге и payroll
mutations сохраняют свой явный optimistic version contract.

### Зарплата

Три плана проверяются независимо:

- revenue status выбирает только `TECH_TIER_1` 500/400 ₽ и `TECH_TIER_2` 300/200 ₽;
- accessory share выбирает только 20%/15% оборота аксессуаров;
- service share выбирает 20%/15% оборота обычных услуг и 20%/15% gross profit подписок
  PlayStation/платных ремонтов.

PS subscriptions и paid repair не дублируются в SERVICE turnover. Дневной фонд делится поровну
между участниками смены, не по часам и не по продавцу. Часы сохраняются для рейтинга и аудита.
Затем вычитаются advance 50 000 ₽ и активные `PENALTY`, `INVENTORY`, `TAX`; итог может быть
отрицательным.

Workflow:

```text
readiness -> preview -> CALCULATED -> APPROVED -> PAID
                         | adjust/void
                         | recalculate in place
APPROVED/PAID -> calculate with revisionReason -> new CALCULATED revision
```

Approval требует complete calculation и `freshness=CURRENT`. Изменение продаж, смен, плана,
классификации или схемы дает `STALE` и блокирует approve/paid до явного перерасчета.
Добавление или отмена удержания всегда продвигает `run.version`, даже если итоговые суммы не
изменились. Удержания допустимы только в `CALCULATED`; после `APPROVED`/`PAID` backend возвращает
`409 PAYROLL_STATE_CONFLICT`. Новая ревизия после этих статусов требует непустой
`revisionReason`, иначе возвращается `400 INVALID_ARGUMENT`.

### Отчёты

- показатели на главном экране рассчитываются динамически за выбранный период; backend независимо
  ограничивает период 366 днями;
- месячный отчёт создаётся автоматически в той же транзакции, в которой payroll переходит в
  `PAID`, и навсегда ссылается на конкретную payroll revision;
- годовой отчёт создаётся только после окончания календарного года и только из точных
  finalized-ревизий всех требуемых месяцев; первый год магазина может быть неполным начиная с
  `reportingStartedOn`;
- correction не изменяет существующий документ: создаётся новая immutable revision с обязательной
  причиной, а прежняя остаётся доступной;
- scheduler и административный backfill идемпотентны; архив доступен отдельным store-scoped API и
  frontend-разделом;
- JSON payload отчёта хранится как валидируемый `text`, чтобы SHA-256 покрывал точные UTF-8 байты
  и оставался воспроизводимым после PostgreSQL round-trip; V30 безопасно восстанавливает хеши
  ранее записанных JSONB-снимков;
- автоматического сравнения отчётов пока нет: документы накапливаются и просматриваются отдельно.

### Качество данных, профиль и ИИ-разбор

- `/quality` показывает readiness выбранного месяца и состояние всех доступных магазинов; backend
  reason/action codes переводятся в русские пользовательские формулировки и не выводятся как
  технические идентификаторы;
- `/profile` содержит данные учетной записи, активные сеансы и пользовательский lifecycle Telegram;
- `/insights` показывает недельный ИИ-разбор при включенном preview flag;
- ADMIN получает операционные экраны LLM jobs и Telegram deliveries/recovery;
- production feature flags LLM/Telegram остаются выключенными до выполнения соответствующих
  staging runbooks.

Frontend сохраняет спокойную визуальную иерархию Apple-подобного дизайна и «жидкое стекло» для
навигационных поверхностей. Responsive-приемка desktop/tablet/mobile и точные результаты тестов
зафиксированы в `docs/FRONTEND_ACCEPTANCE.md`.

## 7. Реализованные HTTP endpoints

Полная таблица request/response находится в `docs/FRONTEND_HANDOFF.md`; здесь — карта групп:

- auth: `/api/auth/csrf|login|me|change-password|logout`;
- stores/quality: `/api/stores`, `/data-status`, `/data-quality`, `/period-quality`,
  `/api/data-quality/summary`;
- KPI: `/kpi`, `/kpi/employees`, `/kpi/categories`, `/kpi/attach-rates`, `/kpi/averages`;
- employees/rating: `/employees`, `/employees/{employeeId}`, `/employee-ratings`, finalize,
  `/employee-rating-settings`;
- plan/schedule: `/performance-plans/{YYYY-MM}`, `/progress`, `/work-schedule`;
- payroll: readiness, preview, calculate, latest, runs, run detail, adjustments, approve, paid,
  compare;
- admin: users, rating schemes, payroll schemes, product payroll assignments and bulk assignment,
  analytics product import;
- reports: `/api/stores/{storeId}/reports`, detail by report ID and ADMIN
  `/api/admin/reports/backfill`;
- LLM: `/api/stores/{storeId}/insights/weekly/current`, ADMIN operations/regenerate/cancel;
- Telegram: linking status/link/confirm/unlink, exact webhook route и ADMIN delivery recovery;
- sync: единый durable jobs/backfill/cancel, bootstrap readiness и обязательный импорт утверждённой
  классификации до первого backfill.

## 8. Ошибки и frontend contract

Общий error shape:

```ts
interface ApiError {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  path: string;
  correlationId: string;
}
```

Основные реакции:

- protected `401 AUTHENTICATION_REQUIRED`/`SESSION_EXPIRED` — завершить local session;
- `403 ACCESS_DENIED` — перечитать user/stores и показать отсутствие права;
- `404 PERFORMANCE_PLAN_NOT_FOUND` — нормальный empty state создания плана;
- `409` version/state conflict — перечитать authoritative resource;
- `400 INVALID_ARGUMENT` при создании следующей payroll revision — запросить непустую причину;
- `409 CURRENT_SESSION_REQUIRES_LOGOUT` — обновить session list и использовать обычный logout;
- `409 PAYROLL_SOURCE_DATA_CHANGED` — показать freshness reasons и предложить recalculation;
- `409 RATING_PERIOD_NOT_CLOSED` — нельзя фиксировать текущий период;
- `413 PAYLOAD_TOO_LARGE` — прекратить отправку body и показать безопасную ошибку размера;
- `429 LOGIN_THROTTLED` — учитывать `Retry-After`;
- `422`/`502` sync errors — безопасное сообщение без upstream body.
- `500 INTERNAL_ERROR` — нейтральное сообщение; `correlationId` сохранять в transport diagnostics, но не показывать обычному пользователю.

Стабильные коды, типизированные исключения и правила журналирования описаны в `error-handling.md`.

`null` означает недоступное/ненадежное значение, не ноль. Enum должен иметь безопасный fallback,
но новый enum value считается потенциальным breaking change. URL пока `/api` без `/v1`.

## 9. Что ещё не включено или не реализовано

- production deployment/Nginx/public environment;
- внешний staging gate и production rollout LLM/Telegram (контур реализован, feature flags выключены);
- sync остатков;
- отдельную аналитику repair orders;
- персональные планы;
- ручные payroll-поощрения/оклад;
- ручное исправление себестоимости через публичный API;
- export Excel/PDF и file uploads;
- отмену finalized rating или обратные payroll status transitions.

## 10. Ближайшие практические шаги

1. Зафиксировать release candidate и повторить обязательные backend/frontend checks именно для
   продвигаемых image digests.
2. На восстановленной production-sized копии БД отрепетировать one-shot migration до V30,
   зафиксировать длительность table lock/update, reconciliation и rollback decision point.
3. Завершить Caddy/HTTPS, trusted proxy, production Compose/image promotion, secret delivery,
   monitoring и отдельные API/WORKER/MIGRATION runtime roles по production runbook.
4. Реализовать backup pipeline и доказать изолированное восстановление с измеренными RPO/RTO.
5. Провести staging smoke/E2E через реальный домен: login/logout, store scope, KPI, employees,
   plan, payroll, reports, sync/restart и безопасное поведение при недоступности LiveSklad.
6. Выбрать закрытый контрольный месяц и сверить рейтинг, period quality и payroll с ручным
   расчетом заказчика; остаток `UNMAPPED` должен быть принят явно.
7. Корректировки весов/ставок оформлять только новыми effective-dated схемами.
8. До отдельного staging rollout держать LLM/Telegram feature flags выключенными; затем выполнить
   их fault drills, quota/budget acceptance и alert delivery.
9. Открывать production пользователям только после закрытия всех launch gates из
   `production-deployment-runbook.md` и письменного одобрения заказчика.

## 11. Карта документации

Frontend начинает с:

- `docs/FRONTEND_HANDOFF.md` — статус, термины, DTO и endpoint tables;
- `docs/frontend-actions.md` — кнопки, enable rules, последствия и cache invalidation;
- `docs/FRONTEND_ACCEPTANCE.md` — фактические browser-сценарии, viewport-покрытие, результаты и риски;
- `docs/frontend-contract.md` — transport и compatibility baseline;
- `docs/authentication-api.md` — session/CSRF/password flow.

Бизнес/API:

- `store-directory-api.md`, `store-data-status-api.md`, `data-quality-api.md`,
  `period-quality-api.md`;
- `store-kpi-api.md`, `employee-kpi-api.md`, `category-kpi-api.md`, `average-kpi-api.md`,
  `attach-rate-api.md`;
- `store-plan-progress-api.md`, `employee-rating-api.md`, `payroll-api.md`;
- `reports.md` — immutable monthly/annual archive, revisions, backfill and integrity;
- `synchronization-api.md`, `product-category-import-api.md`.

Архитектура/операции:

- `architecture.md`, `database-design.md`, `security-hardening.md`, `supply-chain-security.md`;
- `audit-log.md`, `error-handling.md`, `observability.md`, `data-retention.md`;
- `llm-production-operations.md`, `yandexgpt-staging-acceptance.md`,
  `llm-response-validation.md`, `telegram-staging-acceptance.md`;
- `payroll-classification-review.md`;
- `livesklad-api-docs.md` — sanitized upstream discovery reference, не frontend contract;
- `analytics-business-rules-draft.md` — provenance подтвержденных правил;
- `employee-rating-salary-discovery.md` — superseded historical discovery, не источник текущей
  реализации.

## 12. Проверка backend

Так как локальная системная Java может быть 11, воспроизводимый полный check запускается в
Java 21 container с доступом к Docker/Testcontainers:

```bash
docker run --rm --add-host host.docker.internal:host-gateway \
  -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal \
  -e DOCKER_API_VERSION=1.44 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /home/pavel/analytical-platform-for-store:/workspace \
  -v /home/pavel/.gradle:/root/.gradle \
  -w /workspace eclipse-temurin:21-jdk-alpine \
  sh -lc "./gradlew --no-daemon :backend:check --rerun-tasks --console=plain"
```

