ALTER TABLE analytics_categories
    ADD COLUMN payroll_category_code text NOT NULL DEFAULT 'UNMAPPED'
        CHECK (payroll_category_code IN (
            'TECH_TIER_1', 'TECH_TIER_2', 'ACCESSORY', 'SERVICE',
            'PLAYSTATION_SUBSCRIPTION', 'PAID_REPAIR', 'EXCLUDE', 'UNMAPPED'
        ));

UPDATE analytics_categories
SET payroll_category_code = CASE
    WHEN code IN ('IPHONE_NEW_ASIS', 'IPHONE_USED', 'SAMSUNG_NEW', 'SAMSUNG_USED')
        THEN 'TECH_TIER_1'
    WHEN code = 'PODS_WATCH_OTHER_DEVICE' THEN 'TECH_TIER_2'
    WHEN category_kind = 'ACCESSORY' THEN 'ACCESSORY'
    WHEN category_kind IN ('SERVICE', 'WARRANTY', 'PROTECTION') THEN 'SERVICE'
    WHEN code = 'EXCLUDE' THEN 'EXCLUDE'
    ELSE 'UNMAPPED'
END;

CREATE TABLE product_payroll_category_assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    product_id uuid NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    payroll_category_code text NOT NULL CHECK (payroll_category_code IN (
        'TECH_TIER_1', 'TECH_TIER_2', 'ACCESSORY', 'SERVICE',
        'PLAYSTATION_SUBSCRIPTION', 'PAID_REPAIR', 'EXCLUDE'
    )),
    valid_from date NOT NULL,
    valid_to date,
    assigned_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    change_reason text NOT NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (valid_to IS NULL OR valid_to > valid_from)
);
ALTER TABLE product_payroll_category_assignments
    ADD CONSTRAINT ex_product_payroll_categories_no_overlap
    EXCLUDE USING gist (
        product_id WITH =,
        daterange(valid_from, COALESCE(valid_to, 'infinity'::date), '[)') WITH &&
    );
CREATE INDEX ix_product_payroll_categories_current
    ON product_payroll_category_assignments (product_id, valid_from DESC)
    WHERE valid_to IS NULL;

CREATE TABLE payroll_schemes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code text NOT NULL UNIQUE,
    effective_from date NOT NULL UNIQUE CHECK (effective_from = date_trunc('month', effective_from)::date),
    achieved_percentage numeric(5, 2) NOT NULL
        CHECK (achieved_percentage BETWEEN 0 AND 100),
    missed_percentage numeric(5, 2) NOT NULL
        CHECK (missed_percentage BETWEEN 0 AND 100),
    achieved_tier1_rate numeric(19, 2) NOT NULL CHECK (achieved_tier1_rate >= 0),
    missed_tier1_rate numeric(19, 2) NOT NULL CHECK (missed_tier1_rate >= 0),
    achieved_tier2_rate numeric(19, 2) NOT NULL CHECK (achieved_tier2_rate >= 0),
    missed_tier2_rate numeric(19, 2) NOT NULL CHECK (missed_tier2_rate >= 0),
    advance_amount numeric(19, 2) NOT NULL CHECK (advance_amount >= 0),
    created_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_payroll_schemes_effective_from
    ON payroll_schemes (effective_from DESC);

INSERT INTO payroll_schemes (
    code,
    effective_from,
    achieved_percentage,
    missed_percentage,
    achieved_tier1_rate,
    missed_tier1_rate,
    achieved_tier2_rate,
    missed_tier2_rate,
    advance_amount
) VALUES (
    'seller-payroll-v1',
    DATE '1970-01-01',
    20.00,
    15.00,
    500.00,
    400.00,
    300.00,
    200.00,
    50000.00
);

