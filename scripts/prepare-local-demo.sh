#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "$SCRIPT_DIR/.." && pwd)"
# shellcheck source=lib/shell-security.sh
source "${SCRIPT_DIR}/lib/shell-security.sh"

readonly COMPOSE_FILE="${PROJECT_DIR}/docker-compose.dev.yml"
readonly POSTGRES_CONTAINER="store-analytics-postgres"
readonly POSTGRES_VOLUME="analytical-platform-for-store_store_analytics_pgdata"
readonly RESET_CONFIRMATION="DELETE_LOCAL_DEMO_DATABASE"

BASE_URL="${BASE_URL:-http://127.0.0.1:8080}"
CONNECTION_KEY="${CONNECTION_KEY:-livesklad-default}"
APP_EMAIL="${APP_EMAIL:-}"
PERIOD_START="${PERIOD_START:-2026-07-20}"
PERIOD_END="${PERIOD_END:-2026-07-26}"
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-7200}"
POLL_SECONDS="${POLL_SECONDS:-5}"
EXPECTED_API_CONTRACT_VERSION="${EXPECTED_API_CONTRACT_VERSION:-9}"
PAYLOAD_FILE="${PAYLOAD_FILE:-${PROJECT_DIR}/outputs/category-review-approved/product-category-assignments-v2.json}"

usage() {
    printf '%s\n' \
        'Usage:' \
        '  scripts/prepare-local-demo.sh reset' \
        '  scripts/prepare-local-demo.sh seed' \
        '' \
        'reset irreversibly removes only the named local PostgreSQL volume.' \
        'seed imports approved categories and runs the durable LiveSklad backfill.'
}

validate_positive_integer() {
    local name="$1"
    local value="$2"
    [[ "$value" =~ ^[1-9][0-9]*$ ]] \
        || security_fail "$name must be a positive integer"
}

validate_period() {
    python3 - "$PERIOD_START" "$PERIOD_END" <<'PY'
from datetime import date
import sys

start = date.fromisoformat(sys.argv[1])
end = date.fromisoformat(sys.argv[2])
if end < start:
    raise SystemExit("PERIOD_END must not be before PERIOD_START")
if (end - start).days > 30:
    raise SystemExit("Local demo period must not exceed 31 inclusive days")
PY
}

require_common_tools() {
    for command_name in curl docker python3; do
        security_require_command "$command_name"
    done
    [[ -f "$COMPOSE_FILE" ]] \
        || security_fail "Compose file is missing: $COMPOSE_FILE"
    docker compose version >/dev/null 2>&1 \
        || security_fail 'Docker Compose v2 is unavailable'
    docker info >/dev/null 2>&1 \
        || security_fail 'Docker daemon is unavailable'
}

reset_database() {
    require_common_tools
    BASE_URL="$(security_normalize_base_url 'https-or-loopback-http' "$BASE_URL")"
    local reset_curl_protocol
    reset_curl_protocol="$(security_curl_protocol "$BASE_URL")"

    if curl --proto "$reset_curl_protocol" --silent --output /dev/null \
        --connect-timeout 1 --max-time 2 "$BASE_URL/api/auth/csrf"; then
        security_fail "Stop the backend before resetting the local database"
    fi

    printf '%s\n' \
        "This will permanently delete only Docker volume:" \
        "  $POSTGRES_VOLUME" \
        'No source files or other Docker volumes will be removed.'
    read -r -p "Type $RESET_CONFIRMATION to continue: " confirmation
    [[ "$confirmation" == "$RESET_CONFIRMATION" ]] \
        || security_fail 'Reset confirmation did not match'
    unset confirmation

    cd -- "$PROJECT_DIR"
    docker compose -f "$COMPOSE_FILE" down
    if docker volume inspect "$POSTGRES_VOLUME" >/dev/null 2>&1; then
        docker volume rm "$POSTGRES_VOLUME" >/dev/null
    fi
    docker compose -f "$COMPOSE_FILE" up -d postgres

    local deadline=$((SECONDS + 90))
    local health=''
    while (( SECONDS < deadline )); do
        health="$(docker inspect --format '{{.State.Health.Status}}' \
            "$POSTGRES_CONTAINER" 2>/dev/null || true)"
        [[ "$health" == 'healthy' ]] && break
        sleep 2
    done
    [[ "$health" == 'healthy' ]] \
        || security_fail 'PostgreSQL did not become healthy within 90 seconds'

    printf '%s\n' \
        'Clean PostgreSQL is ready.' \
        'Start backend in another terminal with: ./gradlew :backend:bootRun' \
        'Complete the bootstrap password change, then run:' \
        '  scripts/prepare-local-demo.sh seed'
}

