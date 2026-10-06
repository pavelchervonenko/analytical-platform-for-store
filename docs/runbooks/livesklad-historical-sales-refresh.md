---
doc_schema: 1
doc_type: runbook
status: draft
owner: integrations
audience:
  - operator
last_verified: 2026-10-06
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - staging
  - production-read-only
operation_type: recovery
environments:
  - staging
  - production
risk_level: high
source_of_truth:
  - backend/src/main/java/com/storeanalytics/common/config/HistoricalSalesRefreshProperties.java
  - backend/src/main/java/com/storeanalytics/sync/service/HistoricalSalesRefreshService.java
  - backend/src/main/java/com/storeanalytics/sync/service/HistoricalSalesRefreshBatchApplier.java
  - backend/src/main/java/com/storeanalytics/sync/service/HistoricalSalesDependencyGuard.java
  - backend/src/main/java/com/storeanalytics/sync/service/KnownReturnRefreshReader.java
  - backend/src/main/java/com/storeanalytics/sync/service/HistoricalSalesRefreshMetrics.java
  - backend/src/main/resources/db/migration/V94__add_historical_sales_refresh.sql
  - deploy/bin/historical-sales-release-safety.sh
  - deploy/compose.production.yml
verification_evidence:
  - level: static
    scope: explicit start, bounded worker configuration, API scheduler disabled and release env validation
    verified_at: 2026-10-06
    evidence: scripts/tests/historical-sales-release-safety-test.sh
required_reviewers:
  - integration
  - backend-data
  - operations
review_triggers:
  - historical-sync-change
  - production-recovery
  - provider-budget-change
supersedes: []
superseded_by: null
---

# Повторная проверка старых продаж

## Статус и область

Начальный механизм прошёл targeted unit/PostgreSQL проверку и независимый review.
Dependency repair и BACKFILL known-return refresh прошли 178 targeted tests и independent review;
Полный exact RC gate также прошёл: 2 000 tests и отдельная OpenAPI-проверка, ноль failures/errors/skips.
CI, production activation и runtime acceptance остаются отдельными проверками.
Runbook остаётся `draft` до проверки release candidate, staging и fresh production-read-only
предусловий. Развёртывание выполняется по [production procedure](production-deployment.md).

Обычный incremental читает даты продаж в overlap; изменение названия за пределами этого окна
не переносит старую продажу в новое окно. Historical refresh повторно читает полную SALE выборку
малого интервала по всем активным магазинам connection. Это не чтение двух документов по ID:
полнота страниц и правила отсутствующих документов сохраняют значение.

Новая задача `HISTORICAL_SALES` выполняет только SALES. Она не доказывает полноту RETURN,
ORDERS, справочников, оплат всей истории или недельных all-phase snapshots. Бизнес-категории,
гарантийные связи и исправление исторических возвратов имеют собственные процедуры и решения.
`CURRENT` обычного sync не является результатом проверки всей истории.

## Подготовка области

1. Зафиксировать включительную дату начала в бизнес-зоне `Europe/Kaliningrad`, исходные отчёты,
   известный cutoff, IDs документов/позиций и read-only baseline. Согласовать, какую историю
   проверяем; не выбирать нижнюю границу автоматически по первому найденному документу.
2. Проверить classification readiness для начала периода. Не менять исходную границу активации
   каталога и не назначать неизвестные категории для обхода guard.
3. Проверить успешный routine INCREMENTAL за текущий бизнес-день, отсутствие конфликтующего
   sync/recovery, реальный общий provider budget и влияние на обычную загрузку.
4. Показать change owner конкретный релиз, область, параметры, backup/restore evidence,
   наблюдение и условия остановки. Activation не следует из успешного read-only экспорта.

## Параметры

Worker получает `SYNC_HISTORICAL_SALES_ENABLED`, `START_DATE`, `WINDOW_MINUTES`,
`MAX_REQUESTS_PER_STEP`, `MAX_REQUESTS_PER_DAY`, `CYCLE_INTERVAL`, `ENQUEUE_DELAY` с общим
префиксом `SYNC_HISTORICAL_SALES_`. Пример поставляется выключенным; API scheduler принудительно
выключен. Enabled требует явной ISO LocalDate, работающего sync worker и routine schedule.

Окно — 15–180 минут; HTTP attempts — максимум 100 на шаг и 200 за бизнес-день, суточный лимит
не ниже лимита шага. Можно выбрать меньшие бюджеты. Учитываются auth и повторы; общий limiter
компании остаётся обязательным. Cycle interval — 1 час–31 день. Release preflight принимает
интервалы как положительное целое с `s`, `m`, `h`, `d`; enqueue delay должен быть положительным.
Это ограничения конфигурации исходников, а не наблюдённые настройки production.

