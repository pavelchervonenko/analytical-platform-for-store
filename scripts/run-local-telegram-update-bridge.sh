#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/shell-security.sh
source "${SCRIPT_DIR}/lib/shell-security.sh"

LOCAL_BACKEND_BASE_URL="${LOCAL_BACKEND_BASE_URL:-http://127.0.0.1:8081}"
TELEGRAM_API_BASE_URL="${TELEGRAM_API_BASE_URL:-https://api.telegram.org}"
TELEGRAM_BOT_CODE="${TELEGRAM_BOT_CODE:-store-analytics-primary}"
TELEGRAM_BOT_USERNAME="${TELEGRAM_BOT_USERNAME:-store_analytics_notify_bot}"
TELEGRAM_BOT_TOKEN_FILE="${TELEGRAM_BOT_TOKEN_FILE:-}"
TELEGRAM_WEBHOOK_SECRET_FILE="${TELEGRAM_WEBHOOK_SECRET_FILE:-}"

read_secret() {
    local label="$1"
    local path="$2"
    local value

    security_require_readable_regular_file "${label}" "${path}"
    [[ "$(wc -c <"${path}")" -le 512 ]] \
        || security_fail "${label} is overlong"
    value="$(<"${path}")"
    [[ -n "${value}" ]] || security_fail "${label} is empty"
    [[ "${value}" != *$'\r'* && "${value}" != *$'\n'* ]] \
        || security_fail "${label} contains CR or LF"
    printf '%s' "${value}"
}

write_telegram_config() {
    local method="$1"
    local output_file="$2"

    [[ "${method}" =~ ^[A-Za-z][A-Za-z0-9]{1,31}$ ]] \
        || security_fail 'Telegram method is invalid'
    printf '%s\n' \
        "url = \"${TELEGRAM_API_BASE_URL}/bot${BOT_TOKEN}/${method}\"" \
        'proto = "=https"' \
        'connect-timeout = 5' \
        'max-time = 30' \
        'max-filesize = 1048576' \
        'silent' \
        'show-error' >"${output_file}"
    chmod 600 "${output_file}"
}

telegram_call() {
    local method="$1"
    local request_file="${2:-}"
    local config_file="${TEMPORARY_DIRECTORY}/telegram-${method}.conf"
    local status

    write_telegram_config "${method}" "${config_file}"
    if [[ -n "${request_file}" ]]; then
        status="$(curl --config "${config_file}" \
            --header 'Content-Type: application/json' \
            --data-binary "@${request_file}" \
            --output "${TELEGRAM_RESPONSE_FILE}" \
            --write-out '%{http_code}')"
    else
        status="$(curl --config "${config_file}" \
            --output "${TELEGRAM_RESPONSE_FILE}" \
            --write-out '%{http_code}')"
    fi
    [[ "${status}" == '200' ]] \
        || security_fail "Telegram ${method} returned HTTP ${status}"
    jq -e '.ok == true and (.result != null)' \
        "${TELEGRAM_RESPONSE_FILE}" >/dev/null \
        || security_fail "Telegram ${method} returned a non-success contract"
}

verify_bot_and_webhook_state() {
    local actual_username webhook_url

    telegram_call 'getMe'
    actual_username="$(jq -er '.result.username' "${TELEGRAM_RESPONSE_FILE}")"
    [[ "${actual_username}" == "${TELEGRAM_BOT_USERNAME}" ]] \
        || security_fail 'Telegram bot username does not match configuration'

    telegram_call 'getWebhookInfo'
    webhook_url="$(jq -er '.result.url // ""' "${TELEGRAM_RESPONSE_FILE}")"
    [[ -z "${webhook_url}" ]] \
        || security_fail 'Telegram webhook is configured; polling bridge refuses to compete with it'
    printf 'Telegram bot identity and polling mode verified.\n'
}

