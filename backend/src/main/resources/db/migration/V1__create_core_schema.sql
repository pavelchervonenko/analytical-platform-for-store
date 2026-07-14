CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE stores (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_system text NOT NULL DEFAULT 'LIVESKLAD' CHECK (source_system IN ('LIVESKLAD', 'MANUAL')),
    external_id text,
    name text NOT NULL,
    address text,
    timezone text NOT NULL DEFAULT 'Europe/Kaliningrad',
    business_day_start time NOT NULL DEFAULT '00:00:00',
    opens_at time NOT NULL DEFAULT '10:00:00',
    closes_at time NOT NULL DEFAULT '21:00:00',
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (opens_at < closes_at)
);
CREATE UNIQUE INDEX ux_stores_source_external_id ON stores (source_system, external_id) WHERE external_id IS NOT NULL;

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

CREATE TABLE user_store_access (
    user_id uuid NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    store_id uuid NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    granted_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    granted_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, store_id)
);
CREATE INDEX ix_user_store_access_store ON user_store_access (store_id);

CREATE TABLE sync_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid REFERENCES stores(id),
    source_system text NOT NULL CHECK (source_system IN ('LIVESKLAD', 'MANUAL', 'AMOCRM', 'AI')),
    trigger_type text NOT NULL CHECK (trigger_type IN ('INITIAL', 'SCHEDULED', 'MANUAL', 'REPROCESS')),
    sync_scope text NOT NULL CHECK (sync_scope IN ('FULL', 'STORES', 'EMPLOYEES', 'PRODUCTS', 'SALES', 'RETURNS', 'PERIOD')),
    status text NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELLED')),
    period_start timestamptz,
    period_end timestamptz,
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    requested_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    records_fetched integer NOT NULL DEFAULT 0 CHECK (records_fetched >= 0),
    records_created integer NOT NULL DEFAULT 0 CHECK (records_created >= 0),
    records_updated integer NOT NULL DEFAULT 0 CHECK (records_updated >= 0),
    records_skipped integer NOT NULL DEFAULT 0 CHECK (records_skipped >= 0),
    records_failed integer NOT NULL DEFAULT 0 CHECK (records_failed >= 0),
    error_summary text,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end IS NULL OR period_start IS NULL OR period_end >= period_start),
    CHECK (finished_at IS NULL OR finished_at >= started_at)
);
CREATE INDEX ix_sync_runs_store_started_at ON sync_runs (store_id, started_at DESC);
CREATE INDEX ix_sync_runs_source_status ON sync_runs (source_system, status, started_at DESC);

CREATE TABLE sync_run_errors (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sync_run_id uuid NOT NULL REFERENCES sync_runs(id) ON DELETE CASCADE,
    stage text NOT NULL,
    entity_type text,
    external_id text,
    error_code text,
    error_message text NOT NULL,
    is_retryable boolean NOT NULL DEFAULT false,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_sync_run_errors_run ON sync_run_errors (sync_run_id, created_at);
CREATE INDEX ix_sync_run_errors_entity ON sync_run_errors (entity_type, external_id);

CREATE TABLE raw_record_versions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid REFERENCES stores(id),
    source_system text NOT NULL CHECK (source_system IN ('LIVESKLAD', 'MANUAL', 'AMOCRM', 'AI')),
    entity_type text NOT NULL,
    external_id text NOT NULL,
    payload jsonb NOT NULL,
    payload_hash varchar(64) NOT NULL CHECK (length(payload_hash) = 64),
    source_updated_at timestamptz,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    first_sync_run_id uuid NOT NULL REFERENCES sync_runs(id),
    last_sync_run_id uuid NOT NULL REFERENCES sync_runs(id),
    normalization_status text NOT NULL DEFAULT 'PENDING'
        CHECK (normalization_status IN ('PENDING', 'NORMALIZED', 'FAILED', 'SKIPPED')),
    normalized_at timestamptz,
    CHECK (last_seen_at >= first_seen_at),
    CHECK ((normalization_status = 'NORMALIZED' AND normalized_at IS NOT NULL) OR normalization_status <> 'NORMALIZED')
);
CREATE UNIQUE INDEX ux_raw_record_versions_identity_hash ON raw_record_versions (
    COALESCE(store_id, '00000000-0000-0000-0000-000000000000'::uuid),
    source_system, entity_type, external_id, payload_hash
);
CREATE INDEX ix_raw_record_versions_entity_seen ON raw_record_versions (source_system, entity_type, external_id, last_seen_at DESC);
CREATE INDEX ix_raw_record_versions_normalization ON raw_record_versions (normalization_status, first_seen_at)
    WHERE normalization_status IN ('PENDING', 'FAILED');

