# Backend observability

Status: backend instrumentation, structured security/audit events and scrape contract implemented
on 2026-07-27. Off-host storage, alert routing, log rotation/retention and deployment network wiring
remain deployment work.

## Design rules

- Metric tags have bounded cardinality. Store, user, job, payroll-run and correlation IDs are never
  tags.
- Database-backed gauges read an in-memory snapshot. A scrape never starts business calculations or
  repository queries.
- Correlation IDs belong to logs and error responses, not metric labels.
- Liveness contains no database or external dependency.
- Readiness contains only dependencies required to serve traffic safely.
- External LiveSklad availability is visible, but it does not cause application restart loops.
- Health details and exception messages are not exposed publicly.
- Historical immutable payroll snapshots do not create operational alerts.
- Scheduled operational collectors and the LiveSklad probe exist only in `WORKER` and `COMBINED`;
  the `API` role does not query PostgreSQL or external systems in background.

## Endpoints and access

| Endpoint | Access | Purpose |
| --- | --- | --- |
| `/actuator/health` | Public | Aggregate health with no component details. |
| `/actuator/health/liveness` | Public | Process liveness only. |
| `/actuator/health/readiness` | Public | Application readiness, PostgreSQL and packaged schema version. |
| `/livez` | Public application port | Kubernetes-style liveness alias. |
| `/readyz` | Public application port | Kubernetes-style readiness alias. |
| `/actuator/info` | Public | Safe build and release identity only. |
| `/actuator/metrics` | Changed-password ADMIN | Meter catalog and individual meter values. |
| `/actuator/prometheus` | Private operator, Bearer token | Prometheus text scrape. Disabled with 404 when no token is configured. |
| `/api/system/status` | Authenticated, password changed | Application name, version and server time. |

The Prometheus Micrometer registry is part of the backend runtime. The scrape endpoint has its own
stateless security chain: missing or invalid Bearer credentials return `401`; an unconfigured token
keeps the endpoint fail-closed with `404`. The deployment must additionally place Actuator on a
separate private management port/network and must not route `/actuator/*` through the public edge.
Bearer authorization is defence in depth, not a substitute for network isolation.

A split deployment scrapes the API role for HTTP/request metrics and the worker role for cached sync,
freshness, quality, payroll, retention and LiveSklad operational state. Every meter receives bounded
`application` and `role` common tags, so the two roles remain distinguishable without instance IDs.

## Correlation ID

`CorrelationIdFilter` always generates the authoritative server request UUID, returns it as
`X-Correlation-ID` and places it in SLF4J MDC as `request.id`. A single syntactically safe
incoming header may be retained separately as the untrusted `client.correlation_id` MDC field; it
never controls the returned ID. Invalid or duplicate values are ignored. CORS permits clients to
send the header and exposes the server response header to browser JavaScript.

The base Logback correlation pattern contains both fields. ECS structured logs receive them as
separate MDC properties. The public error property remains `correlationId` for compatibility and
contains `request.id`. Neither identifier is a metric label, and the client value is never used
for security or business decisions. See `error-handling.md` for validation and error-response
rules.

## Structured security and audit events

The default console format is ECS JSON (`LOG_STRUCTURED_FORMAT=ecs`). The application writes to
stdout; it does not manage production log files itself. Request-scoped records include the MDC
`request.id` and, when accepted, `client.correlation_id`. Security and business-audit records use
constant messages and bounded key-value
fields, so untrusted text cannot create field injection or unbounded labels.

Both streams share the versioned SIEM envelope:

| Field | Contract |
| --- | --- |
| `event_schema_version` | Integer `1`; increment it before making an incompatible field or semantic change |
| `event_category` | `security` or `business_audit` |
| `event_type` | Declared lower-snake-case event name |
| `event_outcome` | `success`, `failure` or `unknown` |
| `event_severity` | `info` or `warn`; always matches the emitted logger level |
| `pseudonym_key_id` | Bounded non-secret rotation identifier |

