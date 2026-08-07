#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
# shellcheck source=lib/shell-security.sh
source "${SCRIPT_DIR}/lib/shell-security.sh"

LOCAL_INTEGRATION_DB="${LOCAL_INTEGRATION_DB:-}"
LOCAL_INTEGRATION_DB_USERNAME="${LOCAL_INTEGRATION_DB_USERNAME:-store_analytics_integration}"
LOCAL_INTEGRATION_DB_PASSWORD_FILE="${LOCAL_INTEGRATION_DB_PASSWORD_FILE:-}"
LOCAL_INTEGRATION_PORT="${LOCAL_INTEGRATION_PORT:-8081}"
LOCAL_INTEGRATION_FRONTEND_ORIGIN="${LOCAL_INTEGRATION_FRONTEND_ORIGIN:-http://localhost:5174}"
LOCAL_INTEGRATION_ENABLE_DAILY_PULSE="${LOCAL_INTEGRATION_ENABLE_DAILY_PULSE:-false}"
YANDEX_AI_FOLDER_ID="${YANDEX_AI_FOLDER_ID:-}"
YANDEX_AI_MODEL_URI="${YANDEX_AI_MODEL_URI:-}"
YANDEX_AI_API_KEY_FILE="${YANDEX_AI_API_KEY_FILE:-}"
TELEGRAM_BOT_USERNAME="${TELEGRAM_BOT_USERNAME:-store_analytics_notify_bot}"
TELEGRAM_BOT_TOKEN_FILE="${TELEGRAM_BOT_TOKEN_FILE:-}"
TELEGRAM_WEBHOOK_SECRET_FILE="${TELEGRAM_WEBHOOK_SECRET_FILE:-}"

read_secret_file() {
    local label="$1"
    local path="$2"
    local max_bytes="$3"
    local value

    security_require_readable_regular_file "${label}" "${path}"
    [[ "$(wc -c <"${path}")" -le "${max_bytes}" ]] \
        || security_fail "${label} is overlong"
    value="$(<"${path}")"
    [[ -n "${value}" ]] || security_fail "${label} is empty"
    [[ "${value}" != *$'\r'* && "${value}" != *$'\n'* ]] \
        || security_fail "${label} contains CR or LF"
    printf '%s' "${value}"
}

