#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly TOKEN_FILE="${TELEGRAM_BOT_TOKEN_FILE:-/etc/store-analytics/secrets/telegram-bot-token}"
readonly CHAT_ID_FILE="${TECH_ALERT_CHAT_ID_FILE:-/etc/store-analytics/secrets/telegram-tech-alert-chat-id}"

[[ -r "${TOKEN_FILE}" ]] || { printf 'Telegram token file is not readable\n' >&2; exit 1; }
for command_name in curl jq mktemp install; do
  command -v "${command_name}" >/dev/null || {
    printf 'Missing command: %s\n' "${command_name}" >&2
    exit 1
  }
done

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "${temporary_directory}"' EXIT
curl_config="${temporary_directory}/curl.conf"
response_file="${temporary_directory}/updates.json"
candidate_file="${temporary_directory}/chat-id"
token="$(<"${TOKEN_FILE}")"

[[ "${token}" =~ ^[0-9]{6,20}:[A-Za-z0-9_-]{30,100}$ ]] || {
  printf 'Telegram token has an invalid format\n' >&2
  exit 1
}
printf '%s\n' \
  "url = \"https://api.telegram.org/bot${token}/getUpdates\"" \
  'proto = "=https"' \
  'connect-timeout = 5' \
  'max-time = 30' \
  'max-filesize = 65536' \
  'silent' \
  'show-error' >"${curl_config}"
chmod 0600 "${curl_config}"
unset token

curl --config "${curl_config}" --output "${response_file}"
jq -e '.ok == true and (.result | type == "array")' "${response_file}" >/dev/null || {
  printf 'Telegram getUpdates returned a non-success contract\n' >&2
  exit 1
}

jq -er '
  [.result[]
   | select(.message.chat.type == "private")
   | select((.message.text // "") | startswith("/start"))]
  | last
  | .message.chat.id
' "${response_file}" >"${candidate_file}" || {
  printf 'No private /start update found. Send /start to the bot and retry.\n' >&2
  exit 1
}

[[ "$(<"${candidate_file}")" =~ ^-?[0-9]+$ ]] || {
  printf 'Telegram chat id has an invalid format\n' >&2
  exit 1
}
install -o root -g root -m 0600 "${candidate_file}" "${CHAT_ID_FILE}"
printf 'Technical Telegram chat id captured securely.\n'
