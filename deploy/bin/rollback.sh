#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/store-analytics/deploy}"
readonly COMPOSE_FILE="${DEPLOY_ROOT}/compose.production.yml"
readonly STATE_DIR="${STATE_DIR:-/var/lib/store-analytics/release-state}"
readonly PREVIOUS_ENV="${STATE_DIR}/previous.env"
readonly DATABASE_SCHEMA_FILE="${STATE_DIR}/database-schema-version"

# shellcheck source=release-safety.sh
source "${DEPLOY_ROOT}/bin/release-safety.sh"

die() {
  printf 'ROLLBACK REFUSED: %s\n' "$*" >&2
  exit 1
}

[[ "$(id -u)" -eq 0 ]] || die 'run as root'
[[ -f "${PREVIOUS_ENV}" ]] || die 'no previous release is recorded'
[[ -f "${DATABASE_SCHEMA_FILE}" ]] \
  || die "database schema state is missing: ${DATABASE_SCHEMA_FILE}"

database_schema="$(<"${DATABASE_SCHEMA_FILE}")"
release_validate_schema_metadata "${PREVIOUS_ENV}" \
  || die 'previous release has no verified schema compatibility metadata; use forward-fix.sh'
release_schema_allows_runtime "${PREVIOUS_ENV}" "${database_schema}" \
  || die "previous release cannot run on schema ${database_schema}; use forward-fix.sh"
"${DEPLOY_ROOT}/bin/preflight-release.sh" "${PREVIOUS_ENV}"

compose() {
  docker compose --env-file "${PREVIOUS_ENV}" -f "${COMPOSE_FILE}" "$@"
}

wait_healthy() {
  local service="$1"
  local deadline=$((SECONDS + 240))
  until [[ "$(compose ps --format json "${service}" \
      | grep -c '"Health":"healthy"' || true)" -ge 1 ]]; do
    (( SECONDS < deadline )) || {
      compose logs --tail=200 "${service}" >&2
      die "${service} did not become healthy in 240 seconds"
    }
    sleep 3
  done
}

compose pull backend-api backend-worker web
compose up -d --remove-orphans backend-api
wait_healthy backend-api
compose up -d --remove-orphans backend-worker
wait_healthy backend-worker
compose up -d --remove-orphans web

APP_DOMAIN="$(sed -n 's/^APP_DOMAIN=//p' "${PREVIOUS_ENV}")" \
  "${DEPLOY_ROOT}/bin/smoke.sh"

install -o root -g root -m 0600 "${PREVIOUS_ENV}" "${STATE_DIR}/current.env"
previous_release="$(release_env_value "${PREVIOUS_ENV}" RELEASE_ID)"
printf '%s\n' "${previous_release}" >"${STATE_DIR}/current-release"
chmod 0640 "${STATE_DIR}/current-release"
printf 'Application containers rolled back on verified schema %s.\n' "${database_schema}"
