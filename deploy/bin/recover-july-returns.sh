#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly SCRIPT_VERSION="july-return-recovery-v4"
readonly BASE_URL="https://store-analytics.net"
readonly EXPECTED_RELEASE_PREFIX="v0.1.0-pilot.22"
readonly EXPECTED_SCHEMA_VERSION="44"
readonly RELEASE_ENV="/etc/store-analytics/release.env"
readonly RELEASE_STATE_DIR="/var/lib/store-analytics/release-state"
readonly ADMIN_PASSWORD_FILE="${ADMIN_PASSWORD_FILE:-/etc/store-analytics/secrets/admin-current-password}"
readonly REASON="Restore verified MAGAZIN July 2026 report discrepancy"
readonly CONFIRMATION_PHRASE="RECOVER_MAGAZIN_JULY_2026_8_RETURNS_716750_RUB"
readonly POLL_ATTEMPTS=120
readonly POLL_DELAY_SECONDS=2
readonly MAX_BACKUP_AGE_SECONDS=129600

readonly -a RECOVERIES=(
  'F000321|6a4eac7e1859763ea8325d9f|79880.00|3'
  'F000340|6a57ead8e861c2d49501db68|141000.00|10'
  'F000342|6a593669c3093767e3e84554|102890.00|3'
  'F000344|6a5938afc309371e11e84925|46000.00|2'
  'F000352|6a5d2b39e861c25fc24d240d|4990.00|1'
  'F000371|6a660193e861c22b80db7be7|103000.00|9'
  'F000378|6a6c795fb75c90fffe3dea54|4990.00|1'
  'F000380|6a6cf266aa17fa34a10d64fd|234000.00|13'
)

work_dir=""
cookie_jar=""
response_file=""
request_file=""
csrf_header_file=""
authenticated=false

die() {
  printf 'JULY RETURN RECOVERY REFUSED: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  if [[ "${authenticated}" == "true" && -n "${cookie_jar}" && -f "${cookie_jar}" && -f "${csrf_header_file}" ]]; then
    local -a logout_args=(
      --proto '=https'
      --tlsv1.2
      --connect-timeout 5
      --max-time 15
      --max-filesize 1048576
      --silent
      --show-error
      --cookie "${cookie_jar}"
      --cookie-jar "${cookie_jar}"
      --header "@${csrf_header_file}"
      --request POST
      --output /dev/null
    )
    curl "${logout_args[@]}" "${BASE_URL}/api/auth/logout" >/dev/null 2>&1 || true
  fi
  if [[ -n "${work_dir}" && -d "${work_dir}" && "${work_dir}" == /tmp/store-analytics-july-recovery.* ]]; then
    rm -rf -- "${work_dir}"
  fi
}
trap cleanup EXIT

usage() {
  cat <<'USAGE'
Usage:
  recover-july-returns.sh plan
  sudo ADMIN_LOGIN='<admin-email>' recover-july-returns.sh check
  sudo ADMIN_LOGIN='<admin-email>' \
    CONFIRM_JULY_RETURN_RECOVERY=RECOVER_MAGAZIN_JULY_2026_8_RETURNS_716750_RUB \
    recover-july-returns.sh run

plan  validates and prints the immutable eight-document manifest; no network or mutation.
check validates production release/schema/backup/flags, authenticates, and rejects active sync.
run   repeats check and submits one recovery at a time, waiting for PROCESSED before continuing.
USAGE
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "missing command: $1"
}

