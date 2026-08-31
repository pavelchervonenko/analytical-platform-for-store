---
doc_schema: 1
doc_type: current
status: current
owner: project
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - docs/history
verification_sources:
  - scripts/check-documentation.py
runtime_evidence: []
required_reviewers:
  - information-architecture
review_triggers:
  - evidence-added
  - history-structure-change
supersedes: []
superseded_by: null
---

# История и evidence

Здесь хранятся датированные releases, audits, canaries, incidents и handoffs. Они отвечают на
вопрос «что было проверено тогда», но не заменяют
[current state](../current/project-state.md) и [runbooks](../runbooks/README.md).

Каждый перенесённый документ сохраняет исходное содержание и получает metadata/banner с датой,
областью и актуальной заменой. Исторические команды не повторяются без нового runbook и approval.
