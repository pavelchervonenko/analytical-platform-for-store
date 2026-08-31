---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - manager
last_verified: 2026-08-31
requirement_sources:
  - docs/reports.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/report
  - contracts/openapi/current.json
verification_sources:
  - backend/src/test/java/com/storeanalytics/report/web/ReportControllerTest.java
  - backend/src/test/java/com/storeanalytics/report/service/ReportSnapshotPayloadPersistenceIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/report/service/MonthlyReportFinalizationServiceTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - product-formula
review_triggers:
  - report-schema-change
  - report-finalization-change
  - payroll-finalization-change
supersedes:
  - docs/reports.md
superseded_by: null
---

# Reports API

## Read API

- `GET /api/stores/{storeId}/reports` — bounded page с optional year/type filters.
- `GET /api/stores/{storeId}/reports/years` — доступные years.
- `GET /api/stores/{storeId}/reports/{reportId}` — exact immutable revision в store scope.

Monthly report finalizes atomically with exact `PAID` payroll revision. Annual report состоит из
exact finalized monthly revisions; correction создаёт новую immutable revision с reason и не
переписывает предыдущую. Payload bytes, schema/template version, source hash и provenance
сохраняются для проверки.

Dashboard за текущий период — dynamic projection и не равен report archive. Employee section
report-а также не следует считать полной сверкой всех store facts, если report schema включает
только payroll/rating roster.

Administrative backfill endpoints из OpenAPI v10 являются отдельным durable workflow с lease,
cursor и cancellation. Их наличие не разрешает запуск без отдельного operations runbook и точного
target.