CREATE TABLE employees (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_system text NOT NULL DEFAULT 'LIVESKLAD' CHECK (source_system IN ('LIVESKLAD', 'MANUAL')),
    external_id text,
    full_name text NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    source_updated_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_employees_source_external_id ON employees (source_system, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX ix_employees_active_name ON employees (is_active, full_name);

CREATE TABLE employee_store_assignments (
    employee_id uuid NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    store_id uuid NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    is_active boolean NOT NULL DEFAULT true,
    participates_in_ranking boolean NOT NULL DEFAULT true,
    assigned_at timestamptz NOT NULL DEFAULT now(),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    PRIMARY KEY (employee_id, store_id)
);
CREATE INDEX ix_employee_store_assignments_store ON employee_store_assignments (store_id, is_active, participates_in_ranking);

CREATE TABLE cash_registers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    source_system text NOT NULL DEFAULT 'LIVESKLAD' CHECK (source_system IN ('LIVESKLAD', 'MANUAL')),
    external_id text NOT NULL,
    name text NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_system, external_id)
);
CREATE INDEX ix_cash_registers_store_active ON cash_registers (store_id, is_active);

CREATE TABLE source_product_groups (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_system text NOT NULL DEFAULT 'LIVESKLAD' CHECK (source_system IN ('LIVESKLAD', 'MANUAL')),
    external_id text,
    path text NOT NULL,
    name text NOT NULL,
    parent_id uuid REFERENCES source_product_groups(id),
    is_active boolean NOT NULL DEFAULT true,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_system, path)
);
CREATE UNIQUE INDEX ux_source_product_groups_external_id ON source_product_groups (source_system, external_id)
    WHERE external_id IS NOT NULL;

CREATE TABLE products (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_system text NOT NULL DEFAULT 'LIVESKLAD' CHECK (source_system IN ('LIVESKLAD', 'MANUAL')),
    external_id text NOT NULL,
    source_group_id uuid REFERENCES source_product_groups(id),
    code text,
    sku text,
    name text NOT NULL,
    source_kind text NOT NULL DEFAULT 'UNKNOWN' CHECK (source_kind IN ('PRODUCT', 'SERVICE', 'UNKNOWN')),
    is_active boolean NOT NULL DEFAULT true,
    source_updated_at timestamptz,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_system, external_id)
);
CREATE INDEX ix_products_source_group ON products (source_group_id);
CREATE INDEX ix_products_active_name ON products (is_active, name);

CREATE TABLE analytics_categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code text NOT NULL UNIQUE,
    name text NOT NULL,
    description text,
    category_kind text NOT NULL
        CHECK (category_kind IN ('DEVICE', 'ACCESSORY', 'SERVICE', 'WARRANTY', 'PROTECTION', 'OTHER', 'EXCLUDED')),
    device_family text NOT NULL DEFAULT 'NONE'
        CHECK (device_family IN ('IPHONE', 'SAMSUNG', 'PODS_WATCH', 'IPAD_MAC', 'OTHER', 'NONE')),
    counts_as_phone boolean NOT NULL DEFAULT false,
    counts_as_device boolean NOT NULL DEFAULT false,
    counts_as_additional_revenue boolean NOT NULL DEFAULT false,
    attach_denominator_code text
        CHECK (attach_denominator_code IN ('IPHONE', 'SAMSUNG', 'PHONE', 'PODS_WATCH', 'IPAD_MAC',
                                           'NEW_DEVICE', 'USED_DEVICE', 'MATCH_DEVICE_CONDITION')),
    requires_same_document_for_attach boolean NOT NULL DEFAULT false,
    is_active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (attach_denominator_code IS NOT NULL OR NOT requires_same_document_for_attach)
);

