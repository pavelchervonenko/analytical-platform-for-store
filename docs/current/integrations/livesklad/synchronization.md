---
doc_schema: 1
doc_type: current
status: current
owner: integrations
audience:
  - developer
  - operator
last_verified: 2026-10-01
requirement_sources:
  - docs/archive/legacy-contracts/synchronization-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotWriter.java
  - backend/src/main/java/com/storeanalytics/sync
  - backend/src/main/java/com/storeanalytics/sync/service/EmployeeSyncBatchApplier.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/SellerMembershipHistoryWriter.java
  - backend/src/main/java/com/storeanalytics/product/model/Product.java
  - backend/src/main/java/com/storeanalytics/product/model/ProductDetails.java
  - backend/src/main/resources/application.yml
  - backend/src/main/resources/db/migration/V53__resolve_false_sale_typed_return_issues.sql
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/model/ProductSourceGroupObservationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/SyncJobIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/SyncJobWorkerTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/StoreSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/EmployeeSyncMembershipHistoryIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/OrderSyncIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - integration
  - backend-data
review_triggers:
  - sync-phase-change
  - retry-policy-change
  - livesklad-client-change
supersedes:
  - docs/archive/legacy-contracts/synchronization-api.md
superseded_by: null
---

# Синхронизация LiveSklad

## Durable lifecycle

Backfill и incremental sync принадлежат `sync_jobs`, а не HTTP request. Один connection имеет не
более одной active job. Каждое окно проходит фазы:

```text
STORES → EMPLOYEES → SALES → RETURNS → ORDERS → следующее окно
```

Cursor и phase коммитятся после каждого шага. Child attempts связаны через `sync_job_id`, lease
позволяет восстановиться после падения worker, а cancellation текущей фазы cooperative.

## Employee roster publication

Фаза EMPLOYEES сначала полностью считывает сотрудников всех активных магазинов. Затем
`EmployeeSyncBatchApplier` в одной транзакции проверяет состав активных магазинов, run/attempt/
lease, публикует нормализованные строки и отключение отсутствующих сотрудников, сверяет
интервалы истории для магазинов с явно утверждённым baseline и переводит run в `SUCCESS`.
Сбой или изменившийся во время чтения список магазинов откатывает весь batch; частичный
fetch не считается пустым roster. До baseline current projection работает по прежним правилам,
а история не угадывается задним числом. Ручной переключатель рейтинга сериализован тем же
store guard. Эта подготовка не включает исторический расчёт Weekly Review.

## Defaults source-tree

| Параметр | Default |
|---|---:|
| Window | 6 часов |
| Минимальное adaptive window | 15 минут |
| Incremental overlap | 3 дня |
| Maximum backfill | 730 дней |
| Attempts | 5 |
| Lease | 2 часа |

Это defaults из `application.yml`, не утверждение о production flags. Schedule creation default-off,
worker default-on. Фактические значения разрешено фиксировать только runtime evidence.

## Retry и source races

Rate limit, transport, retryable HTTP, transient DB, `LIVESKLAD_ORDER_CHANGED` и
`LIVESKLAD_RETURN_CHANGED` повторяются с bounded backoff. Source-capacity/rate pressure может
уменьшить child window. List/detail mismatch обычного изменяемого документа — source race, а не
безвозвратная ошибка.

Malformed/rejected payload и unclassified `LiveSkladException` завершаются permanent code
(`LIVESKLAD_PERMANENT` для последнего случая) и требуют анализа причины. Нельзя автоматически
повторять любой permanent failure, не уточнив классификацию.

Targeted webhook sync не запускает period-wide absence/deletion. Для продаж и заказов period sync
делает absence-based deletion только после полного успешного чтения соответствующей области.
Возвраты являются отдельным случаем и следуют правилу ниже.

Targeted sale-return sync читает кассовые операции в одном или двух десятиминутных окнах вокруг
`occurredAt` и `sourceUpdatedAt` выбранного документа и после валидации отбрасывает операции с
другим document ID. Пересекающиеся окна объединяются. Количество API-вызовов ограничено двумя на
комбинацию кассы и статьи `saleReturn`, а не числом документов в месяце. Автоматический повтор
только из-за `RETURN_CASH_TRANSACTION_MISMATCH` пока не включён: warning сохраняется после успешной
обработки и требует новой доставки либо точечного recheck.

