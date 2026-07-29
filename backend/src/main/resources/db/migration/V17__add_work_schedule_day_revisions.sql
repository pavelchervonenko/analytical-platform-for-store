CREATE TABLE work_schedule_day_revisions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    store_id uuid NOT NULL REFERENCES stores(id) ON DELETE CASCADE,
    work_date date NOT NULL,
    revision bigint NOT NULL DEFAULT 1 CHECK (revision > 0),
    updated_by uuid REFERENCES app_users(id) ON DELETE SET NULL,
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (store_id, work_date)
);

CREATE TRIGGER tr_work_schedule_day_revisions_updated_at
    BEFORE UPDATE ON work_schedule_day_revisions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

COMMENT ON TABLE work_schedule_day_revisions IS
    'Aggregate revision for optimistic concurrency of a complete store work-schedule day.';