| Stream | Event-specific fields | Metric |
| --- | --- | --- |
| `SECURITY_AUDIT` | Exact per-type allowlist of HMAC references, bounded labels and non-negative numeric values | `storeanalytics.security.events{type}` |
| `BUSINESS_AUDIT` | `audit_category`, `target_ref` and optional `actor_ref` | `storeanalytics.audit.events{category,action}` |

The shared logging boundary validates the complete required/optional field set before incrementing a
counter or emitting a record. Unknown fields, envelope collisions, raw values in `*_ref`, control
characters, unbounded labels and unsupported value types fail closed. `@timestamp`, service
identity and `log.level` are supplied by ECS; request correlation remains optional MDC context and
is deliberately not part of the event schema.

Security event types additionally include `bootstrap_admin_created`, `bootstrap_admin_skipped` and
`break_glass_login_succeeded` alongside login, throttle, authentication, authorization, CSRF,
session, password and user-administration events. Business-audit categories are
`user_administration`, `break_glass`, `payroll`, `synchronization`, `report_backfill`, `retention`
and `business`.


`sessions_revoked` is an expected INFO security action. Its only event-specific fields are
pseudonymous `user_ref`, bounded `scope=single|all_other` and numeric count; neither raw nor opaque
session references are logged or used as metric labels.
All counter series are pre-registered from enums. User, email, client address, target, exception,
request path and correlation ID are never metric tags.

A business-audit monitoring event is published in the transaction that persists `audit_log`, but its
structured log and counter are emitted only by an `AFTER_COMMIT` listener. A rolled-back command
therefore produces neither a false external audit event nor a counter increment.

### Pseudonymization and redaction contract

External telemetry references use HMAC-SHA-256 with domain-separated inputs (`email`, `client`,
`user`, or an action-specific audit target namespace). The exposed value is the versioned prefix
`h1_` plus 96 bits of the MAC. The secret is required at startup, contains 32–256 characters and is
never logged. `pseudonym_key_id` is a bounded non-secret identifier used to distinguish rotations.
The internal login-throttle database key has a separate hasher and is never exported as telemetry.

Rotation of the HMAC secret intentionally breaks correlation with old references. During an
investigation that spans a rotation, correlate by the safe key ID and the off-host event time; do not
copy the old secret into logs or dashboards. Access to the current and retired keys belongs in the
deployment secret manager and follows the incident-retention policy.

Never add raw request/response bodies, LiveSklad payloads, passwords, cookies, authorization or CSRF
headers, tokens, email addresses, IP addresses, exception messages, or arbitrary user-supplied text
to these streams. New event fields must be allowlisted, bounded and covered by a test that asserts
the raw sensitive value is absent.

### Alert inputs and deployment boundary

| Required signal | Backend source |
| --- | --- |
| Login failure or throttle spike | rate/increase of `storeanalytics.security.events{type=login_failed|login_throttled}` |
| Repeated forbidden or CSRF rejection | rate/increase of `storeanalytics.security.events{type=access_denied|csrf_rejected}` |
| Admin, role/store-access or password change | security `user_administration`/`password_changed` plus business-audit `category=user_administration` |
| Bootstrap secret remains or emergency account is used | security `bootstrap_admin_skipped`/`break_glass_login_succeeded` and audit `category=break_glass` |
| Payroll state transition | `storeanalytics.audit.events{category=payroll}` |
| Synchronization or backfill storm | `storeanalytics.audit.events{category=synchronization|report_backfill}` plus job gauges/timers |
| Retention execution or failure | audit `category=retention`, retention duration outcomes and last-success timestamp |
| Failed backup | backup-system metric/event; no application process can prove that an off-host backup succeeded |
| Disk or database-pool saturation | standard `disk.free` and `hikaricp.connections.*` meters from the private scrape endpoint |

Thresholds, evaluation windows, deduplication and escalation routes must be selected from production
baselines and business SLOs. The deployment must ship stdout to access-controlled off-host
append-only storage, enforce bounded local container/runtime rotation, set retention and legal hold,
synchronize clocks, monitor the shipper itself, and route alerts to an owned on-call channel. A
backend-local file alone is not an audit archive: disk exhaustion or host compromise can remove it.

