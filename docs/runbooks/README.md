---
doc_schema: 1
doc_type: current
status: current
owner: operations
audience:
  - operator
  - developer
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - docs/runbooks
verification_sources:
  - scripts/check-documentation.py
runtime_evidence: []
required_reviewers:
  - operations
  - security-privacy
review_triggers:
  - runbook-change
  - operational-procedure-change
supersedes: []
superseded_by: null
---

# Runbooks

Runbook разрешает только то, что допускают его `status`, verification levels и evidence. Все
production write/migration/recovery процедуры ниже остаются `draft`, пока не выполнены указанные в
них rehearsal и runtime gates.

## Release, database и host

- [Production deployment](production-deployment.md)
- [Migration failure и forward-fix](migration-failure-and-forward-fix.md)
- [Application rollback](application-rollback.md)
- [Backup restore и DR](backup-restore-and-dr.md)
- [Database ACL repair](database-acl-repair.md)
- [Host rebuild](host-rebuild.md)

## Security и incidents

- [Alert response](alert-response.md)
- [Incident response](incident-response.md)
- [Secret rotation](secret-rotation.md)
- [Access и break-glass](access-and-break-glass.md)
- [Retention rollout](retention-rollout.md)

## LiveSklad

- [Webhook canary и обработка](livesklad-webhooks.md)
- [Return recovery](livesklad-return-recovery.md)

## AI и Telegram

- [Weekly Review AI](weekly-review-ai.md)
- [Legacy LLM](legacy-llm.md)
- [AI evaluation](ai-evaluation.md)
- [Telegram](telegram.md)
- [Daily store pulse](daily-store-pulse.md)
