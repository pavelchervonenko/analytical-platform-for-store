#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(cd -- "${TEST_DIR}/../.." && pwd)"

# shellcheck source=../../deploy/bin/release-safety.sh
source "${PROJECT_ROOT}/deploy/bin/release-safety.sh"

fail_test() {
  printf 'WEEKLY REVIEW AI RELEASE SAFETY TEST FAILED: %s\n' "$*" >&2
  exit 1
}

temporary_directory="$(mktemp -d)"
trap 'rm -rf -- "${temporary_directory}"' EXIT
release_env="${temporary_directory}/release.env"

printf '%s\n' \
  'WEEKLY_REVIEW_ENABLED=true' \
  'SELLER_WEEKLY_REVIEW_ENABLED=false' \
  'SELLER_WEEKLY_PREPARATION_ENABLED=false' \
  'WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=false' \
  'WEEKLY_REVIEW_AI_ENABLED=true' \
  'WEEKLY_REVIEW_AI_PLANNER_ENABLED=false' \
  'WEEKLY_REVIEW_AI_WORKER_ENABLED=true' \
  'WEEKLY_REVIEW_AI_PROVIDER_CODE=YANDEX' \
  'WEEKLY_REVIEW_AI_MAX_PROVIDER_CALLS=2' \
  'YANDEX_AI_FOLDER_ID=folder1234' \
  'YANDEX_AI_MODEL_URI=gpt://folder1234/yandexgpt-5.1' \
  >"${release_env}"

release_validate_weekly_review_ai_configuration "${release_env}" \
  || fail_test 'valid bounded worker canary was rejected'

sed -i 's/SELLER_WEEKLY_PREPARATION_ENABLED=false/SELLER_WEEKLY_PREPARATION_ENABLED=invalid/' \
  "${release_env}"
if release_validate_weekly_review_ai_configuration "${release_env}" >/dev/null 2>&1; then
  fail_test 'invalid historical free preparation flag was accepted'
fi
sed -i 's/SELLER_WEEKLY_PREPARATION_ENABLED=invalid/SELLER_WEEKLY_PREPARATION_ENABLED=true/' \
  "${release_env}"
if release_validate_weekly_review_ai_configuration "${release_env}" >/dev/null 2>&1; then
  fail_test 'historical free preparation without seller feature was accepted'
fi
sed -i 's/SELLER_WEEKLY_PREPARATION_ENABLED=true/SELLER_WEEKLY_PREPARATION_ENABLED=false/' \
  "${release_env}"

sed -i 's#yandexgpt-5.1#latest#' "${release_env}"
if release_validate_weekly_review_ai_configuration "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'mutable latest model was accepted'
fi
sed -i 's#latest#yandexgpt-5.1#' "${release_env}"

sed -i 's/WEEKLY_REVIEW_AI_ENABLED=true/WEEKLY_REVIEW_AI_ENABLED=false/' \
  "${release_env}"
if release_validate_weekly_review_ai_configuration "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'worker flag without parent feature was accepted'
fi
sed -i 's/WEEKLY_REVIEW_AI_ENABLED=false/WEEKLY_REVIEW_AI_ENABLED=true/' \
  "${release_env}"

sed -i 's/WEEKLY_REVIEW_AI_MAX_PROVIDER_CALLS=2/WEEKLY_REVIEW_AI_MAX_PROVIDER_CALLS=3/' \
  "${release_env}"
if release_validate_weekly_review_ai_configuration "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'three paid calls per job were accepted'
fi
sed -i 's/WEEKLY_REVIEW_AI_MAX_PROVIDER_CALLS=3/WEEKLY_REVIEW_AI_MAX_PROVIDER_CALLS=2/' \
  "${release_env}"

sed -i 's/WEEKLY_REVIEW_ENABLED=true/WEEKLY_REVIEW_ENABLED=invalid/' "${release_env}"
if release_validate_weekly_review_ai_configuration "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'invalid read flag boolean was accepted'
fi
sed -i 's/WEEKLY_REVIEW_ENABLED=invalid/WEEKLY_REVIEW_ENABLED=true/' "${release_env}"

sed -i 's/SELLER_WEEKLY_REVIEW_ENABLED=false/SELLER_WEEKLY_REVIEW_ENABLED=invalid/' \
  "${release_env}"
