ALTER TABLE employee_work_shifts
    DROP CONSTRAINT employee_work_shifts_employee_id_work_date_key;

ALTER TABLE employee_work_shifts
    ADD CONSTRAINT uk_employee_work_shifts_store_employee_date
        UNIQUE (store_id, employee_id, work_date);
