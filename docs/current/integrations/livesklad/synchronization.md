---
doc_schema: 1
doc_type: current
status: current
owner: integrations
audience:
  - developer
  - operator
last_verified: 2026-10-06
requirement_sources:
  - docs/archive/legacy-contracts/synchronization-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/integration/livesklad/client/LiveSkladListingCompleteness.java
  - backend/src/main/java/com/storeanalytics/integration/livesklad/client/HttpLiveSkladClient.java
  - backend/src/main/java/com/storeanalytics/integration/livesklad/client/HttpLiveSkladOrderClient.java
  - backend/src/main/java/com/storeanalytics/sync/service/HistoricalSalesRefreshService.java
  - backend/src/main/java/com/storeanalytics/sync/service/HistoricalSalesRefreshBatchApplier.java
  - backend/src/main/java/com/storeanalytics/sync/service/HistoricalSalesDependencyGuard.java
  - backend/src/main/java/com/storeanalytics/sync/service/KnownReturnRefreshReader.java
  - backend/src/main/resources/db/migration/V94__add_historical_sales_refresh.sql
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
  - backend/src/test/java/com/storeanalytics/integration/livesklad/client/HttpLiveSkladClientTest.java
  - backend/src/test/java/com/storeanalytics/integration/livesklad/client/HttpLiveSkladOrderClientTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/SalesSyncSourceNameTest.java
  - backend/src/test/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/product/model/ProductSourceGroupObservationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/SyncJobIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/SyncJobWorkerTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/StoreSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/EmployeeSyncMembershipHistoryIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/KnownReturnBackfillIntegrationTest.java
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

`sync_jobs.attempt_count` — счётчик неудач текущего шага, а не номер попытки с единицы.
Первая обработка имеет значение `0`; успешный переход фазы и уменьшение окна сбрасывают его
в `0`. `SyncExecutionContext.jobAttempt` передаёт этот счётчик без прибавления единицы:
публикация сотрудников сверяет его с сохранённым значением вместе с действующей lease,
фазой, подключением и отсутствием отмены. Нулевое значение допустимо, отрицательное — нет.
Проверки: `SyncExecutionContextTest` и `SyncJobExecutionServiceTest` покрывают первый запуск
всех пяти фаз, backfill/incremental и сохранение счётчика при повторе.

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

Targeted webhook sync не запускает period-wide absence/deletion. Для продаж period sync
разрешает удаление по отсутствию только после полного успешного чтения соответствующей области.
Для заказов удаление отсутствующей нормализованной позиции ограничено полностью принятым detail
одного заказа и его source version; отсутствие заказа в changed-listing не удаляет весь период.
Возвраты являются отдельным случаем и следуют правилу ниже.

SALE, CASH и ORDER listing используют единый completeness guard. Declared total должен быть
неотрицательным, одинаковым на всех страницах и равным числу принятых строк на terminal page.
Пустая/короткая страница до этого числа, excess, изменение total и переход null ↔ declared
завершают чтение ошибкой до публикации фактов или успешного coverage. Если total отсутствует
на всех страницах обычного listing, сохраняется bounded short-page fallback; это не доказывает
независимую полноту источника. Historical SALE требует declared total. EMPLOYEES endpoint
с собственным no-total shape не меняется. Page caps, unique-ID, payload и HTTP budget guards
сохраняются. Targeted before/after: 14 failures до коррекции, 69 checks после, Checkstyle PASS;
real provider occurrence и production monetary impact этим не подтверждены.

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

