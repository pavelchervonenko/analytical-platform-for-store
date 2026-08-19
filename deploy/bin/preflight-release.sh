#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly DEPLOY_ROOT="${DEPLOY_ROOT:-/opt/store-analytics/deploy}"
readonly RELEASE_ENV="${1:-${DEPLOY_ROOT}/release.env}"
readonly COMPOSE_FILE="${DEPLOY_ROOT}/compose.production.yml"

# shellcheck source=release-safety.sh
source "${DEPLOY_ROOT}/bin/release-safety.sh"

die() {
  printf 'RELEASE PREFLIGHT FAILED: %s\n' "$*" >&2
  exit 1
}

[[ "$(id -u)" -eq 0 || "${RELEASE_PREFLIGHT_ALLOW_NON_ROOT:-false}" == 'true' ]] \
  || die 'run as root'
[[ -f "${COMPOSE_FILE}" ]] || die "Compose file does not exist: ${COMPOSE_FILE}"
for command_name in docker awk stat; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || die "missing command: ${command_name}"
done

release_validate_env_file "${RELEASE_ENV}" || exit 1
docker compose --env-file "${RELEASE_ENV}" -f "${COMPOSE_FILE}" \
  --profile tools config --quiet

printf 'Release preflight passed before database migration.\n'
