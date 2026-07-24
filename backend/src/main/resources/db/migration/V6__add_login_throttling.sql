CREATE TABLE auth_login_throttles (
    scope varchar(16) NOT NULL,
    identifier_hash varchar(24) NOT NULL,
    failure_count integer NOT NULL,
    window_started_at timestamptz NOT NULL,
    blocked_until timestamptz,
    last_failure_at timestamptz NOT NULL,
    CONSTRAINT pk_auth_login_throttles PRIMARY KEY (scope, identifier_hash),
    CONSTRAINT ck_auth_login_throttles_scope
        CHECK (scope IN ('EMAIL', 'IP')),
    CONSTRAINT ck_auth_login_throttles_failure_count
        CHECK (failure_count > 0)
);

CREATE INDEX ix_auth_login_throttles_last_failure
    ON auth_login_throttles (last_failure_at);

COMMENT ON TABLE auth_login_throttles IS
    'Hashed login identifiers used for database-backed brute-force throttling.';
