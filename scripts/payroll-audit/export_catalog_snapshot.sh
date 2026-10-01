#!/usr/bin/env bash
# One-time operator-run audit. No role/grant/configuration changes, no database writes.
set -Eeuo pipefail
set +x
umask 077
readonly PATH='/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin'
readonly RELEASE_ENV='/etc/store-analytics/release.env'
[[ $# -eq 0 && "$(id -u)" -eq 0 ]] || {
    printf 'Run this script as root without arguments.\n' >&2; exit 64;
}
[[ -f "$RELEASE_ENV" && ! -L "$RELEASE_ENV" ]] || exit 1
# Parse only the eight required literal keys; never source the release environment.
value() {
    awk -v key="$1" 'index($0, key "=") == 1 {
        count++; value = substr($0, length(key) + 2)
    } END {
        if (count == 1 && length(value)) { sub(/\r$/, "", value); print value }
        else { exit 1 }
    }' "$RELEASE_ENV"
}
db_cert_host="$(value DB_CERT_HOST)"
db_host_address="$(value DB_HOST_ADDRESS)"
db_port="$(value DB_PORT)"
db_name="$(value DB_NAME)"
db_schema="$(value DB_APP_SCHEMA)"
db_user="$(value DB_BACKUP_USER)"
password_file="$(value POSTGRES_BACKUP_PASSWORD_FILE)"
ca_file="$(value POSTGRES_CA_FILE)"
[[ "$db_schema" == app && "$db_user" == store_backup_reader && "$db_port" =~ ^[0-9]+$ ]] || {
    printf 'Unexpected database configuration; stopped.\n' >&2; exit 1;
}
for material_path in "$password_file" "$ca_file"; do
    [[ "$material_path" == /* && -f "$material_path" && ! -L "$material_path" &&
       -r "$material_path" && -s "$material_path" ]] || {
        printf 'Required database material is unavailable.\n' >&2; exit 1;
    }
done
# Output directory is new and private. Never overwrite an existing audit.
[[ -d /home/pavel && ! -L /home/pavel ]] || exit 1
audit_owner_uid="$(id -u pavel)"
audit_owner_gid="$(id -g pavel)"
audit_dir="$(mktemp -d /home/pavel/payroll-catalog-snapshot-XXXXXXXX)"
trap 'unset audit_password; printf "Export failed; private partial output is NOT a valid snapshot: %s\n" "$audit_dir" >&2' ERR
audit_password="$(<"$password_file")"
# Empty environment prevents inherited PG* settings / psqlrc from changing the connection.
env -i PATH="$PATH" LC_ALL=C.UTF-8 \
    PGHOST="$db_cert_host" PGHOSTADDR="$db_host_address" PGPORT="$db_port" \
    PGDATABASE="$db_name" PGUSER="$db_user" PGPASSWORD="$audit_password" \
    PGSSLMODE=verify-full PGSSLROOTCERT="$ca_file" PGCONNECT_TIMEOUT=10 \
    PGAPPNAME=payroll-catalog-readonly-snapshot \
    PGOPTIONS='-c default_transaction_read_only=on' \
    psql -X -q -A -t -v ON_ERROR_STOP=1 -P pager=off \
    >"$audit_dir/snapshot.jsonl.partial" 2>"$audit_dir/export.stderr" <<'PAYROLL_SNAPSHOT_SQL'
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '60s';
SET LOCAL lock_timeout = '3s';
SET LOCAL idle_in_transaction_session_timeout = '60s';
SET LOCAL search_path = pg_catalog, app;
SET LOCAL timezone = 'UTC';
-- Deliberately fail before exporting if the operator used a different database role.
SELECT jsonb_build_object(
    'kind', 'snapshot_start', 'contract', 'payroll-catalog-snapshot-v1',
    'role_guard', 1 / CASE WHEN current_user = 'store_backup_reader'
        AND current_setting('transaction_read_only') = 'on'
        AND current_setting('transaction_isolation') = 'repeatable read' THEN 1 ELSE 0 END,
    'snapshot_at', transaction_timestamp(), 'database_role', current_user,
    'read_only', current_setting('transaction_read_only'),
    'isolation', current_setting('transaction_isolation'),
    'scope', 'all normalized rows including deleted rows; no provider completeness assertion'
);

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'integration_connections', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'connection_key', 'source_system', 'is_active')))
FROM app.integration_connections t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'integration_connections',
    'row_count', (SELECT count(*) FROM app.integration_connections),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.integration_connections'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'connection_key', 'source_system', 'is_active')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'stores', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'connection_id', 'external_id', 'name', 'timezone', 'business_day_start', 'is_active')))
FROM app.stores t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'stores',
    'row_count', (SELECT count(*) FROM app.stores),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.stores'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'connection_id', 'external_id', 'name', 'timezone', 'business_day_start', 'is_active')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'employees', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'connection_id', 'external_id', 'is_active')))
FROM app.employees t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'employees',
    'row_count', (SELECT count(*) FROM app.employees),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.employees'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'connection_id', 'external_id', 'is_active')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'source_product_groups', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'connection_id', 'external_id', 'parent_id', 'name', 'path', 'is_active')))
FROM app.source_product_groups t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'source_product_groups',
    'row_count', (SELECT count(*) FROM app.source_product_groups),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.source_product_groups'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'connection_id', 'external_id', 'parent_id', 'name', 'path', 'is_active')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'products', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'connection_id', 'external_id', 'source_group_id', 'code', 'sku', 'name', 'source_kind', 'is_active', 'source_updated_at', 'version', 'created_at', 'updated_at')))
FROM app.products t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'products',
    'row_count', (SELECT count(*) FROM app.products),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.products'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'connection_id', 'external_id', 'source_group_id', 'code', 'sku', 'name', 'source_kind', 'is_active', 'source_updated_at', 'version', 'created_at', 'updated_at')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'analytics_categories', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'code', 'name', 'category_kind', 'device_family', 'counts_as_phone', 'counts_as_device', 'counts_as_additional_revenue', 'attach_denominator_code', 'requires_same_document_for_attach', 'payroll_category_code', 'is_active', 'version')))
FROM app.analytics_categories t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'analytics_categories',
    'row_count', (SELECT count(*) FROM app.analytics_categories),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.analytics_categories'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'code', 'name', 'category_kind', 'device_family', 'counts_as_phone', 'counts_as_device', 'counts_as_additional_revenue', 'attach_denominator_code', 'requires_same_document_for_attach', 'payroll_category_code', 'is_active', 'version')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'product_category_assignments', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'product_id', 'analytics_category_id', 'condition_type', 'assignment_source', 'rule_version', 'valid_from', 'valid_to', 'created_at')))
FROM app.product_category_assignments t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'product_category_assignments',
    'row_count', (SELECT count(*) FROM app.product_category_assignments),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.product_category_assignments'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'product_id', 'analytics_category_id', 'condition_type', 'assignment_source', 'rule_version', 'valid_from', 'valid_to', 'created_at')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'product_payroll_category_assignments', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'product_id', 'payroll_category_code', 'valid_from', 'valid_to', 'version', 'created_at', 'updated_at')))
FROM app.product_payroll_category_assignments t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'product_payroll_category_assignments',
    'row_count', (SELECT count(*) FROM app.product_payroll_category_assignments),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.product_payroll_category_assignments'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'product_id', 'payroll_category_code', 'valid_from', 'valid_to', 'version', 'created_at', 'updated_at')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'sales_documents', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'connection_id', 'external_id', 'store_id', 'employee_id', 'original_document_id', 'document_kind', 'source_document_type', 'source_status', 'occurred_at', 'business_date', 'net_amount', 'cost_amount', 'is_deleted', 'source_updated_at', 'last_sync_run_id', 'version', 'updated_at', 'attach_source_employee_external_id')))
FROM app.sales_documents t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'sales_documents',
    'row_count', (SELECT count(*) FROM app.sales_documents),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.sales_documents'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'connection_id', 'external_id', 'store_id', 'employee_id', 'original_document_id', 'document_kind', 'source_document_type', 'source_status', 'occurred_at', 'business_date', 'net_amount', 'cost_amount', 'is_deleted', 'source_updated_at', 'last_sync_run_id', 'version', 'updated_at', 'attach_source_employee_external_id')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'sales_document_items', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'sales_document_id', 'external_id', 'original_item_id', 'product_id', 'product_name_snapshot', 'source_group_name_snapshot', 'analytics_category_id', 'category_assignment_id', 'classification_version', 'condition_type_snapshot', 'quantity', 'unit_price', 'gross_amount', 'discount_amount', 'net_amount', 'cost_amount', 'cost_quality', 'is_work', 'is_deleted', 'version', 'updated_at')))
FROM app.sales_document_items t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'sales_document_items',
    'row_count', (SELECT count(*) FROM app.sales_document_items),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.sales_document_items'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'sales_document_id', 'external_id', 'original_item_id', 'product_id', 'product_name_snapshot', 'source_group_name_snapshot', 'analytics_category_id', 'category_assignment_id', 'classification_version', 'condition_type_snapshot', 'quantity', 'unit_price', 'gross_amount', 'discount_amount', 'net_amount', 'cost_amount', 'cost_quality', 'is_work', 'is_deleted', 'version', 'updated_at')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'store_performance_plans', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'store_id', 'plan_month', 'revenue_target', 'accessory_share_target', 'service_share_target', 'additional_share_target', 'version')))
FROM app.store_performance_plans t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'store_performance_plans',
    'row_count', (SELECT count(*) FROM app.store_performance_plans),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.store_performance_plans'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'store_id', 'plan_month', 'revenue_target', 'accessory_share_target', 'service_share_target', 'additional_share_target', 'version')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'employee_work_shifts', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'store_id', 'employee_id', 'work_date', 'worked_hours', 'is_active', 'version')))
FROM app.employee_work_shifts t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'employee_work_shifts',
    'row_count', (SELECT count(*) FROM app.employee_work_shifts),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.employee_work_shifts'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'store_id', 'employee_id', 'work_date', 'worked_hours', 'is_active', 'version')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'payroll_schemes', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'code', 'effective_from', 'achieved_percentage', 'missed_percentage', 'achieved_tier1_rate', 'missed_tier1_rate', 'achieved_tier2_rate', 'missed_tier2_rate', 'advance_amount', 'created_at')))
FROM app.payroll_schemes t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'payroll_schemes',
    'row_count', (SELECT count(*) FROM app.payroll_schemes),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.payroll_schemes'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'code', 'effective_from', 'achieved_percentage', 'missed_percentage', 'achieved_tier1_rate', 'missed_tier1_rate', 'achieved_tier2_rate', 'missed_tier2_rate', 'advance_amount', 'created_at')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'payroll_runs', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'store_id', 'period_month', 'revision', 'supersedes_run_id', 'scheme_id', 'status', 'revenue_plan_target', 'actual_revenue', 'revenue_plan_achieved', 'accessory_share_target', 'actual_accessory_turnover', 'actual_accessory_share_percent', 'accessory_plan_achieved', 'service_share_target', 'actual_service_turnover', 'actual_service_share_percent', 'service_plan_achieved', 'calculation_complete', 'unmapped_item_count', 'missing_cost_item_count', 'days_without_shift', 'approved_at', 'paid_at', 'version', 'created_at', 'updated_at')))
FROM app.payroll_runs t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'payroll_runs',
    'row_count', (SELECT count(*) FROM app.payroll_runs),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.payroll_runs'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'store_id', 'period_month', 'revision', 'supersedes_run_id', 'scheme_id', 'status', 'revenue_plan_target', 'actual_revenue', 'revenue_plan_achieved', 'accessory_share_target', 'actual_accessory_turnover', 'actual_accessory_share_percent', 'accessory_plan_achieved', 'service_share_target', 'actual_service_turnover', 'actual_service_share_percent', 'service_plan_achieved', 'calculation_complete', 'unmapped_item_count', 'missing_cost_item_count', 'days_without_shift', 'approved_at', 'paid_at', 'version', 'created_at', 'updated_at')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'payroll_daily_pools', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'payroll_run_id', 'store_id', 'work_date', 'accessory_turnover', 'service_turnover', 'playstation_gross_profit', 'paid_repair_gross_profit', 'tier1_quantity', 'tier2_quantity', 'accessory_percentage_rate', 'service_percentage_rate', 'tier1_rate', 'tier2_rate', 'accessory_reward', 'service_reward', 'playstation_reward', 'paid_repair_reward', 'tier1_reward', 'tier2_reward', 'fund_amount', 'shift_employee_count', 'unmapped_item_count', 'missing_cost_item_count', 'calculation_complete')))
FROM app.payroll_daily_pools t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'payroll_daily_pools',
    'row_count', (SELECT count(*) FROM app.payroll_daily_pools),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.payroll_daily_pools'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'payroll_run_id', 'store_id', 'work_date', 'accessory_turnover', 'service_turnover', 'playstation_gross_profit', 'paid_repair_gross_profit', 'tier1_quantity', 'tier2_quantity', 'accessory_percentage_rate', 'service_percentage_rate', 'tier1_rate', 'tier2_rate', 'accessory_reward', 'service_reward', 'playstation_reward', 'paid_repair_reward', 'tier1_reward', 'tier2_reward', 'fund_amount', 'shift_employee_count', 'unmapped_item_count', 'missing_cost_item_count', 'calculation_complete')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'payroll_daily_allocations', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'payroll_run_id', 'store_id', 'employee_id', 'work_date', 'amount')))
FROM app.payroll_daily_allocations t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'payroll_daily_allocations',
    'row_count', (SELECT count(*) FROM app.payroll_daily_allocations),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.payroll_daily_allocations'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'payroll_run_id', 'store_id', 'employee_id', 'work_date', 'amount')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'payroll_adjustments', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'payroll_run_id', 'store_id', 'employee_id', 'adjustment_type', 'amount', 'is_active', 'version')))
FROM app.payroll_adjustments t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'payroll_adjustments',
    'row_count', (SELECT count(*) FROM app.payroll_adjustments),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.payroll_adjustments'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'payroll_run_id', 'store_id', 'employee_id', 'adjustment_type', 'amount', 'is_active', 'version')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'payroll_statements', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'payroll_run_id', 'store_id', 'employee_id', 'shift_count', 'worked_hours', 'earned_amount', 'advance_amount', 'penalty_amount', 'inventory_amount', 'tax_amount', 'payable_amount')))
FROM app.payroll_statements t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'payroll_statements',
    'row_count', (SELECT count(*) FROM app.payroll_statements),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.payroll_statements'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'payroll_run_id', 'store_id', 'employee_id', 'shift_count', 'worked_hours', 'earned_amount', 'advance_amount', 'penalty_amount', 'inventory_amount', 'tax_amount', 'payable_amount')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'report_snapshots', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'store_id', 'report_type', 'period_type', 'period_start', 'period_end', 'status', 'formula_version', 'classification_version', 'input_hash', 'generated_at', 'approved_at')))
FROM app.report_snapshots t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'report_snapshots',
    'row_count', (SELECT count(*) FROM app.report_snapshots),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.report_snapshots'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'store_id', 'report_type', 'period_type', 'period_start', 'period_end', 'status', 'formula_version', 'classification_version', 'input_hash', 'generated_at', 'approved_at')));

-- Whitelist only: adding a source column never exports it automatically.
SELECT jsonb_build_object('kind', 'sync_runs', 'data',
    (SELECT jsonb_object_agg(k, v) FROM jsonb_each(to_jsonb(t)) AS f(k, v)
     WHERE k IN ('id', 'connection_id', 'store_id', 'source_system', 'trigger_type', 'sync_scope', 'status', 'period_start', 'period_end', 'started_at', 'finished_at', 'records_fetched', 'records_created', 'records_updated', 'records_skipped', 'records_failed')))
FROM app.sync_runs t ORDER BY t.id;
SELECT jsonb_build_object('kind', 'table_manifest', 'table', 'sync_runs',
    'row_count', (SELECT count(*) FROM app.sync_runs),
    'exported_columns', (SELECT jsonb_agg(attname ORDER BY attname)
        FROM pg_attribute WHERE attrelid = 'app.sync_runs'::regclass
        AND attnum > 0 AND NOT attisdropped AND attname IN ('id', 'connection_id', 'store_id', 'source_system', 'trigger_type', 'sync_scope', 'status', 'period_start', 'period_end', 'started_at', 'finished_at', 'records_fetched', 'records_created', 'records_updated', 'records_skipped', 'records_failed')));

-- Read the actual fallback definition, but never execute it during extraction.
SELECT jsonb_build_object('kind', 'payroll_resolver',
    'identity', p.oid::regprocedure::text, 'definition', pg_get_functiondef(p.oid))
FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname = 'app' AND p.proname = 'resolve_default_payroll_category'
  AND p.prokind = 'f';
SELECT jsonb_build_object('kind', 'snapshot_end',
    'snapshot_at', transaction_timestamp(), 'read_only', current_setting('transaction_read_only'),
    'first_business_date', (SELECT min(business_date) FROM app.sales_documents),
    'last_business_date', (SELECT max(business_date) FROM app.sales_documents));
ROLLBACK;
PAYROLL_SNAPSHOT_SQL
unset audit_password
# With ON_ERROR_STOP and a successful ROLLBACK the entire snapshot has completed.
mv -- "$audit_dir/snapshot.jsonl.partial" "$audit_dir/snapshot.jsonl"
(
    cd -- "$audit_dir"
    sha256sum snapshot.jsonl > snapshot.sha256
)
chmod 600 "$audit_dir/snapshot.jsonl" "$audit_dir/snapshot.sha256" "$audit_dir/export.stderr"
chown "$audit_owner_uid:$audit_owner_gid" "$audit_dir/snapshot.jsonl" \
    "$audit_dir/snapshot.sha256" "$audit_dir/export.stderr"
# Hand over the directory only after all root writes have finished.
chown "$audit_owner_uid:$audit_owner_gid" "$audit_dir"
trap - ERR
printf 'Готово: %s\n' "$audit_dir"
wc -l < "$audit_dir/snapshot.jsonl"