## Timers

| Metric | Tags | Meaning |
| --- | --- | --- |
| `storeanalytics.sync.duration` | `scope=stores|employees|sales|returns`, `trigger=manual|scheduled|initial`, `outcome=success|failure` | End-to-end duration of direct and job-driven synchronization. |
| `storeanalytics.report.backfill.step.duration` | `phase=monthly|annual`, `outcome=success|failure` | Duration of one atomic durable report-backfill step. |
| `storeanalytics.backend.request.duration` | `area=kpi|payroll|report`, HTTP `method`, `outcome=success|client_error|server_error` | MVC duration of KPI, payroll and finalized-report requests, including controller/service work and response completion. |
| `http.server.requests` | Spring Boot standard tags | Standard HTTP server timer retained for endpoint-level diagnostics. |

Custom timers publish percentile histograms. They deliberately omit exception class and concrete URI
parameters to prevent an unbounded time series count.

Rejected LiveSklad payloads expose a separate low-cardinality counter:

| Metric | Tags | Meaning |
| --- | --- | --- |
| `storeanalytics.livesklad.payload.rejections` | bounded `reason` enum | Responses, JSON documents, collection cardinality or raw payloads rejected by a safety limit. |

The seven `reason` series are registered from an enum. Upstream URLs, resource IDs, exception
classes and payload content are never labels.

Technical-data retention exposes a separate bounded metric family:

| Metric | Tags | Meaning |
| --- | --- | --- |
| `storeanalytics.maintenance.retention.duration` | `outcome=success|skipped|failure` | Scheduled retention duration. |
| `storeanalytics.maintenance.retention.affected` | bounded `target` | Cumulative rows aggregated or deleted. |
| `storeanalytics.maintenance.retention.last.success.timestamp` | none | Unix timestamp of the last lock-owning successful run. |

The last-success gauge is `NaN` before the first successful run. Candidate counts remain in safe
aggregate logs/audit metadata and are not dynamic metric labels. See `data-retention.md`.

## Operational gauges

| Metric | Tags | Meaning |
| --- | --- | --- |
| `storeanalytics.sync.jobs` | `status=failed|retrying|expired_lease` | Current persisted failed/retrying jobs and running jobs whose lease has expired. |
| `storeanalytics.report.backfill.jobs` | `status=failed|retrying|expired_lease` | Current persisted report-backfill failures, retries and expired worker leases. |
| `storeanalytics.data.freshness.age` | `source=sales|returns` | Worst age in seconds of the latest successful data-through boundary among active stores. `NaN` means no successful data exists. |
| `storeanalytics.data.freshness.missing` | `source=sales|returns` | Active stores with no successful synchronization for the source. |
| `storeanalytics.quality.issues` | `status=open` | Persisted open data-quality issues. |
| `storeanalytics.payroll.runs` | `state=incomplete|stale|unknown` | Latest actionable payroll revisions. Paid historical revisions are excluded. |
| `storeanalytics.notification.delivery.state` | `channel=TELEGRAM`, bounded `status` | Current ready backlog, active/expired leases, terminal failures and blocked subscriptions. |

Payroll freshness is evaluated against the same source fingerprints used by approval checks.
`stale` includes unknown freshness, while the separate `unknown` series identifies the subset
that could not be classified safely.

Retention preserves both the latest terminal synchronization identity and the greatest
SUCCESS/PARTIAL_SUCCESS SALES/RETURNS data-through boundary for every source scope. Consequently,
removing old technical runs cannot make the freshness gauges regress to an older boundary or
become missing solely because a newer terminal run failed.

The default cached refresh intervals are:

- sync jobs, data freshness, quality issues and Telegram delivery state: 1 minute;
- payroll state: 5 minutes;
- initial metric collection delay: 30 seconds.

The values remain the last successful snapshot if collection fails; the failure is logged with a
full stack trace. General collectors use a four-thread scheduler. `SyncJobWorker` and
`ReportBackfillJobWorker` use separate single-thread `syncWorkerScheduler` and
`reportBackfillScheduler` executors. Fixed-delay execution starts the next claim only after the
previous phase completes, so each workload has node-local concurrency one without an accumulating
submission queue. Database row locks and leases coordinate replicas. A slow synchronization,
report step or LiveSklad probe cannot consume the other worker or the general collector pool.

