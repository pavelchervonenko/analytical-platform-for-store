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
audit_dir="$(mktemp -d /home/pavel/catalog-migration-state-XXXXXXXX)"
trap 'unset audit_password; printf "Export failed; private partial output is NOT a valid snapshot: %s\n" "$audit_dir" >&2' ERR
audit_password="$(<"$password_file")"
# Empty environment prevents inherited PG* settings / psqlrc from changing the connection.
env -i PATH="$PATH" LC_ALL=C.UTF-8 \
    PGHOST="$db_cert_host" PGHOSTADDR="$db_host_address" PGPORT="$db_port" \
    PGDATABASE="$db_name" PGUSER="$db_user" PGPASSWORD="$audit_password" \
    PGSSLMODE=verify-full PGSSLROOTCERT="$ca_file" PGCONNECT_TIMEOUT=10 \
    PGAPPNAME=catalog-migration-readonly-state \
    PGOPTIONS='-c default_transaction_read_only=on' \
    psql -X -q -A -t -v ON_ERROR_STOP=1 -P pager=off \
    >"$audit_dir/snapshot.jsonl.partial" 2>"$audit_dir/export.stderr" <<'CATALOG_MIGRATION_STATE_SQL'
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '3s';
SET LOCAL idle_in_transaction_session_timeout = '60s';
SET LOCAL search_path = pg_catalog, app;
SET LOCAL timezone = 'UTC';
SELECT jsonb_build_object(
    'kind', 'snapshot_start', 'contract', 'catalog-migration-state-v1',
    'role_guard', 1 / CASE WHEN current_user = 'store_backup_reader'
        AND current_setting('transaction_read_only') = 'on'
        AND current_setting('transaction_isolation') = 'repeatable read' THEN 1 ELSE 0 END,
    'snapshot_at', transaction_timestamp(),
    'read_only', current_setting('transaction_read_only'),
    'scope', 'migration history and protected-table presence only; not a backup or rollout permission'
);
-- Whitelist excludes installed_by and any business/provider data.
-- Exact schema only: missing table or insufficient grants fails the entire export.
SELECT jsonb_build_object('kind', 'migration',
    'installed_rank', installed_rank, 'version', version, 'type', type,
    'script', script, 'checksum', checksum, 'installed_on', installed_on,
    'success', success)
FROM app.flyway_schema_history ORDER BY installed_rank;
SELECT jsonb_build_object('kind', 'protected_data',
    'sales_document_items', EXISTS (SELECT 1 FROM app.sales_document_items),
    'product_category_assignments', EXISTS (SELECT 1 FROM app.product_category_assignments),
    'product_payroll_category_assignments', EXISTS (SELECT 1 FROM app.product_payroll_category_assignments));
SELECT jsonb_build_object('kind', 'snapshot_end',
    'snapshot_at', transaction_timestamp(),
    'history_rows', (SELECT count(*) FROM app.flyway_schema_history),
    'read_only', current_setting('transaction_read_only'));
ROLLBACK;
CATALOG_MIGRATION_STATE_SQL
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
