#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=../lib/shell-security.sh
source "${REPOSITORY_ROOT}/scripts/lib/shell-security.sh"

usage() {
    printf '%s\n' \
        'Usage:' \
        '  scripts/llm-eval/shadow.sh plan' \
        '  scripts/llm-eval/shadow.sh run <max-paid-calls> <max-cost-rub>' \
        '' \
        'run requires:' \
        '  YANDEX_AI_FOLDER_ID=<folder-id>' \
        '  YANDEX_AI_MODEL_URI=gpt://<folder-id>/<versioned-model>' \
        '  YANDEX_AI_API_KEY_FILE=/secure/path/api-key' \
        '  CONFIRM_YANDEX_LLM_SHADOW=CALL_YANDEX_SHADOW' \
        '' \
        'The runner never retries automatically and resumes completed responses.'
}

configure_java() {
    local java_home_candidate user_home_directory java_major_version
    java_home_candidate="${JAVA_HOME:-}"
    if [[ -z "${java_home_candidate}" \
            || ! -x "${java_home_candidate}/bin/java" ]]; then
        user_home_directory="$(getent passwd "$(id -u)" | cut -d: -f6)"
        java_home_candidate="${user_home_directory}/.sdkman/candidates/java/current"
    fi
    [[ -x "${java_home_candidate}/bin/java" ]] \
        || security_fail 'Java runtime was not found; Java 21 or newer is required'
    java_major_version="$("${java_home_candidate}/bin/java" -version 2>&1 \
        | sed -nE '1s/.*version "([0-9]+).*/\1/p')"
    [[ "${java_major_version}" =~ ^[0-9]+$ && "${java_major_version}" -ge 21 ]] \
        || security_fail 'Java 21 or newer is required'
    export JAVA_HOME="${java_home_candidate}"
    export PATH="${JAVA_HOME}/bin:${PATH}"
}

export_inputs() {
    cd -- "${REPOSITORY_ROOT}"
    python3 scripts/llm-eval/evaluate.py \
        --export-inputs build/llm-eval/inputs
}

plan() {
    configure_java
    export_inputs
    cd -- "${REPOSITORY_ROOT}"
    LLM_EVAL_MODE=plan ./gradlew --no-daemon :backend:llmEvalShadow
}

validate_execution() {
    local max_paid_calls="$1"
    local max_cost_rub="$2"
    [[ "${max_paid_calls}" =~ ^[1-9][0-9]*$ ]] \
        || security_fail 'max-paid-calls must be a positive integer'
    [[ "${max_cost_rub}" =~ ^[0-9]+([.][0-9]{1,6})?$ ]] \
        || security_fail 'max-cost-rub must be a positive decimal'
    [[ ! "${max_cost_rub}" =~ ^0+([.]0+)?$ ]] \
        || security_fail 'max-cost-rub must be greater than zero'
    [[ "${YANDEX_AI_FOLDER_ID:-}" =~ ^[A-Za-z0-9_-]{4,100}$ ]] \
        || security_fail 'YANDEX_AI_FOLDER_ID is invalid'
    [[ "${YANDEX_AI_MODEL_URI:-}" == "gpt://${YANDEX_AI_FOLDER_ID}/"* ]] \
        || security_fail 'YANDEX_AI_MODEL_URI must belong to YANDEX_AI_FOLDER_ID'
    [[ "${YANDEX_AI_MODEL_URI}" =~ ^gpt://[A-Za-z0-9_-]{4,100}/[A-Za-z0-9._/-]{2,160}$ ]] \
        || security_fail 'YANDEX_AI_MODEL_URI is invalid'
    [[ "${YANDEX_AI_MODEL_URI}" != */latest ]] \
        || security_fail 'YANDEX_AI_MODEL_URI must be versioned, not latest'
    [[ "${CONFIRM_YANDEX_LLM_SHADOW:-}" == 'CALL_YANDEX_SHADOW' ]] \
        || security_fail 'Set CONFIRM_YANDEX_LLM_SHADOW=CALL_YANDEX_SHADOW'
    security_require_readable_regular_file \
        'YANDEX_AI_API_KEY_FILE' "${YANDEX_AI_API_KEY_FILE:-}"
    [[ "$(wc -c <"${YANDEX_AI_API_KEY_FILE}")" -le 512 ]] \
        || security_fail 'YANDEX_AI_API_KEY_FILE is overlong'
}

run_shadow() {
    local max_paid_calls="$1"
    local max_cost_rub="$2"
    local api_key
    validate_execution "${max_paid_calls}" "${max_cost_rub}"
    configure_java
    export_inputs
    api_key="$(<"${YANDEX_AI_API_KEY_FILE}")"
    [[ -n "${api_key}" ]] || security_fail 'Yandex API key is empty'
    [[ "${api_key}" != *$'\r'* && "${api_key}" != *$'\n'* ]] \
        || security_fail 'Yandex API key contains CR or LF'
    cd -- "${REPOSITORY_ROOT}"
    export YANDEX_AI_API_KEY="${api_key}"
    export LLM_EVAL_MODE=execute
    export LLM_EVAL_MAX_PAID_CALLS="${max_paid_calls}"
    export LLM_EVAL_MAX_COST_RUB="${max_cost_rub}"
    unset api_key
    ./gradlew --no-daemon :backend:llmEvalShadow
    unset YANDEX_AI_API_KEY
}

main() {
    case "${1:-}" in
        plan)
            [[ "$#" -eq 1 ]] || { usage; exit 2; }
            plan
            ;;
        run)
            [[ "$#" -eq 3 ]] || { usage; exit 2; }
            run_shadow "$2" "$3"
            ;;
        *)
            usage
            exit 2
            ;;
    esac
}

main "$@"
