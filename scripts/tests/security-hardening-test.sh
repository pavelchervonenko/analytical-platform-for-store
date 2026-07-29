#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

TEST_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd -- "${TEST_DIR}/../.." && pwd)"
HELPER="${PROJECT_ROOT}/scripts/lib/script_security.py"
# shellcheck source=../lib/shell-security.sh
source "${PROJECT_ROOT}/scripts/lib/shell-security.sh"

fail_test() {
    printf 'SCRIPT SECURITY TEST FAILED: %s\n' "$*" >&2
    exit 1
}
file_mode() {
    python3 -c 'import os, sys; print(f"{os.stat(sys.argv[1]).st_mode & 0o777:03o}")' "$1"
}



assert_rejected_url() {
    local policy="$1"
    local url="$2"

    if python3 "${HELPER}" validate-base-url "${policy}" "${url}" \
        >/dev/null 2>&1; then
        fail_test "unsafe URL was accepted: ${url}"
    fi
}

for command_name in bash python3 curl git jq; do
    command -v "${command_name}" >/dev/null 2>&1 \
        || fail_test "required test command is missing: ${command_name}"
done
docker_ignore="${PROJECT_ROOT}/.dockerignore"
[[ -f "${docker_ignore}" ]] || fail_test '.dockerignore is missing'
for protected_pattern in '**/.env' '**/.env.*' '**/*.orig' '**/*.rej' '**/*.bak'; do
    grep -Fx -- "${protected_pattern}" "${docker_ignore}" >/dev/null \
        || fail_test ".dockerignore does not protect ${protected_pattern}"
done
if git -C "${PROJECT_ROOT}" ls-files -z -- '*.orig' '*.rej' '*.bak' \
    | grep -q .; then
    fail_test 'a backup or rejected patch artifact is tracked by Git'
fi
dockerfile="${PROJECT_ROOT}/backend/Dockerfile"
for required_instruction in \
    'COPY gradlew ./' \
    'COPY gradle ./gradle' \
    'RUN --mount=type=cache,target=/root/.gradle ./gradlew :backend:bootJar --no-daemon'; do
    grep -Fx -- "${required_instruction}" "${dockerfile}" >/dev/null \
        || fail_test "Docker build bypasses repository Gradle wrapper: ${required_instruction}"
done
if grep -Eq '^RUN[[:space:]]+gradle[[:space:]]' "${dockerfile}"; then
    fail_test 'Docker build invokes an image-provided Gradle instead of the wrapper'
fi

