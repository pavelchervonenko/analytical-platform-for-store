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
  postgres-backup-password \
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
  printf 'RELEASE_COMMIT=cccccccccccccccccccccccccccccccccccccccc\n'
  printf 'BACKEND_IMAGE=ghcr.io/test/store-analytics-backend@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n'
  printf 'WEB_IMAGE=ghcr.io/test/store-analytics-web@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n'
  printf 'BACKEND_IMAGE_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\n'
  printf 'WEB_IMAGE_DIGEST=sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n'
  printf 'SCHEMA_VERSION=42\n'
  printf 'MIGRATION_SOURCE_MIN_VERSION=39\n'
  printf 'RUNTIME_SCHEMA_MIN_VERSION=42\n'
  printf 'RUNTIME_SCHEMA_MAX_VERSION=42\n'
  printf 'DB_CERT_HOST=database.example.test\n'
  printf 'DB_HOST_ADDRESS=10.20.0.20\n'
  printf 'DB_PORT=5432\n'
  printf 'DB_NAME=store_analytics\n'
  printf 'DB_APP_SCHEMA=app\n'
  printf 'DB_RUNTIME_USER=store_runtime\n'
  printf 'DB_MIGRATOR_USER=store_migrator\n'
  printf 'DB_BACKUP_USER=store_backup_reader\n'
  printf 'POSTGRES_RUNTIME_PASSWORD_FILE=%s/postgres-runtime-password\n' "${temporary_directory}"
  printf 'POSTGRES_MIGRATOR_PASSWORD_FILE=%s/postgres-migrator-password\n' "${temporary_directory}"
  printf 'POSTGRES_BACKUP_PASSWORD_FILE=%s/postgres-backup-password\n' "${temporary_directory}"
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

invalid_release_env="${temporary_directory}/invalid-release.env"
cp "${release_env}" "${invalid_release_env}"
sed -i \
  's/^WEB_IMAGE_DIGEST=.*/WEB_IMAGE_DIGEST=sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/' \
  "${invalid_release_env}"
if release_validate_release_identity "${invalid_release_env}" >/dev/null 2>&1; then
  fail_test 'web image digest mismatch was accepted'
fi
cp "${release_env}" "${invalid_release_env}"
sed -i 's/^DB_BACKUP_USER=.*/DB_BACKUP_USER=store_runtime/' \
  "${invalid_release_env}"
if release_validate_database_target "${invalid_release_env}" >/dev/null 2>&1; then
  fail_test 'overlapping database roles were accepted'
fi

printf '%s\n' \
  'INTERPRETATION_GENERATION_ENABLED=true' \
  'LLM_PROMPT_VERSION=weekly-interpretation-v21' \
  'LLM_CONTENT_SCHEMA_VERSION=3' \
  >>"${release_env}"
release_validate_llm_configuration "${release_env}" \
  || fail_test 'valid v21/schema3 generation configuration was rejected'
sed -i '$d' "${release_env}"
printf '%s\n' 'LLM_CONTENT_SCHEMA_VERSION=2' >>"${release_env}"
if release_validate_llm_configuration "${release_env}" >/dev/null 2>&1; then
  fail_test 'mismatched v21/schema2 generation configuration was accepted'
fi
sed -i '$d' "${release_env}"
sed -i '$d' "${release_env}"
if release_validate_llm_configuration "${release_env}" >/dev/null 2>&1; then
  fail_test 'enabled generation without an explicit prompt version was accepted'
fi
sed -i '$d' "${release_env}"

release_schema_allows_migration_source "${release_env}" 39 \
  || fail_test 'declared migration source was rejected'
release_schema_allows_migration_source "${release_env}" 39.1 \
  || fail_test 'production Flyway 39.1 migration source was rejected'
if release_schema_allows_migration_source "${release_env}" 38; then
  fail_test 'unsupported migration source was accepted'
fi
if release_schema_allows_migration_source "${release_env}" 38.99; then
  fail_test 'unsupported dotted migration source was accepted'
fi
if release_schema_allows_migration_source "${release_env}" 42.1; then
  fail_test 'migration source newer than target was accepted'
fi
release_schema_allows_runtime "${release_env}" 42 \
  || fail_test 'declared runtime schema was rejected'
release_schema_allows_runtime "${release_env}" 42.0 \
  || fail_test 'equivalent dotted runtime schema was rejected'
if release_schema_allows_runtime "${release_env}" 43; then
  fail_test 'incompatible runtime schema was accepted'
fi

printf '%s\n' 'PRODUCT_CLASSIFICATION_RECONCILIATION_ENABLED=true' \
  >>"${release_env}"