CREATE TABLE product_category_assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES products(id),
    analytics_category_id uuid NOT NULL REFERENCES analytics_categories(id),
    condition_type text NOT NULL DEFAULT 'NOT_APPLICABLE'
        CHECK (condition_type IN ('NEW', 'ASIS', 'USED', 'NOT_APPLICABLE', 'UNKNOWN')),
    assignment_source text NOT NULL CHECK (assignment_source IN ('INITIAL_IMPORT', 'AUTO', 'MANUAL')),
    rule_version text,
    valid_from timestamptz NOT NULL,
    valid_to timestamptz,
    assigned_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    change_reason text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);
ALTER TABLE product_category_assignments ADD CONSTRAINT ex_product_category_assignments_no_overlap
    EXCLUDE USING gist (
        product_id WITH =,
        tstzrange(valid_from, COALESCE(valid_to, 'infinity'::timestamptz), '[)') WITH &&
    );
CREATE INDEX ix_product_category_assignments_current ON product_category_assignments (product_id, valid_from DESC)
    WHERE valid_to IS NULL;
CREATE INDEX ix_product_category_assignments_category ON product_category_assignments (analytics_category_id, valid_from);

CREATE TABLE store_product_inventory (
    store_id uuid NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    product_id uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    quantity numeric(19, 3) NOT NULL DEFAULT 0,
    retail_price numeric(19, 2) CHECK (retail_price IS NULL OR retail_price >= 0),
    cost_amount numeric(19, 2) CHECK (cost_amount IS NULL OR cost_amount >= 0),
    source_updated_at timestamptz,
    last_sync_run_id uuid REFERENCES sync_runs(id),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, product_id)
);
CREATE INDEX ix_store_product_inventory_product ON store_product_inventory (product_id);

CREATE TABLE sales_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    source_system text NOT NULL DEFAULT 'LIVESKLAD' CHECK (source_system IN ('LIVESKLAD', 'MANUAL')),
    external_id text NOT NULL,
    store_id uuid NOT NULL REFERENCES stores(id),
    employee_id uuid REFERENCES employees(id),
    original_document_id uuid REFERENCES sales_documents(id),
    document_number text,
    document_kind text NOT NULL CHECK (document_kind IN ('SALE', 'RETURN')),
    source_document_type text NOT NULL,
    source_status text,
    occurred_at timestamptz NOT NULL,
    business_date date NOT NULL,
    net_amount numeric(19, 2) NOT NULL CHECK (net_amount >= 0),
    cost_amount numeric(19, 2) CHECK (cost_amount IS NULL OR cost_amount >= 0),
    is_deleted boolean NOT NULL DEFAULT false,
    source_updated_at timestamptz,
    raw_record_version_id uuid REFERENCES raw_record_versions(id),
    last_sync_run_id uuid NOT NULL REFERENCES sync_runs(id),
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (source_system, external_id),
    CHECK (document_kind = 'RETURN' OR original_document_id IS NULL)
);
CREATE INDEX ix_sales_documents_store_business_date ON sales_documents (store_id, business_date DESC, document_kind)
    WHERE NOT is_deleted;
CREATE INDEX ix_sales_documents_employee_business_date ON sales_documents (employee_id, business_date DESC)
    WHERE employee_id IS NOT NULL AND NOT is_deleted;
CREATE INDEX ix_sales_documents_original ON sales_documents (original_document_id) WHERE original_document_id IS NOT NULL;

