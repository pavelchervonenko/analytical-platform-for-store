---
doc_schema: 1
doc_type: current
status: current
owner: product
audience:
  - developer
  - manager
last_verified: 2026-09-14
requirement_sources:
  - docs/archive/legacy-contracts/employee-rating-api.md
  - docs/archive/discoveries/analytics-business-rules-draft.md
  - docs/current/product/periods.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics/repository/EmployeeKpiRepository.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingService.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeCardService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewTeamEmployeeProjector.java
  - backend/src/main/resources/db/migration/V4__add_employee_performance_rating.sql
  - frontend/src/insights/weekly-review/WeeklyReviewContent.tsx
  - frontend/src/insights/weekly-review/weeklyReviewViewModel.ts
  - frontend/src/plan-schedule/forms.ts
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics/repository/EmployeeKpiIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/performance/repository/EmployeeRatingIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/performance/service/EmployeeRatingServiceTest.java
  - backend/src/test/java/com/storeanalytics/performance/service/EmployeeCardServiceTest.java
  - backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewTeamEmployeeProjectorTest.java
  - frontend/src/insights/WeeklyReviewView.test.tsx
  - frontend/src/insights/weekly-review/weeklyReviewViewModel.test.ts
  - frontend/src/plan-schedule/forms.test.ts
runtime_evidence:
  - docs/history/audits/2026/09/WEEKLY_REVIEW_LOCAL_PRERELEASE_2026-09-14.md
required_reviewers:
  - product
  - backend
review_triggers:
  - employee-scope-change
  - rating-formula-change
  - return-attribution-change
supersedes: []
superseded_by: null
---

# Сотрудники и рейтинг

| Состав | Кто входит | Назначение |
|---|---|---|
| Full employee KPI | Назначенные/исторические с фактами, вне рейтинга, «Не назначен» | Store reconciliation |
| Rating eligible | Active employee + active assignment + participates | Возможность участия |
| Rating candidate | Eligible + минимум одна смена | Benchmark и место |
| Overview roster | Активные назначенные участники; смена не обязательна | Управленческий subtotal |
| Shift selection | Rating eligible | Выбор продавца для новой смены |
| Weekly Review roster | Rating eligible + активность в одной из двух недель | Персональные карточки snapshot |

Overview roster не обязан сходиться со store total.

`Rating eligible` означает одновременно `employeeActive=true`, `assignmentActive=true` и
`participatesInRanking=true`. Сам флаг участия ещё не гарантирует место: для rating candidate нужна
хотя бы одна смена выбранного периода, а для карточки Weekly Review — завершённая продажа,
ненулевая чистая выручка или смена хотя бы в одной из двух сравниваемых недель.

Один и тот же eligibility используется в режиме `SELLERS` на главной, в выборе продавцов для новых
смен и при создании персональных карточек Weekly Review. Уже сохранённая смена исключённого
сотрудника не удаляется автоматически, а уже созданный weekly-review snapshot не фильтруется заново
при чтении. Историческое представление меняется только новой immutable revision.

### Вклад и эффективность в Weekly Review

Чистая выручка сотрудника в недельном разборе — это его вклад в результат магазина и собственная
динамика относительно прошлой недели. Она не используется для оценочного сравнения с коллегами.
Сравнение эффективности допустимо только по `REVENUE_PER_HOUR` с медианой минимум трёх сотрудников,
у которых достаточно продаж, смен и часов в обеих сравниваемых неделях. Неполная база любой из
двух недель исключает сотрудника из benchmark и не создаёт оценочного peer comparison.

Смены для менеджера являются optional operational data: их отсутствие не обесценивает уже
доступные продажи сотрудника. При незаполненных сменах `SHIFT_COUNT`, `WORKED_HOURS` и
`REVENUE_PER_HOUR` недоступны, peer comparison отсутствует, но сотрудник, команда и весь Weekly
Review не получают `LIMITED`/`PARTIAL` только по этой причине. Такой пробел не создаёт
`ATTENTION` или действие. Реальная нехватка продаж и нераспределённые возвраты остаются отдельными
адресными ограничениями.

Если сотрудник уже показан в Weekly Review по достаточному sales-сигналу, отсутствие time-оценки
обозначается одной нейтральной подписью `Часть смен не заполнена — оценка по часам недоступна`.
Список исключений не получает отдельную серую сводку: заголовок содержит `N из M требуют проверки`,
а каждая карточка объясняет только собственную причину.

## Rating v1

Четыре направления имеют вес `25%`, cap `150`:

```text
Contribution = clamp(employee revenue / mean(candidate revenue) * 100, 0, 150)
Efficiency = clamp((employee revenue / hours) /
  (sum(candidate revenue) / sum(candidate hours)) * 100, 0, 150)
Structure = 50% * clamp(accessory share / target * 100, 0, 150)
  + 50% * clamp(service share / target * 100, 0, 150)
Attach = average(clamp(employee rate / store rate * 100, 0, 150))
Overall = sum(score * weight / 100) * 100 / available coverage
```

Валовая прибыль и маржа могут отображаться как финансовые показатели сотрудника, но не являются
отдельными направлениями Rating v1 и не добавляют баллы в `Overall`. Рейтинг использует выручку,
выручку за час, структуру аксессуаров/услуг и attach-rate.

Attach участвует при employee denominator `>=3` и положительном store benchmark. Место присваивается
при coverage `>=75%`, ранжирование dense. Нет смены — не candidate; нулевые/отрицательные часы —
efficiency `null`; малая attach-база — score `null`.

Простые UI-номинации «лидер» пока не требуют минимального оборота, поэтому малая база способна дать
неустойчивый вывод.

Возврат уменьшает показатели сотрудника исходной продажи. Сотрудник, который только оформил
возврат, не получает этот финансовый факт. Orphan return без найденной продажи временно попадает в
«Не назначен» и переатрибутируется после появления оригинала.
