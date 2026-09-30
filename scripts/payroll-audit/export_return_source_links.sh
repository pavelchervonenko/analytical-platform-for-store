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
audit_dir="$(mktemp -d /home/pavel/payroll-return-links-snapshot-XXXXXXXX)"
trap 'unset audit_password; printf "Export failed; private partial output is NOT a valid snapshot: %s\n" "$audit_dir" >&2' ERR
audit_password="$(<"$password_file")"
# Empty environment prevents inherited PG* settings / psqlrc from changing the connection.
env -i PATH="$PATH" LC_ALL=C.UTF-8 \
    PGHOST="$db_cert_host" PGHOSTADDR="$db_host_address" PGPORT="$db_port" \
    PGDATABASE="$db_name" PGUSER="$db_user" PGPASSWORD="$audit_password" \
    PGSSLMODE=verify-full PGSSLROOTCERT="$ca_file" PGCONNECT_TIMEOUT=10 \
    PGAPPNAME=payroll-return-links-readonly-snapshot \
    PGOPTIONS='-c default_transaction_read_only=on' \
    psql -X -q -A -t -v ON_ERROR_STOP=1 -P pager=off \
    >"$audit_dir/snapshot.jsonl.partial" 2>"$audit_dir/export.stderr" <<'PAYROLL_RETURN_LINKS_SQL'
BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
SET LOCAL statement_timeout = '30s';
SET LOCAL lock_timeout = '3s';
SET LOCAL idle_in_transaction_session_timeout = '60s';
SET LOCAL search_path = pg_catalog, app;
SET LOCAL timezone = 'UTC';
SELECT jsonb_build_object('kind', 'snapshot_start', 'contract', 'payroll-return-source-links-v1',
    'snapshot_at', transaction_timestamp(), 'read_only', current_setting('transaction_read_only'),
    'role_guard', 1 / CASE WHEN current_user = 'store_backup_reader'
        AND current_setting('transaction_read_only') = 'on' THEN 1 ELSE 0 END);
WITH targets(external_id) AS (VALUES
    ('6957a26f214c1120750ba83e'), ('6960fd73096f1ac88ace62a3'),
    ('69711ea4f0a757c4315c8d5f'), ('6979db31fe1a25aeda5b8ba8'),
    ('699195c04bec1103021d24ca'), ('6abcdc7a2f1d9632bd64d00c')
)
SELECT jsonb_build_object(
    'kind', 'return_source_link', 'target_external_id', target.external_id,
    'return_id', returned.id, 'return_kind', returned.document_kind,
    'connection_id', returned.connection_id, 'store_id', returned.store_id,
    'business_date', returned.business_date, 'return_deleted', returned.is_deleted,
    'original_document_id', returned.original_document_id,
    'employee_id', returned.employee_id,
    'raw_id', raw.id, 'raw_first_seen_at', raw.first_seen_at,
    'raw_last_seen_at', raw.last_seen_at, 'payload_policy_version', raw.payload_policy_version,
    'source_parent_id', raw.payload #>> '{detail,parentDocument,id}',
    'source_return_date', raw.payload #>> '{detail,date}',
    'source_positions_are_array', jsonb_typeof(raw.payload #> '{detail,positions}') = 'array',
    'parent_id_in_db', parent.id, 'parent_store_id', parent.store_id,
    'parent_kind', parent.document_kind, 'parent_deleted', parent.is_deleted,
    'parent_business_date', parent.business_date, 'parent_employee_id', parent.employee_id,
    'items', COALESCE((
        SELECT jsonb_agg(jsonb_build_object(
            'item_id', item.id, 'external_id', item.external_id, 'item_deleted', item.is_deleted,
            'original_item_id', item.original_item_id, 'product_id', item.product_id,
            'quantity', item.quantity, 'net_amount', item.net_amount, 'cost_amount', item.cost_amount,
            'source_position_match_count', (
                SELECT count(*) FROM jsonb_array_elements(
                    CASE WHEN jsonb_typeof(raw.payload #> '{detail,positions}') = 'array'
                         THEN raw.payload #> '{detail,positions}' ELSE '[]'::jsonb END
                ) pos WHERE pos ->> 'positionId' = item.external_id),
            'source_positions', COALESCE((
                SELECT jsonb_agg(jsonb_build_object(
                    'original_position_external_id', pos ->> 'salePositionId',
                    'product_external_id', pos ->> 'nomenclatureId',
                    'original_item_in_db', original.id,
                    'original_item_deleted', original.is_deleted,
                    'original_product_id', original.product_id,
                    'original_quantity', original.quantity,
                    'original_net_amount', original.net_amount,
                    'original_cost_amount', original.cost_amount
                ))
                FROM jsonb_array_elements(
                    CASE WHEN jsonb_typeof(raw.payload #> '{detail,positions}') = 'array'
                         THEN raw.payload #> '{detail,positions}' ELSE '[]'::jsonb END
                ) pos
                LEFT JOIN app.sales_document_items original
                  ON original.sales_document_id = parent.id
                 AND original.external_id = pos ->> 'salePositionId'
                WHERE pos ->> 'positionId' = item.external_id
            ), '[]'::jsonb)
        ) ORDER BY item.id)
        FROM app.sales_document_items item WHERE item.sales_document_id = returned.id
    ), '[]'::jsonb)
)
FROM targets target
LEFT JOIN app.sales_documents returned
  ON returned.external_id = target.external_id
 AND returned.connection_id = 'dc622e79-8a8e-4813-9266-8a567483a0eb'::uuid
LEFT JOIN LATERAL (
    SELECT version.id, version.payload, version.first_seen_at, version.last_seen_at,
           version.payload_policy_version
    FROM app.raw_record_versions version
    WHERE version.connection_id = returned.connection_id
      AND version.store_id = returned.store_id
      AND version.entity_type = 'RETURN_DOCUMENT'
      AND version.external_id = target.external_id
    ORDER BY version.first_seen_at DESC, version.id DESC LIMIT 1
) raw ON true
LEFT JOIN app.sales_documents parent
  ON parent.connection_id = returned.connection_id
 AND parent.external_id = raw.payload #>> '{detail,parentDocument,id}'
ORDER BY target.external_id;
SELECT jsonb_build_object('kind', 'snapshot_end', 'snapshot_at', transaction_timestamp(),
    'read_only', current_setting('transaction_read_only'));
ROLLBACK;
PAYROLL_RETURN_LINKS_SQL
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
