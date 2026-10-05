---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-10-05
requirement_sources:
  - docs/history/audits/2026/08/CUSTOMER_KPI_FORMULA_AUDIT_2026-08-13.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotWriter.java
  - backend/src/main/java/com/storeanalytics/sync/service/SalesSyncPersistence.java
  - backend/src/main/java/com/storeanalytics/sync/service/ReturnSyncPersistence.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/AnalyticalDocumentSql.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/EmployeeKpiRepository.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/EmployeeCategoryKpiRepository.java
  - backend/src/main/java/com/storeanalytics/performance/repository/EmployeePerformanceRepository.java
  - backend/src/main/resources/db/migration/V43__make_livesklad_webhook_inbox_processable.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/StoreKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/EmployeeKpiIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - product
  - integrations
  - backend
review_triggers:
  - return-attribution-change
  - synchronization-change
  - metric-change
supersedes: []
superseded_by: null
---

# Продажи и возвраты

Продажа создаёт положительные signed-строки, возврат — отрицательные по сумме, количеству и
себестоимости. Удалённый документ/строка не участвует. Повтор одного external ID обновляет тот же
факт. Возврат может сохраниться без найденной исходной продажи и связаться позднее.

## Атрибуция сотруднику

Сохранённый `sales_documents.employee_id` по-прежнему следует исходной продаже. Это
совместимый факт для payroll/reconciliation, **не аналитический автор возврата**:

- linked return получает `employee_id` исходного SALE;
- processing employee возврата не используется как fallback, даже если успешно разрешён;
- orphan return без найденного SALE сохраняется с `employee_id = null`;
- после появления оригинала повторная синхронизация связывает возврат и назначает исходного
  продавца.

Независимая аналитическая проекция по
[ADR-0006](../../decisions/ADR-0006-livesklad-return-employee-analytics.md) использует сотрудника
записи возврата из сохранённого `attach_source_employee_external_id`. Разрешение производится
только среди LiveSklad employees той же connection, без fallback на исходного продавца.
Отсутствующий/неразрешённый сотрудник даёт аналитическое «Не назначен», независимо от наличия
автора оригинала. Известный сотрудник orphan return учитывается без ожидания original link.

Возврат входит в выбранный период по собственной `business_date`, даже если исходная продажа была
в более раннем месяце. Он уменьшает аналитические показатели сотрудника записи возврата в периоде
возврата; переносить аналитический минус назад в месяц продажи система не должна.

`ReturnSyncIntegrationTest` использует двух разных разрешённых сотрудников и проверяет как
приоритет исходного продавца, так и отсутствие fallback у orphan return. Store/category signed
totals от атрибуции не меняются. Общий сохранённый автор проверяется этим sync-тестом;
аналитический автор KPI, категорий, количества возвратов и финансовых составляющих рейтинга
разрешается отдельной read-only проекцией. Attach v4 имеет отдельные правила ниже.
История прежнего решения сохранена в [ADR-0001](../../decisions/ADR-0001-return-employee-attribution.md).

Historical eligibility projection использует ту же проекцию автора и собственный timestamp
возврата, но historical membership пока не подключён ко всем агрегатам: текущий roster остаётся
текущим, не восстановленным задним числом. Код не переписывает документы или immutable reports.
Наличие реализации в ветке не означает production cutover; состояние среды — в project-state.

Нулевая оплата не доказывает отсутствие возврата: авторитетны signed items. Missing cost возврата
делает cost/GP/margin неполными; неожиданный ноль остаётся quality gap.

## Аналитическое исключение: attach v4

Гарантия и её возврат относятся к устройству и его исходному периоду/сотруднику. Возврат
устройства корректирует исходную гарантийную базу. Для остальных attach-категорий возврат
учитывается в своей дате у сотрудника строки LiveSklad, сохранённого отдельно от финансового
employee_id. Raw retention не удаляет сохранённый ID. Подробности: [attach-rate](attach-rate.md).

## Shadow-снимок аксессуарной роли: локальный этап каталога

Для новых строк sync предусмотрен отдельный выключенный по умолчанию writer с явно заданной
датой начала. Он не меняет категорию, деньги, зарплаты или действующие signed-показатели.
Роль и свидетельство продажи фиксируются один раз. Новый связанный возврат копирует
актуальный снимок исходной продажи, даже если позднее подтверждение товара отозвано.
Без подходящего снимка сохраняется DEFER_TO_EXISTING, не нулевой вклад и не сегодняшняя
роль товара. Повторный sync не создаёт снимки для ранее сохранённых строк и не заменяет
первоначальный снимок. Поздний relink/существенная правка факта дают STALE.

Официальные SQL-расчёты эти снимки ещё не используют. Очередь исправлений, контролируемые
ревизии и подключение N/B — последующие блоки. Подробный контракт и ограничения:
[снимки и перенос](classification.md#снимки-новых-фактов-и-проверяемый-перенос-четвёртый-блок-42).
