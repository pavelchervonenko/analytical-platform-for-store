#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly BASE_URL="${BASE_URL:-https://store-analytics.net}"
readonly ADMIN_LOGIN="${ADMIN_LOGIN:-pavel.chervonenko.97@gmail.com}"
readonly ADMIN_PASSWORD_FILE="${ADMIN_PASSWORD_FILE:-/etc/store-analytics/secrets/admin-current-password}"
readonly ACCOUNT_DOMAIN="${ACCOUNT_DOMAIN:-store-analytics.net}"
readonly SECRET_DIRECTORY="${SECRET_DIRECTORY:-/etc/store-analytics/secrets}"
readonly MANIFEST_FILE="${MANIFEST_FILE:-/var/lib/store-analytics/release-state/pilot-manager-accounts.json}"

[[ "$(id -u)" -eq 0 ]] || { printf 'Manager provisioning must run as root\n' >&2; exit 1; }
[[ -s "${ADMIN_PASSWORD_FILE}" ]] || { printf 'Missing administrator password file\n' >&2; exit 1; }

work_dir="$(mktemp -d)"
trap 'rm -rf -- "${work_dir}"' EXIT
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
  --arg password "$(<"${ADMIN_PASSWORD_FILE}")" \
  '{email:$email,password:$password}' >"${work_dir}/login.json"
curl --fail-with-body --silent --show-error \
  --cookie "${cookie_jar}" --cookie-jar "${cookie_jar}" \
  -H "X-XSRF-TOKEN: ${csrf_token}" \
  -H 'Content-Type: application/json' \
  --data-binary "@${work_dir}/login.json" \
  "${BASE_URL}/api/auth/login" >/dev/null

curl --fail-with-body --silent --show-error \
  --cookie "${cookie_jar}" --cookie-jar "${cookie_jar}" \
  "${BASE_URL}/api/stores" >"${work_dir}/stores.json"
jq -e 'type == "array" and length == 2' "${work_dir}/stores.json" >/dev/null || {
  printf 'Expected exactly two active stores before manager provisioning\n' >&2
  exit 1
}
jq '[.[].id]' "${work_dir}/stores.json" >"${work_dir}/store-ids.json"
jq -n '{accounts: []}' >"${work_dir}/manifest.json"

for number in 1 2 3; do
  email="manager${number}@${ACCOUNT_DOMAIN}"
  display_name="Руководитель ${number}"
  password_file="${SECRET_DIRECTORY}/manager${number}-initial-password"
  response_file="${work_dir}/manager${number}.json"
  [[ -s "${password_file}" ]] || {
    printf 'SA!%sz9' "$(openssl rand -hex 20)" >"${password_file}"
    chmod 0600 "${password_file}"
  }

  fetch_csrf
  jq -n \
    --arg email "${email}" \
    --arg password "$(<"${password_file}")" \
    --arg displayName "${display_name}" \
    --slurpfile storeIds "${work_dir}/store-ids.json" \
    '{email:$email,temporaryPassword:$password,displayName:$displayName,
      role:"MANAGER",storeIds:$storeIds[0]}' >"${work_dir}/request.json"
  curl --fail-with-body --silent --show-error \
    --cookie "${cookie_jar}" --cookie-jar "${cookie_jar}" \
    -H "X-XSRF-TOKEN: ${csrf_token}" \
    -H 'Content-Type: application/json' \
    --data-binary "@${work_dir}/request.json" \
    "${BASE_URL}/api/admin/users" >"${response_file}"
  jq --slurpfile account "${response_file}" \
    '.accounts += [($account[0] | {id,email,displayName,role,storeIds})]' \
    "${work_dir}/manifest.json" >"${work_dir}/manifest.next.json"
  mv "${work_dir}/manifest.next.json" "${work_dir}/manifest.json"
  printf 'Created manager account: %s\n' "${email}"
done

install -o root -g root -m 0600 "${work_dir}/manifest.json" "${MANIFEST_FILE}"
printf 'Three manager accounts created for both active stores.\n'
