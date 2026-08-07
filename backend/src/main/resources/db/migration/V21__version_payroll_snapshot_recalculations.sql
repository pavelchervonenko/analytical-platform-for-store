ALTER TABLE payroll_runs
    ADD COLUMN calculation_generation bigint NOT NULL DEFAULT 1
        CHECK (calculation_generation > 0);

COMMENT ON COLUMN payroll_runs.calculation_generation IS
    'Monotonic snapshot generation; forces the optimistic version to advance on every recalculation.';
