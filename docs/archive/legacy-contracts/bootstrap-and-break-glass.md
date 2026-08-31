---
doc_schema: 1
doc_type: archive
status: archived
owner: security
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/runbooks/access-and-break-glass.md"
original_content_sha256: 672d5b8401a2376c5df11ca7e5b234e71c4c2f5e42e514ea69f2d44a28140fe6
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/runbooks/access-and-break-glass.md`.

# Bootstrap administrator and break-glass access

Status: backend controls implemented and tested on 2026-07-26. Secret provisioning, alert routing,
MFA recovery factors and the customer incident rehearsal remain deployment/operations work.

## Security invariants

- Bootstrap is disabled when both bootstrap email and password are absent.
- Partial configuration stops startup with a constant error that contains no email or credential.
- Creation is serialized across replicas by a PostgreSQL transaction-scoped advisory lock.
- The administrator is created only when `app_users` is empty after that lock is acquired.
- Every new user, including the bootstrap administrator, starts with
  `password_change_required=true`; ordinary/admin endpoints remain forbidden until the temporary
  password is replaced.
- Creation and every configured break-glass login are stored in immutable PostgreSQL audit with the
  `SECURITY` retention class and also emitted as structured pseudonymous monitoring events.
- Bootstrap is not an account-recovery bypass. It never creates a user in a non-empty database.

## One-time bootstrap procedure

Use a controlled maintenance window and a customer-owned one-time secret channel. For the one-off
backend container, mount Spring config-tree files with these exact names:

- `app.security.bootstrap-admin.email`;
- `app.security.bootstrap-admin.password`;
- optionally `app.security.bootstrap-admin.display-name`.

The password file is a unique temporary password accepted by `PasswordPolicy`. Files are owned by
the deployment account, mode `0600`, and are not printed by diagnostics or `docker compose config`.
Do not pass the password on a command line or commit it to Compose/environment templates.

Execution checklist:

1. Verify the intended database and schema migration state, and independently confirm that
   `app_users` is empty. A non-empty database is a stop condition.
2. Mount the three one-time config-tree files only into the bootstrap API/combined instance and
   start it. Concurrent starts are safe, but unnecessary replicas should not receive the secret.
3. Require exactly one `bootstrap_admin_created` security event and one persistent
   `BOOTSTRAP_ADMIN_CREATED` audit row. No email or password appears in either event.
4. Log in through HTTPS, replace the temporary password immediately, then log in again with the new
   password. Until this succeeds, the account can access only authenticated session/password-change
   endpoints.
5. Create and verify a second named administrator so loss of one account does not remove all normal
   administration paths.
6. Stop the bootstrap instance, unmount and destroy the one-time files, remove their secret-manager
   versions, and deploy the normal service configuration without bootstrap properties.
7. Restart once and confirm there is no `bootstrap_admin_skipped` event. That warning means
   bootstrap credentials are still being supplied after users exist and must page the deployment
   owner; the backend still refuses to create another user.

The password must be rotated if it was exposed before step 6, even though the first login replaced
the database hash. Never reuse the one-time password for the permanent or break-glass account.

## Break-glass account preparation

Create a dedicated, named ADMIN through the ordinary administration API, change its temporary
password, verify access, and store its recovery material in a customer-owned offline password
manager. It must not be a shared daily-use account.

Set `BREAK_GLASS_USER_IDS` to the comma-separated UUIDs of those accounts. UUIDs are non-secret;
credentials and email addresses do not belong in this property. A successful configured account
login produces all of the following:

- `BREAK_GLASS_LOGIN_SUCCEEDED` in persistent audit;
- `storeanalytics.audit.events{category="break_glass",action="break_glass_login_succeeded"}`;
- `storeanalytics.security.events{type="break_glass_login_succeeded"}`;
- a WARN `SECURITY_AUDIT` event containing only HMAC-pseudonymous user/client references.

Any increase must alert the customer security owner. Rehearse access at a documented interval,
review the resulting alert and audit row, then return the credential to sealed storage. A test login
is still break-glass use and must not be filtered from alerts.

## Recovery and compromise cases

### Lost ordinary or last administrator access

Use another named administrator or the prepared break-glass account. The backend prevents an active
last administrator from being demoted or deactivated. Do not re-enable bootstrap credentials: a
non-empty database intentionally makes bootstrap return `USERS_EXIST`.

If every administrator and break-glass credential is lost, there is no public or bootstrap reset
endpoint. Treat this as a security incident owned by the database/customer operator. Keep the
service isolated, preserve logs/backups, verify authorization out of band, and use only a separately
reviewed schema-version-specific DBA recovery procedure. Direct improvised password-hash edits are
not an accepted routine runbook because they bypass application audit. A tested DBA recovery tool
must exist before MFA is made mandatory.

### Compromised administrator

From a separate trusted administrator, deactivate the account or reset its password. Both operations
increment `security_version`, invalidating its existing sessions. Rotate any secrets the account
could access, review security/business audit and deployment logs from before the first suspicious
event, and preserve off-host evidence. If the compromised account is the last normal administrator,
use the break-glass account; never use the bootstrap secret.

### Lost MFA devices

Application MFA is not currently implemented under the documented SEC-01 temporary exception, so
the backend must not claim MFA recovery support. Before that exception expires and MFA is enabled,
the identity design must provide customer-held recovery codes or a separately protected break-glass
factor, audit every recovery, revoke lost factors and notify the security owner. Bootstrap credentials
must never serve as an MFA bypass.