configure_java() {
    local java_home_candidate user_home_directory java_major_version

    java_home_candidate="${JAVA_HOME:-}"
    if [[ -z "${java_home_candidate}" || ! -x "${java_home_candidate}/bin/java" ]]; then
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

validate_configuration() {
    [[ "${LOCAL_INTEGRATION_DB}" =~ ^[a-z][a-z0-9_]{2,62}$ ]] \
        || security_fail 'LOCAL_INTEGRATION_DB is invalid'
    [[ "${LOCAL_INTEGRATION_PORT}" =~ ^[0-9]{4,5}$ ]] \
        || security_fail 'LOCAL_INTEGRATION_PORT is invalid'
    [[ "${LOCAL_INTEGRATION_DB_USERNAME}" =~ ^[a-z][a-z0-9_]{2,62}$ ]] \
        || security_fail 'LOCAL_INTEGRATION_DB_USERNAME is invalid'
    [[ "${LOCAL_INTEGRATION_ENABLE_DAILY_PULSE}" == 'true' \
        || "${LOCAL_INTEGRATION_ENABLE_DAILY_PULSE}" == 'false' ]] \
        || security_fail 'LOCAL_INTEGRATION_ENABLE_DAILY_PULSE must be true or false'
    [[ "${YANDEX_AI_FOLDER_ID}" =~ ^[a-z0-9]{20}$ ]] \
        || security_fail 'YANDEX_AI_FOLDER_ID is invalid'
    [[ "${TELEGRAM_BOT_USERNAME}" =~ ^[A-Za-z0-9_]{5,64}$ ]] \
        || security_fail 'TELEGRAM_BOT_USERNAME is invalid'
    security_normalize_base_url 'https-or-loopback-http' \
        "${LOCAL_INTEGRATION_FRONTEND_ORIGIN}" >/dev/null
    if [[ -z "${YANDEX_AI_MODEL_URI}" ]]; then
        YANDEX_AI_MODEL_URI="gpt://${YANDEX_AI_FOLDER_ID}/yandexgpt-5.1"
    fi
}

main() {
    validate_configuration
    configure_java

    export SPRING_DATASOURCE_PASSWORD
    SPRING_DATASOURCE_PASSWORD="$(read_secret_file \
        'LOCAL_INTEGRATION_DB_PASSWORD_FILE' \
        "${LOCAL_INTEGRATION_DB_PASSWORD_FILE}" 512)"

    export YANDEX_AI_API_KEY
    YANDEX_AI_API_KEY="$(read_secret_file \
        'YANDEX_AI_API_KEY_FILE' "${YANDEX_AI_API_KEY_FILE}" 512)"
    export TELEGRAM_BOT_TOKEN
    TELEGRAM_BOT_TOKEN="$(read_secret_file \
        'TELEGRAM_BOT_TOKEN_FILE' "${TELEGRAM_BOT_TOKEN_FILE}" 512)"
    export TELEGRAM_WEBHOOK_SECRET
    TELEGRAM_WEBHOOK_SECRET="$(read_secret_file \
        'TELEGRAM_WEBHOOK_SECRET_FILE' "${TELEGRAM_WEBHOOK_SECRET_FILE}" 512)"

    export SPRING_PROFILES_ACTIVE=dev
    export SERVER_PORT="${LOCAL_INTEGRATION_PORT}"
    export APP_RUNTIME_ROLE=COMBINED
    export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:5432/${LOCAL_INTEGRATION_DB}"
    export SPRING_DATASOURCE_USERNAME="${LOCAL_INTEGRATION_DB_USERNAME}"
    export CORS_ALLOWED_ORIGINS="${LOCAL_INTEGRATION_FRONTEND_ORIGIN}"

    export INTERPRETATION_SNAPSHOT_ENABLED=true
    export INTERPRETATION_SNAPSHOT_WORKER_ENABLED=true
    export INTERPRETATION_SNAPSHOT_PLANNER_ENABLED=true
    export INTERPRETATION_SNAPSHOT_PLANNER_SCAN_DELAY=10s
    export INTERPRETATION_GENERATION_ENABLED=true
    export INTERPRETATION_GENERATION_PLANNER_ENABLED=true
    export INTERPRETATION_GENERATION_PLANNER_SCAN_DELAY=10s
    export INTERPRETATION_GENERATION_JOB_DEADLINE=10m
    export INTERPRETATION_GENERATION_WORKER_ENABLED=true
    export INTERPRETATION_GENERATION_WORKER_DELAY=3s
    export INTERPRETATION_GENERATION_LEASE_DURATION=4m
    export INTERPRETATION_GENERATION_HEARTBEAT_INTERVAL=15s
    export INTERPRETATION_GENERATION_PROVIDER_CALL_TIMEOUT=180s
    export INTERPRETATION_PUBLICATION_ENABLED=true

    export LLM_PROMPT_VERSION=weekly-interpretation-v4
    export LLM_CONTENT_SCHEMA_VERSION=2
    export LLM_TEMPERATURE=0.2
    export LLM_MAX_OUTPUT_TOKENS=8000
    export LLM_MAX_PROVIDER_CALLS=2
    export YANDEX_AI_FOLDER_ID
    export YANDEX_AI_MODEL_URI
    export YANDEX_AI_READ_TIMEOUT=180s
    export YANDEX_AI_CONTEXT_WINDOW_TOKENS=32768

    export TELEGRAM_NOTIFICATIONS_ENABLED=true
    export TELEGRAM_FANOUT_ENABLED=true
    export TELEGRAM_BOT_CODE=store-analytics-primary
    export TELEGRAM_BOT_USERNAME
    export TELEGRAM_LINKING_ENABLED=true
    export TELEGRAM_WEBHOOK_ENABLED=true
    export TELEGRAM_DELIVERY_ENABLED=true
    export TELEGRAM_LINK_TOKEN_TTL=30m
    export TELEGRAM_DELIVERY_DELAY=3s
    export DAILY_STORE_PULSE_ENABLED="${LOCAL_INTEGRATION_ENABLE_DAILY_PULSE}"

    printf 'Starting local LLM/Telegram integration on port %s with database %s.\n' \
        "${LOCAL_INTEGRATION_PORT}" "${LOCAL_INTEGRATION_DB}"
    cd -- "${REPOSITORY_ROOT}"
    exec ./gradlew :backend:bootRun
}

main "$@"
