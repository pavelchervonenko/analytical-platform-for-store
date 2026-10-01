#!/usr/bin/env bash

set -Eeuo pipefail
set +x

readonly PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=../../deploy/bin/release-safety.sh
source "${PROJECT_ROOT}/deploy/bin/release-safety.sh"

schema=90
boundary="$(TZ=Europe/Kaliningrad date -d '+2 days 00:00' +%Y-%m-%dT%H:%M:%S%:z)"
capture=true
capture_from="${boundary}"

# Replace only the file reader; the actual preflight parser is exercised by deploy tests.
release_env_value() {
  case "$2" in
    SCHEMA_VERSION) printf '%s\n' "${schema}" ;;
    APP_CATALOG_CLASSIFICATION_ACTIVATE_FROM) printf '%s\n' "${boundary}" ;;
    APP_CATALOG_COMPATIBILITY_SNAPSHOTS_ENABLED) printf '%s\n' "${capture}" ;;
    APP_CATALOG_COMPATIBILITY_SNAPSHOTS_FROM) printf '%s\n' "${capture_from}" ;;
    *) return 1 ;;
  esac
}

release_validate_catalog_cutover unused
capture_from='2026-01-01T00:00:00Z'
if release_validate_catalog_cutover unused 2>/dev/null; then
  printf '%s\n' 'Mismatched capture boundary was accepted' >&2
  exit 1
fi
capture_from="${boundary}"
capture=false
if release_validate_catalog_cutover unused 2>/dev/null; then
  printf '%s\n' 'Disabled snapshot capture was accepted' >&2
  exit 1
fi
capture=true
valid_boundary="${boundary}"
boundary="$(date -u -d '+2 days' +%Y-%m-%dT%H:%M:%SZ)"
capture_from="${boundary}"
if release_validate_catalog_cutover unused 2>/dev/null; then
  printf '%s\n' 'Non-midnight activation boundary was accepted' >&2
  exit 1
fi
boundary='2026-01-01T00:00:00Z'
if release_validate_catalog_cutover unused 2>/dev/null; then
  printf '%s\n' 'Past activation boundary was accepted' >&2
  exit 1
fi
schema=51
release_validate_catalog_cutover unused