В рабочей копии durable BACKFILL дополнительно перечитывает уже сохранённые активные
`LIVESKLAD/saleReturn` документы выбранных connection/stores по occurrence-полуинтервалу.
Они объединяются с полной CASH выборкой по external ID: известный cashless возврат получает
detail, платный читается один раз, deleted source candidate сохраняет явную deletion semantics.
Soft-deleted факты не включаются по сохранённым IDs. Максимум 70 unique detail candidates;
чтение 71 известного факта прекращает шаг до fetch/publication, без silent truncation.
Все details проверяются до транзакции; publication повторно проверяет BACKFILL RETURNS phase,
exact attempt, window, requestedBy, cancel/lease и current anchors под job → connection locks.
Версия каждого известного документа должна совпадать с preimage до detail fetch. Concurrent
accepted normalization, даже при равном source clock/null dateChange, вызывает существующий
`LIVESKLAD_RETURN_CHANGED` и bounded retry с новым чтением; ранее принятое observation сохраняется.
Равные source clocks без вмешавшейся записи не запрещают correction. Это не определяет порядок
изменений источника, ещё не наблюдавшихся приложением.
Потеря условий после flush откатывает весь batch. MANUAL, INCREMENTAL и webhook paths этим
расширением не меняются. Это refresh известных фактов, не независимое обнаружение неизвестных
cashless возвратов; последнее всё ещё требует source index/export или подтверждённой доставки.
Все 24 operational PostgreSQL cases и независимый final review проходят. Полный exact RC gate
также прошёл: 2 000 tests и отдельная OpenAPI-проверка, ноль failures/errors/skips.
CI и runtime acceptance ещё не завершены.

Для возвратов child window фильтрует кассовые операции, а не дату документа. LiveSklad может
провести возврат денег спустя несколько часов после создания документа, поэтому detail допустимо
находиться в другом child window. `business_date` при этом сохраняется по самому документу.
Отсутствие возврата в отдельном кассовом окне не является доказательством удаления: soft-delete
выполняется только по явному source-событию `delete`.
Cash API использует диапазон `date=[start,end]`, поэтому клиент допускает запись ровно на границе
`end`; повтор той же транзакции в соседнем child window безопасен благодаря идемпотентности по
source ID и версии.

## Coverage и API

ADMIN API создаёт backfill, читает readiness/list/detail и запрашивает cancel.
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

## Необязательная запись роли новых строк

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
не изменены. Сохранённые роли читаются catalog-проекциями attach v3/v4; для отсутствующего
или legacy-снимка сохраняется прежний путь. Это связь в коде, не доказательство включения
writer на сервере. Формат, per-sale проверки и ограничения — в
[классификации](../../product/classification.md).

## Старые продажи и повторная проверка

Overlap выбирает продажи по occurrence date; позднее изменение имени не переносит старый документ
в новый диапазон. Accepted SALE item snapshot использует имя собственного source detail,
независимо от более поздней общей карточки товара. Категория и condition сохраняют действующие
правила event-time resolver; source-local name не является категорией.

Новый `HISTORICAL_SALES` job выполняет только SALES, читая полную выборку малого интервала всех
активных магазинов. Default-off, явная дата начала, durable cursor, бюджеты HTTP attempts,
приоритет routine sync и проверка lease/cancel ограничивают выполнение. SALE-only SUCCESS
не подтверждает all-phase coverage для weekly snapshots. Неполная объявленная выборка не
разрешает запись; изменение materialized зависимостей связанных RETURN требует rollback и
согласованного BACKFILL по сохранённой области. Блокировка переживает retention job.

Конфигурация, пределы, восстановление и наблюдение: [historical refresh runbook](../../../runbooks/livesklad-historical-sales-refresh.md).
Начальные 127 targeted unit/PostgreSQL checks и независимый review прошли. Позднее выявлен
неполный repair postcondition: один covering BACKFILL SUCCESS не доказывает обновление
связанного cashless RETURN. Durable block теперь сохраняет exact RETURN/parent UUID pairs
независимо от job retention. Перед advance нужны accepted parent publication этого BACKFILL
и accepted RETURN observations для всех сохранённых identities: согласованные raw/run pointers,
connection/store, original links и текущие inherited employee/classification/name snapshots.
Физические DB publication clocks сравниваются между собой; raw last_seen не считается
обновлением факта. Деньги и количество partial refund не приравниваются к исходной продаже.
Moved, missing, unsupported или stale dependency сохраняет block; deleted RETURN требует
accepted explicit deletion evidence. Финальные 178 targeted tests, Checkstyle и independent
review проходят; полный exact RC gate также прошёл. CI, production activation и runtime
acceptance не выполнены. Snapshot name входит в linked RETURN
postcondition: переименование может изменить SETUP_SERVICE N, а clock-only observation допускается.
Runtime `CURRENT`
обычной загрузки не подтверждает повторное чтение всей истории.