validate_dataset() {
  local row document_number external_id amount position_count
  local whole fractional amount_cents
  local total_cents=0
  local total_positions=0
  declare -A documents=()
  declare -A external_ids=()

  [[ "${#RECOVERIES[@]}" -eq 8 ]] || die "manifest must contain exactly eight records"
  for row in "${RECOVERIES[@]}"; do
    IFS='|' read -r document_number external_id amount position_count <<<"${row}"
    [[ "${document_number}" =~ ^F[0-9]{6}$ ]] || die "invalid document number in manifest"
    [[ "${external_id}" =~ ^[0-9a-f]{24}$ ]] || die "invalid external ID for ${document_number}"
    [[ "${amount}" =~ ^[1-9][0-9]*\.[0-9]{2}$ ]] || die "invalid amount for ${document_number}"
    [[ "${position_count}" =~ ^[1-9][0-9]*$ ]] || die "invalid position count for ${document_number}"
    [[ -z "${documents[${document_number}]:-}" ]] || die "duplicate document number: ${document_number}"
    [[ -z "${external_ids[${external_id}]:-}" ]] || die "duplicate external ID: ${external_id}"
    documents["${document_number}"]=1
    external_ids["${external_id}"]=1

    whole="${amount%.*}"
    fractional="${amount#*.}"
    amount_cents=$((10#${whole} * 100 + 10#${fractional}))
    total_cents=$((total_cents + amount_cents))
    total_positions=$((total_positions + 10#${position_count}))
  done

  [[ "${total_cents}" -eq 71675000 ]] || die "manifest amount must equal 716750.00"
  [[ "${total_positions}" -eq 42 ]] || die "manifest API position count must equal 42"
}

print_plan() {
  local row document_number external_id amount position_count
  printf 'Script: %s\n' "${SCRIPT_VERSION}"
  printf 'Target: MAGAZIN, 2026-07-01..2026-07-31\n'
  printf '%-9s %-24s %12s %9s\n' "Document" "External ID" "Amount" "Positions"
  for row in "${RECOVERIES[@]}"; do
    IFS='|' read -r document_number external_id amount position_count <<<"${row}"
    printf '%-9s %-24s %12s %9s\n' "${document_number}" "${external_id}" "${amount}" "${position_count}"
  done
  printf 'TOTAL: 8 documents, 716750.00 RUB, 42 LiveSklad API positions.\n'
}

prepare_workspace() {
  work_dir="$(mktemp -d /tmp/store-analytics-july-recovery.XXXXXXXX)"
  [[ "${work_dir}" == /tmp/store-analytics-july-recovery.* ]] || die "unexpected temporary directory"
  cookie_jar="${work_dir}/cookies"
  response_file="${work_dir}/response.json"
  request_file="${work_dir}/request.json"
  csrf_header_file="${work_dir}/csrf-header"
  : >"${cookie_jar}"
  : >"${response_file}"
  : >"${request_file}"
  chmod 0600 "${cookie_jar}" "${response_file}" "${request_file}"
}

release_env_value() {
  local key="$1"
  local value count
  value="$(sed -n "s/^${key}=//p" "${RELEASE_ENV}")"
  count="$(grep -c "^${key}=" "${RELEASE_ENV}" || true)"
  [[ "${count}" -eq 1 && -n "${value}" ]] || die "${key} must occur exactly once in release.env"
  printf '%s' "${value}"
}

validate_secret_file() {
  [[ -f "${ADMIN_PASSWORD_FILE}" && ! -L "${ADMIN_PASSWORD_FILE}" ]] || die "admin password must be a regular non-symlink file"
  [[ -s "${ADMIN_PASSWORD_FILE}" && -r "${ADMIN_PASSWORD_FILE}" ]] || die "admin password file is missing or unreadable"
  [[ "$(stat -c '%u' "${ADMIN_PASSWORD_FILE}")" -eq 0 ]] || die "admin password file must be root-owned"
  local mode
  mode="$(stat -c '%a' "${ADMIN_PASSWORD_FILE}")"
  [[ "${mode}" == "600" || "${mode}" == "400" ]] || die "admin password file mode must be 0600 or 0400"
}

validate_production_state() {
  [[ "$(id -u)" -eq 0 ]] || die "check/run must execute as root"
  local command_name
  for command_name in curl jq awk grep sed stat systemctl mktemp tr seq sleep date psql; do
    require_command "${command_name}"
  done
  [[ -n "${ADMIN_LOGIN:-}" ]] || die "ADMIN_LOGIN is required for check/run"
  case "${ADMIN_LOGIN}" in
    *$'\n'*|*$'\r'*) die "ADMIN_LOGIN contains a line break" ;;
  esac
  [[ "${#ADMIN_LOGIN}" -le 320 ]] || die "ADMIN_LOGIN is too long"
  validate_secret_file

  [[ -r "${RELEASE_ENV}" ]] || die "release.env is unreadable"
  [[ -r "${RELEASE_STATE_DIR}/current-release" ]] || die "current release state is missing"
  [[ -r "${RELEASE_STATE_DIR}/database-schema-version" ]] || die "database schema state is missing"

  local current_release current_schema
  current_release="$(tr -d '\r\n' <"${RELEASE_STATE_DIR}/current-release")"
  current_schema="$(tr -d '\r\n' <"${RELEASE_STATE_DIR}/database-schema-version")"
  [[ "${current_release}" == "${EXPECTED_RELEASE_PREFIX}"* ]] || die "expected production release prefix ${EXPECTED_RELEASE_PREFIX}"
  [[ "${current_schema}" == "${EXPECTED_SCHEMA_VERSION}" ]] || die "expected production schema ${EXPECTED_SCHEMA_VERSION}"

  [[ "$(release_env_value LIVESKLAD_WEBHOOK_ENABLED)" == "true" ]] || die "LiveSklad webhook receiver is not enabled"
  [[ "$(release_env_value LIVESKLAD_WEBHOOK_WORKER_ENABLED)" == "true" ]] || die "LiveSklad sale-return worker is not enabled"
  [[ "$(release_env_value LIVESKLAD_ORDER_RETURN_WEBHOOK_WORKER_ENABLED)" == "false" ]] || die "order-return worker changed; review this recovery separately"

  systemctl is-active --quiet store-analytics-backup.timer || die "backup timer is not active"
  [[ "$(systemctl show store-analytics-backup.service --property=Result --value)" == "success" ]] || die "last backup service result is not success"
  systemctl is-active --quiet store-analytics-health.timer || die "health timer is not active"

  local backup_timestamp backup_epoch now_epoch backup_age
  backup_timestamp="$(systemctl show store-analytics-backup.service --property=ExecMainExitTimestamp --value)"
  backup_epoch="$(date --date="${backup_timestamp}" +%s 2>/dev/null || true)"
  now_epoch="$(date +%s)"
  [[ "${backup_epoch}" =~ ^[0-9]+$ ]] || die "last backup timestamp is invalid"
  backup_age=$((now_epoch - backup_epoch))
  [[ "${backup_age}" -ge 0 && "${backup_age}" -le "${MAX_BACKUP_AGE_SECONDS}" ]] || die "last successful backup is older than 36 hours"

  curl --proto '=https' --tlsv1.2 --connect-timeout 5 --max-time 15 --max-filesize 1048576 --fail --silent --show-error --output /dev/null "${BASE_URL}/readyz" || die "public readiness check failed"

  printf 'Production preflight: release=%s schema=%s backup_age=%ss flags=expected.\n' "${current_release}" "${current_schema}" "${backup_age}"
}

get_json() {
  local path="$1"
  local -a args=(
    --proto '=https'
    --tlsv1.2
    --connect-timeout 5
    --max-time 30
    --max-filesize 1048576
    --fail-with-body
    --silent
    --show-error
    --cookie "${cookie_jar}"
    --cookie-jar "${cookie_jar}"
    --header 'Accept: application/json'
    --output "${response_file}"
  )
  curl "${args[@]}" "${BASE_URL}${path}"
}

write_csrf_header() {
  local token="$1"
  [[ -n "${token}" ]] || die "CSRF token cookie is missing"
  case "${token}" in
    *$'\n'*|*$'\r'*) die "CSRF token contains a line break" ;;
  esac
  [[ "${#token}" -le 1024 ]] || die "CSRF token is too long"
  printf 'X-XSRF-TOKEN: %s\n' "${token}" >"${csrf_header_file}"
  chmod 0600 "${csrf_header_file}"
}

post_json() {
  local path="$1"
  local idempotency_key="${2:-}"
  local -a args=(
    --proto '=https'
    --tlsv1.2
    --connect-timeout 5
    --max-time 30
    --max-filesize 1048576
    --fail-with-body
    --silent
    --show-error
    --cookie "${cookie_jar}"
    --cookie-jar "${cookie_jar}"
    --header "@${csrf_header_file}"
    --header 'Accept: application/json'
    --header 'Content-Type: application/json'
    --data-binary "@${request_file}"
    --output "${response_file}"
  )
  if [[ -n "${idempotency_key}" ]]; then
    printf 'Idempotency-Key: %s\n' "${idempotency_key}" >"${work_dir}/idempotency-header"
    chmod 0600 "${work_dir}/idempotency-header"
    args+=(--header "@${work_dir}/idempotency-header")
  fi
  curl "${args[@]}" "${BASE_URL}${path}"
}

fetch_csrf() {
  get_json "/api/auth/csrf"
  local token
  token="$(awk '$6 == "XSRF-TOKEN" { value=$7 } END { print value }' "${cookie_jar}")"
  write_csrf_header "${token}"
}

authenticate() {
  prepare_workspace
  fetch_csrf
  jq -n --arg email "${ADMIN_LOGIN}" --rawfile password "${ADMIN_PASSWORD_FILE}" '{email:$email,password:($password | sub("[\\r\\n]+$"; ""))}' >"${request_file}"
  post_json "/api/auth/login"
  jq -e '.role == "ADMIN" and .passwordChangeRequired == false' "${response_file}" >/dev/null || die "recovery account is not a ready ADMIN"
  authenticated=true
  fetch_csrf
  printf 'Authenticated a ready ADMIN account.\n'
}

verify_no_active_sync() {
  get_json "/api/sync/jobs?limit=100"
  jq -e 'type == "array"' "${response_file}" >/dev/null || die "sync jobs response is not an array"
  local active_count
  active_count="$(jq '[.[] | select(.status == "PENDING" or .status == "RUNNING" or .status == "WAITING_RETRY")] | length' "${response_file}")"
  [[ "${active_count}" -eq 0 ]] || die "found ${active_count} active synchronization job(s)"
  printf 'No active synchronization jobs.\n'
}

prepare_f000380_retry() {
  local db_cert_host db_host_address db_port db_name db_user
  local db_password_file db_ca_file db_connection password_mode

  db_cert_host="$(release_env_value DB_CERT_HOST)"
  db_host_address="$(release_env_value DB_HOST_ADDRESS)"
  db_port="$(release_env_value DB_PORT)"
  db_name="$(release_env_value DB_NAME)"
  db_user="$(release_env_value DB_MIGRATOR_USER)"
  db_password_file="$(release_env_value POSTGRES_MIGRATOR_PASSWORD_FILE)"
  db_ca_file="$(release_env_value POSTGRES_CA_FILE)"

  [[ -f "${db_password_file}" && ! -L "${db_password_file}" ]] || die "database password must be a regular non-symlink file"
  [[ -s "${db_password_file}" && -r "${db_password_file}" ]] || die "database password file is missing or unreadable"
  [[ "$(stat -c '%u' "${db_password_file}")" -eq 0 ]] || die "database password file must be root-owned"
  password_mode="$(stat -c '%a' "${db_password_file}")"
  [[ "${password_mode}" == "600" || "${password_mode}" == "400" ]] || die "database password file mode must be 0600 or 0400"
  [[ -f "${db_ca_file}" && ! -L "${db_ca_file}" && -r "${db_ca_file}" ]] || die "database CA file is missing or unreadable"

  db_connection="host=${db_cert_host} hostaddr=${db_host_address} port=${db_port} dbname=${db_name} user=${db_user} sslmode=verify-full sslrootcert=${db_ca_file} application_name=july-return-forward-fix"

  PGPASSWORD="$(<"${db_password_file}")" psql "${db_connection}" -X -v ON_ERROR_STOP=1 -P pager=off <<'SQL'
BEGIN;
SELECT pg_advisory_xact_lock(
    hashtextextended('july-return-f000380-forward-fix', 0)
);

DO $forward_fix$
DECLARE
    recovery livesklad_webhook_receipts%ROWTYPE;
    document_exists boolean;
BEGIN
    SELECT *
    INTO recovery
    FROM livesklad_webhook_receipts
    WHERE id = '33aa9855-109e-407a-a77f-cf71b82abc3a'::uuid
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'guard failed: F000380 recovery row is missing';
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM sales_documents
        WHERE source_system = 'LIVESKLAD'
          AND external_id = '6a6cf266aa17fa34a10d64fd'
          AND document_kind = 'RETURN'
          AND NOT is_deleted
    )
    INTO document_exists;

    IF document_exists THEN
        IF recovery.processing_status = 'PROCESSED'
                AND recovery.recovery_expected_position_count = 13
                AND recovery.terminal_failure = false THEN
            RAISE NOTICE 'F000380 is already recovered with 13 API positions';
            RETURN;
        END IF;
        RAISE EXCEPTION
            'guard failed: F000380 fact exists in an unexpected recovery state';
    END IF;

    IF recovery.webhook_kind <> 'SALE_RETURN'
            OR recovery.event_id
                <> 'manual-recovery-33aa9855-109e-407a-a77f-cf71b82abc3a'
            OR recovery.action_name <> 'manualRecovery'
            OR recovery.source_document_id <> '6a6cf266aa17fa34a10d64fd'
            OR recovery.recovery_expected_document_number <> 'F000380'
            OR recovery.recovery_expected_net_amount <> 234000.00
            OR recovery.payload_mismatch
            OR recovery.delivery_count <> 1 THEN
        RAISE EXCEPTION 'guard failed: F000380 immutable identity mismatch';
    END IF;

    IF recovery.recovery_expected_position_count = 13
            AND recovery.processing_status IN ('RECEIVED', 'PROCESSING')
            AND recovery.terminal_failure = false THEN
        RAISE NOTICE 'F000380 retry is already queued with 13 API positions';
        RETURN;
    END IF;

    IF recovery.recovery_expected_position_count <> 10
            OR recovery.processing_status <> 'FAILED'
            OR recovery.terminal_failure = false
            OR recovery.error_code
                <> 'RETURN_RECOVERY_EXPECTATION_MISMATCH'
            OR recovery.processing_attempt_count <> 1
            OR recovery.processed_at IS NOT NULL
            OR recovery.lease_owner IS NOT NULL
            OR recovery.lease_until IS NOT NULL THEN
        RAISE EXCEPTION 'guard failed: F000380 is not the exact first mismatch';
    END IF;

    UPDATE livesklad_webhook_receipts
    SET recovery_expected_position_count = 13,
        processing_status = 'RECEIVED',
        terminal_failure = false,
        available_at = now(),
        error_code = NULL,
        error_summary = NULL
    WHERE id = recovery.id;

    INSERT INTO audit_log (
        actor_user_id,
        action,
        entity_type,
        entity_id,
        metadata,
        retention_class,
        retain_until
    )
    VALUES (
        recovery.recovery_requested_by,
        'RETURN_RECOVERY_REQUESTED',
        'RETURN_DOCUMENT',
        recovery.id::text,
        jsonb_build_object(
            'reason', 'Correct grouped CRM rows to LiveSklad API positions',
            'before', jsonb_build_object('expectedPositionCount', 10),
            'after', jsonb_build_object('expectedPositionCount', 13)
        ),
        'FINANCIAL',
        NULL
    );

    RAISE NOTICE 'F000380 requeued with 13 verified API positions';
END
$forward_fix$;
COMMIT;
SQL
}

request_recovery() {
  local document_number="$1"
  local external_id="$2"
  local amount="$3"
  local position_count="$4"
  local idempotency_key="july-2026-${document_number}-v1"

  jq -n --arg externalId "${external_id}" --arg expectedDocumentNumber "${document_number}" --argjson expectedNetAmount "${amount}" --argjson expectedPositionCount "${position_count}" --arg reason "${REASON}" '{
    externalId:$externalId,
    expectedDocumentNumber:$expectedDocumentNumber,
    expectedNetAmount:$expectedNetAmount,
    expectedPositionCount:$expectedPositionCount,
    reason:$reason
  }' >"${request_file}"

  post_json "/api/admin/integrations/livesklad/returns/recoveries" "${idempotency_key}"

  jq -e --arg externalId "${external_id}" --arg documentNumber "${document_number}" --arg amount "${amount}" --argjson positionCount "${position_count}" '.externalId == $externalId
    and .expectedDocumentNumber == $documentNumber
    and (.expectedNetAmount | tonumber) == ($amount | tonumber)
    and .expectedPositionCount == $positionCount
    and (.id | type == "string")
    and (.status | type == "string")' "${response_file}" >/dev/null || die "recovery response does not match ${document_number}"
  jq -er '.id' "${response_file}"
}