get_json() {
    local url="$1"
    local expected_status="$2"
    local status
    status="$(curl "${CURL_ARGS[@]}" --silent --show-error \
        --cookie "$COOKIE_JAR" --cookie-jar "$COOKIE_JAR" \
        --output "$RESPONSE_FILE" --write-out '%{http_code}' "$url")"
    if [[ "$status" != "$expected_status" ]]; then
        printf 'Request failed with HTTP %s for %s.\n' "$status" "$url" >&2
        security_print_bounded_response "$RESPONSE_FILE" 8192 >&2
        exit 1
    fi
    if ! jq -e . "$RESPONSE_FILE" >/dev/null 2>&1; then
        printf 'Request returned invalid JSON for %s.\n' "$url" >&2
        security_print_bounded_response "$RESPONSE_FILE" 8192 >&2
        exit 1
    fi
}

preflight_backend() {
    get_json "$BASE_URL/readyz" '200'
    jq -e '.status == "UP"' "$RESPONSE_FILE" >/dev/null \
        || security_fail 'Backend readiness is not UP'
}

refresh_csrf() {
    curl "${CURL_ARGS[@]}" --fail --silent --show-error \
        --cookie "$COOKIE_JAR" --cookie-jar "$COOKIE_JAR" \
        "$BASE_URL/api/auth/csrf" --output /dev/null
    local token
    token="$(awk '$0 !~ /^#/ && $6 == "XSRF-TOKEN" { value = $7 } END { print value }' \
        "$COOKIE_JAR")"
    [[ -n "$token" ]] || security_fail 'Could not obtain an XSRF token'
    security_write_header_file 'X-XSRF-TOKEN' "$token" "$CSRF_HEADER_FILE"
}

login() {
    refresh_csrf
    local email password status
    if [[ -n "$APP_EMAIL" ]]; then
        email="$APP_EMAIL"
    else
        read -r -p 'Administrator email: ' email
    fi
    read -r -s -p "Password for $email: " password
    printf '\n'
    status="$(
        printf '%s\0%s' "$email" "$password" \
            | python3 -c 'import json, sys; e, p = sys.stdin.buffer.read().split(b"\0", 1); print(json.dumps({"email": e.decode(), "password": p.decode()}))' \
            | curl "${CURL_ARGS[@]}" --silent --show-error \
                --cookie "$COOKIE_JAR" --cookie-jar "$COOKIE_JAR" \
                --header 'Content-Type: application/json' \
                --header "@$CSRF_HEADER_FILE" --data-binary @- \
                --output "$RESPONSE_FILE" --write-out '%{http_code}' \
                "$BASE_URL/api/auth/login"
    )"
    unset password
    [[ "$status" == '200' ]] || {
        printf 'Authentication failed with HTTP %s.\n' "$status" >&2
        security_print_bounded_response "$RESPONSE_FILE" 8192 >&2
        exit 1
    }
    refresh_csrf
}

verify_authenticated_contract() {
    get_json "$BASE_URL/api/auth/me" '200'
    jq -e '.role == "ADMIN" and .passwordChangeRequired == false' \
        "$RESPONSE_FILE" >/dev/null \
        || security_fail 'Demo seed requires an ADMIN with completed password change'

    get_json "$BASE_URL/api/system/status" '200'
    local application contract_version
    application="$(jq -er '.application' "$RESPONSE_FILE")"
    contract_version="$(jq -er '.apiContractVersion' "$RESPONSE_FILE")"
    [[ "$application" == 'store-analytics' ]] \
        || security_fail 'Connected backend has an unexpected application identity'
    [[ "$contract_version" == "$EXPECTED_API_CONTRACT_VERSION" ]] \
        || security_fail "API contract mismatch: expected $EXPECTED_API_CONTRACT_VERSION, got $contract_version"
    printf 'Backend preflight: application=%s contractVersion=%s readiness=UP.\n' \
        "$application" "$contract_version"
}

