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

printf '%s\n' 'Weekly review AI release safety tests passed.'