wait_for_recovery() {
  local document_number="$1"
  local recovery_id="$2"
  local attempt status terminal_failure error_code

  [[ "${recovery_id}" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || die "invalid recovery ID for ${document_number}"
  for attempt in $(seq 1 "${POLL_ATTEMPTS}"); do
    get_json "/api/admin/integrations/livesklad/returns/recoveries/${recovery_id}"
    status="$(jq -er '.status' "${response_file}")"
    terminal_failure="$(jq -r '.terminalFailure | tostring' "${response_file}")"
    error_code="$(jq -r '.errorCode // ""' "${response_file}")"
    [[ "${terminal_failure}" == "true" || "${terminal_failure}" == "false" ]] || die "invalid terminalFailure for ${document_number}"
    [[ "${error_code}" =~ ^[A-Z0-9_]*$ ]] || die "unsafe error code returned for ${document_number}"
    if [[ "${status}" == "PROCESSED" ]]; then
      printf '%s PROCESSED (recovery %s).\n' "${document_number}" "${recovery_id}"
      return
    fi
    if [[ "${terminal_failure}" == "true" ]]; then
      die "${document_number} failed permanently: ${error_code:-UNKNOWN}"
    fi
    case "${status}" in
      RECEIVED|PROCESSING|FAILED) ;;
      *) die "unexpected status ${status} for ${document_number}" ;;
    esac
    sleep "${POLL_DELAY_SECONDS}"
  done
  die "${document_number} did not reach PROCESSED before timeout"
}

