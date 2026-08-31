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
  - docs/archive/legacy-contracts/reports.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/report/service/MonthlyReportFinalizationService.java
  - backend/src/main/java/com/storeanalytics/report/service/AnnualReportAggregationService.java
  - backend/src/main/java/com/storeanalytics/report/service/AnnualReportFinalizationService.java
verification_sources:
  - backend/src/test/java/com/storeanalytics/report
runtime_evidence: []
required_reviewers:
  - product
  - backend
review_triggers:
  - report-schema-change
  - report-finalization-change
  - employee-scope-change
supersedes: []
superseded_by: null
---

# Месячные и годовые отчёты

Отчёт — immutable snapshot, а не live-проекция. Месячный payload фиксирует store KPI, категории,
attach-rate, rating/plan и payroll за календарный месяц с provenance.

Employee-таблица месяца строится из `payroll.statements`. Это payroll cohort, не full employee KPI;
её сумма не доказывает reconciliation с магазином.

Годовой отчёт агрегирует финализированные месяцы. Если хотя бы один месяц имеет incomplete cost,
годовые cost/GP/margin равны `null`. Employee payload объединяет rating/payroll, но содержит в
основном смены, часы, revenue и выплаты; employee GP и структура допов отсутствуют.

Monthly snapshot собирается несколькими calculators; отдельного concurrency-test единого cut при
параллельной sync нет. Финализация требует coverage/quality. `ReportsPage` не имеет полноценного
frontend test suite.