validate_category_payload() {
    jq -e '
        type == "object"
        and (.validFrom | type == "string" and length > 0)
        and (.ruleVersion | type == "string" and length > 0)
        and (.assignments | type == "array" and length > 0 and length <= 10000)
        and all(.assignments[];
            (.externalProductId | type == "string" and length > 0)
            and (.productName | type == "string" and length > 0)
            and (.categoryCode | type == "string" and length > 0)
            and (.conditionType | type == "string" and length > 0)
        )
    ' "$PAYLOAD_FILE" >/dev/null \
        || security_fail 'Approved category payload has an invalid or empty contract shape'
}

post_json() {
    local url="$1"
    local input_file="$2"
    local expected_status="$3"
    local status
    status="$(curl "${CURL_ARGS[@]}" --silent --show-error \
        --cookie "$COOKIE_JAR" --header 'Content-Type: application/json' \
        --header "@$CSRF_HEADER_FILE" --data-binary "@$input_file" \
        --output "$RESPONSE_FILE" --write-out '%{http_code}' "$url")"
    if [[ "$status" != "$expected_status" ]]; then
        printf 'Request failed with HTTP %s for %s.\n' "$status" "$url" >&2
        security_print_bounded_response "$RESPONSE_FILE" 8192 >&2
        exit 1
    fi
    if ! jq -e . "$RESPONSE_FILE" >/dev/null 2>&1; then
        printf 'Request returned invalid JSON for %s.\n' "$url" >&2
        security_print_bounded_response "$RESPONSE_FILE" 8192 >&2
        exit 1
    fi
}

verify_database() {
    local summary stores employees sales returns items assignments
    summary="$(cd -- "$PROJECT_DIR" && docker compose -f "$COMPOSE_FILE" \
        exec -T postgres sh -c 'psql -At -F "|" -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT (SELECT count(*) FROM stores WHERE is_active), (SELECT count(*) FROM employees WHERE is_active), (SELECT count(*) FROM sales_documents WHERE original_document_id IS NULL AND NOT is_deleted), (SELECT count(*) FROM sales_documents WHERE original_document_id IS NOT NULL AND NOT is_deleted), (SELECT count(*) FROM sales_document_items WHERE NOT is_deleted), (SELECT count(*) FROM product_category_assignments);"')"
    IFS='|' read -r stores employees sales returns items assignments <<<"$summary"
    printf '%s\n' \
        'Database verification:' \
        "  stores=$stores" \
        "  employees=$employees" \
        "  sales=$sales" \
        "  returns=$returns" \
        "  items=$items" \
        "  categoryAssignments=$assignments"
    (( stores > 0 )) || security_fail 'No active stores were synchronized'
    (( employees > 0 )) || security_fail 'No active employees were synchronized'
    (( sales > 0 )) || security_fail 'No sales were synchronized'
    (( returns > 0 )) || security_fail 'No returns were synchronized'
    (( items > 0 )) || security_fail 'No sale or return items were synchronized'
    (( assignments > 0 )) || security_fail 'Approved categories were not imported'
}

verify_store_api() {
    get_json "$BASE_URL/api/stores" '200'
    jq -e '
        type == "array"
        and any(.[];
            .active == true
            and (.timezone | type == "string" and length > 0)
            and (.businessDayStart | type == "string" and length > 0)
        )
    ' "$RESPONSE_FILE" >/dev/null \
        || security_fail 'No accessible active store with timezone/business-day configuration was found'
    printf 'Store API verification: accessible=%s active=%s.\n' \
        "$(jq 'length' "$RESPONSE_FILE")" \
        "$(jq '[.[] | select(.active == true)] | length' "$RESPONSE_FILE")"
}

print_job_progress() {
    jq -c '
        {
            status,
            phase,
            completedSteps,
            totalRetries,
            attemptCount,
            maxAttempts,
            nextAttemptAt,
            cancelRequested
        }
        + if .status == "WAITING_RETRY" or .status == "FAILED"
            then {errorSummary}
            else {}
          end
    ' "$RESPONSE_FILE" >"$JOB_PROGRESS_FILE"
    printf 'Backfill: '
    security_print_bounded_response "$JOB_PROGRESS_FILE" 2048
}

