---
doc_schema: 1
doc_type: decision
status: accepted
owner: product
audience:
  - developer
  - manager
decision_date: 2026-10-01
implementation_status: partial
decision_sources:
  - docs/maintenance/weekly-review-seller-analytics-design.md
  - docs/maintenance/weekly-review-seller-first-handoff.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiJobStore.java
  - backend/src/main/resources/db/migration/V91__seller_membership_history.sql
  - backend/src/main/java/com/storeanalytics/metrics/repository/SellerMembershipHistoryWriter.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/SellerHistoricalDocumentSelectionRepository.java
  - backend/src/main/java/com/storeanalytics/sync/service/EmployeeSyncBatchApplier.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiJobStoreIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/SellerMembershipHistoryMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/SellerMembershipHistoryWriterIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/SellerHistoricalDocumentSelectionRepositoryIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/EmployeeSyncMembershipHistoryIntegrationTest.java
required_reviewers:
  - product
  - backend
  - operations
supersedes: []
superseded_by: null
---

# ADR-0004: Исторический состав и восстановление недельного seller-отчёта

## Контекст

Текущий seller-v3 расчёт использует сегодняшний состав рейтинга и только последнюю закрытую
неделю. После задержки данных через следующую границу недели отчёт может пропасть; пересчёт
прошлого периода по сегодняшнему составу дал бы недостоверные итоги.

## Решение

1. Участие в рейтинге действует вперёд с даты изменения. Продажа проверяет состояние сотрудника
   на дату исходной продажи; связанный возврат наследует эту дату и сотрудника.
2. Отсутствие доказанного исторического интервала означает `UNKNOWN`, а не отсутствие участия.
   Автоматически восстанавливать только полные недели после утверждённой даты начала достоверной
   истории для данного магазина. Более ранние периоды не выдавать за исторически точные.
3. Если продавец позже ушёл или выключен из рейтинга, его вклад остаётся в отчёте периода работы
   с пометкой «не в текущей команде». Для него не создаётся новое действие на будущую неделю.
4. После восстановления полноты источников автоматически формировать пропущенные отчёты через
   ограниченную, наблюдаемую очередь. Неполный coverage не разрешает публиковать числа.
5. На магазин и неделю разрешена одна автоматическая задача платного ИИ с одной попыткой
   провайдера, даже после исправления отчёта. Повтор — только через exact approved operator path.
6. Первая версия не допускает backdated correction. Повторный найм сохраняет текущую настройку
   рейтинга, но открывает новый интервал; отсутствие смен не блокирует финансовый отчёт.

Уточнение от 4 октября: [ADR-0005](ADR-0005-weekly-ai-activation-and-retries.md) фиксирует
forward-only baseline и разрешение необходимых ограниченных provider retries. Инвариант одной
автоматической задачи на магазин/неделю сохранён; запрет второго вызова из пункта 5 заменяется
проверяемой retry policy. Это не означает её активацию в runtime.

Уточнение от 5 октября: [ADR-0006](ADR-0006-livesklad-return-employee-analytics.md) заменяет
наследование аналитического сотрудника возврата в пункте 1. Для seller facts автором служит
сотрудник записи возврата, с membership на дату возврата; связь с оригиналом не подменяет автора.
Локальная экспериментальная projection реализует это уточнение, но ещё не подключена к
агрегатам; действующие показатели этим reader-ом не меняются.

## Текущее реализованное поведение

Ограничение автоматической AI-задачи реализовано локально. Migration V91 добавляет пустое
хранилище истории; проверенный baseline для магазина записывается только явной операцией.
Ручное изменение флага и полный employee sync после baseline теперь пишут интервалы и текущие
назначения атомарно. Точечные локальные тесты это подтверждают, но baseline не активирован,
отдельная document-level eligibility projection проверена на synthetic sale/return cases,
но не подключена к агрегатам. Периодный read API и durable backlog ещё не реализованы.
Поэтому включение постоянной автоматической публикации до их проверки запрещено.

## Условия вступления решения в силу

Нужны утверждённый per-store baseline, атомарная история manual/sync изменений, единая temporal
атрибуция продаж и возвратов, периодный planner/read API, тест задержки через две границы недель,
полный backend gate и локальная проверка интерфейса. Дата baseline не выводится из `assigned_at`
или текущего флага задним числом.

## Альтернативы

1. Восстанавливать старые недели по сегодняшнему составу — отклонено из-за ложной атрибуции.
2. Только ручное восстановление пропуска — отклонено: владелец подтвердил автоматический режим.
3. Повторять ИИ автоматически на каждой исправленной ревизии — отклонено из-за платных вызовов.

## Последствия и проверка

История и очередь увеличивают объём миграций и требуют явного cutover. Проверять midweek
toggle, уход/возврат продавца, linked/orphan returns, UNKNOWN до baseline, гонки sync/manual,
повторы scheduler и AI, а также отсутствие новых платных задач на исправленной ревизии.

Связанные документы: [план](../maintenance/weekly-review-seller-analytics-design.md),
[handoff](../maintenance/weekly-review-seller-first-handoff.md),
[текущий контракт](../current/ai/weekly-review.md).
