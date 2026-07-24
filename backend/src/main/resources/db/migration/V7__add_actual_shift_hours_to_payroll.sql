ALTER TABLE employee_work_shifts
    DROP CONSTRAINT employee_work_shifts_worked_hours_check;

ALTER TABLE employee_work_shifts
    ADD CONSTRAINT employee_work_shifts_worked_hours_check
    CHECK (worked_hours > 0 AND worked_hours <= 11.00);

ALTER TABLE payroll_daily_allocations
    ADD COLUMN worked_hours numeric(5, 2) NOT NULL DEFAULT 11.00
        CHECK (worked_hours > 0 AND worked_hours <= 11.00);

ALTER TABLE payroll_daily_allocations
    ALTER COLUMN worked_hours DROP DEFAULT;

COMMENT ON COLUMN employee_work_shifts.worked_hours IS
    'Actual hours worked for the day; a full 10:00-21:00 shift is 11.00 hours.';

COMMENT ON COLUMN payroll_daily_allocations.worked_hours IS
    'Immutable actual-hours snapshot used by this payroll revision; allocation remains equal per shift participant.';
