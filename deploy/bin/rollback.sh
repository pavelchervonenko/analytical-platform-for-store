#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/store-analytics/deploy}"
readonly COMPOSE_FILE="${DEPLOY_ROOT}/compose.production.yml"
readonly STATE_DIR="${STATE_DIR:-/var/lib/store-analytics/release-state}"
readonly PREVIOUS_ENV="${STATE_DIR}/previous.env"

[[ "$(id -u)" -eq 0 ]] || { printf 'Run as root\n' >&2; exit 1; }
[[ -f "${PREVIOUS_ENV}" ]] || { printf 'No previous release is recorded\n' >&2; exit 1; }

compose() {
  docker compose --env-file "${PREVIOUS_ENV}" -f "${COMPOSE_FILE}" "$@"
}

compose pull backend-api backend-worker web
compose up -d --remove-orphans backend-api backend-worker web

APP_DOMAIN="$(sed -n 's/^APP_DOMAIN=//p' "${PREVIOUS_ENV}")" \
  "${DEPLOY_ROOT}/bin/smoke.sh"

install -o root -g root -m 0600 "${PREVIOUS_ENV}" "${STATE_DIR}/current.env"
printf 'Application containers rolled back. Database migrations were not reversed.\n'
