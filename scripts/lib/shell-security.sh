#!/usr/bin/env bash

SHELL_SECURITY_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_SECURITY_HELPER="${SHELL_SECURITY_DIR}/script_security.py"

security_fail() {
    printf 'ERROR: %s\n' "$*" >&2
    exit 1
}

security_require_command() {
    command -v "$1" >/dev/null 2>&1 \
        || security_fail "Required command is not installed: $1"
}

security_normalize_base_url() {
    local policy="$1"
    local value="$2"

    python3 "${SCRIPT_SECURITY_HELPER}" validate-base-url "${policy}" "${value}"
}

security_curl_protocol() {
    local base_url="$1"

    if [[ "${base_url}" == https://* ]]; then
        printf '=https\n'
    else
        printf '=http\n'
    fi
}

security_print_bounded_response() {
    local response_file="$1"
    local limit="${2:-8192}"

    python3 "${SCRIPT_SECURITY_HELPER}" render-response \
        "${response_file}" "${limit}"
}

security_write_header_file() {
    local header_name="$1"
    local value="$2"
    local output_file="$3"

    [[ "${header_name}" =~ ^[A-Za-z0-9-]{1,64}$ ]] \
        || security_fail 'HTTP header name is invalid'
    [[ -n "${value}" && "${#value}" -le 512 ]] \
        || security_fail "${header_name} value is empty or overlong"
    [[ "${value}" != *$'\r'* && "${value}" != *$'\n'* ]] \
        || security_fail "${header_name} value contains CR or LF"

    printf '%s: %s\n' "${header_name}" "${value}" >"${output_file}"
    chmod 600 "${output_file}"
}

security_require_path_segment() {
    local name="$1"
    local value="$2"

    if [[ ! "${value}" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]]; then
        security_fail "${name} must be a 1-64 character safe path segment"
    fi
}

security_require_readable_regular_file() {
    local name="$1"
    local path="$2"

    [[ -f "${path}" && -r "${path}" ]] \
        || security_fail "${name} is not a readable regular file: ${path}"
}
