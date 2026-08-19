#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(cd -- "${TEST_DIR}/../.." && pwd)"

# shellcheck source=../../deploy/bin/release-safety.sh
source "${PROJECT_ROOT}/deploy/bin/release-safety.sh"

fail_test() {
  printf 'DEPLOY RELEASE SAFETY TEST FAILED: %s\n' "$*" >&2
  exit 1
}

temporary_directory="$(mktemp -d)"
cleanup() {
  rm -rf -- "${temporary_directory}"
}
trap cleanup EXIT

secret_value='0123456789abcdef0123456789abcdef0123456789abcdef'
for secret_name in \
  postgres-runtime-password \
  postgres-migrator-password \
  livesklad-login \
  livesklad-password \
  livesklad-sale-return-webhook-secret \
  livesklad-order-return-webhook-secret \
  yandex-ai-api-key \
  telegram-bot-token \
  telegram-webhook-secret \
  security-telemetry-pseudonym-key \
  prometheus-scrape-token \
  bootstrap-admin-password; do
  printf '%s' "${secret_value}" >"${temporary_directory}/${secret_name}"
  chmod 0600 "${temporary_directory}/${secret_name}"
done
printf '%s\n' 'test-ca' >"${temporary_directory}/postgresql-ca.crt"
chmod 0644 "${temporary_directory}/postgresql-ca.crt"

release_env="${temporary_directory}/release.env"
{
  printf 'RELEASE_ID=test-release\n'
  printf 'SCHEMA_VERSION=42\n'
  printf 'MIGRATION_SOURCE_MIN_VERSION=39\n'
  printf 'RUNTIME_SCHEMA_MIN_VERSION=42\n'
  printf 'RUNTIME_SCHEMA_MAX_VERSION=42\n'
  printf 'POSTGRES_RUNTIME_PASSWORD_FILE=%s/postgres-runtime-password\n' "${temporary_directory}"
  printf 'POSTGRES_MIGRATOR_PASSWORD_FILE=%s/postgres-migrator-password\n' "${temporary_directory}"
  printf 'LIVESKLAD_LOGIN_FILE=%s/livesklad-login\n' "${temporary_directory}"
  printf 'LIVESKLAD_PASSWORD_FILE=%s/livesklad-password\n' "${temporary_directory}"
  printf 'LIVESKLAD_SALE_RETURN_WEBHOOK_SECRET_FILE=%s/livesklad-sale-return-webhook-secret\n' "${temporary_directory}"
  printf 'LIVESKLAD_ORDER_RETURN_WEBHOOK_SECRET_FILE=%s/livesklad-order-return-webhook-secret\n' "${temporary_directory}"
  printf 'YANDEX_AI_API_KEY_FILE=%s/yandex-ai-api-key\n' "${temporary_directory}"
  printf 'TELEGRAM_BOT_TOKEN_FILE=%s/telegram-bot-token\n' "${temporary_directory}"
  printf 'TELEGRAM_WEBHOOK_SECRET_FILE=%s/telegram-webhook-secret\n' "${temporary_directory}"
  printf 'SECURITY_TELEMETRY_PSEUDONYM_KEY_FILE=%s/security-telemetry-pseudonym-key\n' "${temporary_directory}"
  printf 'PROMETHEUS_SCRAPE_TOKEN_FILE=%s/prometheus-scrape-token\n' "${temporary_directory}"
  printf 'BOOTSTRAP_ADMIN_PASSWORD_FILE=%s/bootstrap-admin-password\n' "${temporary_directory}"
  printf 'POSTGRES_CA_FILE=%s/postgresql-ca.crt\n' "${temporary_directory}"
} >"${release_env}"
chmod 0600 "${release_env}"

export RELEASE_EXPECTED_SECRET_UID
RELEASE_EXPECTED_SECRET_UID="$(id -u)"
release_validate_env_file "${release_env}" \
  || fail_test 'valid release fixture was rejected'
release_schema_allows_migration_source "${release_env}" 39 \
  || fail_test 'declared migration source was rejected'
if release_schema_allows_migration_source "${release_env}" 38; then
  fail_test 'unsupported migration source was accepted'
fi
release_schema_allows_runtime "${release_env}" 42 \
  || fail_test 'declared runtime schema was rejected'
if release_schema_allows_runtime "${release_env}" 43; then
  fail_test 'incompatible runtime schema was accepted'
fi

printf '%s' 'short' \
  >"${temporary_directory}/livesklad-order-return-webhook-secret"
if release_validate_secret_files "${release_env}" >/dev/null 2>&1; then
  fail_test 'short webhook secret was accepted'
fi
printf '%s' "${secret_value}" \
  >"${temporary_directory}/livesklad-order-return-webhook-secret"
printf '%s\n' \
  "LIVESKLAD_ORDER_RETURN_WEBHOOK_SECRET_FILE=${temporary_directory}/livesklad-order-return-webhook-secret" \
  >>"${release_env}"
if release_validate_secret_files "${release_env}" >/dev/null 2>&1; then
  fail_test 'duplicate webhook secret path was accepted'
fi
sed -i '$d' "${release_env}"

deploy_script="${PROJECT_ROOT}/deploy/bin/deploy.sh"
rollback_script="${PROJECT_ROOT}/deploy/bin/rollback.sh"
forward_fix_script="${PROJECT_ROOT}/deploy/bin/forward-fix.sh"
preflight_line="$(grep -n 'preflight-release.sh' "${deploy_script}" | head -1 | cut -d: -f1)"
marker_line="$(grep -n 'MIGRATION_IN_PROGRESS' "${deploy_script}" | cut -d: -f1)"
migration_line="$(grep -n 'Applying database migrations' "${deploy_script}" | cut -d: -f1)"
api_line="$(grep -n 'Starting backend API before' "${deploy_script}" | cut -d: -f1)"
worker_line="$(grep -n 'Starting background worker after API readiness' "${deploy_script}" | cut -d: -f1)"
(( preflight_line < marker_line && marker_line < migration_line \
    && migration_line < api_line && api_line < worker_line )) \
  || fail_test 'deploy safety operations are ordered incorrectly'
grep -F 'release_schema_allows_migration_source' "${deploy_script}" >/dev/null \
  || fail_test 'deploy does not enforce its recorded migration source schema'
grep -F 'release_schema_allows_runtime' "${rollback_script}" >/dev/null \
  || fail_test 'rollback does not enforce runtime schema compatibility'
grep -F 'use forward-fix.sh' "${rollback_script}" >/dev/null \
  || fail_test 'rollback does not direct incompatible releases to forward-fix'
grep -F 'release_schema_allows_migration_source' "${forward_fix_script}" >/dev/null \
  || fail_test 'forward-fix does not validate its source schema'

printf 'Deploy release safety tests passed.\n'
