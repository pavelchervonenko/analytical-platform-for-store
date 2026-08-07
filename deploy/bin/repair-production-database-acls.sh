#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly PASSWORD_FILE="${POSTGRES_MIGRATOR_PASSWORD_FILE:-/etc/store-analytics/secrets/postgres-migrator-password}"
readonly RUNTIME_PASSWORD_FILE="${POSTGRES_RUNTIME_PASSWORD_FILE:-/etc/store-analytics/secrets/postgres-runtime-password}"
readonly BACKUP_PASSWORD_FILE="${POSTGRES_BACKUP_PASSWORD_FILE:-/etc/store-analytics/secrets/postgres-backup-password}"
readonly CA_FILE="${POSTGRES_CA_FILE:-/etc/store-analytics/pki/postgresql-ca.crt}"
readonly DB_CERT_HOST="${DB_CERT_HOST:-managed-631415-8744455}"
readonly DB_HOST_ADDRESS="${DB_HOST_ADDRESS:-10.20.0.20}"
readonly DB_PORT="${DB_PORT:-5432}"
readonly DB_NAME="${DB_NAME:-store_analytics}"
readonly DB_MIGRATOR_USER="${DB_MIGRATOR_USER:-store_migrator}"

[[ "$(id -u)" -eq 0 ]] || { printf 'ACL repair must run as root\n' >&2; exit 1; }
[[ -s "${PASSWORD_FILE}" ]] || { printf 'Missing migrator password file\n' >&2; exit 1; }

configure_role_search_path() {
  local role="$1"
  local password_file="$2"

  PGPASSWORD="$(< "${password_file}")" psql \
    "host=${DB_CERT_HOST} hostaddr=${DB_HOST_ADDRESS} port=${DB_PORT} dbname=${DB_NAME} user=${role} sslmode=verify-full sslrootcert=${CA_FILE} application_name=deployment-role-hardening" \
    -X -v ON_ERROR_STOP=1 -c \
    "ALTER ROLE ${role} IN DATABASE ${DB_NAME} SET search_path TO app, pg_catalog" \
    >/dev/null
}

configure_role_search_path store_runtime "${RUNTIME_PASSWORD_FILE}"
configure_role_search_path store_migrator "${PASSWORD_FILE}"
configure_role_search_path store_backup_reader "${BACKUP_PASSWORD_FILE}"
export PGPASSWORD="$(< "${PASSWORD_FILE}")"

psql \
  "host=${DB_CERT_HOST} hostaddr=${DB_HOST_ADDRESS} port=${DB_PORT} dbname=${DB_NAME} user=${DB_MIGRATOR_USER} sslmode=verify-full sslrootcert=${CA_FILE} application_name=deployment-acl-repair" \
  -X -v ON_ERROR_STOP=1 <<'SQL'
BEGIN;

REVOKE CREATE ON SCHEMA app FROM store_runtime, store_backup_reader;
GRANT USAGE ON SCHEMA app TO store_runtime, store_backup_reader;

GRANT SELECT, INSERT, UPDATE, DELETE
  ON ALL TABLES IN SCHEMA app TO store_runtime;
REVOKE TRUNCATE, REFERENCES, TRIGGER
  ON ALL TABLES IN SCHEMA app FROM store_runtime;
GRANT SELECT ON ALL TABLES IN SCHEMA app TO store_backup_reader;
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
  ON ALL TABLES IN SCHEMA app FROM store_backup_reader;

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA app TO store_runtime;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA app TO store_backup_reader;
REVOKE USAGE, UPDATE ON ALL SEQUENCES IN SCHEMA app FROM store_backup_reader;

DO $$
DECLARE
  function_signature text;
BEGIN
  FOR function_signature IN
    SELECT format(
             '%I.%I(%s)',
             namespace.nspname,
             procedure.proname,
             pg_get_function_identity_arguments(procedure.oid)
           )
      FROM pg_proc procedure
      JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
     WHERE namespace.nspname = 'app'
       AND procedure.prokind = 'f'
       AND procedure.proowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
  LOOP
    EXECUTE format(
      'GRANT EXECUTE ON FUNCTION %s TO store_runtime',
      function_signature
    );
    EXECUTE format(
      'REVOKE EXECUTE ON FUNCTION %s FROM PUBLIC, store_backup_reader',
      function_signature
    );
  END LOOP;
END
$$;

ALTER DEFAULT PRIVILEGES FOR ROLE store_migrator IN SCHEMA app
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO store_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE store_migrator IN SCHEMA app
  REVOKE TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM store_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE store_migrator IN SCHEMA app
  GRANT SELECT ON TABLES TO store_backup_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE store_migrator IN SCHEMA app
  REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
  ON TABLES FROM store_backup_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE store_migrator IN SCHEMA app
  GRANT USAGE, SELECT ON SEQUENCES TO store_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE store_migrator IN SCHEMA app
  GRANT SELECT ON SEQUENCES TO store_backup_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE store_migrator IN SCHEMA app
  REVOKE USAGE, UPDATE ON SEQUENCES FROM store_backup_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE store_migrator IN SCHEMA app
  GRANT USAGE ON TYPES TO store_runtime, store_backup_reader;
ALTER DEFAULT PRIVILEGES FOR ROLE store_migrator IN SCHEMA app
  GRANT EXECUTE ON FUNCTIONS TO store_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE store_migrator IN SCHEMA app
  REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, store_backup_reader;

COMMIT;
SQL

unset PGPASSWORD
