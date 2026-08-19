#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/store-analytics/deploy}"
readonly RELEASE_ENV="${1:-${DEPLOY_ROOT}/release.env}"
readonly COMPOSE_FILE="${DEPLOY_ROOT}/compose.production.yml"
readonly STATE_DIR="${STATE_DIR:-/var/lib/store-analytics/release-state}"
readonly SMOKE_SCRIPT="${DEPLOY_ROOT}/bin/smoke.sh"
readonly DATABASE_SCHEMA_FILE="${STATE_DIR}/database-schema-version"

# shellcheck source=release-safety.sh
source "${DEPLOY_ROOT}/bin/release-safety.sh"

die() {
  printf 'DEPLOY FAILED: %s\n' "$*" >&2
  exit 1
}

[[ "$(id -u)" -eq 0 ]] || die 'run as root'
[[ -f "${COMPOSE_FILE}" ]] || die "Compose file does not exist: ${COMPOSE_FILE}"
[[ -x "${SMOKE_SCRIPT}" ]] || die "smoke script is not executable: ${SMOKE_SCRIPT}"

"${DEPLOY_ROOT}/bin/preflight-release.sh" "${RELEASE_ENV}"

mkdir -p "${STATE_DIR}"
chmod 0750 "${STATE_DIR}"

compose() {
  docker compose --env-file "${RELEASE_ENV}" -f "${COMPOSE_FILE}" "$@"
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

if [[ -f "${DATABASE_SCHEMA_FILE}" ]]; then
  database_schema="$(<"${DATABASE_SCHEMA_FILE}")"
  release_schema_allows_migration_source "${RELEASE_ENV}" "${database_schema}" \
    || die "release cannot migrate from recorded database schema ${database_schema}"
fi

release_id="$(sed -n 's/^RELEASE_ID=//p' "${RELEASE_ENV}")"
skip_image_pull="$(sed -n 's/^SKIP_IMAGE_PULL=//p' "${RELEASE_ENV}")"
[[ "${release_id}" =~ ^[A-Za-z0-9._-]{7,128}$ ]] || die 'invalid RELEASE_ID'

if [[ -f "${STATE_DIR}/current.env" ]]; then
  install -o root -g root -m 0600 \
    "${STATE_DIR}/current.env" "${STATE_DIR}/previous.env"
fi
install -o root -g root -m 0600 "${RELEASE_ENV}" "${STATE_DIR}/candidate.env"

if [[ "${skip_image_pull:-false}" == 'true' ]]; then
  printf 'Using preloaded images for release %s\n' "${release_id}"
else
  printf 'Pulling immutable images for release %s\n' "${release_id}"
  compose pull backend-api backend-worker web
fi

expected_schema="$(release_env_value "${RELEASE_ENV}" SCHEMA_VERSION)"
image_schema="$(compose --profile tools run --rm --no-deps \
  --entrypoint java migrate -jar /app/app.jar --print-expected-schema-version \
  | tail -n 1)"
[[ "${image_schema}" == "${expected_schema}" ]] \
  || die "backend image schema ${image_schema} does not match release metadata ${expected_schema}"

printf '%s\n' 'MIGRATION_IN_PROGRESS' >"${DATABASE_SCHEMA_FILE}"
chmod 0640 "${DATABASE_SCHEMA_FILE}"
printf 'Applying database migrations\n'
compose --profile tools run --rm migrate || die \
  'migration failed; schema state is unknown, inspect Flyway history before recovery'
printf '%s\n' "${expected_schema}" >"${DATABASE_SCHEMA_FILE}"
chmod 0640 "${DATABASE_SCHEMA_FILE}"

printf 'Reasserting least-privilege database ACLs\n'
"${DEPLOY_ROOT}/bin/repair-production-database-acls.sh"

printf 'Starting backend API before background workers\n'
compose up -d --remove-orphans backend-api
wait_healthy backend-api

printf 'Starting background worker after API readiness\n'
compose up -d --remove-orphans backend-worker
wait_healthy backend-worker

printf 'Starting HTTPS edge\n'
compose up -d --remove-orphans web

APP_DOMAIN="$(sed -n 's/^APP_DOMAIN=//p' "${RELEASE_ENV}")" \
  "${SMOKE_SCRIPT}"

install -o root -g root -m 0600 \
  "${STATE_DIR}/candidate.env" "${STATE_DIR}/current.env"
printf '%s\n' "${release_id}" >"${STATE_DIR}/current-release"
chmod 0640 "${STATE_DIR}/current-release"
rm -f "${STATE_DIR}/candidate.env"

docker image prune -f --filter 'until=336h' >/dev/null
printf 'Release %s deployed successfully\n' "${release_id}"