if release_validate_product_classification_reconciliation "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'reconciliation without an approved scope was accepted'
fi
printf '%s\n' \
  'PRODUCT_CLASSIFICATION_RECONCILIATION_PRODUCT_IDS=product-a,product-b' \
  'PRODUCT_CLASSIFICATION_RECONCILIATION_EXPECTED_ITEMS=3' \
  >>"${release_env}"
release_validate_product_classification_reconciliation "${release_env}" \
  || fail_test 'valid reconciliation scope was rejected'
sed -i '$d' "${release_env}"
sed -i '$d' "${release_env}"
sed -i '$d' "${release_env}"
printf '%s\n' \
  'PRODUCT_CLASSIFICATION_RECONCILIATION_ENABLED=false' \
  'PRODUCT_CLASSIFICATION_RECONCILIATION_PRODUCT_IDS=stale-product' \
  >>"${release_env}"
if release_validate_product_classification_reconciliation "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'disabled reconciliation with a stale scope was accepted'
fi
sed -i '$d' "${release_env}"
sed -i '$d' "${release_env}"

printf '%s\n' \
  'LIVESKLAD_ORDER_RETURN_WEBHOOK_WORKER_ENABLED=true' \
  >>"${release_env}"
if release_validate_livesklad_webhook_processing "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'order-return processing without its recovery path was accepted'
fi
printf '%s\n' \
  'LIVESKLAD_WEBHOOK_ENABLED=true' \
  'LIVESKLAD_WEBHOOK_WORKER_ENABLED=true' \
  'SYNC_WORKER_ENABLED=true' \
  'SYNC_SCHEDULE_ENABLED=true' \
  'SYNC_INCREMENTAL_OVERLAP_DAYS=1' \
  >>"${release_env}"
if release_validate_livesklad_webhook_processing "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'order-return processing with insufficient overlap was accepted'
fi
sed -i '$d' "${release_env}"
printf '%s\n' 'SYNC_INCREMENTAL_OVERLAP_DAYS=3' >>"${release_env}"
release_validate_livesklad_webhook_processing "${release_env}" \
  || fail_test 'order-return processing with daily recovery was rejected'
for ignored in 1 2 3 4 5 6; do
  sed -i '$d' "${release_env}"
done

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
stop_worker_line="$(grep -n 'compose stop -t 90 backend-worker' "${deploy_script}" | cut -d: -f1)"
stop_api_line="$(grep -n 'compose stop -t 60 backend-api' "${deploy_script}" | cut -d: -f1)"
marker_line="$(grep -n 'MIGRATION_IN_PROGRESS' "${deploy_script}" | cut -d: -f1)"
migration_line="$(grep -n 'Applying database migrations' "${deploy_script}" | cut -d: -f1)"
api_line="$(grep -n 'Starting backend API before' "${deploy_script}" | cut -d: -f1)"
worker_line="$(grep -n 'Starting background worker after API readiness' "${deploy_script}" | cut -d: -f1)"
(( preflight_line < stop_worker_line && stop_worker_line < stop_api_line \
    && stop_api_line < marker_line && marker_line < migration_line \
    && migration_line < api_line && api_line < worker_line )) \
  || fail_test 'deploy safety operations are ordered incorrectly'
grep -F 'release_verify_local_image_provenance' "${deploy_script}" >/dev/null \
  || fail_test 'deploy does not verify pulled image provenance'
grep -F 'release_schema_allows_migration_source' "${deploy_script}" >/dev/null \
  || fail_test 'deploy does not enforce its recorded migration source schema'
grep -F 'release_schema_allows_runtime' "${rollback_script}" >/dev/null \
  || fail_test 'rollback does not enforce runtime schema compatibility'
grep -F 'use forward-fix.sh' "${rollback_script}" >/dev/null \
  || fail_test 'rollback does not direct incompatible releases to forward-fix'
grep -F 'release_schema_allows_migration_source' "${forward_fix_script}" >/dev/null \
  || fail_test 'forward-fix does not validate its source schema'

acl_script="${PROJECT_ROOT}/deploy/bin/repair-production-database-acls.sh"
grep -F 'release_validate_env_file "${RELEASE_ENV}"' "${acl_script}" >/dev/null \
  || fail_test 'ACL repair does not validate the exact release env'
grep -F 'DB_APP_SCHEMA' "${acl_script}" >/dev/null \
  || fail_test 'ACL repair does not use the declared application schema'
if grep -Eq 'managed-631415|10\.20\.0\.20|store_runtime|store_backup_reader' \
    "${acl_script}"; then
  fail_test 'ACL repair still contains infrastructure or role defaults'
fi

printf 'Deploy release safety tests passed.\n'
