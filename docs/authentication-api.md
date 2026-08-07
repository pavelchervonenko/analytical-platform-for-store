# Authentication and authorization API

Status: implemented and revalidated against security configuration on 2026-07-27. Frontend button
flow and enable rules are summarized in `docs/frontend-actions.md`.

The dashboard uses a server-side Spring Security session. JWT is intentionally not used for the
current single-backend browser application.

## Authentication flow

1. Call GET /api/auth/csrf and retain the XSRF-TOKEN cookie.
2. Send the raw cookie value in the X-XSRF-TOKEN header with POST /api/auth/login.
3. Retain the JSESSIONID cookie returned after successful authentication.
4. Call GET /api/auth/csrf again after login because authentication rotates the CSRF token.
5. Include both cookies and X-XSRF-TOKEN for every unsafe request.

Main endpoints:

~~~text
GET  /api/auth/csrf
POST /api/auth/login
GET  /api/auth/me
GET  /api/auth/sessions
DELETE /api/auth/sessions/{sessionReference}
DELETE /api/auth/sessions/others
POST /api/auth/change-password
POST /api/auth/logout
~~~

Temporary passwords restrict the session to authenticated auth/session endpoints. Changing a
password invalidates the current session and requires a new login.

## Login throttling and sessions

Failed logins are throttled in PostgreSQL by both normalized-email hash and resolved client-address
hash. The default email limit is five failures in 15 minutes, followed by a 15-minute block. The IP
limit is intentionally higher to reduce accidental lockout behind an office NAT. A successful login
clears only its email failures; it does not clear the shared IP history. A blocked request returns
429 LOGIN_THROTTLED with a Retry-After header. Raw emails and addresses are not stored in the
throttle table or security audit messages.

Client address resolution is fail-closed. With an empty `TRUSTED_PROXY_CIDRS` list, forwarding
headers are ignored. A request from an explicitly trusted proxy is resolved right-to-left through
`X-Forwarded-For` until the nearest untrusted hop. IPv4 has one strict decimal representation;
IPv4-mapped IPv6 is converted to IPv4; IPv6 uses its full normalized value for audit and a `/64`
prefix for throttling. Invalid, duplicate or unbounded forwarding chains fall back to the direct
socket peer rather than accepting a client-selected value.

The session ID is rotated at login. The default idle timeout is 30 minutes, the absolute timeout is
12 hours and a user may have at most three active sessions. Opening a fourth session expires the
oldest one. Production session and CSRF cookies are Secure; session cookies are also HttpOnly and
SameSite=Lax.

An authenticated user can list at most those three active sessions. The response exposes only:

- a fixed-shape HMAC `sessionReference`, never the raw `JSESSIONID`;
- `lastSeenAt`;
- the `current` flag.

IP and User-Agent are deliberately absent until a separate privacy and device-label model is
accepted. `DELETE /api/auth/sessions/{sessionReference}` idempotently expires one other session;
`DELETE /api/auth/sessions/others` expires every other session visible at command time. Both
require CSRF. The current session must use ordinary logout; trying its reference returns
`409 CURRENT_SESSION_REQUIRES_LOGOUT`. Every effective revoke publishes a bounded
`sessions_revoked` security event containing only a user pseudonym, scope and count.

The registry is process-local while production is constrained to one API replica. Before enabling
multiple API replicas, P1-01 must replace it with the accepted shared Spring Session implementation;
the public response and command contract stays unchanged.

## Roles and store access

- ADMIN automatically accesses every store and can manage users, imports and synchronization.
- MANAGER accesses only stores listed in user_store_access.
- Every store-scoped controller method declares the central StoreAccessAuthorization check.
- An architecture test fails if a new store-scoped endpoint omits @PreAuthorize.
- Role, activation or credential changes increment security_version; an older session is rejected
  on its next request.
- Security routing is fail-closed: known route groups are explicit and all unmatched requests use
  denyAll().

There is no public registration. Administrators create manager accounts with temporary passwords.
The last active administrator cannot be deactivated or demoted, and an administrator cannot change
their own role or deactivate their own account. The last-administrator check uses a pessimistic
database lock, so two concurrent updates cannot remove every active administrator.

## Password policy

New, reset and bootstrap passwords are canonicalized to Unicode NFC and must contain at least 12 and
at most 128 Unicode code points. They must fit within bcrypt's 72-byte UTF-8 limit, must not contain
control characters and must not match the context-specific denylist or the versioned offline
compromised-password blocklist. The blocklist contains 46,146 SHA-256 fingerprints derived from the
policy-eligible entries in the SecLists top-one-million dataset; no password data is sent to an
external service.

New hashes use bcrypt cost 12 and retain the standard `{bcrypt}` prefix. Existing weaker bcrypt
hashes are transparently rehashed with cost 12 after a successful login. A legacy hash created from
non-NFC Unicode remains valid for its original input and is transparently replaced with the NFC hash
after that login. The policy has no character-class composition rule or scheduled password expiry,
and does not prevent password-manager paste/autofill.

## First administrator

Flyway V2 adds email authentication fields but never stores a predefined password. If `app_users` is
empty, a one-time administrator can be created through Spring configuration. The transactional
creation path acquires a PostgreSQL advisory lock before checking emptiness, so concurrent replicas
create exactly one account. Partial configuration stops startup without echoing input. A non-empty
database always returns `USERS_EXIST` and creates no user.

The new administrator requires an immediate password change. Creation is stored as
`BOOTSTRAP_ADMIN_CREATED` persistent security audit; configured credentials left after users exist
produce a bounded `bootstrap_admin_skipped` warning/counter. Production values come from one-time
config-tree secret files and must be destroyed after bootstrap.

Configured emergency-account UUIDs in `BREAK_GLASS_USER_IDS` receive a dedicated persistent audit
row and security alert on every successful login. See `bootstrap-and-break-glass.md` for the exact
one-time and recovery procedures.

## HTTP behavior

- 401 AUTHENTICATION_REQUIRED for missing or invalidated sessions;
- 401 INVALID_CREDENTIALS for failed login, without revealing whether the email exists;
- 401 SESSION_EXPIRED when a session is displaced by the concurrent-session limit or explicitly
  revoked from another authenticated session;
- 429 LOGIN_THROTTLED after the configured failure threshold;
- 403 ACCESS_DENIED for insufficient role, missing store access, required password change or an
  unlisted route;
- 409 for conflicting user administration operations and
  CURRENT_SESSION_REQUIRES_LOGOUT when revoke targets the caller's current session;
- passwords, password hashes and upstream tokens are never returned by the API.
