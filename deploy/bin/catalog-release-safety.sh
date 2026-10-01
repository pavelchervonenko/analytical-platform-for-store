#!/usr/bin/env bash

# Sourced by release-safety.sh after its version and env helpers are defined.
release_validate_catalog_cutover() {
  local env_file="$1"
  local schema_version activation snapshots_enabled snapshots_from activation_epoch now_epoch business_time

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
  (( activation_epoch > now_epoch + 3600 )) \
    || { release_safety_fail 'Catalog activation must be more than one hour in the future at release preflight'; return 1; }
  [[ "${snapshots_enabled}" == 'true' && "${snapshots_from}" == "${activation}" ]] \
    || { release_safety_fail 'Catalog snapshot capture must be enabled from the same activation instant'; return 1; }
}