seed_database() {
    require_common_tools
    for command_name in awk jq; do
        security_require_command "$command_name"
    done
    security_require_path_segment 'CONNECTION_KEY' "$CONNECTION_KEY"
    security_require_readable_regular_file 'Approved category payload' "$PAYLOAD_FILE"
    validate_positive_integer 'WAIT_TIMEOUT_SECONDS' "$WAIT_TIMEOUT_SECONDS"
    validate_positive_integer 'POLL_SECONDS' "$POLL_SECONDS"
    validate_positive_integer 'EXPECTED_API_CONTRACT_VERSION' \
        "$EXPECTED_API_CONTRACT_VERSION"
    (( POLL_SECONDS <= WAIT_TIMEOUT_SECONDS )) \
        || security_fail 'POLL_SECONDS must not exceed WAIT_TIMEOUT_SECONDS'
    validate_period
    validate_category_payload

    BASE_URL="$(security_normalize_base_url 'https-or-loopback-http' "$BASE_URL")"
    CURL_PROTOCOL="$(security_curl_protocol "$BASE_URL")"
    readonly BASE_URL CURL_PROTOCOL
    CURL_ARGS=(--proto "$CURL_PROTOCOL" --connect-timeout 5 --max-time 120 --max-filesize 65536)
    readonly -a CURL_ARGS

    TEMPORARY_DIRECTORY="$(mktemp -d)"
    COOKIE_JAR="$TEMPORARY_DIRECTORY/cookies.txt"
    RESPONSE_FILE="$TEMPORARY_DIRECTORY/response.json"
    CSRF_HEADER_FILE="$TEMPORARY_DIRECTORY/xsrf-header"
    BACKFILL_FILE="$TEMPORARY_DIRECTORY/backfill.json"
    JOB_PROGRESS_FILE="$TEMPORARY_DIRECTORY/job-progress.json"
    trap 'rm -rf -- "$TEMPORARY_DIRECTORY"' EXIT

    preflight_backend
    login
    verify_authenticated_contract
    post_json "$BASE_URL/api/integration-connections/$CONNECTION_KEY/product-category-imports" \
        "$PAYLOAD_FILE" '200'
    printf 'Category import: '
    security_print_bounded_response "$RESPONSE_FILE" 8192

    jq -n --arg period_start "$PERIOD_START" \
        --arg period_end "$PERIOD_END" \
        '{periodStart: $period_start, periodEndInclusive: $period_end}' >"$BACKFILL_FILE"
    post_json "$BASE_URL/api/sync/jobs/backfill" "$BACKFILL_FILE" '202'
    local job_id status deadline
    job_id="$(jq -er '.id' "$RESPONSE_FILE")"
    security_require_path_segment 'Backfill job id' "$job_id"
    deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS))
    printf 'Backfill job %s started for %s through %s.\n' \
        "$job_id" "$PERIOD_START" "$PERIOD_END"

    while (( SECONDS < deadline )); do
        get_json "$BASE_URL/api/sync/jobs/$job_id" '200'
        status="$(jq -er '.status' "$RESPONSE_FILE")"
        print_job_progress
        case "$status" in
            SUCCESS) break ;;
            FAILED|CANCELLED)
                security_print_bounded_response "$RESPONSE_FILE" 8192 >&2
                security_fail "Backfill finished with status $status"
                ;;
            PENDING|RUNNING|WAITING_RETRY) ;;
            *)
                security_print_bounded_response "$RESPONSE_FILE" 8192 >&2
                security_fail 'Backfill returned an unknown status'
                ;;
        esac
        sleep "$POLL_SECONDS"
    done
    [[ "${status:-}" == 'SUCCESS' ]] \
        || security_fail 'Backfill did not finish before the configured timeout'
    verify_database
    verify_store_api
    printf 'Local demo data is ready for application and E2E verification.\n'
}

main() {
    case "${1:-}" in
        reset) reset_database ;;
        seed) seed_database ;;
        *) usage; exit 2 ;;
    esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
