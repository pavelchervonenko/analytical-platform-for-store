CREATE TABLE store_performance_plans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    plan_month date NOT NULL,
    revenue_target numeric(19, 2) NOT NULL CHECK (revenue_target > 0),
    accessory_share_target numeric(5, 2) NOT NULL
        CHECK (accessory_share_target BETWEEN 0 AND 100),
    service_share_target numeric(5, 2) NOT NULL
        CHECK (service_share_target BETWEEN 0 AND 100),
    additional_share_target numeric(5, 2) NOT NULL
        CHECK (additional_share_target BETWEEN 0 AND 100),
    updated_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (plan_month = date_trunc('month', plan_month)::date),
    UNIQUE (store_id, plan_month)
);
CREATE INDEX ix_store_performance_plans_store_month
    ON store_performance_plans (store_id, plan_month DESC);

CREATE TABLE employee_work_shifts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    employee_id uuid NOT NULL REFERENCES employees(id) ON DELETE CASCADE,
    work_date date NOT NULL,
    worked_hours numeric(5, 2) NOT NULL DEFAULT 11.00
        CHECK (worked_hours > 0 AND worked_hours <= 24),
    is_active boolean NOT NULL DEFAULT true,
    updated_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (employee_id, store_id)
        REFERENCES employee_store_assignments(employee_id, store_id),
    UNIQUE (employee_id, work_date)
);
CREATE INDEX ix_employee_work_shifts_store_date
    ON employee_work_shifts (store_id, work_date, employee_id)
    WHERE is_active;

CREATE TABLE rating_schemes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code text NOT NULL UNIQUE,
    effective_from date NOT NULL UNIQUE,
    contribution_weight numeric(5, 2) NOT NULL
        CHECK (contribution_weight BETWEEN 0 AND 100),
    efficiency_weight numeric(5, 2) NOT NULL
        CHECK (efficiency_weight BETWEEN 0 AND 100),
    structure_weight numeric(5, 2) NOT NULL
        CHECK (structure_weight BETWEEN 0 AND 100),
    attach_weight numeric(5, 2) NOT NULL
        CHECK (attach_weight BETWEEN 0 AND 100),
    accessory_structure_weight numeric(5, 2) NOT NULL
        CHECK (accessory_structure_weight BETWEEN 0 AND 100),
    service_structure_weight numeric(5, 2) NOT NULL
        CHECK (service_structure_weight BETWEEN 0 AND 100),
    minimum_attach_denominator numeric(19, 3) NOT NULL
        CHECK (minimum_attach_denominator > 0),
    score_cap numeric(6, 2) NOT NULL CHECK (score_cap >= 100),
    minimum_coverage_percent numeric(5, 2) NOT NULL
        CHECK (minimum_coverage_percent BETWEEN 0 AND 100),
    created_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (
        contribution_weight + efficiency_weight + structure_weight + attach_weight = 100.00
    ),
    CHECK (accessory_structure_weight + service_structure_weight = 100.00)
);
CREATE INDEX ix_rating_schemes_effective_from
    ON rating_schemes (effective_from DESC);

INSERT INTO rating_schemes (
    code,
    effective_from,
    contribution_weight,
    efficiency_weight,
    structure_weight,
    attach_weight,
    accessory_structure_weight,
    service_structure_weight,
    minimum_attach_denominator,
    score_cap,
    minimum_coverage_percent
) VALUES (
    'employee-rating-v1',
    DATE '1970-01-01',
    25.00,
    25.00,
    25.00,
    25.00,
    50.00,
    50.00,
    3.000,
    150.00,
    75.00
);

CREATE TRIGGER tr_store_performance_plans_updated_at
    BEFORE UPDATE ON store_performance_plans
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
CREATE TRIGGER tr_employee_work_shifts_updated_at
    BEFORE UPDATE ON employee_work_shifts
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE store_performance_plans IS
    'Manual monthly targets belong to a store; employees do not have personal plans.';
COMMENT ON TABLE employee_work_shifts IS
    'Actual full-shift roster used for per-hour performance and later payroll allocation.';
COMMENT ON TABLE rating_schemes IS
    'Immutable effective-dated rating formula versions; latest effective version wins.';
