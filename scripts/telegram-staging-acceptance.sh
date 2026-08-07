#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/shell-security.sh
source "${SCRIPT_DIR}/lib/shell-security.sh"

APP_BASE_URL="${APP_BASE_URL:-}"
TELEGRAM_API_BASE_URL="${TELEGRAM_API_BASE_URL:-https://api.telegram.org}"
TELEGRAM_BOT_CODE="${TELEGRAM_BOT_CODE:-store-analytics-primary}"
TELEGRAM_BOT_USERNAME="${TELEGRAM_BOT_USERNAME:-}"
TELEGRAM_BOT_TOKEN_FILE="${TELEGRAM_BOT_TOKEN_FILE:-}"
TELEGRAM_WEBHOOK_SECRET_FILE="${TELEGRAM_WEBHOOK_SECRET_FILE:-}"
CONFIRM_TELEGRAM_WEBHOOK_CHANGE="${CONFIRM_TELEGRAM_WEBHOOK_CHANGE:-}"

usage() {
    printf '%s\n' \
        'Usage:' \
        '  scripts/telegram-staging-acceptance.sh verify' \
        '  scripts/telegram-staging-acceptance.sh configure' \
        '' \
        'Required:' \
        '  APP_BASE_URL=https://staging.example.com' \
        '  TELEGRAM_BOT_USERNAME=store_analytics_staging_bot' \
        '  TELEGRAM_BOT_TOKEN_FILE=/secure/path/token' \
        '' \
        'configure additionally requires:' \
        '  TELEGRAM_WEBHOOK_SECRET_FILE=/secure/path/webhook-secret' \
        '  CONFIRM_TELEGRAM_WEBHOOK_CHANGE=SET_STAGING_WEBHOOK' \
        '' \
        'Secret environment variables are accepted as a fallback, but files are preferred.'
}

read_secret() {
    local direct_name="$1"
    local file_path="$2"
    local direct_value="${!direct_name:-}"
    local value=''

    if [[ -n "${direct_value}" && -n "${file_path}" ]]; then
        security_fail "Use either ${direct_name} or its file, not both"
    fi
    if [[ -n "${file_path}" ]]; then
        security_require_readable_regular_file "${direct_name}_FILE" "${file_path}"
        [[ "$(wc -c <"${file_path}")" -le 512 ]] \
            || security_fail "${direct_name}_FILE is overlong"
        value="$(<"${file_path}")"
    else
        value="${direct_value}"
    fi
    [[ -n "${value}" ]] || security_fail "${direct_name} is required"
    [[ "${value}" != *$'\r'* && "${value}" != *$'\n'* ]] \
        || security_fail "${direct_name} contains CR or LF"
    printf '%s' "${value}"
}

validate_configuration() {
    APP_BASE_URL="$(security_normalize_base_url 'https-only' "${APP_BASE_URL}")" \
        || return 1
    TELEGRAM_API_BASE_URL="$(
        security_normalize_base_url 'https-only' "${TELEGRAM_API_BASE_URL}"
    )" || return 1
    security_require_path_segment 'TELEGRAM_BOT_CODE' "${TELEGRAM_BOT_CODE}"
    [[ "${TELEGRAM_BOT_USERNAME}" =~ ^[A-Za-z0-9_]{5,64}$ ]] \
        || security_fail 'TELEGRAM_BOT_USERNAME is invalid'
}

write_telegram_curl_config() {
    local method="$1"
    local output_file="$2"
    local bot_token="$3"

    [[ "${method}" =~ ^[A-Za-z][A-Za-z0-9]{1,31}$ ]] \
        || security_fail 'Telegram API method is invalid'
    printf '%s\n' \
        "url = \"${TELEGRAM_API_BASE_URL}/bot${bot_token}/${method}\"" \
        'proto = "=https"' \
        'connect-timeout = 5' \
        'max-time = 30' \
        'max-filesize = 65536' \
        'silent' \
        'show-error' >"${output_file}"
    chmod 600 "${output_file}"
}

telegram_call() {
    local method="$1"
    local request_file="${2:-}"
    local config_file="${TEMPORARY_DIRECTORY}/telegram-curl.conf"
    local status

    write_telegram_curl_config "${method}" "${config_file}" "${BOT_TOKEN}"
    if [[ -n "${request_file}" ]]; then
        status="$(curl --config "${config_file}" \
            --header 'Content-Type: application/json' \
            --data-binary "@${request_file}" \
            --output "${RESPONSE_FILE}" \
            --write-out '%{http_code}')"
    else
        status="$(curl --config "${config_file}" \
            --output "${RESPONSE_FILE}" \
            --write-out '%{http_code}')"
    fi
    [[ "${status}" == '200' ]] \
        || security_fail "Telegram ${method} returned HTTP ${status}"
    jq -e '.ok == true and (.result != null)' "${RESPONSE_FILE}" >/dev/null \
        || security_fail "Telegram ${method} returned a non-success contract"
}

verify_bot_identity() {
    local actual_username
    telegram_call 'getMe'
    jq -e '.result.is_bot == true' "${RESPONSE_FILE}" >/dev/null \
        || security_fail 'Telegram token does not belong to a bot'
    actual_username="$(jq -er '.result.username' "${RESPONSE_FILE}")"
    [[ "${actual_username}" == "${TELEGRAM_BOT_USERNAME}" ]] \
        || security_fail 'Telegram bot username does not match staging configuration'
    printf 'Bot identity: verified (%s).\n' "${TELEGRAM_BOT_USERNAME}"
}

