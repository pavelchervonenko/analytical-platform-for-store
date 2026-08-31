---
doc_schema: 1
doc_type: working
status: closed
owner: project
audience:
  - developer
created_at: 2026-08-31
review_by: 2026-09-07
source_material:
  - docs/maintenance/documentation-inventory.tsv
required_reviewers:
  - information-architecture
exit_target: archive
---

# Карта остаточной миграции evidence

Три датированных audit-файла из корня репозитория являются историческим evidence, а не текущими
инструкциями. Перенос выполняется только после отдельного candidate commit и независимого review.

| Исходный путь | Новый путь | Актуальная замена |
|---|---|---|
| `BACKEND_STARTUP_SYNC_REGRESSION_AUDIT.md` | `docs/history/audits/2026/07/backend/BACKEND_STARTUP_SYNC_REGRESSION_AUDIT.md` | `docs/current/architecture/application.md`; `docs/current/integrations/livesklad/synchronization.md`; `docs/current/architecture/migrations.md` |
| `BACKEND_STARTUP_SYNC_REGRESSION_AUDIT_RESULT.md` | `docs/history/audits/2026/07/backend/BACKEND_STARTUP_SYNC_REGRESSION_AUDIT_RESULT.md` | `docs/current/architecture/application.md`; `docs/current/integrations/livesklad/synchronization.md`; `docs/current/architecture/error-handling.md` |
| `PRODUCTION_READINESS_SECURITY_AUDIT_TEMP.md` | `docs/history/audits/2026/07/security/PRODUCTION_READINESS_SECURITY_AUDIT_TEMP.md` | `docs/security/baseline.md`; `docs/security/threat-model-and-risk-register.md`; `docs/runbooks/production-deployment.md` (draft; production NO-GO) |

## Gates

- Исходное тело сохраняется с SHA-256 и только link relocation.
- Production-команды из evidence не становятся разрешённым runbook.
- Все входящие ссылки обновляются.
- Старые пути остаются tombstone с отдельным reviewer sign-off.
- `.dockerignore` исключает `docs/history` и `docs/archive`, поэтому historical evidence не
  расширяет backend Docker build context после переноса.

## Итог

Перенос завершён 2026-08-31 и независимо подтверждён в
`docs/history/audits/2026/08/residual-documentation-migration-signoff.md`: 3/3 rename,
3/3 исходных SHA-256 и полное совпадение тел после удаления metadata/banner.
