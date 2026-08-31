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
  - docs/payroll-api.md
  - docs/payroll-classification-review.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/salary/service/PayrollComputationEngine.java
  - backend/src/main/java/com/storeanalytics/salary/service/PayrollCalculationService.java
  - backend/src/main/resources/db/migration/V5__add_payroll.sql
verification_sources:
  - backend/src/test/java/com/storeanalytics/salary/service/PayrollComputationEngineTest.java
  - backend/src/test/java/com/storeanalytics/salary/service/PayrollCalculationServiceTest.java
  - backend/src/test/java/com/storeanalytics/salary/repository/PayrollSalesRepositoryIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - product
  - backend
review_triggers:
  - payroll-formula-change
  - payroll-classification-change
  - plan-change
supersedes: []
superseded_by: null
---

# Расчёт зарплаты

Payroll использует отдельную зарплатную классификацию, полный месяц, план и смены.

```text
RevenueAchieved = revenue >= revenue target
AccessoryAchieved = revenue > 0 AND accessory * 100 >= accessory target * revenue
ServiceAchieved = revenue > 0 AND service * 100 >= service target * revenue
```

Действующая scheme v1: аксессуары/услуги `20%` при выполнении, иначе `15%`; Tier 1 — `500/400 ₽`,
Tier 2 — `300/200 ₽`; аванс сотрудника со сменами — `50 000 ₽`.

```text
DailyFund = accessory turnover * accessory rate
  + service turnover * service rate
  + PlayStation GP * service rate
  + paid repair GP * service rate
  + Tier1 quantity * Tier1 rate
  + Tier2 quantity * Tier2 rate
```

Missing classification/cost делает необходимый фонд `null`. Фонд делится поровну между
сотрудниками смены, остаток копеек распределяется детерминированно; часы не являются весом.

```text
Earned = sum(daily equal shares)
Payable = Earned - Advance - Penalty - Inventory - Tax
```

`Payable` может быть отрицательным. `canCalculate` и `canApprove` — разные gates. Payroll form не
исправляет analytics category. `ZERO_UNEXPECTED` и impact смены employee return attribution требуют
отдельного продуктового решения/tests.
