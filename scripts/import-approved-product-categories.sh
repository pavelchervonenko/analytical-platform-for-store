#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/shell-security.sh
source "${SCRIPT_DIR}/lib/shell-security.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
CONNECTION_KEY="${CONNECTION_KEY:-livesklad-default}"
APP_EMAIL="${APP_EMAIL:-}"
PAYLOAD_FILE="${PAYLOAD_FILE:-$PROJECT_DIR/outputs/category-review-approved/product-category-assignments-v1.json}"

for command_name in curl awk python3; do
    security_require_command "$command_name"
done

BASE_URL="$(security_normalize_base_url 'https-or-loopback-http' "${BASE_URL}")"
CURL_PROTOCOL="$(security_curl_protocol "${BASE_URL}")"
readonly BASE_URL CURL_PROTOCOL
CURL_COMMON_ARGS=(
    --proto "${CURL_PROTOCOL}"
    --connect-timeout 5
    --max-time 60
    --max-filesize 65536
)
readonly -a CURL_COMMON_ARGS

security_require_path_segment 'CONNECTION_KEY' "$CONNECTION_KEY"
security_require_readable_regular_file 'Import payload' "$PAYLOAD_FILE"

temporary_directory="$(mktemp -d)"
cookie_jar="$temporary_directory/cookies.txt"
login_response="$temporary_directory/login-response.json"
response_file="$temporary_directory/import-response.json"
csrf_header_file="$temporary_directory/xsrf-header"

cleanup() {
    rm -rf -- "$temporary_directory"
}
trap cleanup EXIT

curl \
    "${CURL_COMMON_ARGS[@]}" \
    --fail \
    --silent \
    --show-error \
    --cookie-jar "$cookie_jar" \
    "$BASE_URL/api/auth/csrf" \
    --output /dev/null

xsrf_token="$(
    awk '$0 !~ /^#/ && $6 == "XSRF-TOKEN" { value = $7 } END { print value }' "$cookie_jar"
)"
[[ -n "$xsrf_token" ]] \
    || security_fail "Could not obtain an XSRF token from ${BASE_URL}/api/auth/csrf"
security_write_header_file 'X-XSRF-TOKEN' "$xsrf_token" "$csrf_header_file"

if [[ -n "$APP_EMAIL" ]]; then
    app_email="$APP_EMAIL"
else
    read -r -p 'Administrator email: ' app_email
fi
read -r -s -p "Password for $app_email: " app_password
printf '\n'

login_status="$(
    printf '%s\0%s' "$app_email" "$app_password" \
        | python3 -c 'import json, sys; email, password = sys.stdin.buffer.read().split(b"\0", 1); print(json.dumps({"email": email.decode(), "password": password.decode()}))' \
        | curl \
            "${CURL_COMMON_ARGS[@]}" \
            --silent \
            --show-error \
            --cookie "$cookie_jar" \
            --cookie-jar "$cookie_jar" \
            --header 'Content-Type: application/json' \
            --header "@$csrf_header_file" \
            --data-binary @- \
            --output "$login_response" \
            --write-out '%{http_code}' \
            "$BASE_URL/api/auth/login"
)"
unset app_password

if [[ "$login_status" != '200' ]]; then
    printf 'Authentication failed with HTTP %s. Response:\n' "$login_status" >&2
    security_print_bounded_response "$login_response" 8192 >&2
    exit 1
fi

# Authentication rotates the CSRF token. Request a fresh cookie for the import.
curl \
    "${CURL_COMMON_ARGS[@]}" \
    --fail \
    --silent \
    --show-error \
    --cookie "$cookie_jar" \
    --cookie-jar "$cookie_jar" \
    "$BASE_URL/api/auth/csrf" \
    --output /dev/null

xsrf_token="$(
    awk '$0 !~ /^#/ && $6 == "XSRF-TOKEN" { value = $7 } END { print value }' "$cookie_jar"
)"
[[ -n "$xsrf_token" ]] \
    || security_fail 'Could not obtain the post-authentication XSRF token'
security_write_header_file 'X-XSRF-TOKEN' "$xsrf_token" "$csrf_header_file"

import_url="$BASE_URL/api/integration-connections/$CONNECTION_KEY/product-category-imports"
http_status="$(
    curl \
        "${CURL_COMMON_ARGS[@]}" \
        --silent \
        --show-error \
        --cookie "$cookie_jar" \
        --header 'Content-Type: application/json' \
        --header "@$csrf_header_file" \
        --data-binary "@$PAYLOAD_FILE" \
        --output "$response_file" \
        --write-out '%{http_code}' \
        "$import_url"
)"

if [[ "$http_status" != '200' ]]; then
    printf 'Import failed with HTTP %s. Response:\n' "$http_status" >&2
    security_print_bounded_response "$response_file" 8192 >&2
    exit 1
fi

security_print_bounded_response "$response_file" 8192
