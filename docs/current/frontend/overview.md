---
doc_schema: 1
doc_type: current
status: current
owner: frontend
audience:
  - developer
  - manager
last_verified: 2026-09-09
requirement_sources:
  - docs/current/product/business-metrics.md
  - docs/current/product/plans-and-shifts.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/service/OverviewMetricsService.java
  - frontend/src/dashboard/OverviewPage.tsx
  - frontend/src/dashboard/OverviewManagementSections.tsx
  - frontend/src/api/queries.ts
verification_sources:
  - frontend/src/dashboard/OverviewPage.test.tsx
  - frontend/src/dashboard/OverviewPage.query-state.test.tsx
runtime_evidence: []
required_reviewers:
  - frontend
  - product
review_triggers:
  - dashboard-ui-change
  - dashboard-query-change
  - metric-change
supersedes: []
superseded_by: null
---

# Главная страница

| Блок | Источник | Период | Cohort | Null/partial | Label |
|---|---|---|---|---|---|
| Freshness | `/data-status` | Latest coverage | Store | Date nullable | «Данные по…»/причина |
| Revenue/GP/margin | `/overview-metrics` | Selected | SELLERS by default / STORE | GP/margin nullable | Selected period |
| Accessories/services/additional | `/overview-metrics` | Selected | Same selected scope | Share nullable | Selected period |
| Sales structure | `/overview-metrics` | Selected | Same selected scope | GP/margin nullable | Selected period + scope |
| Plan | `/performance-plans/{month}/progress?scope=` | Month..asOf | Same selected scope | No plan state | «План месяца» |
| Team | `/kpi/employees` + `/employee-ratings` | Selected | Overview roster | Score/rank nullable | «Основные продавцы» |
| Attach map | `/kpi/attach-rates` + rating | Selected | Store + roster | Rate/base nullable | Empty-state reason |

Переключатель находится слева сверху внутри тёмного блока. `SELLERS` («Только продавцы») — режим
по умолчанию; `STORE` («Весь магазин») включает сотрудников вне рейтинга и факты без сотрудника.
Выбор хранится в query-параметре `overviewScope`. Продавец определяется backend-признаком
`rankingEligible`: активный сотрудник, активное назначение и «Участвует в рейтинге».

В month mode тёмные карточки показывают цель этого же месяца и scope; процентные пункты
подписаны как отклонение «к цели», а денежное отклонение — как «выше цели» или «не хватает».
В week/custom тёмный блок показывает только selected-period amount, quantity и share, чтобы не
смешивать недельный факт с месячной целью.

«Структура продаж» использует тот же выбранный scope, а attach-map
намеренно остаётся STORE и имеет явную подпись. `null` GP/margin не показывается как zero.
Главная не запрашивает administrator-only period-quality. Менеджер видит только локальное
последствие: конкретная прибыль или показатель временно недоступны. Технические issue code,
severity и affected count на главной не показываются. Отсутствующая себестоимость объясняется один
раз рядом с зависимыми показателями, а пустые attach-rate строки не размножают одинаковые причины.

Основной KPI блокирует страницу только без ранее загруженных данных. Вспомогательные запросы плана,
команды, attach-rate и категорий деградируют независимо. Ошибка фонового обновления сохраняет все
последние успешные значения и показывается одной компактной заметкой над страницей.

`overview-metrics-v1` сверяет STORE с полной employee-проекцией, а также инварианты
`Допы = Аксессуары + Услуги` и одинаковую выручку seller cohort между employee KPI и category KPI.
При расхождении backend не отдаёт частично согласованный результат.
