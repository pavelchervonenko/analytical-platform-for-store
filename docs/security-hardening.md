# Backend security hardening

Status: implemented controls revalidated against configuration and routes on 2026-07-27. This is
an engineering/operations document; frontend-visible reactions are in `FRONTEND_HANDOFF.md`.

This document records the controls implemented after the July 2026 backend review.

## Implemented controls

1. **Brute force:** PostgreSQL-backed limits by email hash and client-address hash, generic login
   errors, 429 and Retry-After, scheduled retention cleanup.
2. **Passwords and tokens:** 12-code-point password minimum, Unicode NFC, bcrypt cost 12, explicit
   72-byte UTF-8 ceiling, a versioned offline 46,146-entry compromised-password blocklist plus
   context-specific/control-character rejection, LiveSklad token length and CR/LF checks, automatic
   weak-cost and legacy non-NFC hash upgrade after login, bounded token cache TTL, Spring Security
   7.1.0. The retained 12-character single-factor minimum is an explicit product risk until MFA.
3. **IDOR:** store-scoped controllers call StoreAccessAuthorization; nested employee and payroll
   resources are checked against their parent store. An architecture test rejects missing method
   authorization on any /api/stores controller.
4. **Access rights:** route groups are explicit and the final rule is anyRequest().denyAll().
   Administrative, sync, import, Swagger and actuator-metrics routes require ADMIN.
5. **SQL injection:** JDBC uses constant SQL with named parameters; JPA queries use parameters.
   No request-controlled sort expression or SQL fragment is accepted.
6. **Fake webhooks:** the application exposes no webhook receiver. A future webhook must start
   denied and add signature, timestamp and replay verification before being routed.
7. **Session theft:** login rotates the session ID; production cookies are Secure/HttpOnly where
   applicable; CSRF double-submit is required; idle, absolute and concurrent-session limits apply;
   security-version changes invalidate prior sessions.
8. **SSRF:** the LiveSklad base URL is configuration-only, accepts only HTTP(S), rejects user-info,
   query and fragment components, and production requires HTTPS plus an exact host allowlist.
9. **File upload:** the application exposes no multipart/file-upload endpoint. A future upload
   endpoint requires an explicit threat model before its route is allowed.
10. **CORS:** only explicit HTTP(S) origins are accepted, wildcard origins are rejected, credentials
    remain bound to the allowlist and unknown preflight origins fail.
11. **Race conditions:** active administrators are locked before the last-admin invariant is
    evaluated; a store row is locked before replacing a day's work schedule; payroll continues to
    use optimistic versions and database uniqueness/exclusion constraints.
12. **Logs and secrets:** security/audit telemetry passes through a fail-closed, versioned SIEM
    schema with exact per-event field allowlists. Identity references are HMAC-pseudonymous; raw
    passwords, tokens, email, client IP, arbitrary exception text and undeclared fields are rejected.
    Bootstrap credentials are never logged; production consumes long-lived secrets from config-tree.
13. **Error disclosure and traceability:** every request has a server-generated UUID in
    `X-Correlation-ID`, the JSON error and `request.id` logging MDC. A valid incoming header is
    retained only as a separate untrusted `client.correlation_id` hint and cannot control request
    identity or application behavior. Expected failures use stable safe messages. Unexpected
    exceptions return only `500 INTERNAL_ERROR`; their full stack trace remains in the server log
    with the request ID. Raw
    `IllegalStateException`/`IllegalArgumentException` messages are never treated as public
    business errors.
14. **Observability exposure:** health responses never expose component details; build info contains
    only generated artifact metadata. Metrics require a changed-password ADMIN. Metric labels never
    contain store, user, job, payroll-run or correlation IDs. The LiveSklad health cache contains
    only state and check time, never the configured URL, credentials, upstream body or exception
    message. Liveness excludes dependencies, and readiness excludes LiveSklad to prevent external
    outages from causing application restart loops.
15. **Retention safety:** physical cleanup is disabled by default, serialized across application
    instances by a PostgreSQL advisory lock and performed in bounded transactional batches. Open
    quality evidence, latest synchronization identities, data-freshness boundaries, finalized
    business snapshots and financial audit events are protected. Audit deletion is additionally
    guarded by immutable retention metadata, active holds and a transaction-local database check.
16. **Proxy/client IP trust:** automatic forwarded-header processing is disabled. A central resolver
    ignores `X-Forwarded-For` unless the socket peer is in an explicit CIDR allowlist, walks trusted
    chains right-to-left and rejects malformed/ambiguous chains. Audit uses the normalized full IP;
    throttling groups IPv6 by `/64` and preserves the shared IP scope after successful login.
17. **Bounded LiveSklad responses:** declared and actually consumed response bytes are capped before
    deserialization. A dedicated Jackson mapper limits document length, token count, nesting depth,
    strings, field names and numbers without changing the application mapper. Successful upstream
    responses must be JSON with identity content encoding; rejected payload content is not logged.
    The HTTP request factory preserves response headers and raw bytes until this guard runs, so
    transparent decompression cannot bypass the explicit content-encoding denial.
18. **Bounded raw persistence:** collection and document-position counts are capped without silent
    truncation. Raw JSON is serialized, size-checked and canonically hashed before any repository
    access. The 4 MiB operational default is backed by an independent 16 MiB database constraint;
    deterministic payload rejection is not retried by the durable synchronization worker.
19. **Payload boundary observability:** every rejection increments one counter whose only label is a
    bounded reason enum. An architecture test prevents raw persistence types and names from entering
    backend web or frontend sources; rejected payload content is never a label or exception message.
