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
  - docs/archive/discoveries/analytics-business-rules-draft.md
  - docs/history/audits/2026/08/CUSTOMER_KPI_FORMULA_AUDIT_2026-08-13.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/metrics
  - backend/src/main/java/com/storeanalytics/performance
  - backend/src/main/java/com/storeanalytics/salary
  - backend/src/main/java/com/storeanalytics/report
verification_sources:
  - backend/src/test/java/com/storeanalytics/metrics
  - backend/src/test/java/com/storeanalytics/performance
  - backend/src/test/java/com/storeanalytics/salary
runtime_evidence: []
required_reviewers:
  - product
  - backend
review_triggers:
  - metric-change
  - classification-change
  - period-semantics-change
supersedes: []
superseded_by: null
---

# Продуктовые контракты

## Назначение и границы

Этот раздел является индексом действующих продуктовых правил. Transport shape остаётся
ответственностью OpenAPI, а production-состояние — `docs/current/project-state.md`.

## Карта контрактов

| Документ | Ответ на вопрос |
|---|---|
| [Бизнес-показатели](business-metrics.md) | Store/category/employee KPI и вложенные группы |
| [Периоды](periods.md) | Включительные даты и период сравнения |
| [Классификация](classification.md) | Аналитическая, attach-rate и зарплатная категории |
| [Продажи и возвраты](sales-and-returns.md) | Знак факта, связь и атрибуция возврата |
| [Attach-rate](attach-rate.md) | Поштучная методика v3 и 14 баз |
| [Планы и смены](plans-and-shifts.md) | Месячный план, дневные цели и смены |
| [Сотрудники и рейтинг](employees-and-rating.md) | Полный financial cohort, roster и рейтинг |
| [Зарплата](payroll.md) | Фонд, ставки, распределение и readiness |
| [Отчёты](reports.md) | Snapshot, месячный/годовой состав |
| [Качество данных](data-quality.md) | ERROR/WARNING, `null` и remediation gaps |
| [Ежедневная сводка](daily-store-pulse.md) | Детерминированный daily pulse и coverage gaps |

## Иерархия источников

1. Реализованное поведение подтверждают код и тесты.
2. Методика заказчика задаёт ожидание, но не доказывает реализацию.
3. Расхождение описывается явно и получает ADR.
4. Датированный аудит не заменяет current-контракт.

## Принятые решения

- [ADR-0001](../../decisions/ADR-0001-return-employee-attribution.md): возврат относится к
  сотруднику исходной продажи.
- [ADR-0002](../../decisions/ADR-0002-overview-period-scope.md): выбранный период и месячный план
  разделены; scope главной переключается между продавцами и магазином.
