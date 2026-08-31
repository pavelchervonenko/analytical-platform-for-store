---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/archive/legacy-contracts/database-design.md
implementation_sources:
  - backend/src/main/resources/db/migration
  - backend/src/main/java/com/storeanalytics
verification_sources:
  - backend/src/test/java/com/storeanalytics/sync/service/StoreSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/OrderSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/integration/livesklad/webhook/LiveSkladWebhookStoreIntegrationTest.java
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
Текущий source-tree заканчивается V48 и включает отдельную decimal-версию V39.1. Применённую в
конкретной БД версию можно утверждать только после чтения `flyway_schema_history`.

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

Классификация, стоимость и наименование позиции фиксируются в item snapshot и не меняются задним
числом после переклассификации товара. Finalized rating/report snapshots и опубликованные weekly
review artifacts append-only; корректировка создаёт новую ревизию с provenance и hash.

### Durable lifecycle

`sync_jobs`, `sync_runs`, report/AI jobs, notification outbox и
`livesklad_webhook_receipts` — изменяемые lifecycle records. Завершённый бизнес-артефакт и его job
имеют разные правила неизменяемости.

## Инварианты LiveSklad

- Внешняя identity уникальна внутри integration connection.
- Sale list/detail должны совпасть по identity, номеру, времени, типу и магазину до записи facts.
- Возвраты группируются по document ID; linked item наследует классификационный snapshot исходной
  продажи.
- После V43 orphan return разрешён: возврат может быть сохранён без найденной исходной продажи и
  связан позже. Старое правило «каждый return обязан ссылаться на sale» больше не действует.
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
- Webhook store tests проверяют dedupe, delivery count, mismatch, leases, retries и completion.

Полная migration compatibility описана отдельно в [`migrations.md`](migrations.md). Формулы KPI и
решение об employee attribution возврата не являются частью физической модели данных.
