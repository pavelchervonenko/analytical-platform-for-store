---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - manager
last_verified: 2026-09-14
requirement_sources:
  - docs/archive/legacy-contracts/store-plan-progress-api.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/performance/service/StorePlanProgressService.java
  - backend/src/main/java/com/storeanalytics/performance/web/StorePlanProgressController.java
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/performance/service/StorePlanProgressServiceTest.java
  - backend/src/test/java/com/storeanalytics/performance/web/StorePlanProgressControllerTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
  - frontend-product
review_triggers:
  - plan-formula-change
  - period-scope-change
  - plan-dto-change
supersedes:
  - docs/archive/legacy-contracts/store-plan-progress-api.md
superseded_by: null
---

# Store Plan Progress API

`GET /api/stores/{storeId}/performance-plans/{yyyy-MM}/progress?asOf=YYYY-MM-DD&scope=SELLERS|STORE`
всегда считает month-to-`asOf`. Это не произвольный выбранный range и не значение только одного
дня. Transport default `STORE` сохраняет совместимость прежних потребителей. Endpoint, чтение и
изменение самого плана требуют и назначения магазина, и функции `PLAN`.

План в базе один. `SELLERS` применяет его к факту `rankingEligible`, `STORE` — ко всему магазину.
Revenue, direction amount, daily actuals, share, forecast и target amount внутри одного response
всегда используют один scope.

Для направления доли:

```text
actualShare = directionAmount / actualNetRevenue × 100%
targetAmount = actualNetRevenue × targetShare / 100%
remaining = max(targetAmount - directionAmount, 0)
neededPerDay = remaining / remainingDays
```

Для `REVENUE` статус `ON_TRACK` определяется прогнозом конца месяца. Для долевых направлений
`ACCESSORY`, `SERVICE` и `ADDITIONAL` текущая реализация не использует прогноз как основание
статуса: достигнутая доля получает `ACHIEVED`, недостигнутая до конца месяца — `AT_RISK`, а в
последний день — `MISSED`. Поэтому «Выполнено» для услуги означает достижение доли на текущую дату.

Achievement сравнивает unrounded values. Response также содержит calendar pace, forecast, focus,
coverage и classification completeness; frontend отображает backend-owned значения и не строит
другую цель.

Будущий revenue baseline использует среднюю фактическую выручку завершённых дней, а при отсутствии
факта — monthly revenue target/число дней. Остаток направления рассчитывается от projected month
revenue и детерминированно распределяется по будущим дням.

Overview передаёт scope явно и показывает month target/gap в верхних карточках только в month mode.
Для week/custom этот endpoint остаётся отдельным блоком «План месяца».
Отдельный раздел «План» также передаёт scope явно и использует `SELLERS` по умолчанию; transport
default endpoint остаётся `STORE` только для обратной совместимости других потребителей.