CREATE TABLE sales_document_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_document_id uuid NOT NULL REFERENCES sales_documents(id) ON DELETE CASCADE,
    external_id text NOT NULL,
    original_item_id uuid REFERENCES sales_document_items(id),
    product_id uuid NOT NULL REFERENCES products(id),
    product_name_snapshot text NOT NULL,
    source_group_name_snapshot text,
    analytics_category_id uuid NOT NULL REFERENCES analytics_categories(id),
    category_assignment_id uuid REFERENCES product_category_assignments(id),
    classification_version text,
    condition_type_snapshot text NOT NULL
        CHECK (condition_type_snapshot IN ('NEW', 'ASIS', 'USED', 'NOT_APPLICABLE', 'UNKNOWN')),
    quantity numeric(19, 3) NOT NULL CHECK (quantity > 0),
    unit_price numeric(19, 2) NOT NULL CHECK (unit_price >= 0),
    gross_amount numeric(19, 2) NOT NULL CHECK (gross_amount >= 0),
    discount_amount numeric(19, 2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    net_amount numeric(19, 2) NOT NULL CHECK (net_amount >= 0),
    cost_amount numeric(19, 2) CHECK (cost_amount IS NULL OR cost_amount >= 0),
    cost_quality text NOT NULL CHECK (cost_quality IN ('KNOWN', 'ZERO_SERVICE', 'MISSING', 'ZERO_UNEXPECTED')),
    is_work boolean NOT NULL DEFAULT false,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (sales_document_id, external_id)
);
CREATE INDEX ix_sales_document_items_document ON sales_document_items (sales_document_id);
CREATE INDEX ix_sales_document_items_product ON sales_document_items (product_id);
CREATE INDEX ix_sales_document_items_category ON sales_document_items (analytics_category_id);
CREATE INDEX ix_sales_document_items_original ON sales_document_items (original_item_id) WHERE original_item_id IS NOT NULL;
CREATE INDEX ix_sales_document_items_cost_quality ON sales_document_items (cost_quality)
    WHERE cost_quality IN ('MISSING', 'ZERO_UNEXPECTED');

CREATE TABLE sales_payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    sales_document_id uuid NOT NULL REFERENCES sales_documents(id) ON DELETE CASCADE,
    external_id text NOT NULL,
    payment_method text NOT NULL CHECK (payment_method IN ('CASH', 'CARD', 'BANK_TRANSFER', 'MIXED', 'OTHER')),
    amount numeric(19, 2) NOT NULL CHECK (amount >= 0),
    paid_at timestamptz,
    is_deleted boolean NOT NULL DEFAULT false,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (sales_document_id, external_id)
);
CREATE INDEX ix_sales_payments_document ON sales_payments (sales_document_id);

CREATE TABLE report_snapshots (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    report_type text NOT NULL,
    period_type text NOT NULL CHECK (period_type IN ('DAY', 'WEEK', 'MONTH', 'YEAR', 'CUSTOM')),
    period_start date NOT NULL,
    period_end date NOT NULL,
    status text NOT NULL CHECK (status IN ('DRAFT', 'CALCULATED', 'APPROVED', 'ARCHIVED')),
    formula_version text NOT NULL,
    classification_version text NOT NULL,
    input_hash varchar(64) CHECK (input_hash IS NULL OR length(input_hash) = 64),
    payload jsonb NOT NULL,
    generated_at timestamptz NOT NULL DEFAULT now(),
    generated_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    approved_at timestamptz,
    approved_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (period_end >= period_start),
    CHECK ((status IN ('APPROVED', 'ARCHIVED') AND approved_at IS NOT NULL) OR status IN ('DRAFT', 'CALCULATED'))
);
CREATE INDEX ix_report_snapshots_store_period ON report_snapshots (store_id, period_start DESC, period_end DESC);
CREATE INDEX ix_report_snapshots_type_status ON report_snapshots (report_type, status, generated_at DESC);

CREATE TABLE data_quality_issues (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid REFERENCES stores(id),
    entity_type text NOT NULL,
    entity_id text NOT NULL,
    issue_code text NOT NULL,
    severity text NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'ERROR')),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'RESOLVED', 'IGNORED')),
    message text NOT NULL,
    detected_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    resolved_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb,
    CHECK ((status = 'OPEN' AND resolved_at IS NULL) OR status <> 'OPEN')
);
CREATE UNIQUE INDEX ux_data_quality_issues_open ON data_quality_issues (entity_type, entity_id, issue_code)
    WHERE status = 'OPEN';
CREATE INDEX ix_data_quality_issues_store_status ON data_quality_issues (store_id, status, severity, detected_at DESC);

