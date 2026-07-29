#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/shell-security.sh
source "${SCRIPT_DIR}/lib/shell-security.sh"

BASE_URL="${BASE_URL:-http://localhost:8080}"
APP_EMAIL="${APP_EMAIL:-}"

for command_name in curl awk python3; do
    security_require_command "$command_name"
done

BASE_URL="$(security_normalize_base_url 'https-or-loopback-http' "${BASE_URL}")"
CURL_PROTOCOL="$(security_curl_protocol "${BASE_URL}")"
readonly BASE_URL CURL_PROTOCOL
CURL_COMMON_ARGS=(
    --proto "${CURL_PROTOCOL}"
    --connect-timeout 5
    --max-time 30
    --max-filesize 65536
)
readonly -a CURL_COMMON_ARGS

temporary_directory="$(mktemp -d)"
cookie_jar="$temporary_directory/cookies.txt"
response_file="$temporary_directory/response.json"
csrf_header_file="$temporary_directory/xsrf-header"

cleanup() {
    rm -rf -- "$temporary_directory"
}
trap cleanup EXIT

if [[ -n "$APP_EMAIL" ]]; then
    app_email="$APP_EMAIL"
else
    read -r -p 'Email: ' app_email
fi
read -r -s -p 'Current password: ' current_password
printf '\n'
read -r -s -p 'New password: ' new_password
printf '\n'
read -r -s -p 'Repeat new password: ' repeated_password
printf '\n'

if [[ "$new_password" != "$repeated_password" ]]; then
    printf 'New passwords do not match.\n' >&2
    exit 1
fi
unset repeated_password

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

login_status="$(
    printf '%s\0%s' "$app_email" "$current_password" \
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
            --output "$response_file" \
            --write-out '%{http_code}' \
            "$BASE_URL/api/auth/login"
)"

if [[ "$login_status" != '200' ]]; then
    printf 'Authentication failed with HTTP %s. Response:\n' "$login_status" >&2
    security_print_bounded_response "$response_file" 8192 >&2
    exit 1
fi

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

change_status="$(
    printf '%s\0%s' "$current_password" "$new_password" \
        | python3 -c 'import json, sys; current, new = sys.stdin.buffer.read().split(b"\0", 1); print(json.dumps({"currentPassword": current.decode(), "newPassword": new.decode()}))' \
        | curl \
            "${CURL_COMMON_ARGS[@]}" \
            --silent \
            --show-error \
            --cookie "$cookie_jar" \
            --header 'Content-Type: application/json' \
            --header "@$csrf_header_file" \
            --data-binary @- \
            --output "$response_file" \
            --write-out '%{http_code}' \
            "$BASE_URL/api/auth/change-password"
)"
unset current_password new_password

if [[ "$change_status" != '204' ]]; then
    printf 'Password change failed with HTTP %s. Response:\n' "$change_status" >&2
    security_print_bounded_response "$response_file" 8192 >&2
    exit 1
fi

printf 'Password changed. Sign in again with the new password.\n'
