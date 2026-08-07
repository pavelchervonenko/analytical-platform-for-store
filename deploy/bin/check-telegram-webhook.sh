#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly TOKEN_FILE="${TELEGRAM_BOT_TOKEN_FILE:-/etc/store-analytics/secrets/telegram-bot-token}"
[[ -r "${TOKEN_FILE}" ]] || { printf 'Telegram token file is not readable\n' >&2; exit 1; }

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "${temporary_directory}"' EXIT
config_file="${temporary_directory}/curl.conf"
response_file="${temporary_directory}/response.json"
token="$(<"${TOKEN_FILE}")"
printf '%s\n' \
  "url = \"https://api.telegram.org/bot${token}/getWebhookInfo\"" \
  'proto = "=https"' \
  'connect-timeout = 5' \
  'max-time = 30' \
  'max-filesize = 65536' \
  'silent' \
  'show-error' >"${config_file}"
chmod 0600 "${config_file}"
unset token

curl --config "${config_file}" --output "${response_file}"
jq -e '.ok == true and (.result | type == "object")' "${response_file}" >/dev/null || {
  printf 'Telegram getWebhookInfo returned a non-success contract\n' >&2
  exit 1
}
jq '{url: .result.url,
     pendingUpdateCount: (.result.pending_update_count // 0),
     lastErrorDate: (.result.last_error_date // null),
     lastErrorMessage: (.result.last_error_message // null),
     allowedUpdates: (.result.allowed_updates // [])}' "${response_file}"