20. **Sync bulkhead:** durable sync claims run on a dedicated single-thread scheduler with fixed-delay
    execution. This isolates blocking upstream work from general collectors and prevents concurrent
    sync phases or an accumulating submission queue.
21. **Bootstrap and emergency access:** first-admin creation is transactionally serialized across
    replicas, allowed only for an empty user table, forces a password change and creates persistent
    audit. Stale bootstrap credentials produce a warning without creating another user. Configured
    break-glass UUIDs create dedicated persistent and structured alerts on every successful login.
    See `bootstrap-and-break-glass.md`.
22. **Operator scripts and local artifacts:** dotenv content is parsed as UTF-8 data by a strict
    dependency-free parser; it is never sourced or evaluated. Only the three required LiveSklad
    variables are imported, duplicate/invalid assignments fail closed, and other variables such as
    `PATH` cannot alter the process. Administrative base URLs are origin-only: plaintext HTTP is
    restricted to exact `localhost` or `127.0.0.1`, while remote access requires HTTPS with normal
    certificate verification. Curl protocol, connect/time and response-size limits are explicit;
    displayed response bodies are bounded and terminal control bytes are escaped. Customer-derived
    `outputs/` are ignored by Git. The review generator creates new artifacts atomically with mode
    `0600` and refuses overwrite. Native negative tests run as
    `:backend:operatorScriptSecurityTest` and before `:backend:test`.
23. **Inbound API body boundary:** a validated 2 MiB default is enforced before Spring Security
    from both declared length and actually consumed encoded bytes. Unknown/chunked length,
    understated headers, reader access and stream skipping cannot bypass it. Rejections use stable
    `413 PAYLOAD_TOO_LARGE`, authoritative correlation ID, `no-store` and `nosniff`, without
    logging or reflecting the body, declared length or configured limit. Form/swallow limits remain
    aligned and domain-specific cardinality/JSON-complexity checks still apply.
24. **Self-service session revocation:** authenticated users can list and expire their own other
    sessions. The API returns an HMAC `sessionReference`, last-seen time and current marker; raw
    `JSESSIONID`, IP and User-Agent never leave the backend. Unknown references are idempotent,
    current-session revoke requires logout, every effective action emits a bounded pseudonymous
    `sessions_revoked` event, and concurrent login registration cannot exceed the process-local
    limit. Multi-replica remains forbidden until the registry is shared under P1-01.



## Production inputs

docker-compose.prod.yml requires these non-secret values:

- POSTGRES_DB, POSTGRES_USER;
- LIVESKLAD_BASE_URL;
- LIVESKLAD_ALLOWED_HOSTS (comma-separated exact host names);
- CORS_ALLOWED_ORIGINS (comma-separated exact origins);
- TRUSTED_PROXY_CIDRS (comma-separated exact Caddy/private proxy CIDRs; empty means trust none).
- BREAK_GLASS_USER_IDS (comma-separated UUIDs of customer-owned emergency accounts; no credentials).

It also requires paths to three secret files:

- POSTGRES_PASSWORD_FILE;
- LIVESKLAD_LOGIN_FILE;
- LIVESKLAD_PASSWORD_FILE.

The files are mounted as Docker secrets and imported with Spring Boot config-tree support. Do not
put their values in Compose, images, source control or command-line arguments.

## Operational notes

- `TRUSTED_PROXY_CIDRS` must identify only the dedicated Caddy/private proxy subnet. Do not configure
  a broad range that can contain normal clients or unrelated containers. The backend port remains
  private; CIDR validation is defence in depth, not a replacement for network isolation.
- Caddy-to-backend E2E tests remain deployment acceptance: verify the intended address, a spoofed
  prefix, multiple proxy hops and direct backend denial before production.
- LiveSklad payload limits default to 2 MB, 100,000 tokens, depth 64, string length 65,536, field-name
  length 256 and number length 128. Tune `LIVESKLAD_MAX_RESPONSE_SIZE`,
  `LIVESKLAD_MAX_DOCUMENT_LENGTH`, `LIVESKLAD_MAX_TOKEN_COUNT`, `LIVESKLAD_MAX_NESTING_DEPTH`,
  `LIVESKLAD_MAX_STRING_LENGTH`, `LIVESKLAD_MAX_NAME_LENGTH`, `LIVESKLAD_MAX_NUMBER_LENGTH`,
  `LIVESKLAD_MAX_RAW_PAYLOAD_SIZE`, `LIVESKLAD_MAX_COLLECTION_RECORDS` and
  `LIVESKLAD_MAX_POSITIONS_PER_DOCUMENT` only from observed source payloads. Raw persistence defaults
  to 4 MiB, 1,000 collection records and 1,000 positions. Startup validation enforces hard ceilings;
  do not raise them merely to make a rejected malformed response pass.
- A separate node-local LiveSklad circuit breaker is intentionally not used while calls are driven
  by persisted jobs with capped exponential backoff, `Retry-After`, request-budget enforcement and
  connect/read timeouts. A per-process open/half-open state would diverge across replicas and
  duplicate durable recovery state. Reconsider only from measured SLO evidence or if concurrent
  direct-call traffic is introduced; define an upstream-scoped state model first.
- The backend has migrated to Spring Boot 4.1.0, Spring Security 7.1.0, springdoc 3.0.3,
  Testcontainers 2.0.5 and native Jackson 3.1.4. The deprecated Boot Jackson 2 compatibility bridge
  is not present; remaining Jackson 2 artifacts are isolated transitive dependencies of tooling.
  Exact-image staging and N-1 rollout checks remain deployment acceptance gates.
- Preserve backend/gradle.lockfile and update it intentionally with dependency changes.
- No application control can replace TLS termination, restricted Docker/host access, database
  backups, secret rotation and centralized monitoring.