bash -n \
    "${PROJECT_ROOT}/scripts/change-own-password.sh" \
    "${PROJECT_ROOT}/scripts/import-approved-product-categories.sh" \
    "${PROJECT_ROOT}/scripts/prepare-local-demo.sh" \
    "${PROJECT_ROOT}/scripts/generate-payroll-classification-review.sh" \
    "${PROJECT_ROOT}/scripts/lib/shell-security.sh" \
    "${PROJECT_ROOT}"/scripts/livesklad-discovery/*.sh

[[ "$(python3 "${HELPER}" validate-base-url \
    https-or-loopback-http http://localhost:8080/)" == 'http://localhost:8080' ]] \
    || fail_test 'localhost HTTP normalization failed'
[[ "$(python3 "${HELPER}" validate-base-url \
    https-or-loopback-http https://Example.COM:8443/)" == 'https://example.com:8443' ]] \
    || fail_test 'HTTPS normalization failed'

assert_rejected_url https-or-loopback-http 'http://example.com'
assert_rejected_url https-or-loopback-http 'http://localhost.example.com'
assert_rejected_url https-or-loopback-http 'https://user:secret@example.com'
assert_rejected_url https-or-loopback-http 'https://example.com/api'
assert_rejected_url https-or-loopback-http 'https://example.com?query=value'
assert_rejected_url https-or-loopback-http 'https://example.com#fragment'
assert_rejected_url https-only 'http://127.0.0.1:8080'
assert_rejected_url https-or-loopback-http 'https://example.com%40evil.test'
assert_rejected_url https-or-loopback-http 'https://example.com\@evil.test'
assert_rejected_url https-or-loopback-http 'https://exa mple.test'

temporary_directory="$(mktemp -d)"
cleanup() {
    rm -rf -- "${temporary_directory}"
}
trap cleanup EXIT

dotenv_fixture="${temporary_directory}/dotenv-fixture"
cat >"${dotenv_fixture}" <<'DOTENV'
# Values that would execute under source must remain literal data.
LIVESKLAD_BASE_URL=https://api.example.test/
LIVESKLAD_LOGIN=$(touch exploit-marker)
LIVESKLAD_PASSWORD=semicolon;dollar$literal#hash
PATH=/attacker/controlled/path
DOTENV

(
    cd -- "${temporary_directory}"
    ENV_FILE="${dotenv_fixture}"
    # shellcheck source=../livesklad-discovery/common.sh
    source "${PROJECT_ROOT}/scripts/livesklad-discovery/common.sh"
    load_livesklad_environment
    [[ "${LIVESKLAD_BASE_URL}" == 'https://api.example.test' ]] \
        || fail_test 'LiveSklad URL was not normalized'
    [[ "${LIVESKLAD_LOGIN}" == '$(touch exploit-marker)' ]] \
        || fail_test 'dotenv command substitution was not retained literally'
    [[ "${LIVESKLAD_PASSWORD}" == 'semicolon;dollar$literal#hash' ]] \
        || fail_test 'dotenv punctuation was not retained literally'
    [[ "${PATH}" != '/attacker/controlled/path' ]] \
        || fail_test 'non-allowlisted dotenv variable changed the environment'
    [[ ! -e exploit-marker ]] \
        || fail_test 'dotenv content was executed as shell code'
)

duplicate_fixture="${temporary_directory}/duplicate-fixture"
cat >"${duplicate_fixture}" <<'DOTENV'
LIVESKLAD_BASE_URL=https://first.example.test
LIVESKLAD_BASE_URL=https://second.example.test
DOTENV
if python3 "${HELPER}" parse-dotenv "${duplicate_fixture}" \
    LIVESKLAD_BASE_URL >/dev/null 2>&1; then
    fail_test 'duplicate dotenv variables were accepted'
fi

response_fixture="${temporary_directory}/response"
header_fixture="${temporary_directory}/sensitive-header"
security_write_header_file 'X-Test-Token' 'private-value' "${header_fixture}"
[[ "$(file_mode "${header_fixture}")" == '600' ]] \
    || fail_test 'sensitive header file mode is not 0600'
if (security_write_header_file 'X-Test-Token' $'bad\r\nvalue' \
    "${header_fixture}") >/dev/null 2>&1; then
    fail_test 'CRLF header value was accepted'
fi
if grep -F 'X-XSRF-TOKEN: $xsrf_token' \
    "${PROJECT_ROOT}/scripts/change-own-password.sh" \
    "${PROJECT_ROOT}/scripts/import-approved-product-categories.sh" >/dev/null; then
    fail_test 'XSRF token remains exposed in curl process arguments'
fi

printf '\033[31m0123456789abcdef' >"${response_fixture}"
rendered="$(python3 "${HELPER}" render-response "${response_fixture}" 8)"
[[ "${rendered}" == *'\u001b'* ]] || fail_test 'terminal control character was not escaped'
[[ "${rendered}" == *'[response truncated after 8 bytes]'* ]] \
    || fail_test 'bounded response did not report truncation'
[[ "${rendered}" != *$'\033'* ]] \
    || fail_test 'bounded response retained a terminal escape byte'

if BASE_URL='http://example.com' \
    bash "${PROJECT_ROOT}/scripts/change-own-password.sh" \
    </dev/null >/dev/null 2>&1; then
    fail_test 'password script accepted remote plaintext HTTP'
fi

classification_input="${temporary_directory}/classification-input.json"
classification_output="${temporary_directory}/classification-output.json"
cat >"${classification_input}" <<'JSON'
{"assignments":[]}
JSON
bash "${PROJECT_ROOT}/scripts/generate-payroll-classification-review.sh" \
    "${classification_input}" "${classification_output}" >/dev/null
[[ "$(file_mode "${classification_output}")" == '600' ]] \
    || fail_test 'classification artifact mode is not 0600'
if bash "${PROJECT_ROOT}/scripts/generate-payroll-classification-review.sh" \
    "${classification_input}" "${classification_output}" \
    >/dev/null 2>&1; then
    fail_test 'classification generator overwrote an existing artifact'
fi

if grep -F 'source "${ENV_FILE}"' \
    "${PROJECT_ROOT}/scripts/livesklad-discovery/common.sh" >/dev/null; then
    fail_test 'LiveSklad discovery still sources the dotenv file'
fi

demo_script="${PROJECT_ROOT}/scripts/prepare-local-demo.sh"
valid_demo_payload="${temporary_directory}/valid-demo-payload.json"
invalid_demo_payload="${temporary_directory}/invalid-demo-payload.json"
cat >"${valid_demo_payload}" <<'JSON'
{
  "validFrom": "2026-07-20T00:00:00Z",
  "ruleVersion": "test-v1",
  "assignments": [{
    "externalProductId": "product-1",
    "productName": "Product 1",
    "categoryCode": "CATEGORY_1",
    "conditionType": "NAME_CONTAINS"
  }]
}
JSON
printf '{"validFrom":"2026-07-20T00:00:00Z","ruleVersion":"test-v1","assignments":[]}' \
    >"${invalid_demo_payload}"
(
    # shellcheck source=../prepare-local-demo.sh
    source "${demo_script}"
    PAYLOAD_FILE="${valid_demo_payload}"
    validate_category_payload
)
if (
    source "${demo_script}"
    PAYLOAD_FILE="${invalid_demo_payload}"
    validate_category_payload
) >/dev/null 2>&1; then
    fail_test 'demo preflight accepted an empty category assignment payload'
fi

auth_response="${temporary_directory}/auth-response.json"
system_response="${temporary_directory}/system-response.json"
bad_system_response="${temporary_directory}/bad-system-response.json"
readiness_response="${temporary_directory}/readiness-response.json"
printf '%s' '{"role":"ADMIN","passwordChangeRequired":false}' >"${auth_response}"
printf '%s' '{"application":"store-analytics","apiContractVersion":"9"}' \
    >"${system_response}"
printf '%s' '{"application":"store-analytics","apiContractVersion":"7"}' \
    >"${bad_system_response}"
printf '%s' '{"status":"UP"}' >"${readiness_response}"
(
    source "${demo_script}"
    RESPONSE_FILE="${temporary_directory}/preflight-response.json"
    EXPECTED_API_CONTRACT_VERSION=9
    get_json() {
        case "$1" in
            */readyz) cp "${readiness_response}" "$RESPONSE_FILE" ;;
            */api/auth/me) cp "${auth_response}" "$RESPONSE_FILE" ;;
            */api/system/status) cp "${system_response}" "$RESPONSE_FILE" ;;
            *) return 1 ;;
        esac
    }
    preflight_backend
    verify_authenticated_contract
) >/dev/null
if (
    source "${demo_script}"
    RESPONSE_FILE="${temporary_directory}/bad-preflight-response.json"
    EXPECTED_API_CONTRACT_VERSION=9
    get_json() {
        case "$1" in
            */api/auth/me) cp "${auth_response}" "$RESPONSE_FILE" ;;
            */api/system/status) cp "${bad_system_response}" "$RESPONSE_FILE" ;;
            *) return 1 ;;
        esac
    }
    verify_authenticated_contract
) >/dev/null 2>&1; then
    fail_test 'demo preflight accepted an incompatible API contract version'
fi

job_response="${temporary_directory}/job-response.json"
job_progress="${temporary_directory}/job-progress.json"
job_output="${temporary_directory}/job-output"
printf '%s' \
    '{"status":"WAITING_RETRY","phase":"SALES","completedSteps":2,"totalRetries":1,"attemptCount":2,"maxAttempts":5,"nextAttemptAt":"2026-07-20T00:01:00Z","cancelRequested":false,"errorSummary":"retry\\u001b[31m"}' \
    >"${job_response}"
(
    source "${demo_script}"
    RESPONSE_FILE="${job_response}"
    JOB_PROGRESS_FILE="${job_progress}"
    print_job_progress
) >"${job_output}"
grep -F '"nextAttemptAt":"2026-07-20T00:01:00Z"' "${job_output}" >/dev/null \
    || fail_test 'demo job progress omitted nextAttemptAt'
grep -F '\u001b' "${job_output}" >/dev/null \
    || fail_test 'demo job progress did not escape terminal control data'
if grep -F $'\033' "${job_output}" >/dev/null; then
    fail_test 'demo job progress emitted a raw terminal escape byte'
fi

printf 'Operator script security tests passed.\n'
