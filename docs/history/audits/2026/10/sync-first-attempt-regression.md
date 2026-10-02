---
doc_schema: 1
doc_type: evidence
status: historical
owner: integrations
audience:
  - developer
  - operator
last_verified: 2026-10-02
snapshot_date: 2026-10-02
verdict: PASS
verdict_scope: Read-only incident diagnosis and local regression reproduction only; production recovery and AI generation pending.
verification_levels:
  - static
  - local
  - production-read-only
source_of_truth:
  - docs/current/integrations/livesklad/synchronization.md
  - docs/current/project-state.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/sync/service/SyncExecutionContext.java
  - backend/src/main/java/com/storeanalytics/sync/service/SyncJobCoordinator.java
  - backend/src/main/java/com/storeanalytics/sync/service/SyncJobExecutionService.java
  - backend/src/main/java/com/storeanalytics/sync/service/EmployeeSyncBatchApplier.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/sync/service/SyncExecutionContextTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/SyncJobExecutionServiceTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/SyncFirstAttemptIntegrationTest.java
required_reviewers:
  - backend-data
  - operations
---

# Регрессия первой попытки синхронизации: диагностика 2 октября

## Production read-only evidence

Владелец выполнил `october01-sync-readonly-audit.sh` и передал очищенный вывод 2026-10-02.
Транзакция `BEGIN READ ONLY` завершилась `ROLLBACK`. Экспорт не содержит employee/customer
данных, credentials, environment dumps или provider payloads. Точное время экспорта не указано:
дата выше — дата получения. Никакая синхронизация этим аудитом не запускалась.

Подтверждены identity установленного release, schema и здоровье трёх сервисов; значения отражены
в [project-state](../../../../current/project-state.md). Это не проверка всех feature flags.

- Задание `a0a34308-ba71-42fb-9858-cf32351763b9` имеет `phase=STORES`, `status=FAILED`,
  период `[2026-09-29 00:00, 2026-10-02 00:00)` в `Europe/Kaliningrad`.
- Оно началось 2026-10-02 03:15:03.489135 и завершилось 03:15:03.523007 по Калининграду.
  `attempt_count=1` — уже после обработки первой ошибки. Child run для этого задания отсутствует.
- Последний успешный job завершил период до 2026-10-01 00:00. Его STORES run успешно прочитал
  два магазина; retained store fields имеют тип string. Это не проверка текущего API ответа.
- За 1 октября у обоих магазинов отсутствует SUCCESS coverage всех трёх financial scopes:
  SALES, RETURNS, ORDERS. Наличие отдельных webhook-фактов не даёт полного покрытия периода.
- На эту дату имеются четыре возврата: `F000482`, `F53`, `F54`, `F55`. Исходные продажи для
  каждого ещё отсутствуют в приложении; последние два имеют один source parent.
- Пять более старых orphan returns МАГАЗИН — `F000061`, `F000066`, `F000086`, `F000096`,
  `F000122` — также имеют отсутствующие parent documents. Их причины и восстановление нельзя
  считать решёнными загрузкой 1 октября.

UI-время обнаружения может отображаться в часовом поясе клиента. Бизнес-период и времена
SQL выше явно приведены к `Europe/Kaliningrad`; разница с `Europe/Moscow` составляет один час.

## Установленная причина и локальное воспроизведение

В source tree соответствующие файлы совпадали с установленным release commit.
`SyncJob.claim()` не увеличивает счётчик, а `SyncJobCoordinator` передаёт в claim сохранённый
`attempt_count=0`. `SyncJobExecutionService` до вызова любого phase service создаёт
`SyncExecutionContext` с этим значением. Новая проверка `jobAttempt < 1` отвергала первую
попытку с `IllegalArgumentException`. Поэтому ни HTTP-запрос фазы, ни создание child run
ещё не выполняются. `retryOrFail` затем увеличивает persisted failure counter до `1`.

Первый запуск `SyncExecutionContextTest` на неисправленном коде: 4 теста, один failure,
`acceptsZeroFailureCounterForFirstDurableAttempt` с `IllegalArgumentException`.
Это воспроизведение конкретного дефекта приложения, не объяснение гипотетическим сбоем CRM.
Ранее lifecycle test проходил через claim/completeStep, но не вызывал реальный execution service.

## Исправление и проверка

Проверка контекста изменена на `jobAttempt < 0`; нулевая первая попытка допустима.
Счётчик не перенумеровывается: employee publication fence по-прежнему сравнивает контекст
с `sync_jobs.attempt_count`, phase, connection, lease и cancel flag. Manual identity guards,
retry limits, даты, classification и финансовые формулы не изменены.

Локально пройдены 44 теста без skips: контекст (4), execution всех фаз для первого incremental,
первого backfill и retry (15), worker (10), PostgreSQL durable lifecycle (15).
`checkstyleMain` и `checkstyleTest` завершились успешно. Дополнительно пройдены два теста
без skips: реальный новый job выполнил STORES/EMPLOYEES services с нулевым lease fence,
и проверка employee membership history завершилась успешно. Всего 46 backend tests.
25 documentation checker unit tests и strict documentation integrity прошли без warnings.
Это не полный backend/frontend regression suite и не подтверждение production acceptance.

При повторе на изолированной ветке от production commit все 31 тест контекста, execution,
worker и двух новых/связанных PostgreSQL проверок прошли; один из 15 существующих lifecycle
тестов (`completesDurablePhasesAndCalendarWindowInOrder`) получил пустой claim и завершился
`NoSuchElementException`. Причина этого отдельного сбоя пока не доказана; он не скрывается
повторным запуском и требует контроля в CI перед выпуском.

## Оставшиеся шаги и ограничения

Исправление на production не установлено. До deploy нужны проверка полного release diff,
CI, immutable image provenance и штатные release gates; при deploy требуется отдельное согласование.
После него — свежий bounded recovery preflight и разрешённая загрузка пропущенного периода,
затем проверка coverage, документов/позиций и возвратов. Нельзя править FAILED job SQL-обновлением,
сбрасывать lease/counters вручную или закрывать quality issues только ради зелёного экрана.

Исторические financial mismatches и нулевая себестоимость проверяются по отдельным контрактам.
Для ИИ нужны актуальные source gates и snapshot; платный вызов выполняется только после exact
preflight и согласования snapshot/hash/лимита стоимости. Этот аудит не является полной
сверкой суммы/себестоимости/продавцов с LiveSklad и не подтверждает готовность ИИ.
