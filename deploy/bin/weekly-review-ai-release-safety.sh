#!/usr/bin/env bash

# Sourced by release-safety.sh after its env helpers are defined.
release_validate_seller_weekly_preparation_bounds() {
  local env_file="$1" suffix fallback minimum maximum value variable_name
  while IFS=: read -r suffix fallback minimum maximum; do
    variable_name="SELLER_WEEKLY_PREPARATION_${suffix}"
    value="$(release_env_value_or_default "${env_file}" "${variable_name}" "${fallback}")" || return 1
    if [[ ! "${value}" =~ ^[0-9]{1,3}$ ]] \
      || (( 10#${value} < minimum || 10#${value} > maximum )); then
      release_safety_fail "${variable_name} is outside safe integer bounds"
      return 1
    fi
  done <<'BOUNDS'
STORE_BATCH_SIZE:10:1:100
DISCOVERY_WEEKS:4:1:52
REFRESH_BATCH_SIZE:25:1:100
BATCH_SIZE:2:1:10
BOUNDS
  release_validate_seller_weekly_preparation_duration "${env_file}" SCAN_DELAY 1m 10000 3600000 || return 1
  release_validate_seller_weekly_preparation_duration "${env_file}" TIME_BUDGET 1m 1000 300000
}

release_validate_seller_weekly_preparation_duration() {
  local env_file="$1" variable_name="SELLER_WEEKLY_PREPARATION_$2" fallback="$3"
  local minimum="$4" maximum="$5" value milliseconds multiplier
  value="$(release_env_value_or_default "${env_file}" "${variable_name}" "${fallback}")" || return 1
  if [[ ! "${value}" =~ ^([1-9][0-9]{0,6})(ms|s|m|h)$ ]]; then
    release_safety_fail "${variable_name} requires a positive integer with ms/s/m/h unit"
    return 1
  fi
  milliseconds="${BASH_REMATCH[1]}"
  case "${BASH_REMATCH[2]}" in
    ms) multiplier=1 ;;
    s) multiplier=1000 ;;
    m) multiplier=60000 ;;
    h) multiplier=3600000 ;;
  esac
  milliseconds=$(( milliseconds * multiplier ))
  if (( milliseconds < minimum || milliseconds > maximum )); then
    release_safety_fail "${variable_name} is outside safe duration bounds"
    return 1
  fi
}

release_validate_weekly_review_ai_configuration() {
  local env_file="$1"
  local read_enabled seller_enabled preparation snapshot_planner enabled planner worker
  local provider folder model max_calls

  read_enabled="$(release_env_value_or_default \
    "${env_file}" WEEKLY_REVIEW_ENABLED false)" || return 1
  seller_enabled="$(release_env_value_or_default \
    "${env_file}" SELLER_WEEKLY_REVIEW_ENABLED false)" || return 1
  preparation="$(release_env_value_or_default \
    "${env_file}" SELLER_WEEKLY_PREPARATION_ENABLED false)" || return 1
  snapshot_planner="$(release_env_value_or_default \
    "${env_file}" WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED false)" || return 1
  enabled="$(release_env_value_or_default \
    "${env_file}" WEEKLY_REVIEW_AI_ENABLED false)" || return 1
  planner="$(release_env_value_or_default \
    "${env_file}" WEEKLY_REVIEW_AI_PLANNER_ENABLED false)" || return 1
  worker="$(release_env_value_or_default \
    "${env_file}" WEEKLY_REVIEW_AI_WORKER_ENABLED false)" || return 1

  [[ "${read_enabled}" == 'true' || "${read_enabled}" == 'false' ]] || {
    release_safety_fail 'WEEKLY_REVIEW_ENABLED must be boolean'
    return 1
  }
  [[ "${seller_enabled}" == 'true' || "${seller_enabled}" == 'false' ]] || {
    release_safety_fail 'SELLER_WEEKLY_REVIEW_ENABLED must be boolean'
    return 1
  }
  if [[ "${seller_enabled}" == 'true' && "${read_enabled}" != 'true' ]]; then
    release_safety_fail 'SELLER_WEEKLY_REVIEW_ENABLED requires WEEKLY_REVIEW_ENABLED=true'
    return 1
  fi
  [[ "${preparation}" == 'true' || "${preparation}" == 'false' ]] || {
    release_safety_fail 'SELLER_WEEKLY_PREPARATION_ENABLED must be boolean'
    return 1
  }
  release_validate_seller_weekly_preparation_bounds "${env_file}" || return 1
  if [[ "${preparation}" == 'true' ]]; then
    if [[ "${seller_enabled}" != 'true' || "${read_enabled}" != 'true' ]]; then
      release_safety_fail 'SELLER_WEEKLY_PREPARATION_ENABLED requires parent and seller features'
      return 1
    fi
  fi
  [[ "${snapshot_planner}" == 'true' || "${snapshot_planner}" == 'false' ]] || {
    release_safety_fail \
      'WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED must be boolean'
    return 1
  }

  case "${enabled}:${planner}:${worker}" in
  false:false:false)
    ;;
  true:false:false|true:true:false|true:false:true|true:true:true)
    ;;
  *)
    release_safety_fail \
      'weekly review AI flags must be booleans and children require WEEKLY_REVIEW_AI_ENABLED=true'
    return 1
    ;;
  esac

  if [[ "${planner}" == 'true' && "${snapshot_planner}" != 'true' && "${preparation}" != 'true' ]]; then
    release_safety_fail \
      'WEEKLY_REVIEW_AI_PLANNER_ENABLED requires deterministic snapshot preparation'
    return 1
  fi

  if [[ "${enabled}" == 'false' ]]; then
    return 0
  fi

  provider="$(release_env_value_or_default \
    "${env_file}" WEEKLY_REVIEW_AI_PROVIDER_CODE YANDEX)" || return 1
  [[ "${provider}" == 'YANDEX' ]] || {
    release_safety_fail 'WEEKLY_REVIEW_AI_PROVIDER_CODE must be YANDEX'
    return 1
  }

  folder="$(release_env_value "${env_file}" YANDEX_AI_FOLDER_ID)" || return 1
  model="$(release_env_value "${env_file}" YANDEX_AI_MODEL_URI)" || return 1
  [[ "${folder}" =~ ^[A-Za-z0-9_-]{4,100}$ ]] || {
    release_safety_fail 'YANDEX_AI_FOLDER_ID is invalid for weekly review AI'
    return 1
  }
  [[ "${model}" == "gpt://${folder}/"* \
    && "${model}" =~ ^gpt://[A-Za-z0-9_-]{4,100}/[A-Za-z0-9._/-]{2,160}$ \
    && "${model}" != */latest ]] || {
    release_safety_fail \
      'weekly review AI requires a versioned YANDEX_AI_MODEL_URI in its folder'
    return 1
  }

  max_calls="$(release_env_value_or_default \
    "${env_file}" WEEKLY_REVIEW_AI_MAX_PROVIDER_CALLS 2)" || return 1
  [[ "${max_calls}" == '1' || "${max_calls}" == '2' ]] || {
    release_safety_fail \
      'WEEKLY_REVIEW_AI_MAX_PROVIDER_CALLS must be 1 or 2'
    return 1
  }
}
