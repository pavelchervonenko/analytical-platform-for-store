#!/usr/bin/env bash

# Sourced by release-safety.sh. Values are read as data; the release env is never sourced.
release_historical_duration_seconds() {
  local value="$1" amount unit multiplier
  [[ "${value}" =~ ^([0-9]{1,7})(s|m|h|d)$ ]] || return 1
  amount="${BASH_REMATCH[1]}"
  unit="${BASH_REMATCH[2]}"
  case "${unit}" in
  s) multiplier=1 ;;
  m) multiplier=60 ;;
  h) multiplier=3600 ;;
  d) multiplier=86400 ;;
  esac
  printf '%s\n' "$(( 10#${amount} * multiplier ))"
}

release_validate_historical_sales_configuration() {
  local env_file="$1" enabled start window step daily cycle delay worker schedule
  local cycle_seconds delay_seconds normalized_date
  enabled="$(release_env_value_or_default "${env_file}" SYNC_HISTORICAL_SALES_ENABLED false)" || return 1
  start="$(release_env_value_or_default "${env_file}" SYNC_HISTORICAL_SALES_START_DATE '')" || return 1
  window="$(release_env_value_or_default "${env_file}" SYNC_HISTORICAL_SALES_WINDOW_MINUTES 180)" || return 1
  step="$(release_env_value_or_default "${env_file}" SYNC_HISTORICAL_SALES_MAX_REQUESTS_PER_STEP 100)" || return 1
  daily="$(release_env_value_or_default "${env_file}" SYNC_HISTORICAL_SALES_MAX_REQUESTS_PER_DAY 200)" || return 1
  cycle="$(release_env_value_or_default "${env_file}" SYNC_HISTORICAL_SALES_CYCLE_INTERVAL 1d)" || return 1
  delay="$(release_env_value_or_default "${env_file}" SYNC_HISTORICAL_SALES_ENQUEUE_DELAY 1m)" || return 1

  [[ "${enabled}" == true || "${enabled}" == false ]] || {
    release_safety_fail 'SYNC_HISTORICAL_SALES_ENABLED must be true or false'; return 1;
  }
  [[ "${window}" =~ ^[0-9]{1,3}$ ]] && (( 10#${window} >= 15 && 10#${window} <= 180 )) || {
    release_safety_fail 'Historical SALE window must be 15..180 minutes'; return 1;
  }
  [[ "${step}" =~ ^[0-9]{1,3}$ && "${daily}" =~ ^[0-9]{1,3}$ ]] \
    && (( 10#${step} >= 1 && 10#${step} <= 100 && 10#${daily} >= 10#${step} && 10#${daily} <= 200 )) || {
    release_safety_fail 'Historical SALE HTTP budgets must be step 1..100 and day step..200'; return 1;
  }
  cycle_seconds="$(release_historical_duration_seconds "${cycle}")" \
    && (( cycle_seconds >= 3600 && cycle_seconds <= 2678400 )) || {
    release_safety_fail 'Historical SALE cycle interval must be 1 hour..31 days using integer s/m/h/d'; return 1;
  }
  delay_seconds="$(release_historical_duration_seconds "${delay}")" \
    && (( delay_seconds > 0 )) || {
    release_safety_fail 'Historical SALE enqueue delay must be positive using integer s/m/h/d'; return 1;
  }
  if [[ -n "${start}" ]]; then
    [[ "${start}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
      && normalized_date="$(date -u -d "${start}" +%F 2>/dev/null)" \
      && [[ "${normalized_date}" == "${start}" ]] || {
      release_safety_fail 'Historical SALE start date must be a valid ISO LocalDate'; return 1;
    }
  fi
  if [[ "${enabled}" == true ]]; then
    [[ -n "${start}" ]] || {
      release_safety_fail 'Enabled historical SALE refresh requires an explicit start date'; return 1;
    }
    worker="$(release_env_value_or_default "${env_file}" SYNC_WORKER_ENABLED true)" || return 1
    schedule="$(release_env_value_or_default "${env_file}" SYNC_SCHEDULE_ENABLED false)" || return 1
    [[ "${worker}" == true && "${schedule}" == true ]] || {
      release_safety_fail 'Historical SALE refresh requires the routine sync worker and schedule'; return 1;
    }
  fi
}
