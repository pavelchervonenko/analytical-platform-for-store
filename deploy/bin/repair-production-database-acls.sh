#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/store-analytics/deploy}"
readonly RELEASE_ENV="${1:-}"

# shellcheck source=release-safety.sh
source "${DEPLOY_ROOT}/bin/release-safety.sh"

die() {
  printf 'ACL REPAIR FAILED: %s\n' "$*" >&2
  exit 1
}

[[ "$(id -u)" -eq 0 ]] || die 'run as root'
[[ -n "${RELEASE_ENV}" ]] || die 'pass the exact reviewed release env path'
command -v psql >/dev/null 2>&1 || die 'psql is required'
release_validate_env_file "${RELEASE_ENV}" || exit 1

readonly MIGRATOR_PASSWORD_FILE="$(release_env_value "${RELEASE_ENV}" POSTGRES_MIGRATOR_PASSWORD_FILE)"
readonly RUNTIME_PASSWORD_FILE="$(release_env_value "${RELEASE_ENV}" POSTGRES_RUNTIME_PASSWORD_FILE)"
readonly BACKUP_PASSWORD_FILE="$(release_env_value "${RELEASE_ENV}" POSTGRES_BACKUP_PASSWORD_FILE)"
readonly CA_FILE="$(release_env_value "${RELEASE_ENV}" POSTGRES_CA_FILE)"
readonly DB_CERT_HOST="$(release_env_value "${RELEASE_ENV}" DB_CERT_HOST)"
readonly DB_HOST_ADDRESS="$(release_env_value "${RELEASE_ENV}" DB_HOST_ADDRESS)"
readonly DB_PORT="$(release_env_value "${RELEASE_ENV}" DB_PORT)"
readonly DB_NAME="$(release_env_value "${RELEASE_ENV}" DB_NAME)"
readonly DB_SCHEMA="$(release_env_value "${RELEASE_ENV}" DB_APP_SCHEMA)"
readonly DB_RUNTIME_USER="$(release_env_value "${RELEASE_ENV}" DB_RUNTIME_USER)"
readonly DB_MIGRATOR_USER="$(release_env_value "${RELEASE_ENV}" DB_MIGRATOR_USER)"
readonly DB_BACKUP_USER="$(release_env_value "${RELEASE_ENV}" DB_BACKUP_USER)"

database_dsn() {
  local role="$1"

  printf '%s\n' \
    "host=${DB_CERT_HOST} hostaddr=${DB_HOST_ADDRESS} port=${DB_PORT} dbname=${DB_NAME} user=${role} sslmode=verify-full sslrootcert=${CA_FILE}"
}

configure_role_search_path() {
  local role="$1"
  local password_file="$2"

  PGPASSWORD="$(<"${password_file}")" psql \
    "$(database_dsn "${role}") application_name=deployment-role-hardening" \
    -X -v ON_ERROR_STOP=1 \
    -v role_name="${role}" \
    -v database_name="${DB_NAME}" \
    -v schema_name="${DB_SCHEMA}" \
    >/dev/null <<'SQL'
ALTER ROLE :"role_name" IN DATABASE :"database_name"
  SET search_path TO :"schema_name", pg_catalog;
SQL
}

printf 'Repairing ACLs for database=%s schema=%s roles=%s,%s,%s\n' \
  "${DB_NAME}" "${DB_SCHEMA}" "${DB_RUNTIME_USER}" \
  "${DB_MIGRATOR_USER}" "${DB_BACKUP_USER}"

configure_role_search_path "${DB_RUNTIME_USER}" "${RUNTIME_PASSWORD_FILE}"
configure_role_search_path "${DB_MIGRATOR_USER}" "${MIGRATOR_PASSWORD_FILE}"
configure_role_search_path "${DB_BACKUP_USER}" "${BACKUP_PASSWORD_FILE}"

PGPASSWORD="$(<"${MIGRATOR_PASSWORD_FILE}")" psql \
  "$(database_dsn "${DB_MIGRATOR_USER}") application_name=deployment-acl-repair" \
  -X -v ON_ERROR_STOP=1 \
  -v schema_name="${DB_SCHEMA}" \
  -v runtime_role="${DB_RUNTIME_USER}" \
  -v migrator_role="${DB_MIGRATOR_USER}" \
  -v backup_role="${DB_BACKUP_USER}" <<'SQL'
BEGIN;

REVOKE CREATE ON SCHEMA :"schema_name" FROM :"runtime_role", :"backup_role";
GRANT USAGE ON SCHEMA :"schema_name" TO :"runtime_role", :"backup_role";

GRANT SELECT, INSERT, UPDATE, DELETE
  ON ALL TABLES IN SCHEMA :"schema_name" TO :"runtime_role";
REVOKE TRUNCATE, REFERENCES, TRIGGER
  ON ALL TABLES IN SCHEMA :"schema_name" FROM :"runtime_role";
GRANT SELECT ON ALL TABLES IN SCHEMA :"schema_name" TO :"backup_role";
REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
  ON ALL TABLES IN SCHEMA :"schema_name" FROM :"backup_role";

GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA :"schema_name" TO :"runtime_role";
GRANT SELECT ON ALL SEQUENCES IN SCHEMA :"schema_name" TO :"backup_role";
REVOKE USAGE, UPDATE ON ALL SEQUENCES IN SCHEMA :"schema_name" FROM :"backup_role";

SELECT format(
         'GRANT EXECUTE ON FUNCTION %I.%I(%s) TO %I',
         namespace.nspname,
         procedure.proname,
         pg_get_function_identity_arguments(procedure.oid),
         :'runtime_role'
       )
  FROM pg_proc procedure
  JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
 WHERE namespace.nspname = :'schema_name'
   AND procedure.prokind = 'f'
   AND procedure.proowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
\gexec

SELECT format(
         'REVOKE EXECUTE ON FUNCTION %I.%I(%s) FROM PUBLIC, %I',
         namespace.nspname,
         procedure.proname,
         pg_get_function_identity_arguments(procedure.oid),
         :'backup_role'
       )
  FROM pg_proc procedure
  JOIN pg_namespace namespace ON namespace.oid = procedure.pronamespace
 WHERE namespace.nspname = :'schema_name'
   AND procedure.prokind = 'f'
   AND procedure.proowner = (SELECT oid FROM pg_roles WHERE rolname = current_user)
\gexec

ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA :"schema_name"
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO :"runtime_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA :"schema_name"
  REVOKE TRUNCATE, REFERENCES, TRIGGER ON TABLES FROM :"runtime_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA :"schema_name"
  GRANT SELECT ON TABLES TO :"backup_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA :"schema_name"
  REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
  ON TABLES FROM :"backup_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA :"schema_name"
  GRANT USAGE, SELECT ON SEQUENCES TO :"runtime_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA :"schema_name"
  GRANT SELECT ON SEQUENCES TO :"backup_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA :"schema_name"
  REVOKE USAGE, UPDATE ON SEQUENCES FROM :"backup_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA :"schema_name"
  GRANT USAGE ON TYPES TO :"runtime_role", :"backup_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA :"schema_name"
  GRANT EXECUTE ON FUNCTIONS TO :"runtime_role";
ALTER DEFAULT PRIVILEGES FOR ROLE :"migrator_role" IN SCHEMA :"schema_name"
  REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, :"backup_role";

COMMIT;
SQL