## Health indicators

### LiveSklad

`LiveSkladHealthIndicator` reads a cached probe result. The probe validates the configured adapter
by loading the store dictionary every 5 minutes. It reports only:

- `UP`: the last probe completed;
- `DOWN`: the last configured probe failed;
- `UNKNOWN`: credentials are not configured or the first probe has not completed.

No base URL, credentials, upstream body or exception message is added to health details. LiveSklad
is part of aggregate health only in `WORKER` and `COMBINED`; the `API` role does not create the
probe or its health indicator. It remains excluded from readiness in every role: restarting this
application cannot repair an external outage.

### Database and schema-version readiness

The readiness group contains:

- Spring Boot `readinessState`;
- the standard `db` indicator, which verifies PostgreSQL connectivity;
- `schemaVersionReadiness`, which reads only `flyway_schema_history` and requires the latest
  successful version to equal the highest versioned migration packaged in the running image.

The expected version is not supplied by an environment variable. A missing history table, failed
migration, database error, older schema or newer schema reports `DOWN` without exposing the SQL
exception. The runtime database role needs `SELECT` on `flyway_schema_history`, but no Flyway bean
or DDL privilege. Liveness contains only `livenessState`.

## Safe version information

Gradle generates `META-INF/build-info.properties` through `springBoot.buildInfo()`. Actuator
publishes the standard build name, artifact, group, version and build time. A separate `release`
detail publishes only application, version, runtime role, expected schema version and build time,
plus validated optional release ID and `sha256` image digest. No Git remote, branch, environment
variables, profiles, filesystem paths, dependency inventory or secret value is returned.

`storeanalytics.release.info` is a constant gauge with bounded `version`, `role`, `schema_version`
and `release_id` tags. It lets a scrape identify the running code/schema pair even when the release
API is unavailable. The authoritative image digest still belongs in the deployment release manifest;
the optional `/actuator/info` digest is corroborating runtime evidence.

`/api/system/status` returns the stable shape:

```json
{
  "application": "store-analytics",
  "version": "0.1.0-SNAPSHOT",
  "apiContractVersion": "9",
  "time": "2026-07-24T12:00:00Z"
}
```

## Configuration

Backend defaults may be overridden by deployment configuration:

| Variable | Default |
| --- | --- |
| `PROMETHEUS_SCRAPE_TOKEN` | empty; scrape disabled |
| `RELEASE_ID` | empty; metric uses `unassigned` |
| `IMAGE_DIGEST` | empty; when set, must be `sha256:<64 lowercase hex>` |
| `LOG_STRUCTURED_FORMAT` | `ecs`; set to an explicit supported Spring Boot format if changed |
| `SECURITY_TELEMETRY_PSEUDONYM_KEY` | required; secret 32–256 characters |
| `SECURITY_TELEMETRY_PSEUDONYM_KEY_ID` | `v1`; bounded non-secret rotation identifier |
| `OBSERVABILITY_STATE_INITIAL_DELAY` | `30s` |
| `OBSERVABILITY_STATE_REFRESH_DELAY` | `1m` |
| `OBSERVABILITY_PAYROLL_REFRESH_DELAY` | `5m` |
| `OBSERVABILITY_LIVESKLAD_INITIAL_DELAY` | `30s` |
| `OBSERVABILITY_LIVESKLAD_REFRESH_DELAY` | `5m` |

Retention schedule, lifetimes and dry-run rollout are configured separately; see
`data-retention.md`.

Alert thresholds should be selected during deployment from actual business SLOs. Reasonable initial
signals are new failed jobs or a failed-job count that keeps growing, retrying jobs that do not
drain, any increase in rejected LiveSklad payloads, missing source data above zero, freshness age
beyond the reporting cutoff, open quality issues, stale/incomplete payroll runs near approval time,
expired worker leases above zero, elevated timer latency and readiness DOWN.
