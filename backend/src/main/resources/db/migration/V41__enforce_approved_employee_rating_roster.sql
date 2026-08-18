-- Keep historical sales and employee records intact while limiting operational
-- employee/rating views to the customer-approved roster for the two pilot stores.
WITH approved_roster(store_name, employee_name) AS (
    VALUES
        ('мобисфера', 'айрих артур'),
        ('мобисфера', 'артур'),
        ('мобисфера', 'платонов андрей'),
        ('мобисфера', 'андрей'),
        ('мобисфера', 'жунусов ильнур'),
        ('мобисфера', 'ильнур'),
        ('магазин', 'вольфберг андрей'),
        ('магазин', 'краснов владимир'),
        ('магазин', 'алексей килиженко'),
        ('магазин', 'алексей кележенко'),
        ('магазин', 'ковзель ангелина'),
        ('магазин', 'козель ангелина'),
        ('магазин', 'думнов алексей'),
        ('магазин', 'малкина лера')
), desired_participation AS (
    SELECT
        assignment.employee_id,
        assignment.store_id,
        EXISTS (
            SELECT 1
            FROM approved_roster roster
            WHERE roster.store_name = lower(btrim(store.name))
              AND roster.employee_name = lower(btrim(employee.full_name))
        ) AS participates_in_ranking
    FROM employee_store_assignments assignment
    JOIN employees employee ON employee.id = assignment.employee_id
    JOIN stores store ON store.id = assignment.store_id
    WHERE lower(btrim(store.name)) IN ('магазин', 'мобисфера')
)
UPDATE employee_store_assignments assignment
SET participates_in_ranking = desired.participates_in_ranking,
    version = assignment.version + 1,
    updated_at = now()
FROM desired_participation desired
WHERE assignment.employee_id = desired.employee_id
  AND assignment.store_id = desired.store_id
  AND assignment.participates_in_ranking IS DISTINCT FROM desired.participates_in_ranking;