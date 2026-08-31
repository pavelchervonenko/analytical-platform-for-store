---
doc_schema: 1
doc_type: current
status: current
owner: security
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - docs/security
verification_sources:
  - scripts/check-documentation.py
runtime_evidence: []
required_reviewers:
  - security-privacy
  - operations
review_triggers:
  - security-control-change
  - threat-model-change
supersedes: []
superseded_by: null
---

# Security

- [Baseline](baseline.md)
- [Authentication и access](authentication-and-access.md)
- [Secrets и key custody](secrets-and-key-custody.md)
- [Data retention](data-retention.md)
- [Supply chain](supply-chain.md)
- [Threat model и реестр рисков](threat-model-and-risk-register.md)

Реализованный control не считается подтверждённым в production без датированного evidence.
Critical/high risks закрываются не документом, а реализацией, проверкой и rehearsal.