run_recoveries() {
  local row document_number external_id amount position_count recovery_id
  for row in "${RECOVERIES[@]}"; do
    IFS='|' read -r document_number external_id amount position_count <<<"${row}"
    printf 'Submitting %s (%s RUB, %s positions).\n' "${document_number}" "${amount}" "${position_count}"
    recovery_id="$(request_recovery "${document_number}" "${external_id}" "${amount}" "${position_count}")"
    wait_for_recovery "${document_number}" "${recovery_id}"
  done
  printf 'All eight recoveries are PROCESSED exactly through the validated API.\n'
  printf 'Next required step: repeat the closed July CRM reconciliation; expected delta is zero.\n'
}

main() {
  local action="${1:-}"
  validate_dataset
  case "${action}" in
    plan)
      [[ "$#" -eq 1 ]] || { usage >&2; exit 2; }
      print_plan
      ;;
    check)
      [[ "$#" -eq 1 ]] || { usage >&2; exit 2; }
      validate_production_state
      authenticate
      verify_no_active_sync
      printf 'Read-only production check passed; no recovery was queued.\n'
      ;;
    run)
      [[ "$#" -eq 1 ]] || { usage >&2; exit 2; }
      [[ "${CONFIRM_JULY_RETURN_RECOVERY:-}" == "${CONFIRMATION_PHRASE}" ]] || die "set CONFIRM_JULY_RETURN_RECOVERY=${CONFIRMATION_PHRASE}"
      validate_production_state
      authenticate
      verify_no_active_sync
      prepare_f000380_retry
      run_recoveries
      ;;
    -h|--help|help)
      [[ "$#" -eq 1 ]] || { usage >&2; exit 2; }
      usage
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
