#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/store-analytics/deploy}"
readonly RELEASE_ENV="${1:-}"
readonly STATE_DIR="${STATE_DIR:-/var/lib/store-analytics/release-state}"
readonly DATABASE_SCHEMA_FILE="${STATE_DIR}/database-schema-version"

# shellcheck source=release-safety.sh
source "${DEPLOY_ROOT}/bin/release-safety.sh"

die() {
  printf 'FORWARD FIX REFUSED: %s\n' "$*" >&2
  exit 1
}

[[ "$(id -u)" -eq 0 ]] || die 'run as root'
[[ -n "${RELEASE_ENV}" && -f "${RELEASE_ENV}" ]] \
  || die 'pass a schema-compatible forward-fix release env'
[[ -f "${DATABASE_SCHEMA_FILE}" ]] \
  || die "database schema state is missing: ${DATABASE_SCHEMA_FILE}"

database_schema="$(<"${DATABASE_SCHEMA_FILE}")"
release_validate_schema_metadata "${RELEASE_ENV}" || exit 1
release_schema_allows_migration_source "${RELEASE_ENV}" "${database_schema}" \
  || die "release cannot migrate forward from database schema ${database_schema}"

"${DEPLOY_ROOT}/bin/preflight-release.sh" "${RELEASE_ENV}"
printf 'Starting forward fix from database schema %s.\n' "${database_schema}"
exec "${DEPLOY_ROOT}/bin/deploy.sh" "${RELEASE_ENV}"
