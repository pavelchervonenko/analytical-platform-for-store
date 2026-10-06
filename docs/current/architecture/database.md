---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - operator
last_verified: 2026-10-06
requirement_sources:
  - docs/archive/legacy-contracts/database-design.md
implementation_sources:
  - backend/src/main/resources/db/migration
  - backend/src/main/java/com/storeanalytics
verification_sources:
  - backend/src/test/java/com/storeanalytics/sync/service/StoreSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/OrderSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/service/ProductClassificationReconciliationServiceTest.java
  - backend/src/test/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladWebhookStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/UserFeatureAccessMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/WorkShiftStoreScopeMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/SaleTypedReturnIssueMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/ZeroCostSeverityMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/performance/service/OptimisticConcurrencyIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - integration
review_triggers:
  - migration
  - persistence-model-change
  - retention-change
supersedes:
  - docs/archive/legacy-contracts/database-design.md
superseded_by: null
---

# Модель данных

## Источник истины

Результирующую схему определяет упорядоченная цепочка Flyway migrations, а не этот текст и не JPA.
Полный набор колонок и ограничений определяют миграции, включая отдельные decimal-версии.
Применённую в конкретной БД версию можно утверждать только после чтения `flyway_schema_history`.

## Основные слои

### Raw evidence

`raw_record_versions` хранит дедуплицированные версии наблюдаемых provider records. Нормализация
использует hash/identity raw-версии, но dashboard API никогда не отдаёт raw payload.

### Нормализованные факты

Магазины, сотрудники, товары, продажи, возвраты, заказы, позиции и платежи имеют стабильные
внутренние identity и внешний identity в рамках connection. Продажи и возвраты хранят положительные
amounts; знак метрики задаёт `document_kind`.

Source corrections обновляют актуальную нормализованную проекцию с optimistic locking. Исчезнувшие
из подтверждённой полной выборки факты soft-delete, а повторно появившиеся re-activate. Dashboard
агрегирует нормализованные факты set-based SQL.

### Снимки и ревизии

Классификация, стоимость и наименование позиции фиксируются в item snapshot. Обычное изменение
товарного справочника не переписывает уже классифицированные позиции. Единственное явное исключение
для классификации — точечная reconciliation активных `UNMAPPED`-позиций по каноническим product IDs
внутри одного exact integration connection; она не меняет уже классифицированные позиции.
Связанный возврат при reconciliation наследует snapshot исходной продажи, поэтому обе позиции не
могут получить разные аналитические категории.

Finalized rating/report snapshots и опубликованные weekly
review artifacts append-only; корректировка создаёт новую ревизию с provenance и hash.

### Durable lifecycle

`sync_jobs`, `sync_runs`, report/AI jobs, notification outbox и
`livesklad_webhook_receipts` — изменяемые lifecycle records. Завершённый бизнес-артефакт и его job
имеют разные правила неизменяемости.

### Доступ пользователей

`user_store_access` задаёт доступ руководителя к магазинам, а добавленная V50 таблица
`user_feature_access` — независимый глобальный доступ к функциям `PLAN`, `SHIFTS`, `PAYROLL`.
Составной primary key `(user_id, feature)` запрещает дубли, enum ограничен CHECK constraint, а
`granted_by` и `granted_at` сохраняют provenance назначения. Для `ADMIN` явные строки не нужны:
роль даёт все магазины и функции. V50 backfill назначает все три функции каждому существовавшему на
момент миграции `MANAGER`, чтобы обновление не отняло ранее доступные разделы; новые руководители
получают только явно выбранные функции.

`employee_work_shifts` имеет составной внешний ключ назначения `(employee_id, store_id)` и после
V52 уникальность `(store_id, employee_id, work_date)`. Поэтому общий employee identity может иметь
независимые смены в двух магазинах в одну дату, но не может получить две записи одной даты внутри
одного магазина. `work_schedule_day_revisions` использует ту же границу магазина и даты.

## Инварианты LiveSklad

- Внешняя identity уникальна внутри integration connection.
- Sale list/detail должны совпасть по identity, номеру, времени, типу и магазину до записи facts.
- Возвраты группируются по document ID; linked item наследует классификационный snapshot исходной
  продажи.
- Окно получения возвратов определяется временем cash transaction, а `business_date` — временем
  return detail. Эти значения могут попадать в разные adaptive windows; отсутствие detail ID в
  отдельном cash window не означает удаление.
- После V43 orphan return разрешён: возврат может быть сохранён без найденной исходной продажи и
  связан позже. Старое правило «каждый return обязан ссылаться на sale» больше не действует.
- V49 добавляет durable режим `EXISTING_ORPHAN_RELINK`: inbox хранит exact nullable current return
  employee, ожидания original sale/employee и JSON-массив соответствий позиций. Уникальность
  recovery задаётся парой `(source_document_id, recovery_mode)`; проверенный missing-return и
  последующий orphan-relink не смешиваются в одну операцию.
- Targeted webhook sync не выполняет period-wide deletion.
- Orders, sales и returns имеют независимое coverage; их минимум определяет freshness магазина.

## Webhook inbox

Inbox дедуплицирует `(webhook_kind, event_id)`, сохраняет первый canonical payload и считает
повторные доставки. Hash последующей доставки сравнивается с первым; расхождение фиксируется в
`payload_mismatch`, а первый payload не переписывается.

Сейчас inbox сохраняет выбранный JSON payload целиком. Для него не найден такой же retained-field
allowlist, как у `raw_record_versions`, и не определена отдельная retention policy. Это открытый
privacy/retention gap: документ не объявляет payload безопасным или бессрочно допустимым.

## Проверяемые свойства

- Flyway и Hibernate сверяют physical columns, enums и numeric precision в интеграционных тестах.
- Sync tests проверяют idempotency, corrections, soft deletion/reactivation и rollback до
  частичной нормализации при provider failure.
- Webhook store tests проверяют dedupe, delivery count, mismatch, leases, retries, completion и
  сохранение exact orphan-relink expectations. Return integration tests проверяют атомарный relink
  и rollback при изменившемся DB state.

Полная migration compatibility описана отдельно в [`migrations.md`](migrations.md). Формулы KPI и
решение об employee attribution возврата не являются частью физической модели данных.

## Состояние historical SALE refresh

`historical_sales_refresh_state` в новой непубликованной миграции хранит connection, явную дату
начала, cursor и фиксированный конец цикла, active job, HTTP budget и блокировку независимо
от retention job. Exact paired RETURN/parent UUID arrays и required flag сохраняют cohort;
БД проверяет размеры, отсутствие null и обязательность identities. Продвижение cursor разрешает
успешное fenced выполнение; для dependency block covering manual BACKFILL SUCCESS дополнен
accepted parent/child publications и current inherited snapshots каждого retained RETURN.
State не обозначает полноту всех sync-фаз.
Область dependent repair и область cursor имеют разный смысл: возврат может произойти позже
продажи. Не удалять state и не переносить начало как способ обхода блокировки.

См. [операционную процедуру](../../runbooks/livesklad-historical-sales-refresh.md).
