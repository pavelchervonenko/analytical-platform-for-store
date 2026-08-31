---
doc_schema: 1
doc_type: current
status: current
owner: project
audience:
  - developer
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - docs/archive
verification_sources:
  - scripts/check-documentation.py
runtime_evidence: []
required_reviewers:
  - information-architecture
review_triggers:
  - archive-change
  - superseded-contract-change
supersedes: []
superseded_by: null
---

# Архив

Архив хранит superseded design, discovery и прежние контракты только для контекста. Эти файлы не
описывают текущее поведение и не разрешают production-операции. Их актуальная замена указывается в
metadata и в карте миграции этапа архивирования.
