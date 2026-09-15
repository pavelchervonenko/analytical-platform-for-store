#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(cd -- "${TEST_DIR}/../.." && pwd)"
readonly RUNNER="${PROJECT_ROOT}/scripts/reconciliation/run-bounded-classification-correction.sh"
readonly SQL_FILE="${PROJECT_ROOT}/scripts/reconciliation/sql/bounded-classification-correction.sql"
readonly INSTALL_HOST="${PROJECT_ROOT}/deploy/bin/install-host.sh"
readonly DEPLOY="${PROJECT_ROOT}/deploy/bin/deploy.sh"

fail_test() {
  printf 'BOUNDED CLASSIFICATION TEST FAILED: %s\n' "$*" >&2
  exit 1
}

bash -n "${PROJECT_ROOT}"/scripts/reconciliation/*.sh
python3 -m unittest scripts/tests/test_bounded_classification_manifests.py

for required_fragment in \
  'set +x' \
  'umask 077' \
  'release_validate_env_file "${RELEASE_ENV}"' \
  'DB_BACKUP_USER' \
  'DB_MIGRATOR_USER' \
  'CONFIRM_BOUNDED_CLASSIFICATION_APPLY' \
  'CORRECTION_EXPECTED_MANIFEST_SHA256' \
  'CORRECTION_EXPECTED_RELEASE_COMMIT' \
  'CORRECTION_APPROVAL_REF' \
  '--set=ON_ERROR_STOP=1' \
  'set +x'; do
  grep -F -- "${required_fragment}" "${RUNNER}" >/dev/null \
    || fail_test "runner is missing safety fragment: ${required_fragment}"
done

for required_fragment in \
  'BEGIN ISOLATION LEVEL SERIALIZABLE' \
  "SET LOCAL lock_timeout = '5s'" \
  "SET LOCAL statement_timeout = '90s'" \
  'pg_try_advisory_xact_lock' \
  'dynamic payroll categories are not supported by this correction' \
  'product-level assignment would affect an unlisted item' \
  'approved or immutable payroll/report data overlaps the manifest' \
  'an active sync, recovery, webhook or report job blocks correction' \
  'ANALYTICS_PRODUCT_CLASSIFIED' \
  'PAYROLL_PRODUCT_CLASSIFIED' \
  'post-apply verification failed; transaction will roll back' \
  '\if :do_apply' \
  'ROLLBACK;'; do
  grep -F -- "${required_fragment}" "${SQL_FILE}" >/dev/null \
    || fail_test "SQL program is missing safety fragment: ${required_fragment}"
done

if grep -Eq '92\.53\.127\.24|managed-[0-9]+|store_runtime|store_migrator|store_backup_reader' \
    "${RUNNER}" "${SQL_FILE}"; then
  fail_test 'correction tooling contains an infrastructure or database-role default'
fi

for required_fragment in \
  'CORRECTION_SOURCE_DIR="${PROJECT_DIR}/scripts/reconciliation"' \
  'CORRECTION_INSTALL_DIR="${INSTALL_DIR}/corrections"' \
  '"${CORRECTION_SOURCE_DIR}"/*.sh "${CORRECTION_INSTALL_DIR}/"' \
  '"${CORRECTION_SOURCE_DIR}"/manifests/*.json' \
  '"${CORRECTION_SOURCE_DIR}"/sql/*.sql'; do
  grep -F -- "${required_fragment}" "${INSTALL_HOST}" >/dev/null \
    || fail_test "host installer is missing correction artifact: ${required_fragment}"
done

if grep -Eq 'bounded-classification|run-bounded-classification-correction' "${DEPLOY}"; then
  fail_test 'ordinary deploy must not execute a classification correction'
fi

if "${RUNNER}" >/dev/null 2>&1; then
  fail_test 'runner accepted execution without a manifest and mode'
fi

printf 'Bounded classification correction static tests passed.\n'