CREATE TABLE payroll_runs (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id),
    period_month date NOT NULL,
    revision integer NOT NULL CHECK (revision > 0),
    supersedes_run_id uuid,
    revision_reason text,
    scheme_id uuid NOT NULL REFERENCES payroll_schemes(id),
    status text NOT NULL CHECK (status IN ('CALCULATED', 'APPROVED', 'PAID')),
    revenue_plan_target numeric(19, 2) NOT NULL CHECK (revenue_plan_target > 0),
    actual_revenue numeric(19, 2) NOT NULL,
    revenue_plan_achieved boolean NOT NULL,
    accessory_share_target numeric(5, 2) NOT NULL
        CHECK (accessory_share_target BETWEEN 0 AND 100),
    actual_accessory_turnover numeric(19, 2) NOT NULL,
    actual_accessory_share_percent numeric(9, 2),
    accessory_plan_achieved boolean NOT NULL,
    service_share_target numeric(5, 2) NOT NULL
        CHECK (service_share_target BETWEEN 0 AND 100),
    actual_service_turnover numeric(19, 2) NOT NULL,
    actual_service_share_percent numeric(9, 2),
    service_plan_achieved boolean NOT NULL,
    calculation_complete boolean NOT NULL,
    unmapped_item_count integer NOT NULL DEFAULT 0 CHECK (unmapped_item_count >= 0),
    missing_cost_item_count integer NOT NULL DEFAULT 0 CHECK (missing_cost_item_count >= 0),
    days_without_shift integer NOT NULL DEFAULT 0 CHECK (days_without_shift >= 0),
    created_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    approved_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    approved_at timestamptz,
    paid_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    paid_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (period_month = date_trunc('month', period_month)::date),
    CHECK ((status = 'CALCULATED' AND approved_at IS NULL AND paid_at IS NULL)
        OR (status = 'APPROVED' AND approved_at IS NOT NULL AND paid_at IS NULL)
        OR (status = 'PAID' AND approved_at IS NOT NULL AND paid_at IS NOT NULL)),
    CHECK ((supersedes_run_id IS NULL AND revision = 1)
        OR (supersedes_run_id IS NOT NULL AND revision > 1)),
    UNIQUE (store_id, period_month, revision),
    UNIQUE (id, store_id),
    FOREIGN KEY (supersedes_run_id, store_id)
        REFERENCES payroll_runs(id, store_id)
);
CREATE INDEX ix_payroll_runs_store_month
    ON payroll_runs (store_id, period_month DESC, revision DESC);

CREATE TABLE payroll_daily_pools (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id uuid NOT NULL,
    store_id uuid NOT NULL,
    work_date date NOT NULL,
    accessory_turnover numeric(19, 2) NOT NULL,
    service_turnover numeric(19, 2) NOT NULL,
    playstation_gross_profit numeric(19, 2),
    paid_repair_gross_profit numeric(19, 2),
    tier1_quantity numeric(19, 3) NOT NULL,
    tier2_quantity numeric(19, 3) NOT NULL,
    accessory_percentage_rate numeric(5, 2) NOT NULL,
    service_percentage_rate numeric(5, 2) NOT NULL,
    tier1_rate numeric(19, 2) NOT NULL,
    tier2_rate numeric(19, 2) NOT NULL,
    accessory_reward numeric(19, 2) NOT NULL,
    service_reward numeric(19, 2) NOT NULL,
    playstation_reward numeric(19, 2),
    paid_repair_reward numeric(19, 2),
    tier1_reward numeric(19, 2) NOT NULL,
    tier2_reward numeric(19, 2) NOT NULL,
    fund_amount numeric(19, 2),
    shift_employee_count integer NOT NULL CHECK (shift_employee_count >= 0),
    unmapped_item_count integer NOT NULL DEFAULT 0 CHECK (unmapped_item_count >= 0),
    missing_cost_item_count integer NOT NULL DEFAULT 0 CHECK (missing_cost_item_count >= 0),
    calculation_complete boolean NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (payroll_run_id, store_id)
        REFERENCES payroll_runs(id, store_id) ON DELETE CASCADE,
    UNIQUE (payroll_run_id, work_date),
    UNIQUE (payroll_run_id, store_id, work_date)
);

