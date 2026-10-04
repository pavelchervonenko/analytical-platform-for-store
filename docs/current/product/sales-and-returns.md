---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-09-30
requirement_sources:
  - docs/history/audits/2026/08/CUSTOMER_KPI_FORMULA_AUDIT_2026-08-13.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotWriter.java
  - backend/src/main/java/com/storeanalytics/sync/service/SalesSyncPersistence.java
  - backend/src/main/java/com/storeanalytics/sync/service/ReturnSyncPersistence.java
  - backend/src/main/resources/db/migration/V43__make_livesklad_webhook_inbox_processable.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/product/service/CatalogSaleRoleSnapshotIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/sync/service/ReturnSyncIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/StoreKpiIntegrationTest.java
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

Финансовая атрибуция возврата всегда следует исходной продаже:

- linked return получает `employee_id` исходного SALE;
- processing employee возврата не используется как fallback, даже если успешно разрешён;
- orphan return без найденного SALE сохраняется с `employee_id = null` и входит в «Не назначен»;
- после появления оригинала повторная синхронизация связывает возврат и назначает исходного
  продавца.

Возврат входит в выбранный период по собственной `business_date`, даже если исходная продажа была
в более раннем месяце. Поэтому он уменьшает показатели продавца исходной продажи именно в периоде
возврата; переносить минус назад в месяц продажи система не должна.

`ReturnSyncIntegrationTest` использует двух разных разрешённых сотрудников и проверяет как
приоритет исходного продавца, так и отсутствие fallback у orphan return. Store/category signed
totals от атрибуции не меняются; денежные employee KPI и финансовые составляющие rating уменьшаются
у продавца продажи. Attach v4 имеет отдельные правила ниже.
Правило принято в [ADR-0001](../../decisions/ADR-0001-return-employee-attribution.md).

Известное расхождение с новым требованием: владелец выбрал аналитического сотрудника из записи
возврата LiveSklad в [ADR-0006](../../decisions/ADR-0006-livesklad-return-employee-analytics.md).
Переход ещё не выполнен; описанный выше выбор оригинального продавца остаётся поведением кода,
а не целевым правилом будущей аналитики. Payroll и специальные гарантии требуют независимой
проверки, чтобы изменение аналитики не затронуло их неявно.

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
