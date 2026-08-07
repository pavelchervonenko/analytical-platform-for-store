#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly BASE_URL="${BASE_URL:-https://store-analytics.net}"
readonly ADMIN_LOGIN="${ADMIN_LOGIN:-pavel.chervonenko.97@gmail.com}"
readonly INITIAL_PASSWORD_FILE="${INITIAL_PASSWORD_FILE:-/etc/store-analytics/secrets/bootstrap-admin-password}"
readonly CURRENT_PASSWORD_FILE="${CURRENT_PASSWORD_FILE:-/etc/store-analytics/secrets/admin-current-password}"

[[ "$(id -u)" -eq 0 ]] || { printf 'Admin activation must run as root\n' >&2; exit 1; }
[[ -s "${INITIAL_PASSWORD_FILE}" ]] || { printf 'Missing initial password\n' >&2; exit 1; }

if [[ ! -s "${CURRENT_PASSWORD_FILE}" ]]; then
  printf 'SA!%sz9' "$(openssl rand -hex 20)" >"${CURRENT_PASSWORD_FILE}"
  chmod 0600 "${CURRENT_PASSWORD_FILE}"
fi

work_dir="$(mktemp -d)"
cleanup() { rm -rf -- "${work_dir}"; }
trap cleanup EXIT
cookie_jar="${work_dir}/cookies"

fetch_csrf() {
  curl --fail --silent --show-error \
    --cookie "${cookie_jar}" --cookie-jar "${cookie_jar}" \
    "${BASE_URL}/api/auth/csrf" >/dev/null
  csrf_token="$(awk '$6 == "XSRF-TOKEN" { token=$7 } END { print token }' "${cookie_jar}")"
  [[ -n "${csrf_token}" ]] || { printf 'CSRF cookie was not issued\n' >&2; exit 1; }
}

fetch_csrf
jq -n \
  --arg email "${ADMIN_LOGIN}" \
  --arg password "$(< "${INITIAL_PASSWORD_FILE}")" \
  '{email:$email,password:$password}' >"${work_dir}/login.json"
curl --fail-with-body --silent --show-error \
  --cookie "${cookie_jar}" --cookie-jar "${cookie_jar}" \
  -H "X-XSRF-TOKEN: ${csrf_token}" \
  -H 'Content-Type: application/json' \
  --data-binary "@${work_dir}/login.json" \
  "${BASE_URL}/api/auth/login" >"${work_dir}/login-response.json"
[[ "$(jq -r '.passwordChangeRequired' "${work_dir}/login-response.json")" == 'true' ]] \
  || { printf 'Bootstrap administrator is already activated\n'; exit 0; }

fetch_csrf
jq -n \
  --arg currentPassword "$(< "${INITIAL_PASSWORD_FILE}")" \
  --arg newPassword "$(< "${CURRENT_PASSWORD_FILE}")" \
  '{currentPassword:$currentPassword,newPassword:$newPassword}' \
  >"${work_dir}/change-password.json"
curl --fail-with-body --silent --show-error \
  --cookie "${cookie_jar}" --cookie-jar "${cookie_jar}" \
  -H "X-XSRF-TOKEN: ${csrf_token}" \
  -H 'Content-Type: application/json' \
  --data-binary "@${work_dir}/change-password.json" \
  "${BASE_URL}/api/auth/change-password" >/dev/null

# Retire the one-time bootstrap credential while retaining a non-empty Docker secret.
openssl rand -base64 48 | tr -d '\n' >"${INITIAL_PASSWORD_FILE}"
chmod 0600 "${INITIAL_PASSWORD_FILE}"

printf 'Bootstrap administrator activated; permanent password stored in %s\n' \
  "${CURRENT_PASSWORD_FILE}"
