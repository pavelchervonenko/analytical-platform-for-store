---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-09-04
requirement_sources:
  - docs/history/audits/2026/08/CUSTOMER_KPI_FORMULA_AUDIT_2026-08-13.md
  - docs/archive/discoveries/analytics-business-rules-draft.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/repository/StoreKpiRepository.java
  - backend/src/main/java/com/storeanalytics/metrics/service/StoreKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/service/CategoryKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/EmployeeKpiRepository.java
  - backend/src/main/java/com/storeanalytics/metrics/service/OverviewMetricsService.java
  - backend/src/main/java/com/storeanalytics/metrics/service/SellerPeriodAnalyticsService.java
  - backend/src/main/resources/db/migration/V65__split_ipad_mac_and_other_device_cases.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/StoreKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/CategoryKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/EmployeeKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/OverviewMetricsServiceTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/SellerPeriodAnalyticsServiceTest.java
  - backend/src/test/java/com/storeanalytics/common/database/DeviceAccessorySplitMigrationIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - product
  - backend
review_triggers:
  - metric-change
  - return-attribution-change
  - classification-change
supersedes: []
superseded_by: null
---

# Бизнес-показатели магазина, категорий и сотрудников

## Базовый signed-факт

```text
s = +1 для SALE; s = -1 для RETURN
R = sum(s * net_amount)
Q = sum(s * quantity)
C = sum(s * cost_amount)
GP = R - C
Margin = GP / R * 100%
```

Строка входит в расчёт, если магазин совпадает, `business_date` находится во включительном
периоде, документ и строка не удалены, а аналитическая категория не `EXCLUDE`.

Если хотя бы у одной включённой строки себестоимость отсутствует, `C`, `GP` и `Margin` равны
`null`; `R` и `Q` остаются доступны. `null` нельзя заменять нулём.

## Store и category scope

- Store KPI включает все подходящие факты, включая `UNMAPPED`.
- `UNMAPPED` не входит в именованные бизнес-группы.
- `EXCLUDE` не входит ни в store total, ни в группы.
- Category KPI использует тот же знак, период и правила удаления.

Структура вложена:

```text
Техника                 Дополнительная выручка
└── Телефоны            ├── Аксессуары
                        └── Услуги
```

Родителя и детей повторно не складывают. В текущем справочнике действует
`Допы = Аксессуары + Услуги`. Услуги включают `SERVICE`, `WARRANTY`, `PROTECTION`; допы
определяются `countsAsAdditionalRevenue`, а не названием товара.

Новые категории `ACCESSORY_IPAD`, `ACCESSORY_MAC` и `CASE_OTHER_DEVICE`
относятся к аксессуарам и дополнительной выручке. V65 переводит восемь
подтверждённых Pencil, Mouse и Keyboard из устройств в аксессуары:
суммы именованных групп меняются, но store KPI и формулы долей — нет.

## Подробный состав техники

Группы магазина, выбранных продавцов и каждого сотрудника дополнены строками
`DEVICE_CATEGORY:<код категории>` для техники, кроме телефонов. Они используют те же
знаковые факты и показатели качества, что и категории; телефоны остаются строкой PHONES.
Телефоны плюс детальные строки равны DEVICES по выручке, количеству, себестоимости
при полном покрытии и числу исходных строк. Детальные строки уже входят в DEVICES:
их нельзя прибавлять к нему повторно или назначать им новые рейтинговые веса.

Строка детализации появляется при наличии исходных фактов, включая возвратный период
и полностью возвращённую продажу с нулевым сальдо. Пустые категории её не создают.
Apple/другие планшеты и ноутбуки, Apple/Samsung/другие часы и остальные виды техники
показываются по сохранённым конечным категориям. IPAD_MAC и PODS_WATCH_OTHER_DEVICE
остаются явным неуточнённым остатком; имя товара здесь не переразмечает историю.

Это дополнительное представление действующей category-kpi-v3: формулы и исходные суммы
не изменены. Published snapshots не пересоздаются. При построении новых снимков уже
существующие CATEGORY-факты содержат детализацию; дублирующие DEVICE_CATEGORY-группы
не добавляются в набор фактов интерпретации. Планы используют прежние общие группы.

## Employee scope

Полный employee KPI включает действующих назначенных, исторических с фактами, сотрудников вне
рейтинга и строку «Не назначен»:

```text
sum(all employee KPI, including «Не назначен») = store KPI
```

Rating roster уже и не обязан сходиться с магазином. Доли сотрудника используют его полную чистую
выручку как знаменатель:

