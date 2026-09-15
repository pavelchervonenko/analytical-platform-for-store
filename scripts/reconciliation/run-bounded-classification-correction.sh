#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
readonly SQL_FILE="${SCRIPT_DIR}/sql/bounded-classification-correction.sql"
readonly MANIFEST_FILE="${1:-}"
readonly MODE="${2:-}"
readonly RELEASE_ENV="${3:-/etc/store-analytics/release.env}"

# shellcheck source=../../deploy/bin/release-safety.sh
source "${PROJECT_DIR}/deploy/bin/release-safety.sh"

die() {
  printf 'BOUNDED CLASSIFICATION CORRECTION FAILED: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf 'Usage: %s MANIFEST (--preflight|--apply|--verify) [RELEASE_ENV]\n' \
    "${0##*/}" >&2
  exit 2
}

[[ -n "${MANIFEST_FILE}" && -n "${MODE}" ]] || usage
[[ "${MODE}" == '--preflight' || "${MODE}" == '--apply' || "${MODE}" == '--verify' ]] \
  || usage
[[ -f "${MANIFEST_FILE}" && -r "${MANIFEST_FILE}" && ! -L "${MANIFEST_FILE}" ]] \
  || die "manifest is not a readable regular file: ${MANIFEST_FILE}"
[[ -f "${SQL_FILE}" && -r "${SQL_FILE}" && ! -L "${SQL_FILE}" ]] \
  || die "SQL program is not a readable regular file: ${SQL_FILE}"

for command_name in base64 jq psql sha256sum; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || die "required command is missing: ${command_name}"
done

release_validate_env_file "${RELEASE_ENV}" || exit 1

readonly OPERATION_ID="$(jq -er '
  .operation_id
  | select(type == "string")
  | select(test("^classification-correction-[a-z0-9-]+-v[0-9]+$"))
' "${MANIFEST_FILE}")" \
  || die 'manifest operation_id is missing or invalid'
readonly MANIFEST_SHA256="$(sha256sum -- "${MANIFEST_FILE}" | awk '{print $1}')"
readonly MANIFEST_B64="$(jq -ceS . "${MANIFEST_FILE}" | base64 -w0)" \
  || die 'manifest is not valid JSON'
readonly RELEASE_COMMIT="$(release_env_value "${RELEASE_ENV}" RELEASE_COMMIT)"
readonly DB_CERT_HOST="$(release_env_value "${RELEASE_ENV}" DB_CERT_HOST)"
readonly DB_HOST_ADDRESS="$(release_env_value "${RELEASE_ENV}" DB_HOST_ADDRESS)"
readonly DB_PORT="$(release_env_value "${RELEASE_ENV}" DB_PORT)"
readonly DB_NAME="$(release_env_value "${RELEASE_ENV}" DB_NAME)"
readonly DB_SCHEMA="$(release_env_value "${RELEASE_ENV}" DB_APP_SCHEMA)"

case "${MODE}" in
--apply)
  readonly DO_APPLY=true
  readonly EXPECT_TARGET=false
  readonly DB_ROLE="$(release_env_value "${RELEASE_ENV}" DB_MIGRATOR_USER)"
  readonly PASSWORD_FILE="$(release_env_value "${RELEASE_ENV}" POSTGRES_MIGRATOR_PASSWORD_FILE)"
  [[ "${CONFIRM_BOUNDED_CLASSIFICATION_APPLY:-}" == "${OPERATION_ID}" ]] \
    || die "set CONFIRM_BOUNDED_CLASSIFICATION_APPLY=${OPERATION_ID}"
  [[ "${CORRECTION_EXPECTED_MANIFEST_SHA256:-}" == "${MANIFEST_SHA256}" ]] \
    || die "CORRECTION_EXPECTED_MANIFEST_SHA256 does not match the reviewed manifest"
  [[ "${CORRECTION_EXPECTED_RELEASE_COMMIT:-}" == "${RELEASE_COMMIT}" ]] \
    || die 'CORRECTION_EXPECTED_RELEASE_COMMIT does not match the deployed release'
  [[ "${CORRECTION_APPROVAL_REF:-}" =~ ^[A-Za-z0-9][A-Za-z0-9._:/-]{2,127}$ ]] \
    || die 'CORRECTION_APPROVAL_REF must be a 3-128 character non-secret reference'
  readonly APPROVAL_REF="${CORRECTION_APPROVAL_REF}"
  ;;
--preflight)
  readonly DO_APPLY=false
  readonly EXPECT_TARGET=false
  readonly DB_ROLE="$(release_env_value "${RELEASE_ENV}" DB_BACKUP_USER)"
  readonly PASSWORD_FILE="$(release_env_value "${RELEASE_ENV}" POSTGRES_BACKUP_PASSWORD_FILE)"
  readonly APPROVAL_REF='read-only-preflight'
  ;;
--verify)
  readonly DO_APPLY=false
  readonly EXPECT_TARGET=true
  readonly DB_ROLE="$(release_env_value "${RELEASE_ENV}" DB_BACKUP_USER)"
  readonly PASSWORD_FILE="$(release_env_value "${RELEASE_ENV}" POSTGRES_BACKUP_PASSWORD_FILE)"
  readonly APPROVAL_REF='read-only-verification'
  ;;
esac

[[ -f "${PASSWORD_FILE}" && -r "${PASSWORD_FILE}" && ! -L "${PASSWORD_FILE}" ]] \
  || die "database password file is not a readable regular file: ${PASSWORD_FILE}"

readonly DATABASE_DSN="host=${DB_CERT_HOST} hostaddr=${DB_HOST_ADDRESS} port=${DB_PORT} dbname=${DB_NAME} user=${DB_ROLE} sslmode=verify-full sslrootcert=$(release_env_value "${RELEASE_ENV}" POSTGRES_CA_FILE) application_name=bounded-classification-${MODE#--}"

printf 'Bounded classification %s: operation=%s manifest_sha256=%s release=%s\n' \
  "${MODE#--}" "${OPERATION_ID}" "${MANIFEST_SHA256}" "${RELEASE_COMMIT}"

database_password="$(<"${PASSWORD_FILE}")"
{
  printf '\\set manifest_b64 %s\n' "${MANIFEST_B64}"
  printf '\\set do_apply %s\n' "${DO_APPLY}"
  printf '\\set expect_target %s\n' "${EXPECT_TARGET}"
  printf '\\set correction_mode %s\n' "${MODE#--}"
  printf '\\set manifest_sha256 %s\n' "${MANIFEST_SHA256}"
  printf '\\set release_commit %s\n' "${RELEASE_COMMIT}"
  printf '\\set approval_ref %s\n' "${APPROVAL_REF}"
  cat -- "${SQL_FILE}"
} | PGPASSWORD="${database_password}" psql \
      "${DATABASE_DSN}" \
      -X --no-psqlrc --set=ON_ERROR_STOP=1 --quiet --tuples-only --no-align \
      --set=schema_name="${DB_SCHEMA}"
unset database_password
