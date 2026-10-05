---
doc_schema: 1
doc_type: evidence
status: historical
owner: backend
audience:
  - developer
  - operations
snapshot_date: 2026-10-05
verdict: PASS
verdict_scope: Targeted daily analytical plan parity and existing payroll regressions; not full release or production activation
verification_levels:
  - local
source_of_truth:
  - docs/decisions/ADR-0006-livesklad-return-employee-analytics.md
  - docs/maintenance/weekly-ai-production-automation-plan.md
required_reviewers:
  - backend
  - operations
---

# Ежедневный аналитический факт плана: локальная проверка

Read-only аналитический resolver подключён к StorePlanDailyActualRepository. Автор RETURN —
сотрудник записи LiveSklad в той же connection; дата — собственная business date возврата.
SELLERS использует действующий активный roster, STORE сохраняет суммы неизвестных авторов.
Новая версия StorePlanProgressService различает семантику чтения без изменения transport shape.
Production, источники, платные вызовы и зарплатные snapshots не изменены.

## Проверки

- Targeted backend run: 44 tests в 7 классах, 0 failures/errors/skips; Checkstyle main/test PASS.
  Классы: StorePlanDailyActualRepositoryIntegrationTest (8), StorePlanProgressServiceTest (6),
  StorePlanProgressControllerTest (3), StoreDataStatusSecurityIntegrationTest (6),
  PayrollSalesRepositoryIntegrationTest (4), PayrollComputationEngineTest (10),
  PayrollCalculationServiceTest (7). PostgreSQL integration выполнены локально через Docker.
- Дневные signed revenue/accessory/service totals совпадают с месячным Overview в STORE/SELLERS.
  Synthetic cases проверяют разные author IDs, возврат в другом месяце, orphan с известным
  сотрудником, EXCLUDE, удалённую позицию, удалённый оригинал/возврат, missing/unresolved author,
  неактивного сотрудника, неактивное назначение и выключенное участие в рейтинге.
  Сохранённый employee_id после аналитического чтения остаётся прежним.
- Documentation: 25 unit tests PASS; strict inventory и diff checks PASS.
  Host security/operator safety checks PASS. Полный backend и frontend здесь не запускались.
  Frontend source/UI не менялись; визуальная проверка в этом пакете не выполнялась.

## Self-review и границы

Resolver не выполняет DML, не выводит автора из original link, не смешивает connection.
SELLERS roster predicate совпадает с SellerCohortRepository. STORE signed sums и исключение
удалённых/EXCLUDE фактов сохранены. Повторное чтение использует уже существующую
REPEATABLE READ транзакцию сервиса; денежные цели/округления/forecast не менялись.
Salary-код не зависит от изменённого repository/resolver, его прежние проверки прошли.
По D-006/R-18 это отдельный аналитический этап, не реализация новой payroll-политики или Q-10.

Historical membership ещё не подключён к агрегатам: текущий состав не является историческим.
Temporal attach, durable backlog и сохранение позднего ответа провайдера остаются отдельными
этапами. Полный release gate окончательного кода, CI, rehearsal, runtime acceptance и точный
paid canary ещё необходимы; этот scoped PASS не означает готовность автоматического режима.