if release_validate_weekly_review_ai_configuration "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'invalid seller review flag boolean was accepted'
fi
sed -i 's/SELLER_WEEKLY_REVIEW_ENABLED=invalid/SELLER_WEEKLY_REVIEW_ENABLED=true/' \
  "${release_env}"
release_validate_weekly_review_ai_configuration "${release_env}" \
  || fail_test 'valid seller review configuration was rejected'

sed -i 's/^WEEKLY_REVIEW_ENABLED=true/WEEKLY_REVIEW_ENABLED=false/' "${release_env}"
if release_validate_weekly_review_ai_configuration "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'seller review without parent review was accepted'
fi
sed -i 's/^WEEKLY_REVIEW_ENABLED=false/WEEKLY_REVIEW_ENABLED=true/' "${release_env}"

sed -i \
  's/WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=false/WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=invalid/' \
  "${release_env}"
if release_validate_weekly_review_ai_configuration "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'invalid snapshot planner boolean was accepted'
fi
sed -i \
  's/WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=invalid/WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=false/' \
  "${release_env}"

sed -i \
  's/WEEKLY_REVIEW_AI_PLANNER_ENABLED=false/WEEKLY_REVIEW_AI_PLANNER_ENABLED=true/' \
  "${release_env}"
if release_validate_weekly_review_ai_configuration "${release_env}" \
    >/dev/null 2>&1; then
  fail_test 'AI planner without deterministic snapshot planner was accepted'
fi

sed -i \
  's/WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=false/WEEKLY_REVIEW_SNAPSHOT_PLANNER_ENABLED=true/' \
  "${release_env}"
release_validate_weekly_review_ai_configuration "${release_env}" \
  || fail_test 'valid automatic snapshot and AI planning configuration was rejected'

sed -i 's/SELLER_WEEKLY_PREPARATION_ENABLED=false/SELLER_WEEKLY_PREPARATION_ENABLED=true/' \
  "${release_env}"
if release_validate_weekly_review_ai_configuration "${release_env}" >/dev/null 2>&1; then
  fail_test 'historical preparation with competing legacy paid planner was accepted'
fi
sed -i 's/WEEKLY_REVIEW_AI_PLANNER_ENABLED=true/WEEKLY_REVIEW_AI_PLANNER_ENABLED=false/' \
  "${release_env}"
release_validate_weekly_review_ai_configuration "${release_env}" \
  || fail_test 'free preparation with bounded manual worker was rejected'

for invalid in \
  'STORE_BATCH_SIZE=0' 'STORE_BATCH_SIZE=101' 'DISCOVERY_WEEKS=0' 'DISCOVERY_WEEKS=53' \
  'REFRESH_BATCH_SIZE=0' 'REFRESH_BATCH_SIZE=101' 'BATCH_SIZE=0' 'BATCH_SIZE=11' \
  'BATCH_SIZE=1+1' 'BATCH_SIZE=999999999999999999999' \
  'SCAN_DELAY=0s' 'SCAN_DELAY=9s' 'SCAN_DELAY=2h' 'SCAN_DELAY=999999h' 'SCAN_DELAY=3600001ms' \
  'TIME_BUDGET=0s' 'TIME_BUDGET=6m' 'TIME_BUDGET=300001ms' 'TIME_BUDGET=1' 'TIME_BUDGET=PT1M'; do
  candidate="${temporary_directory}/invalid.env"
  cp -- "${release_env}" "${candidate}"
  printf 'SELLER_WEEKLY_PREPARATION_%s\n' "${invalid}" >>"${candidate}"
  if release_validate_weekly_review_ai_configuration "${candidate}" >/dev/null 2>&1; then
    fail_test 'unsafe free preparation bounds were accepted'
  fi
done
for valid in 'SCAN_DELAY=10s' 'SCAN_DELAY=1h' 'SCAN_DELAY=3600000ms' \
  'TIME_BUDGET=1000ms' 'TIME_BUDGET=5m' 'TIME_BUDGET=300000ms' 'BATCH_SIZE=008'; do
  candidate="${temporary_directory}/valid.env"
  cp -- "${release_env}" "${candidate}"
  printf 'SELLER_WEEKLY_PREPARATION_%s\n' "${valid}" >>"${candidate}"
  release_validate_weekly_review_ai_configuration "${candidate}" \
    || fail_test 'valid free preparation bounds were rejected'
done

printf '%s\n' 'Weekly review AI release safety tests passed.'