verify_webhook() {
    local expected_url="${APP_BASE_URL}/api/integrations/telegram/${TELEGRAM_BOT_CODE}/webhook"
    local actual_url pending_updates last_error_date

    telegram_call 'getWebhookInfo'
    actual_url="$(jq -er '.result.url' "${RESPONSE_FILE}")"
    [[ "${actual_url}" == "${expected_url}" ]] \
        || security_fail 'Telegram webhook URL does not match APP_BASE_URL and bot code'
    jq -e \
        '.result.allowed_updates | type == "array"' \
        "${RESPONSE_FILE}" >/dev/null \
        || security_fail 'Telegram webhook has no explicit allowed_updates'
    jq -e \
        '.result.allowed_updates | index("message") != null' \
        "${RESPONSE_FILE}" >/dev/null \
        || security_fail 'Telegram webhook does not allow message updates'
    jq -e \
        '.result.allowed_updates | index("my_chat_member") != null' \
        "${RESPONSE_FILE}" >/dev/null \
        || security_fail 'Telegram webhook does not allow my_chat_member updates'

    pending_updates="$(jq -er '.result.pending_update_count // 0' "${RESPONSE_FILE}")"
    [[ "${pending_updates}" =~ ^[0-9]+$ ]] \
        || security_fail 'Telegram returned an invalid pending update count'
    printf 'Webhook: verified; pending updates: %s.\n' "${pending_updates}"
    last_error_date="$(jq -r '.result.last_error_date // empty' "${RESPONSE_FILE}")"
    if [[ -n "${last_error_date}" ]]; then
        printf 'WARNING: Telegram reports a historical webhook error at Unix time %s.\n' \
            "${last_error_date}" >&2
    fi
}

verify_backend_boundary() {
    local readiness_status webhook_status
    local invalid_header_file="${TEMPORARY_DIRECTORY}/invalid-webhook-header"
    local probe_file="${TEMPORARY_DIRECTORY}/invalid-webhook-probe.json"

    readiness_status="$(curl --proto '=https' --connect-timeout 5 --max-time 20 \
        --max-filesize 65536 --silent --show-error \
        --output "${RESPONSE_FILE}" --write-out '%{http_code}' \
        "${APP_BASE_URL}/readyz")"
    [[ "${readiness_status}" == '200' ]] \
        || security_fail "Backend readiness returned HTTP ${readiness_status}"

    security_write_header_file 'X-Telegram-Bot-Api-Secret-Token' \
        'invalid-staging-boundary-probe' "${invalid_header_file}"
    printf '%s' '{"update_id":1}' >"${probe_file}"
    webhook_status="$(curl --proto '=https' --connect-timeout 5 --max-time 20 \
        --max-filesize 65536 --silent --show-error \
        --header 'Content-Type: application/json' \
        --header "@${invalid_header_file}" \
        --data-binary "@${probe_file}" \
        --output "${RESPONSE_FILE}" --write-out '%{http_code}' \
        "${APP_BASE_URL}/api/integrations/telegram/${TELEGRAM_BOT_CODE}/webhook")"
    [[ "${webhook_status}" == '401' ]] \
        || security_fail \
            "Webhook invalid-secret probe returned HTTP ${webhook_status}, expected 401"
    printf 'Backend readiness and webhook authentication boundary: verified.\n'
}

configure_webhook() {
    local webhook_secret request_file expected_url

    [[ "${CONFIRM_TELEGRAM_WEBHOOK_CHANGE}" == 'SET_STAGING_WEBHOOK' ]] \
        || security_fail \
            'Set CONFIRM_TELEGRAM_WEBHOOK_CHANGE=SET_STAGING_WEBHOOK to change Telegram'
    webhook_secret="$(
        read_secret 'TELEGRAM_WEBHOOK_SECRET' "${TELEGRAM_WEBHOOK_SECRET_FILE}"
    )"
    unset TELEGRAM_WEBHOOK_SECRET
    [[ "${webhook_secret}" =~ ^[A-Za-z0-9_-]{16,256}$ ]] \
        || security_fail 'TELEGRAM_WEBHOOK_SECRET is invalid'

    expected_url="${APP_BASE_URL}/api/integrations/telegram/${TELEGRAM_BOT_CODE}/webhook"
    request_file="${TEMPORARY_DIRECTORY}/set-webhook.json"
    jq -n \
        --arg url "${expected_url}" \
        --arg secret "${webhook_secret}" \
        '{url: $url, secret_token: $secret,
          allowed_updates: ["message", "my_chat_member"],
          drop_pending_updates: false}' >"${request_file}"
    chmod 600 "${request_file}"
    unset webhook_secret
    telegram_call 'setWebhook' "${request_file}"
    printf 'Webhook configuration accepted by Telegram.\n'
}

main() {
    local command="${1:-}"
    for command_name in curl jq python3 wc; do
        security_require_command "${command_name}"
    done
    validate_configuration

    TEMPORARY_DIRECTORY="$(mktemp -d)"
    RESPONSE_FILE="${TEMPORARY_DIRECTORY}/response.json"
    trap 'rm -rf -- "${TEMPORARY_DIRECTORY}"' EXIT

    BOT_TOKEN="$(read_secret 'TELEGRAM_BOT_TOKEN' "${TELEGRAM_BOT_TOKEN_FILE}")"
    unset TELEGRAM_BOT_TOKEN
    [[ "${BOT_TOKEN}" =~ ^[0-9]{6,20}:[A-Za-z0-9_-]{30,100}$ ]] \
        || security_fail 'TELEGRAM_BOT_TOKEN is invalid'

    case "${command}" in
        verify)
            verify_bot_identity
            verify_webhook
            verify_backend_boundary
            ;;
        configure)
            verify_bot_identity
            configure_webhook
            verify_webhook
            verify_backend_boundary
            ;;
        *)
            usage
            exit 2
            ;;
    esac
    printf 'Automated Telegram staging preflight completed successfully.\n'
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