```text
AccessoryShare = employee accessory revenue / employee net revenue * 100%
ServiceShare = employee service revenue / employee net revenue * 100%
AdditionalShare = employee additional revenue / employee net revenue * 100%
```

При нулевом знаменателе доля недоступна. Поведение при отрицательной employee revenue различается
между отдельными проекциями и остаётся открытым gap.

Для главной страницы доступны два scope:

- `SELLERS` — только `rankingEligible` (`employee.is_active`, активное назначение и
  `participates_in_ranking`);
- `STORE` — полный store total, включая сотрудников вне рейтинга и «Не назначен».

В обоих режимах числитель и знаменатель берутся из одного периода и cohort. Один результат
контролирует равенства полного employee total и store total, seller revenue между двумя
employee-проекциями и `Допы = Аксессуары + Услуги`. Несовпадение завершает расчёт ошибкой.

## Внутренний seller analytics baseline

`SellerPeriodAnalyticsService` добавлен как внутренний расчётный слой первого этапа seller-first.
SELLERS-путь `OverviewMetricsService` использует его проекцию `readForOverview`; STORE остаётся на
существующих readers. Weekly Review пока не переключён. Текущие UI/API-контракты и persisted
weekly snapshots остаются прежними.

`readForOverview` читает employee/category aggregates по одному разу и отдаёт seller metrics
вместе с отдельными полными проекциями исключительно для сверки с STORE. Даже при пустом roster
сверка сохраняется, но значения STORE не подставляются вместо seller totals. Документы, attach,
workload и AI в этом пути не читаются. Overview и вызывающий его месячный plan progress используют
read-only `REPEATABLE_READ`, чтобы состав и суммы не читались из разных состояний БД.
Это не гарантия завершённости многотранзакционного sync. Формулы/targets месячного плана не меняются.

Лёгкий `readMetrics` возвращает seller totals, категории и персональные исходные агрегаты без
запросов document breakdown или attach. Расширенный `readComparison` читает оба периода в одной
read-only `REPEATABLE_READ` транзакции по единственному текущему roster. Состав задаётся store ID и
employee IDs с тремя действующими eligibility-признаками; это не историческая membership-модель.
Изменение флага влияет на следующий расчёт обеих недель, но не изменяет уже возвращённые факты.

Формулы category/financial KPI переиспользуются, исходные деньги и количества суммируются до
вычисления отношений. All-SALE document count не смешивается с completed-sale sample: последний
дополнительно требует source type `sale` и хотя бы одну неудалённую не-EXCLUDE строку. Пустой
roster не переключает расчёт на STORE. Нет покрытия источника в этом низкоуровневом результате:
нулевые суммы сами по себе не доказывают, что данные загружены полностью.

Attach-часть следует активной политике атрибуции: legacy v3 при выключенном флаге, v4 при включённом.
Выбранные IDs применяются к автору attach-факта, который может отличаться от финансового автора.
Поздняя гарантия относится к продавцу и периоду устройства; остальные возвраты attach — к их
исходному автору LiveSklad и периоду возврата, без fallback на финансового автора. См.
[ADR-0003](../../decisions/ADR-0003-warranty-attach-attribution.md). Общие ограничения неизвестной
атрибуции могут сопровождать seller-факты, но денежные суммы и raw attach количества STORE не
подмешиваются. Готовность нового Weekly Review требует отдельных gates A3–A6.
Реализация, оставшиеся gates и границы A/B описаны в
[seller-first плане](../../maintenance/weekly-review-seller-analytics-design.md).

## Средние и округление

```text
AverageReceipt = R / count(non-deleted SALE documents)
AdditionalPerPhone = additional revenue / signed phone quantity
CategoryAverage = category revenue / category quantity
Change = (current raw - previous raw) / previous raw * 100%
```

Возвраты уменьшают числитель среднего чека, но не count SALE-документов. Backend обычно отдаёт
decimal до двух знаков, UI показывает часть процентов с одним; двойное presentation-округление
может дать отличие `0,1 п. п.` от правила одного финального округления.

Возврат относится к сотруднику исходной продажи. Обработчик возврата не получает финансовый факт;
до появления исходной продажи orphan return остаётся в «Не назначен». Правило принято в
[ADR-0001](../../decisions/ADR-0001-return-employee-attribution.md).

`EXCLUDE` по-прежнему не входит в «всю чистую выручку» аналитической системы; изменение этого
правила требует отдельного продуктового решения.
