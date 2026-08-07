#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/store-analytics/deploy}"
readonly RELEASE_ENV="${1:-${DEPLOY_ROOT}/release.env}"
readonly COMPOSE_FILE="${DEPLOY_ROOT}/compose.production.yml"
readonly STATE_DIR="${STATE_DIR:-/var/lib/store-analytics/release-state}"
readonly SMOKE_SCRIPT="${DEPLOY_ROOT}/bin/smoke.sh"

die() {
  printf 'DEPLOY FAILED: %s\n' "$*" >&2
  exit 1
}

[[ "$(id -u)" -eq 0 ]] || die 'run as root'
[[ -f "${RELEASE_ENV}" ]] || die "release env does not exist: ${RELEASE_ENV}"
[[ -f "${COMPOSE_FILE}" ]] || die "Compose file does not exist: ${COMPOSE_FILE}"
[[ -x "${SMOKE_SCRIPT}" ]] || die "smoke script is not executable: ${SMOKE_SCRIPT}"

env_mode="$(stat -c '%a' "${RELEASE_ENV}")"
[[ "${env_mode}" == '600' || "${env_mode}" == '400' ]] \
  || die "release env must have mode 0600 or 0400, got ${env_mode}"

mkdir -p "${STATE_DIR}"
chmod 0750 "${STATE_DIR}"

compose() {
  docker compose --env-file "${RELEASE_ENV}" -f "${COMPOSE_FILE}" "$@"
}

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

printf 'Applying database migrations\n'
compose --profile tools run --rm migrate

printf 'Starting private application services\n'
compose up -d --remove-orphans backend-api backend-worker

deadline=$((SECONDS + 240))
until [[ "$(compose ps --format json backend-api | grep -c '"Health":"healthy"' || true)" -ge 1 ]]; do
  (( SECONDS < deadline )) || {
    compose logs --tail=200 backend-api backend-worker >&2
    die 'backend API did not become healthy in 240 seconds'
  }
  sleep 3
done

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
