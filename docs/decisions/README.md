---
doc_schema: 1
doc_type: current
status: current
owner: project
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - docs/decisions
verification_sources:
  - scripts/check-documentation.py
runtime_evidence: []
required_reviewers:
  - information-architecture
  - product
review_triggers:
  - decision-change
  - architecture-change
supersedes: []
superseded_by: null
---

# Architecture Decision Records

- [ADR-0001: атрибуция возврата сотруднику исходной продажи](ADR-0001-return-employee-attribution.md) — прежнее реализованное правило; аналитическое решение заменено ADR-0006, переход ещё не выполнен.
- [ADR-0002: период и cohort показателей главной](ADR-0002-overview-period-scope.md) — accepted, implemented.

Первые два решения приняты 2026-08-31. Переход `implementation_status` в `verified` требует выполнения
указанных внутри integration/visual gates.

- [ADR-0003: независимая атрибуция гарантий](ADR-0003-warranty-attach-attribution.md) — accepted, implemented за настройкой включения.
- [ADR-0004: исторический состав и восстановление seller-недель](ADR-0004-seller-weekly-historical-membership.md) — accepted, реализована только защита автоматических AI-вызовов; temporal/backlog остаются отдельным gate.
- [ADR-0005: начало истории и необходимые повторы недельного ИИ](ADR-0005-weekly-ai-activation-and-retries.md) — accepted, уточняет baseline и retry policy без немедленной production-активации.
- [ADR-0006: аналитический сотрудник возврата из LiveSklad](ADR-0006-livesklad-return-employee-analytics.md) — accepted, partial: независимая eligibility projection, без подключения к KPI; payroll и гарантийные исключения сохраняются.
