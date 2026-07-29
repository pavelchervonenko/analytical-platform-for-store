#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
ENV_FILE="${ENV_FILE:-${PROJECT_ROOT}/.env}"
# shellcheck source=../lib/shell-security.sh
source "${PROJECT_ROOT}/scripts/lib/shell-security.sh"

fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || fail "Required command is not installed: $1"
}

require_variable() {
    local name="$1"
    [[ -n "${!name:-}" ]] || fail "Required environment variable is not configured: ${name}"
    [[ "${!name}" != *$'\r'* ]] || fail "${name} contains CR; save dotenv with LF line endings"
}

load_livesklad_environment() {
    require_command curl
    require_command jq
    require_command python3
    [[ -f "${ENV_FILE}" && -r "${ENV_FILE}" ]] \
        || fail "Environment file is not a readable regular file: ${ENV_FILE}"

    local variable_name
    local variable_value
    local parser_complete='false'
    while IFS= read -r -d '' variable_name \
        && IFS= read -r -d '' variable_value; do
        if [[ "${variable_name}" == '__STRICT_DOTENV_COMPLETE__' ]]; then
            parser_complete="${variable_value}"
            continue
        fi
        printf -v "${variable_name}" '%s' "${variable_value}"
        export "${variable_name}"
    done < <(
        python3 "${SCRIPT_SECURITY_HELPER}" parse-dotenv "${ENV_FILE}" \
            LIVESKLAD_BASE_URL LIVESKLAD_LOGIN LIVESKLAD_PASSWORD
    )
    [[ "${parser_complete}" == 'true' ]] \
        || fail 'Environment file failed strict dotenv validation'

    require_variable LIVESKLAD_BASE_URL
    require_variable LIVESKLAD_LOGIN
    require_variable LIVESKLAD_PASSWORD

    LIVESKLAD_BASE_URL="$(security_normalize_base_url 'https-only' "${LIVESKLAD_BASE_URL}")" \
        || fail 'LIVESKLAD_BASE_URL failed strict validation'
    DISCOVERY_MAX_RESPONSE_BYTES="${DISCOVERY_MAX_RESPONSE_BYTES:-1048576}"
    [[ "${DISCOVERY_MAX_RESPONSE_BYTES}" =~ ^[1-9][0-9]*$ ]] \
        || fail 'DISCOVERY_MAX_RESPONSE_BYTES must be a positive integer'
    ((DISCOVERY_MAX_RESPONSE_BYTES <= 8388608)) \
        || fail 'DISCOVERY_MAX_RESPONSE_BYTES must not exceed 8388608'
}

livesklad_auth_response() {
    jq -jrn '
        "login=" + (env.LIVESKLAD_LOGIN | @uri)
        + "&password=" + (env.LIVESKLAD_PASSWORD | @uri)
    ' | curl \
        --fail-with-body \
        --silent \
        --show-error \
        --connect-timeout 5 \
        --max-time 30 \
        --max-filesize "${DISCOVERY_MAX_RESPONSE_BYTES}" \
        --proto '=https' \
        --request POST \
        --header 'Content-Type: application/x-www-form-urlencoded' \
        --data-binary @- \
        "${LIVESKLAD_BASE_URL}/auth"
}

livesklad_access_token() {
    local response

    response="$(livesklad_auth_response)" || return 1
    jq -er '.token | select(type == "string" and length > 0)' <<<"${response}"
    unset response
}

livesklad_get() {
    local token="$1"
    local path="$2"
    shift 2

    [[ "${path}" == /* ]] || fail "LiveSklad API path must start with /"

    printf 'Authorization: %s\n' "${token}" | curl \
        --fail-with-body \
        --silent \
        --show-error \
        --connect-timeout 5 \
        --max-time 30 \
        --max-filesize "${DISCOVERY_MAX_RESPONSE_BYTES}" \
        --proto '=https' \
        --header @- \
        "$@" \
        "${LIVESKLAD_BASE_URL}${path}"
}

livesklad_get_paginated() {
    local token="$1"
    local path="$2"
    shift 2

    local page=1
    local page_size="${DISCOVERY_PAGE_SIZE:-50}"
    local max_pages="${DISCOVERY_MAX_PAGES:-5}"
    local response=''
    local batch_count=0
    local reported_total='null'
    local response_fields='[]'
    local remain_request='null'
    local expire_date='null'
    local data_file=''
    local output_status=0

    [[ "${page_size}" =~ ^[1-9][0-9]*$ ]] || fail "DISCOVERY_PAGE_SIZE must be a positive integer"
    [[ "${max_pages}" =~ ^[1-9][0-9]*$ ]] || fail "DISCOVERY_MAX_PAGES must be a positive integer"

    data_file="$(mktemp)" || fail "Could not create a temporary pagination file"

    while ((page <= max_pages)); do
        response="$(livesklad_get "${token}" "${path}" \
            --get \
            "$@" \
            --data-urlencode "page=${page}" \
            --data-urlencode "pageSize=${page_size}")" || {
                rm -f -- "${data_file}"
                return 1
            }

        if ! jq -e '.data | type == "array"' >/dev/null <<<"${response}"; then
            rm -f -- "${data_file}"
            printf 'ERROR: Paginated response for %s does not contain a data array\n' "${path}" >&2
            return 1
        fi

        batch_count="$(jq '.data | length' <<<"${response}")"
        if ! jq -c '.data[]' <<<"${response}" >>"${data_file}"; then
            rm -f -- "${data_file}"
            return 1
        fi

        if ((page == 1)); then
            reported_total="$(jq -c '.total // null' <<<"${response}")"
            response_fields="$(jq -c 'keys | sort' <<<"${response}")"
        fi

        remain_request="$(jq -c '.remainRequest // null' <<<"${response}")"
        expire_date="$(jq -c '.expireDate // null' <<<"${response}")"

        if ((batch_count < page_size)); then
            jq -s \
                --argjson pagesFetched "${page}" \
                --argjson reportedTotal "${reported_total}" \
                --argjson responseFields "${response_fields}" \
                --argjson remainRequest "${remain_request}" \
                --argjson expireDate "${expire_date}" \
                '{
                    data: .,
                    pagesFetched: $pagesFetched,
                    reportedTotal: $reportedTotal,
                    responseFields: $responseFields,
                    remainRequest: $remainRequest,
                    expireDate: $expireDate
                }' "${data_file}"
            output_status=$?
            rm -f -- "${data_file}"
            return "${output_status}"
        fi

        ((page += 1))
    done

    rm -f -- "${data_file}"
    fail "Discovery page limit reached for ${path}; increase DISCOVERY_MAX_PAGES deliberately"
}
