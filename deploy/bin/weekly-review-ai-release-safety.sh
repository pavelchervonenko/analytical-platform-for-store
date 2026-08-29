#!/usr/bin/env bash

# Sourced by release-safety.sh after its env helpers are defined.
release_validate_weekly_review_ai_configuration() {
  local env_file="$1"
  local read_enabled snapshot_planner enabled planner worker
  local provider folder model max_calls

  read_enabled="$(release_env_value_or_default \
    "${env_file}" WEEKLY_REVIEW_ENABLED false)" || return 1
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

  if [[ "${planner}" == 'true' && "${snapshot_planner}" != 'true' ]]; then
    release_safety_fail \
      'WEEKLY_REVIEW_AI_PLANNER_ENABLED requires WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=true'
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