CREATE TABLE payroll_daily_allocations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id uuid NOT NULL,
    store_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    work_date date NOT NULL,
    amount numeric(19, 2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (payroll_run_id, store_id, work_date)
        REFERENCES payroll_daily_pools(payroll_run_id, store_id, work_date) ON DELETE CASCADE,
    FOREIGN KEY (employee_id, store_id)
        REFERENCES employee_store_assignments(employee_id, store_id),
    UNIQUE (payroll_run_id, employee_id, work_date)
);
CREATE INDEX ix_payroll_daily_allocations_employee
    ON payroll_daily_allocations (employee_id, work_date);

CREATE TABLE payroll_adjustments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id uuid NOT NULL,
    store_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    adjustment_type text NOT NULL CHECK (adjustment_type IN ('PENALTY', 'INVENTORY', 'TAX')),
    amount numeric(19, 2) NOT NULL CHECK (amount > 0),
    reason text NOT NULL,
    is_active boolean NOT NULL DEFAULT true,
    created_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    voided_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    void_reason text,
    voided_at timestamptz,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (payroll_run_id, store_id)
        REFERENCES payroll_runs(id, store_id) ON DELETE CASCADE,
    FOREIGN KEY (employee_id, store_id)
        REFERENCES employee_store_assignments(employee_id, store_id),
    CHECK ((is_active AND voided_at IS NULL AND voided_by IS NULL AND void_reason IS NULL)
        OR (NOT is_active AND voided_at IS NOT NULL AND void_reason IS NOT NULL))
);
CREATE INDEX ix_payroll_adjustments_run_employee
    ON payroll_adjustments (payroll_run_id, employee_id)
    WHERE is_active;

CREATE TABLE payroll_statements (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id uuid NOT NULL,
    store_id uuid NOT NULL,
    employee_id uuid NOT NULL,
    shift_count integer NOT NULL CHECK (shift_count >= 0),
    worked_hours numeric(19, 2) NOT NULL CHECK (worked_hours >= 0),
    earned_amount numeric(19, 2) NOT NULL,
    advance_amount numeric(19, 2) NOT NULL CHECK (advance_amount >= 0),
    penalty_amount numeric(19, 2) NOT NULL CHECK (penalty_amount >= 0),
    inventory_amount numeric(19, 2) NOT NULL CHECK (inventory_amount >= 0),
    tax_amount numeric(19, 2) NOT NULL CHECK (tax_amount >= 0),
    payable_amount numeric(19, 2) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (payroll_run_id, store_id)
        REFERENCES payroll_runs(id, store_id) ON DELETE CASCADE,
    FOREIGN KEY (employee_id, store_id)
        REFERENCES employee_store_assignments(employee_id, store_id),
    UNIQUE (payroll_run_id, employee_id)
);

CREATE TABLE payroll_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    payroll_run_id uuid NOT NULL REFERENCES payroll_runs(id) ON DELETE CASCADE,
    event_type text NOT NULL CHECK (event_type IN (
        'CALCULATED', 'RECALCULATED', 'REVISION_CREATED',
        'ADJUSTMENT_ADDED', 'ADJUSTMENT_VOIDED', 'APPROVED', 'PAID'
    )),
    actor_user_id uuid REFERENCES app_users(id) ON DELETE SET NULL,
    details text,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ix_payroll_events_run_created
    ON payroll_events (payroll_run_id, created_at);

CREATE TRIGGER tr_payroll_runs_updated_at
    BEFORE UPDATE ON payroll_runs
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_product_payroll_categories_updated_at
    BEFORE UPDATE ON product_payroll_category_assignments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_payroll_adjustments_updated_at
    BEFORE UPDATE ON payroll_adjustments
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON COLUMN analytics_categories.payroll_category_code IS
    'Safe default payroll category; effective-dated product overrides take precedence.';
COMMENT ON TABLE payroll_runs IS
    'Immutable-after-approval monthly payroll calculation revision for one store.';
COMMENT ON TABLE payroll_daily_pools IS
    'Auditable daily formula inputs and rewards before equal shift allocation.';
