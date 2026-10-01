#!/usr/bin/env bash

# Sourced by release-safety.sh after its version and env helpers are defined.
release_validate_catalog_cutover() {
  local env_file="$1"
  local schema_version activation snapshots_enabled snapshots_from activation_epoch now_epoch business_time
  local installed_env installed_schema installed_activation

  schema_version="$(release_env_value "${env_file}" SCHEMA_VERSION)" || return 1
  if ! release_version_lte '90' "${schema_version}"; then
    return 0
  fi

  activation="$(release_env_value "${env_file}" APP_CATALOG_CLASSIFICATION_ACTIVATE_FROM)" || return 1
  snapshots_enabled="$(release_env_value "${env_file}" APP_CATALOG_COMPATIBILITY_SNAPSHOTS_ENABLED)" || return 1
  snapshots_from="$(release_env_value "${env_file}" APP_CATALOG_COMPATIBILITY_SNAPSHOTS_FROM)" || return 1

  [[ "${activation}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\.[0-9]{1,6})?(Z|[+-][0-9]{2}:[0-9]{2})$ ]] \
    || { release_safety_fail 'Catalog activation requires an ISO instant with timezone and microsecond precision'; return 1; }
  activation_epoch="$(date -u -d "${activation}" +%s 2>/dev/null)" \
    || { release_safety_fail 'Catalog activation instant is invalid'; return 1; }
  business_time="$(TZ=Europe/Kaliningrad date -d "${activation}" +%H:%M:%S.%N 2>/dev/null)" \
    || { release_safety_fail 'Catalog business-day boundary is invalid'; return 1; }
  [[ "${business_time}" == '00:00:00.000000000' ]] \
    || { release_safety_fail 'Catalog activation must begin at Europe/Kaliningrad business-day midnight'; return 1; }
  now_epoch="$(date -u +%s)"
  if (( activation_epoch <= now_epoch + 3600 )); then
    # A later release must retain the immutable boundary, not invent another future date.
    # Only the protected installed release record can establish this is a repeat rollout.
    # The migration role and API/worker independently verify the actual database marker.
    installed_env="${STATE_DIR:-/var/lib/store-analytics/release-state}/current.env"
    release_validate_secret_file 'installed catalog release record' "${installed_env}" || return 1
    installed_schema="$(release_env_value "${installed_env}" SCHEMA_VERSION)" || return 1
    installed_activation="$(release_env_value "${installed_env}" APP_CATALOG_CLASSIFICATION_ACTIVATE_FROM)" || return 1
    release_require_version 'installed catalog schema' "${installed_schema}" || return 1
    release_version_lte '90' "${installed_schema}" \
      && [[ "${installed_activation}" == "${activation}" ]] \
      || { release_safety_fail 'Past catalog boundary must match a protected installed catalog release'; return 1; }
  fi
  [[ "${snapshots_enabled}" == 'true' && "${snapshots_from}" == "${activation}" ]] \
    || { release_safety_fail 'Catalog snapshot capture must be enabled from the same activation instant'; return 1; }
}
