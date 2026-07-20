ALTER TABLE app_users RENAME COLUMN username TO email;
ALTER INDEX ux_app_users_username RENAME TO ux_app_users_email;

ALTER TABLE app_users
    ADD COLUMN password_change_required boolean NOT NULL DEFAULT true,
    ADD COLUMN security_version bigint NOT NULL DEFAULT 0,
    ADD COLUMN last_login_at timestamptz;

COMMENT ON COLUMN app_users.email IS
    'Case-insensitive application login. Email addresses are normalized by the application.';
COMMENT ON COLUMN app_users.password_hash IS
    'DelegatingPasswordEncoder value. Plain-text passwords are never stored.';
COMMENT ON COLUMN app_users.password_change_required IS
    'Restricts access to ordinary endpoints until a temporary password is replaced.';
COMMENT ON COLUMN app_users.security_version IS
    'Incremented when credentials, role, or activation state changes to invalidate existing sessions.';