Return-feed иногда повторно отдаёт обычную продажу с `detail.type=sale`. Если exact external ID уже
принадлежит активной продаже того же connection/store, normalizer сохраняет документ, позиции и
оплаты без изменений, помечает raw-version как skipped и закрывает ложный
`RETURN_ORIGINAL_DOCUMENT_MISSING`. Отсутствующий, deleted, foreign-store или не-SALE документ не
ослабляет guard: запись остаётся unresolved и требует диагностики.

Validated targeted recovery имеет два exact-document режима. `MISSING_RETURN` предназначен только
для отсутствующего факта. `EXISTING_ORPHAN_RELINK` требует существующий активный orphan и полный
набор ожидаемых current employee, original sale/item/employee, product, quantity, net и cost
значений. Nullable current employee проверяется буквально: `null` требует отсутствия employee, а
external ID — точного совпадения. Режим не является period backfill: worker получает и нормализует
один return, а транзакция откатывается при любом расхождении source или текущего DB state.

Для возвратов child window фильтрует кассовые операции, а не дату документа. LiveSklad может
провести возврат денег спустя несколько часов после создания документа, поэтому detail допустимо
находиться в другом child window. `business_date` при этом сохраняется по самому документу.
Отсутствие возврата в отдельном кассовом окне не является доказательством удаления: soft-delete
выполняется только по явному source-событию `delete`.
Cash API использует диапазон `date=[start,end]`, поэтому клиент допускает запись ровно на границе
`end`; повтор той же транзакции в соседнем child window безопасен благодаря идемпотентности по
source ID и версии.

## Coverage и API

ADMIN API из OpenAPI v13 создаёт backfill, читает readiness/list/detail и запрашивает cancel.
Backfill dates включительны в reporting zone; внутри хранятся instant-полуинтервалы. Создание
требует effective classification на начало периода и ограничено 730 днями.

Freshness магазина использует минимум coverage SALES, RETURNS и ORDERS. Public data-status DTO пока
не раскрывает отдельную дату ORDERS; gap описан в
[`../../api/store-data-status.md`](../../api/store-data-status.md).

## Отсутствующие сведения о группе товара

Sale/order/return position DTO не содержат подтверждённую группу каталога.
Их ProductDetails с null-группой означают `sourceGroupObserved=false`, а не просьбу очистить
`products.source_group_id`. Product.updateFromLiveSklad сохраняет ранее известную группу,
обновляя остальные доступные сведения; повтор равного наблюдения остаётся идемпотентным.
Это также относится к claimLiveSkladIdentity, который использует тот же метод обновления.

Присланная группа должна принадлежать тому же source/connection. Явное отсутствие допускается
только через наблюдение с `sourceGroupObserved=true` и null; старое/недатированное наблюдение
не обходит существующий timestamp guard. Обычные document-потоки эту команду не создают.
Признак sourceGroupObserved — входной контракт модели, не новый столбец БД и не дата действия
аналитической категории.

Получение групп выбрано через Excel; пока реализован только офлайн-preview, без записи в БД.
См. [подготовку импорта групп](../../product/classification.md#подготовка-импорта-исходных-групп-из-excel).
Подключение группы к классификации и исторический пересчёт этой правкой не включаются.

## Инварианты

- Raw payload version hash делает повторное чтение идемпотентным.
- Все необходимые pages/details валидируются до normalization transaction.
- Никакой token, credential, upstream body или PII не входит в error summary.
- Полный historical backfill не заменяет ежедневный overlap и webhook correction path.

## Необязательная shadow-проекция роли новых строк

SalesSyncPersistence/ReturnSyncPersistence вызывают CatalogSaleRoleSnapshotWriter только
после создания новой строки. По умолчанию writer ничего не делает; включение требует
явной даты начала через `app.catalog-compatibility.snapshots-from` и отдельного
`app.catalog-compatibility.snapshots-enabled`. Это контракт исходников, не состояние сервера.
При включении JPA flush и запись снимка входят в ту же транзакцию sync: ошибку нельзя
молча проглотить, запись откатывается вместе с фактом. Нагрузка включает дополнительный
flush на новую строку; перед включением нужен bounded local sync/performance acceptance.

Существующие строки и факты до даты начала не заполняются автоматически. Снимок продажи
использует имя строки и известную при приёме группу объединённой карточки; последняя не
выдаётся за полученную из старого документа. Возврат наследует снимок оригинала или
остаётся на legacy-пути. Исходные деньги/категории/зарплаты, порядок фаз и retry-policy
не изменены. Shadow-таблицы не подключены к официальным метрикам; формат и ограничения
описаны в [классификации](../../product/classification.md).