CREATE TABLE audit_log (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id uuid REFERENCES app_users(id) ON DELETE SET NULL,
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
CREATE INDEX ix_audit_log_action_created_at ON audit_log (action, created_at DESC);

INSERT INTO analytics_categories (
    code, name, category_kind, device_family,
    counts_as_phone, counts_as_device, counts_as_additional_revenue,
    attach_denominator_code, requires_same_document_for_attach
) VALUES
    ('IPHONE_NEW_ASIS', 'iPhone New/ASIS+', 'DEVICE', 'IPHONE', true, true, false, NULL, false),
    ('IPHONE_USED', 'iPhone Б/У', 'DEVICE', 'IPHONE', true, true, false, NULL, false),
    ('SAMSUNG_NEW', 'Samsung новый', 'DEVICE', 'SAMSUNG', true, true, false, NULL, false),
    ('SAMSUNG_USED', 'Samsung Б/У', 'DEVICE', 'SAMSUNG', true, true, false, NULL, false),
    ('PODS_WATCH_OTHER_DEVICE', 'Pods/Watch/другое устройство', 'DEVICE', 'PODS_WATCH', false, true, false, NULL, false),
    ('IPAD_MAC', 'iPad/Mac', 'DEVICE', 'IPAD_MAC', false, true, false, NULL, false),
    ('CASE_APPLE_IPHONE', 'Чехлы Apple/iPhone', 'ACCESSORY', 'IPHONE', false, false, true, 'IPHONE', true),
    ('CHARGER_CABLE', 'Зарядные устройства и кабели', 'ACCESSORY', 'NONE', false, false, true, 'PHONE', true),
    ('GLASS_CAMERA_IPHONE', 'Стекла и защита камеры iPhone', 'ACCESSORY', 'IPHONE', false, false, true, 'IPHONE', true),
    ('FILM_PHONE', 'Пленки для телефонов', 'ACCESSORY', 'NONE', false, false, true, 'PHONE', true),
    ('CASE_SAMSUNG', 'Чехлы Samsung', 'ACCESSORY', 'SAMSUNG', false, false, true, 'SAMSUNG', true),
    ('GLASS_CAMERA_SAMSUNG', 'Стекла Samsung', 'ACCESSORY', 'SAMSUNG', false, false, true, 'SAMSUNG', true),
    ('ACCESSORY_PODS_WATCH', 'Аксессуары Pods/Watch', 'ACCESSORY', 'PODS_WATCH', false, false, true, 'PODS_WATCH', true),
    ('ACCESSORY_IPAD_MAC', 'Аксессуары iPad/Mac', 'ACCESSORY', 'IPAD_MAC', false, false, true, 'IPAD_MAC', true),
    ('OTHER_ACCESSORY_PRODUCT', 'Прочие аксессуары и товары', 'ACCESSORY', 'OTHER', false, false, true, NULL, false),
    ('SETUP_SERVICE', 'Настройки и услуги', 'SERVICE', 'NONE', false, false, true, 'PHONE', true),
    ('WARRANTY_GENERIC', 'Гарантия', 'WARRANTY', 'NONE', false, false, true, 'MATCH_DEVICE_CONDITION', true),
    ('PREMIUM_PROTECTION', 'Премиум и протекция', 'PROTECTION', 'NONE', false, false, true, 'NEW_DEVICE', true),
    ('UNMAPPED', 'Не классифицировано', 'OTHER', 'NONE', false, false, false, NULL, false),
    ('EXCLUDE', 'Исключить из аналитики', 'EXCLUDED', 'NONE', false, false, false, NULL, false);

COMMENT ON TABLE raw_record_versions IS
    'Versioned source payloads for replay and diagnostics; dashboard queries must not use this table.';
COMMENT ON TABLE products IS
    'Company-wide products; store-specific stock is stored in store_product_inventory.';
COMMENT ON TABLE product_category_assignments IS
    'Non-overlapping category history. Category changes apply only from valid_from.';
COMMENT ON COLUMN sales_document_items.analytics_category_id IS
    'Category snapshot captured during normalization and never changed retroactively.';
COMMENT ON TABLE report_snapshots IS
    'Saved historical reports. Ordinary dashboard requests are not persisted here.';
