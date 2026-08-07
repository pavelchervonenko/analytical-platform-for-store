#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly APP_DOMAIN="${APP_DOMAIN:?required}"
readonly TELEGRAM_BOT_TOKEN_FILE="${TELEGRAM_BOT_TOKEN_FILE:?required}"
readonly TECH_ALERT_CHAT_ID_FILE="${TECH_ALERT_CHAT_ID_FILE:?required}"
readonly STATE_FILE="${STATE_FILE:-/var/lib/store-analytics/monitor-state}"
readonly CHECK_URL="https://${APP_DOMAIN}/readyz"

send_message() {
  local message="$1"
  local token chat_id config_file
  token="$(<"${TELEGRAM_BOT_TOKEN_FILE}")"
  chat_id="$(<"${TECH_ALERT_CHAT_ID_FILE}")"
  config_file="$(mktemp)"
  chmod 0600 "${config_file}"
  {
    printf 'url = "https://api.telegram.org/bot%s/sendMessage"\n' "${token}"
    printf 'request = "POST"\n'
    printf 'silent\nshow-error\nfail\n'
  } >"${config_file}"
  unset token
  local status=0
  curl --config "${config_file}" \
    --data-urlencode "chat_id=${chat_id}" \
    --data-urlencode "text=${message}" >/dev/null || status=$?
  rm -f -- "${config_file}"
  return "${status}"
}

previous_state='unknown'
[[ -r "${STATE_FILE}" ]] && previous_state="$(<"${STATE_FILE}")"

current_state='failed'
if curl --fail --silent --show-error \
  --connect-timeout 5 --max-time 15 "${CHECK_URL}" >/dev/null; then
  current_state='healthy'
fi

if [[ "${current_state}" != "${previous_state}" ]]; then
  if [[ "${current_state}" == 'failed' ]]; then
    send_message "ALERT store-analytics: readiness недоступен: ${CHECK_URL}"
  elif [[ "${previous_state}" == 'failed' ]]; then
    send_message "RECOVERY store-analytics: readiness восстановлен: ${CHECK_URL}"
  fi
fi

printf '%s\n' "${current_state}" >"${STATE_FILE}"
chmod 0640 "${STATE_FILE}"
[[ "${current_state}" == 'healthy' ]]
