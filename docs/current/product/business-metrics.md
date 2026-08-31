---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/history/audits/2026/08/CUSTOMER_KPI_FORMULA_AUDIT_2026-08-13.md
  - docs/archive/discoveries/analytics-business-rules-draft.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/repository/StoreKpiRepository.java
  - backend/src/main/java/com/storeanalytics/metrics/service/StoreKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/service/CategoryKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/EmployeeKpiRepository.java
  - backend/src/main/java/com/storeanalytics/metrics/service/OverviewMetricsService.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/StoreKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/CategoryKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/repository/EmployeeKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/metrics/service/OverviewMetricsServiceTest.java
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
