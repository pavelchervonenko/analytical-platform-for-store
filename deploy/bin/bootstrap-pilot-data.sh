#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly BASE_URL="${BASE_URL:-https://store-analytics.net}"
readonly ADMIN_LOGIN="${ADMIN_LOGIN:-pavel.chervonenko.97@gmail.com}"
readonly ADMIN_PASSWORD_FILE="${ADMIN_PASSWORD_FILE:-/etc/store-analytics/secrets/admin-current-password}"
readonly CLASSIFICATION_FILE="${CLASSIFICATION_FILE:-/home/pavel/product-category-assignments-v2.json}"
readonly PERIOD_START="${PERIOD_START:-2026-07-01}"
readonly PERIOD_END_INCLUSIVE="${PERIOD_END_INCLUSIVE:-$(TZ=Europe/Kaliningrad date -d yesterday +%F)}"
readonly STATE_FILE="${STATE_FILE:-/var/lib/store-analytics/release-state/initial-backfill-job.json}"

[[ "$(id -u)" -eq 0 ]] || { printf 'Pilot bootstrap must run as root\n' >&2; exit 1; }
[[ -s "${ADMIN_PASSWORD_FILE}" ]] || { printf 'Missing admin password file\n' >&2; exit 1; }
[[ -s "${CLASSIFICATION_FILE}" ]] || { printf 'Missing classification file\n' >&2; exit 1; }

work_dir="$(mktemp -d)"
cleanup() { rm -rf -- "${work_dir}"; }
trap cleanup EXIT

cookie_jar="${work_dir}/cookies"
response_file="${work_dir}/response.json"

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
  --arg password "$(< "${ADMIN_PASSWORD_FILE}")" \
  '{email:$email,password:$password}' >"${work_dir}/login.json"
curl --fail-with-body --silent --show-error \
  --cookie "${cookie_jar}" --cookie-jar "${cookie_jar}" \
  -H "X-XSRF-TOKEN: ${csrf_token}" \
  -H 'Content-Type: application/json' \
  --data-binary "@${work_dir}/login.json" \
  "${BASE_URL}/api/auth/login" >"${response_file}"
printf 'Authenticated as %s with role %s\n' \
  "$(jq -r '.email' "${response_file}")" \
  "$(jq -r '.role' "${response_file}")"

fetch_csrf
curl --fail-with-body --silent --show-error \
  --cookie "${cookie_jar}" --cookie-jar "${cookie_jar}" \
  -H "X-XSRF-TOKEN: ${csrf_token}" \
  -H 'Content-Type: application/json' \
  --data-binary "@${CLASSIFICATION_FILE}" \
  "${BASE_URL}/api/integration-connections/livesklad-default/product-category-imports" \
  >"${response_file}"
jq -c '{requested,productsCreated,assignmentsCreated,assignmentsUnchanged}' "${response_file}"

curl --fail-with-body --silent --show-error \
  --cookie "${cookie_jar}" --cookie-jar "${cookie_jar}" \
  "${BASE_URL}/api/sync/jobs/backfill-readiness?periodStart=${PERIOD_START}" \
  >"${response_file}"
jq -c . "${response_file}"
[[ "$(jq -r '.ready' "${response_file}")" == 'true' ]] \
  || { printf 'Classification readiness is false; backfill not started\n' >&2; exit 1; }

jq -n \
  --arg periodStart "${PERIOD_START}" \
  --arg periodEndInclusive "${PERIOD_END_INCLUSIVE}" \
  '{periodStart:$periodStart,periodEndInclusive:$periodEndInclusive}' \
  >"${work_dir}/backfill.json"
curl --fail-with-body --silent --show-error \
  --cookie "${cookie_jar}" --cookie-jar "${cookie_jar}" \
  -H "X-XSRF-TOKEN: ${csrf_token}" \
  -H 'Content-Type: application/json' \
  --data-binary "@${work_dir}/backfill.json" \
  "${BASE_URL}/api/sync/jobs/backfill" >"${response_file}"

install -o root -g root -m 0600 "${response_file}" "${STATE_FILE}"
jq -c '{id,status,phase,periodStart,periodEnd,completedSteps}' "${STATE_FILE}"