forward_update() {
    local update_file="$1"
    local status protocol

    protocol="$(security_curl_protocol "${LOCAL_BACKEND_BASE_URL}")"
    status="$(curl --proto "${protocol}" \
        --connect-timeout 3 --max-time 10 --max-filesize 65536 \
        --silent --show-error \
        --header 'Content-Type: application/json' \
        --header "@${WEBHOOK_HEADER_FILE}" \
        --data-binary "@${update_file}" \
        --output "${BACKEND_RESPONSE_FILE}" \
        --write-out '%{http_code}' \
        "${LOCAL_BACKEND_BASE_URL}/api/integrations/telegram/${TELEGRAM_BOT_CODE}/webhook")"
    [[ "${status}" == '200' ]] \
        || security_fail "Local Telegram webhook returned HTTP ${status}"
}

poll_forever() {
    local offset=0 request_file update_file update_id

    request_file="${TEMPORARY_DIRECTORY}/get-updates.json"
    update_file="${TEMPORARY_DIRECTORY}/update.json"
    while true; do
        jq -n --argjson offset "${offset}" \
            '{offset: $offset, timeout: 20, limit: 20,
              allowed_updates: ["message", "my_chat_member"]}' \
            >"${request_file}"
        chmod 600 "${request_file}"
        telegram_call 'getUpdates' "${request_file}"
        while IFS= read -r update; do
            printf '%s' "${update}" >"${update_file}"
            chmod 600 "${update_file}"
            update_id="$(jq -er '.update_id' "${update_file}")"
            [[ "${update_id}" =~ ^[0-9]+$ ]] \
                || security_fail 'Telegram update_id is invalid'
            forward_update "${update_file}"
            offset="$((update_id + 1))"
            printf 'Forwarded one Telegram update to the local webhook.\n'
        done < <(jq -c '.result[]' "${TELEGRAM_RESPONSE_FILE}")
    done
}

main() {
    local command_name
    for command_name in curl jq python3 wc; do
        security_require_command "${command_name}"
    done
    LOCAL_BACKEND_BASE_URL="$(security_normalize_base_url \
        'https-or-loopback-http' "${LOCAL_BACKEND_BASE_URL}")"
    TELEGRAM_API_BASE_URL="$(security_normalize_base_url \
        'https-only' "${TELEGRAM_API_BASE_URL}")"
    security_require_path_segment 'TELEGRAM_BOT_CODE' "${TELEGRAM_BOT_CODE}"
    [[ "${TELEGRAM_BOT_USERNAME}" =~ ^[A-Za-z0-9_]{5,64}$ ]] \
        || security_fail 'TELEGRAM_BOT_USERNAME is invalid'

    TEMPORARY_DIRECTORY="$(mktemp -d)"
    TELEGRAM_RESPONSE_FILE="${TEMPORARY_DIRECTORY}/telegram-response.json"
    BACKEND_RESPONSE_FILE="${TEMPORARY_DIRECTORY}/backend-response.json"
    WEBHOOK_HEADER_FILE="${TEMPORARY_DIRECTORY}/webhook-header"
    trap 'rm -rf -- "${TEMPORARY_DIRECTORY}"' EXIT

    BOT_TOKEN="$(read_secret \
        'TELEGRAM_BOT_TOKEN_FILE' "${TELEGRAM_BOT_TOKEN_FILE}")"
    WEBHOOK_SECRET="$(read_secret \
        'TELEGRAM_WEBHOOK_SECRET_FILE' "${TELEGRAM_WEBHOOK_SECRET_FILE}")"
    [[ "${BOT_TOKEN}" =~ ^[0-9]{6,20}:[A-Za-z0-9_-]{30,100}$ ]] \
        || security_fail 'Telegram bot token is invalid'
    [[ "${WEBHOOK_SECRET}" =~ ^[A-Za-z0-9_-]{16,256}$ ]] \
        || security_fail 'Telegram webhook secret is invalid'
    security_write_header_file 'X-Telegram-Bot-Api-Secret-Token' \
        "${WEBHOOK_SECRET}" "${WEBHOOK_HEADER_FILE}"
    unset WEBHOOK_SECRET

    verify_bot_and_webhook_state
    printf 'Local Telegram polling bridge is running.\n'
    poll_forever
}

main "$@"