Дата начала сохраняется в `historical_sales_refresh_state`; её изменение требует отдельной
проверки существующего cursor, а не удаления state. Конец цикла фиксируется перед текущим
overlap. Успешный шаг продвигает cursor; после завершения цикла следующий начинает ту же
явную область с новой верхней границей. Приоритет имеет текущая синхронизация.

## Блокировки и восстановление

Ошибка чтения, неполная выборка, потеря lease или отмена не разрешают частичную публикацию.
Исчерпание бюджета не считается пустым ответом. После permanent failure cursor остаётся на
непроверенном интервале; блокировка хранится независимо от retention завершённого job.
Не удалять её SQL-командой и не принимать исчезновение старого job за успех.

Изменение SALE может затронуть сохранённые поля связанного RETURN. Проверка зависимостей
должна остановить публикацию такого изменения, пока не проведено согласованное повторное
чтение зависимой области. Это относится и к snapshot name: действующая SETUP_SERVICE методика
использует имя строки, поэтому переименование SALE при прежнем RETURN может изменить N.
Clock-only observation без изменения snapshot полей допускается. Обычный BACKFILL проходит все фазы; выбранные включительные даты
должны покрывать SALE и все затронутые RETURN occurrence dates. BACKFILL только дня продажи
не гарантирует исправление возвратов других дней. В блокировке сохраняются exact пары
RETURN/parent IDs, а covering BACKFILL SUCCESS является только одним предусловием.
Перед продвижением проверяются accepted parent публикация repair job и каждый зависимый
RETURN: текущие raw/run pointers, source/scope, original links и inherited snapshot поля.
DB publication clocks сравниваются внутри одной clock domain; raw last_seen не заменяет
accepted normalization. Partial-refund суммы и количество сохраняются независимо от SALE.
Исчезновение или перенос child, более позднее изменение parent и stale raw оставляют block.
Явно удалённый RETURN принимается только с accepted source delete evidence.

RETURNS фаза durable BACKFILL перечитывает известные активные возвраты по их occurrence dates,
включая cashless, и объединяет их с кассовыми candidates. Soft-deleted факты не воскрешаются
по retained IDs. Если число известных фактов превышает 70, шаг останавливается до detail reads;
worker штатно уменьшает child window, а превышение лимита минимального окна требует анализа.
Не продолжать по обрезанной выборке. При concurrent accepted child observation между fetch
и publication exact entity-version guard сохраняет новый факт; `LIVESKLAD_RETURN_CHANGED`
повторяет чтение по существующему bounded retry контракту. Equal source clocks сами по себе
не запрещают correction. Отслеживать retry exhaustion и сохранённый scope/attempt.
Этот путь не обнаруживает неизвестные cashless документы. Обычные validated recovery режимы
missing/orphan не являются командой refresh для уже связанного RETURN. Повторная доставка
того же обработанного webhook также не доказывает новое normalization.

Перед BACKFILL повторить read-only expectations, classification readiness, backup/queues и
provider capacity. Запрос выполняется через обычную ADMIN/CSRF сессию на
`POST /api/sync/jobs/backfill`; `periodStart` и `periodEndInclusive` задаются явно. Это отдельная
операция записи по review конкретной области, без credentials или готовых target IDs в документе.

## Наблюдение и остановка

Проверять `storeanalytics.sync.history.pending_span_seconds`, `pending_cycle_age_seconds`,
`last_success_age_seconds`, `completed_cycle_age_seconds`, `request_attempts_today`, `blocked`.
Все имена после первого имеют общий префикс `storeanalytics.sync.history.`. Первый незавершённый
цикл отслеживается отдельно; неизвестное значение не заменяется нулём. Наличие метрик не
доказывает подключённую доставку оповещений.

Остановить activation при деградации routine sync, неожиданных financial/identity deltas,
нарушении shared quota, permanent block, потере наблюдаемости или росте очередей/ошибок.
Выключение scheduler останавливает создание новых заданий; уже созданное задание требует
штатной отмены через `POST /api/sync/jobs/{jobId}/cancel` и проверки terminal status.

Application rollback проверяется отдельно по packaged schema range и enum support. После
создания `HISTORICAL_SALES` rows прежний runtime, не знающий этот тип, не считается совместимым;
выключение scheduler не удаляет такие rows и не делает rollback безопасным. Не удалять задания
или миграцию ради обхода проверки: при несовместимости требуется reviewed forward-fix.

После первого успешного цикла повторить документы, позиции, source-local names, финансовые
значения и доступные API показатели для согласованной области. Отдельно проверить seller
атрибуцию, категории, гарантии, attach N/B и опубликованную историю рейтинга. Только после
этой проверки закрывать исходное расхождение и подтверждать фактический срок обновления.
