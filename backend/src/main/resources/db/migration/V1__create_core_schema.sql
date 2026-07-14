CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE stores (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    external_id text,
    name text NOT NULL,
    timezone text NOT NULL DEFAULT 'Europe/Moscow',
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_stores_external_id
    ON stores (external_id)
    WHERE external_id IS NOT NULL;

CREATE TABLE app_users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    username text NOT NULL,
    password_hash text NOT NULL,
    display_name text NOT NULL,
    role text NOT NULL CHECK (role IN ('ADMIN', 'MANAGER')),
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_app_users_username ON app_users (lower(username));

CREATE TABLE sync_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid REFERENCES stores(id),
    source_system text NOT NULL CHECK (source_system IN ('LIVESKLAD', 'MANUAL', 'AMOCRM', 'AI')),
    sync_type text NOT NULL,
    status text NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    period_start timestamptz,
    period_end timestamptz,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    requested_by uuid REFERENCES app_users(id),
    processed_records integer NOT NULL DEFAULT 0,
    error_message text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_sync_runs_store_started_at ON sync_runs (store_id, started_at DESC);
CREATE INDEX ix_sync_runs_source_status ON sync_runs (source_system, status);

CREATE TABLE external_raw_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid REFERENCES stores(id),
    source_system text NOT NULL CHECK (source_system IN ('LIVESKLAD', 'MANUAL', 'AMOCRM', 'AI')),
    entity_type text NOT NULL,
    external_id text NOT NULL,
    payload jsonb NOT NULL,
    payload_hash text NOT NULL,
    source_updated_at timestamptz,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    last_sync_run_id uuid REFERENCES sync_runs(id)
);

CREATE UNIQUE INDEX ux_external_raw_records_identity
    ON external_raw_records (
        COALESCE(store_id, '00000000-0000-0000-0000-000000000000'::uuid),
        source_system,
        entity_type,
        external_id
    );
CREATE INDEX ix_external_raw_records_entity ON external_raw_records (source_system, entity_type);
CREATE INDEX ix_external_raw_records_payload ON external_raw_records USING gin (payload);

CREATE TABLE report_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid REFERENCES stores(id),
    report_type text NOT NULL,
    period_type text NOT NULL CHECK (period_type IN ('DAY', 'WEEK', 'MONTH', 'YEAR', 'CUSTOM')),
    period_start date NOT NULL,
    period_end date NOT NULL,
    status text NOT NULL CHECK (status IN ('DRAFT', 'CALCULATED', 'APPROVED', 'ARCHIVED')),
    generated_at timestamptz NOT NULL DEFAULT now(),
    approved_at timestamptz,
    approved_by uuid REFERENCES app_users(id),
    formula_version text,
    input_hash text,
    payload jsonb NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_report_snapshots_store_period ON report_snapshots (store_id, period_start, period_end);
CREATE INDEX ix_report_snapshots_type_status ON report_snapshots (report_type, status);

CREATE TABLE audit_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id uuid REFERENCES app_users(id),
    store_id uuid REFERENCES stores(id),
    action text NOT NULL,
    entity_type text,
    entity_id text,
    ip_address inet,
    user_agent text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ix_audit_log_actor_created_at ON audit_log (actor_user_id, created_at DESC);
CREATE INDEX ix_audit_log_store_created_at ON audit_log (store_id, created_at DESC);
CREATE INDEX ix_audit_log_action ON audit_log (action);
