#!/usr/bin/env bash

release_safety_fail() {
  printf 'RELEASE SAFETY CHECK FAILED: %s\n' "$*" >&2
  return 1
}

release_env_value() {
  local env_file="$1"
  local variable_name="$2"
  local matches

  matches="$(awk -v key="${variable_name}" '
    index($0, key "=") == 1 { count++; value = substr($0, length(key) + 2) }
    END {
      if (count == 1) {
        sub(/\r$/, "", value)
        print value
      } else {
        exit 1
      }
    }
  ' "${env_file}")" || {
    release_safety_fail \
      "${variable_name} must occur exactly once in ${env_file}"
    return 1
  }
  printf '%s\n' "${matches}"
}

release_env_value_or_default() {
  local env_file="$1"
  local variable_name="$2"
  local default_value="$3"
  local occurrences value

  occurrences="$(awk -v key="${variable_name}" '
    index($0, key "=") == 1 { count++ }
    END { print count + 0 }
  ' "${env_file}")" || return 1
  case "${occurrences}" in
  0)
    printf '%s\n' "${default_value}"
    ;;
  1)
    value="$(release_env_value "${env_file}" "${variable_name}")" || return 1
    printf '%s\n' "${value}"
    ;;
  *)
    release_safety_fail \
      "${variable_name} must not occur more than once in ${env_file}"
    return 1
    ;;
  esac
}

release_validate_release_identity() {
  local env_file="$1"
  local release_id release_commit backend_image web_image
  local backend_digest web_digest backend_ref_digest web_ref_digest

  release_id="$(release_env_value "${env_file}" RELEASE_ID)" || return 1
  release_commit="$(release_env_value "${env_file}" RELEASE_COMMIT)" || return 1
  backend_image="$(release_env_value "${env_file}" BACKEND_IMAGE)" || return 1
  web_image="$(release_env_value "${env_file}" WEB_IMAGE)" || return 1
  backend_digest="$(release_env_value "${env_file}" BACKEND_IMAGE_DIGEST)" \
    || return 1
  web_digest="$(release_env_value "${env_file}" WEB_IMAGE_DIGEST)" || return 1

  [[ "${release_id}" =~ ^[A-Za-z0-9._-]{7,128}$ ]] \
    || { release_safety_fail 'RELEASE_ID is invalid'; return 1; }
  [[ "${release_commit}" =~ ^[a-f0-9]{40}$ ]] \
    || { release_safety_fail 'RELEASE_COMMIT must be a lowercase 40-character Git commit'; return 1; }
  [[ "${backend_image}" =~ ^ghcr\.io/[a-z0-9][a-z0-9._-]*/store-analytics-backend@sha256:([a-f0-9]{64})$ ]] \
    || { release_safety_fail 'BACKEND_IMAGE must be an immutable Store Analytics GHCR digest reference'; return 1; }
  backend_ref_digest="sha256:${BASH_REMATCH[1]}"
  [[ "${web_image}" =~ ^ghcr\.io/[a-z0-9][a-z0-9._-]*/store-analytics-web@sha256:([a-f0-9]{64})$ ]] \
    || { release_safety_fail 'WEB_IMAGE must be an immutable Store Analytics GHCR digest reference'; return 1; }
  web_ref_digest="sha256:${BASH_REMATCH[1]}"
  [[ "${backend_digest}" == "${backend_ref_digest}" ]] \
    || { release_safety_fail 'BACKEND_IMAGE_DIGEST does not match BACKEND_IMAGE'; return 1; }
  [[ "${web_digest}" == "${web_ref_digest}" ]] \
    || release_safety_fail 'WEB_IMAGE_DIGEST does not match WEB_IMAGE'
}

release_validate_database_target() {
  local env_file="$1"
  local cert_host host_address port database schema runtime_user migrator_user backup_user
  local identifier octet
  local -a host_octets

  cert_host="$(release_env_value "${env_file}" DB_CERT_HOST)" || return 1
  host_address="$(release_env_value "${env_file}" DB_HOST_ADDRESS)" || return 1
  port="$(release_env_value "${env_file}" DB_PORT)" || return 1
  database="$(release_env_value "${env_file}" DB_NAME)" || return 1
  schema="$(release_env_value "${env_file}" DB_APP_SCHEMA)" || return 1
  runtime_user="$(release_env_value "${env_file}" DB_RUNTIME_USER)" || return 1
  migrator_user="$(release_env_value "${env_file}" DB_MIGRATOR_USER)" || return 1
  backup_user="$(release_env_value "${env_file}" DB_BACKUP_USER)" || return 1

  [[ "${cert_host}" =~ ^[A-Za-z0-9]([A-Za-z0-9.-]*[A-Za-z0-9])?$ ]] \
    || { release_safety_fail 'DB_CERT_HOST is invalid'; return 1; }
  [[ "${host_address}" =~ ^([0-9]{1,3}\.){3}[0-9]{1,3}$ ]] \
    || { release_safety_fail 'DB_HOST_ADDRESS must be an explicit IPv4 address'; return 1; }
  IFS='.' read -r -a host_octets <<<"${host_address}"
  for octet in "${host_octets[@]}"; do
    (( 10#${octet} <= 255 )) \
      || { release_safety_fail 'DB_HOST_ADDRESS contains an invalid IPv4 octet'; return 1; }
  done
  [[ "${port}" =~ ^[0-9]+$ ]] && (( 10#${port} >= 1 && 10#${port} <= 65535 )) \
    || { release_safety_fail 'DB_PORT must be between 1 and 65535'; return 1; }

  for identifier in "${database}" "${schema}" "${runtime_user}" \
      "${migrator_user}" "${backup_user}"; do
    [[ "${identifier}" =~ ^[a-z_][a-z0-9_]{0,62}$ ]] \
      || { release_safety_fail 'database, schema and role identifiers must be safe PostgreSQL identifiers'; return 1; }
  done
  [[ "${runtime_user}" != "${migrator_user}" \
      && "${runtime_user}" != "${backup_user}" \
      && "${migrator_user}" != "${backup_user}" ]] \
    || release_safety_fail 'runtime, migrator and backup roles must be distinct'
}

release_verify_remote_image_provenance() {
  local env_file="$1"
  local release_commit image revision image_variable

  release_commit="$(release_env_value "${env_file}" RELEASE_COMMIT)" || return 1
  for image_variable in BACKEND_IMAGE WEB_IMAGE; do
    image="$(release_env_value "${env_file}" "${image_variable}")" || return 1
    revision="$(docker buildx imagetools inspect "${image}" \
      --format '{{ index .Image.Config.Labels "org.opencontainers.image.revision" }}')" \
      || { release_safety_fail "cannot inspect remote provenance for ${image_variable}"; return 1; }
    [[ "${revision}" == "${release_commit}" ]] \
      || { release_safety_fail "${image_variable} remote OCI revision does not match RELEASE_COMMIT"; return 1; }
  done
}

release_verify_local_image_provenance() {
  local env_file="$1"
  local release_commit image revision image_variable

  release_commit="$(release_env_value "${env_file}" RELEASE_COMMIT)" || return 1
  for image_variable in BACKEND_IMAGE WEB_IMAGE; do
    image="$(release_env_value "${env_file}" "${image_variable}")" || return 1
    revision="$(docker image inspect "${image}" \
      --format '{{ index .Config.Labels "org.opencontainers.image.revision" }}')" \
      || { release_safety_fail "cannot inspect local provenance for ${image_variable}"; return 1; }
    [[ "${revision}" == "${release_commit}" ]] \
      || { release_safety_fail "${image_variable} local OCI revision does not match RELEASE_COMMIT"; return 1; }
  done
}

release_require_version() {
  local name="$1"
  local value="$2"
  [[ "${value}" =~ ^[1-9][0-9]*(\.[0-9]+)*$ ]] \
    || release_safety_fail "${name} must be a positive Flyway schema version"
}

release_compare_versions() {
  local left="$1"
  local right="$2"
  local index maximum left_segment right_segment
  local -a left_segments right_segments

  release_require_version left_version "${left}" || return 1
  release_require_version right_version "${right}" || return 1
  IFS='.' read -r -a left_segments <<<"${left}"
  IFS='.' read -r -a right_segments <<<"${right}"
  maximum="${#left_segments[@]}"
  if (( ${#right_segments[@]} > maximum )); then
    maximum="${#right_segments[@]}"
  fi

  for ((index = 0; index < maximum; index++)); do
    left_segment="${left_segments[index]:-0}"
    right_segment="${right_segments[index]:-0}"
    if (( 10#${left_segment} < 10#${right_segment} )); then
      printf '%s\n' '-1'
      return 0
    fi
    if (( 10#${left_segment} > 10#${right_segment} )); then
      printf '%s\n' '1'
      return 0
    fi
  done
  printf '%s\n' '0'
}

release_version_lte() {
  local comparison

  comparison="$(release_compare_versions "$1" "$2")" || return 1
  (( comparison <= 0 ))
}

release_validate_schema_metadata() {
  local env_file="$1"
  local schema_version migration_min runtime_min runtime_max

  schema_version="$(release_env_value "${env_file}" SCHEMA_VERSION)" || return 1
  migration_min="$(release_env_value "${env_file}" MIGRATION_SOURCE_MIN_VERSION)" \
    || return 1
  runtime_min="$(release_env_value "${env_file}" RUNTIME_SCHEMA_MIN_VERSION)" \
    || return 1
  runtime_max="$(release_env_value "${env_file}" RUNTIME_SCHEMA_MAX_VERSION)" \
    || return 1

  release_require_version SCHEMA_VERSION "${schema_version}" || return 1
  release_require_version MIGRATION_SOURCE_MIN_VERSION "${migration_min}" || return 1
  release_require_version RUNTIME_SCHEMA_MIN_VERSION "${runtime_min}" || return 1
  release_require_version RUNTIME_SCHEMA_MAX_VERSION "${runtime_max}" || return 1
  release_version_lte "${migration_min}" "${schema_version}" \
    || { release_safety_fail 'MIGRATION_SOURCE_MIN_VERSION exceeds SCHEMA_VERSION'; return 1; }
  release_version_lte "${runtime_min}" "${schema_version}" \
    && release_version_lte "${schema_version}" "${runtime_max}" \
    || release_safety_fail 'SCHEMA_VERSION is outside the runtime compatibility range'
}

release_schema_allows_migration_source() {
  local env_file="$1"
  local actual_version="$2"
  local minimum target

  release_require_version actual_version "${actual_version}" || return 1
  minimum="$(release_env_value "${env_file}" MIGRATION_SOURCE_MIN_VERSION)" || return 1
  target="$(release_env_value "${env_file}" SCHEMA_VERSION)" || return 1
  release_version_lte "${minimum}" "${actual_version}" \
    && release_version_lte "${actual_version}" "${target}"
}

release_schema_allows_runtime() {
  local env_file="$1"
  local actual_version="$2"
  local minimum maximum

  release_require_version actual_version "${actual_version}" || return 1
  minimum="$(release_env_value "${env_file}" RUNTIME_SCHEMA_MIN_VERSION)" || return 1
  maximum="$(release_env_value "${env_file}" RUNTIME_SCHEMA_MAX_VERSION)" || return 1
  release_version_lte "${minimum}" "${actual_version}" \
    && release_version_lte "${actual_version}" "${maximum}"
}

release_validate_secret_file() {
  local variable_name="$1"
  local path="$2"
  local expected_uid="${RELEASE_EXPECTED_SECRET_UID:-0}"
  local mode owner

  [[ "${path}" == /* ]] \
    || { release_safety_fail "${variable_name} must contain an absolute path"; return 1; }
  [[ -f "${path}" && ! -L "${path}" && -r "${path}" && -s "${path}" ]] \
    || { release_safety_fail "${variable_name} is not a readable non-empty regular file"; return 1; }
  mode="$(stat -c '%a' "${path}")"
  owner="$(stat -c '%u' "${path}")"
  [[ "${mode}" == '600' ]] \
    || { release_safety_fail "${variable_name} must have mode 0600"; return 1; }
  [[ "${owner}" == "${expected_uid}" ]] \
    || release_safety_fail "${variable_name} has unexpected owner uid"
}

release_validate_webhook_secret() {
  local variable_name="$1"
  local path="$2"
  local value

  release_validate_secret_file "${variable_name}" "${path}" || return 1
  value="$(<"${path}")"
  [[ "${value}" =~ ^[A-Za-z0-9_-]{32,256}$ ]] \
    || release_safety_fail \
      "${variable_name} must contain 32-256 URL-safe characters"
}

release_validate_secret_files() {
  local env_file="$1"
  local variable_name path
  local webhook_sale_default='/etc/store-analytics/secrets/livesklad-sale-return-webhook-secret'
  local webhook_order_default='/etc/store-analytics/secrets/livesklad-order-return-webhook-secret'
  local -a secret_variables=(
    POSTGRES_RUNTIME_PASSWORD_FILE
    POSTGRES_MIGRATOR_PASSWORD_FILE
    POSTGRES_BACKUP_PASSWORD_FILE
    LIVESKLAD_LOGIN_FILE
    LIVESKLAD_PASSWORD_FILE
    YANDEX_AI_API_KEY_FILE
    TELEGRAM_BOT_TOKEN_FILE
    TELEGRAM_WEBHOOK_SECRET_FILE
    SECURITY_TELEMETRY_PSEUDONYM_KEY_FILE
    PROMETHEUS_SCRAPE_TOKEN_FILE
    BOOTSTRAP_ADMIN_PASSWORD_FILE
  )

  for variable_name in "${secret_variables[@]}"; do
    path="$(release_env_value "${env_file}" "${variable_name}")" || return 1
    release_validate_secret_file "${variable_name}" "${path}" || return 1
  done

  path="$(release_env_value_or_default \
    "${env_file}" LIVESKLAD_SALE_RETURN_WEBHOOK_SECRET_FILE \
    "${webhook_sale_default}")"
  release_validate_webhook_secret \
    LIVESKLAD_SALE_RETURN_WEBHOOK_SECRET_FILE "${path}" || return 1
  path="$(release_env_value_or_default \
    "${env_file}" LIVESKLAD_ORDER_RETURN_WEBHOOK_SECRET_FILE \
    "${webhook_order_default}")"
  release_validate_webhook_secret \
    LIVESKLAD_ORDER_RETURN_WEBHOOK_SECRET_FILE "${path}" || return 1
}

release_validate_ca_file() {
  local env_file="$1"
  local path

  path="$(release_env_value "${env_file}" POSTGRES_CA_FILE)" || return 1
  [[ "${path}" == /* && -f "${path}" && ! -L "${path}" && -r "${path}" ]] \
    || release_safety_fail 'POSTGRES_CA_FILE is not a readable regular file'
}

release_validate_product_classification_reconciliation() {
  local env_file="$1"
  local enabled product_ids expected_items

  enabled="$(release_env_value_or_default \
    "${env_file}" PRODUCT_CLASSIFICATION_RECONCILIATION_ENABLED false)" \
    || return 1
  product_ids="$(release_env_value_or_default \
    "${env_file}" PRODUCT_CLASSIFICATION_RECONCILIATION_PRODUCT_IDS '')" \
    || return 1
  expected_items="$(release_env_value_or_default \
    "${env_file}" PRODUCT_CLASSIFICATION_RECONCILIATION_EXPECTED_ITEMS 0)" \
    || return 1

  case "${enabled}" in
  false)
    [[ -z "${product_ids}" && "${expected_items}" == '0' ]] \
      || release_safety_fail \
        'disabled product classification reconciliation must have an empty scope and zero expected items'
    ;;
  true)
    [[ "${product_ids}" =~ ^[A-Za-z0-9._:-]+(,[A-Za-z0-9._:-]+)*$ ]] \
      || { release_safety_fail \
        'enabled product classification reconciliation requires a comma-separated product ID allowlist'; return 1; }
    [[ "${expected_items}" =~ ^[1-9][0-9]*$ ]] \
      || release_safety_fail \
        'enabled product classification reconciliation requires a positive expected item count'
    ;;
  *)
    release_safety_fail \
      'PRODUCT_CLASSIFICATION_RECONCILIATION_ENABLED must be true or false'
    ;;
  esac
}

release_validate_livesklad_webhook_processing() {
  local env_file="$1"
  local order_worker receiver worker sync_worker schedule overlap

  order_worker="$(release_env_value_or_default \
    "${env_file}" LIVESKLAD_ORDER_RETURN_WEBHOOK_WORKER_ENABLED false)" \
    || return 1
  case "${order_worker}" in
  false)
    return 0
    ;;
  true)
    ;;
  *)
    release_safety_fail \
      'LIVESKLAD_ORDER_RETURN_WEBHOOK_WORKER_ENABLED must be true or false'
    return 1
    ;;
  esac

  receiver="$(release_env_value_or_default \
    "${env_file}" LIVESKLAD_WEBHOOK_ENABLED false)" || return 1
  worker="$(release_env_value_or_default \
    "${env_file}" LIVESKLAD_WEBHOOK_WORKER_ENABLED false)" || return 1
  sync_worker="$(release_env_value_or_default \
    "${env_file}" SYNC_WORKER_ENABLED true)" || return 1
  schedule="$(release_env_value_or_default \
    "${env_file}" SYNC_SCHEDULE_ENABLED false)" || return 1
  overlap="$(release_env_value_or_default \
    "${env_file}" SYNC_INCREMENTAL_OVERLAP_DAYS 3)" || return 1

  [[ "${receiver}" == 'true' ]] \
    || { release_safety_fail \
      'order-return processing requires LIVESKLAD_WEBHOOK_ENABLED=true'; return 1; }
  [[ "${worker}" == 'true' ]] \
    || { release_safety_fail \
      'order-return processing requires LIVESKLAD_WEBHOOK_WORKER_ENABLED=true'; return 1; }
  [[ "${sync_worker}" == 'true' ]] \
    || { release_safety_fail \
      'order-return processing requires SYNC_WORKER_ENABLED=true'; return 1; }
  [[ "${schedule}" == 'true' ]] \
    || { release_safety_fail \
      'order-return processing requires SYNC_SCHEDULE_ENABLED=true as a recovery path'; return 1; }
  [[ "${overlap}" =~ ^[0-9]+$ ]] && (( 10#${overlap} >= 2 )) \
    || release_safety_fail \
      'order-return processing requires SYNC_INCREMENTAL_OVERLAP_DAYS >= 2'
}

release_validate_llm_configuration() {
  local env_file="$1"
  local enabled prompt_version content_schema_version expected_schema

  enabled="$(release_env_value_or_default \
    "${env_file}" INTERPRETATION_GENERATION_ENABLED false)" || return 1
  case "${enabled}" in
  false)
    return 0
    ;;
  true)
    ;;
  *)
    release_safety_fail \
      'INTERPRETATION_GENERATION_ENABLED must be true or false'
    return 1
    ;;
  esac

  prompt_version="$(release_env_value "${env_file}" LLM_PROMPT_VERSION)" \
    || return 1
  content_schema_version="$(release_env_value \
    "${env_file}" LLM_CONTENT_SCHEMA_VERSION)" || return 1

  case "${prompt_version}" in
  weekly-interpretation-v3)
    expected_schema=1
    ;;
  weekly-interpretation-v4|weekly-interpretation-v5|weekly-interpretation-v6|\
  weekly-interpretation-v7|weekly-interpretation-v8|weekly-interpretation-v9|\
  weekly-interpretation-v10|weekly-interpretation-v11|weekly-interpretation-v12)
    expected_schema=2
    ;;
  weekly-interpretation-v13|weekly-interpretation-v14|weekly-interpretation-v15|\
  weekly-interpretation-v16|weekly-interpretation-v17|weekly-interpretation-v18|\
  weekly-interpretation-v19|weekly-interpretation-v20|weekly-interpretation-v21)
    expected_schema=3
    ;;
  *)
    release_safety_fail \
      'LLM_PROMPT_VERSION is not packaged by the production application'
    return 1
    ;;
  esac

  [[ "${content_schema_version}" == "${expected_schema}" ]] \
    || release_safety_fail \
      "${prompt_version} requires LLM_CONTENT_SCHEMA_VERSION=${expected_schema}"
}

# shellcheck source=weekly-review-ai-release-safety.sh
source "$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/weekly-review-ai-release-safety.sh"

release_validate_env_file() {
  local env_file="$1"
  local mode

  [[ -f "${env_file}" && ! -L "${env_file}" ]] \
    || { release_safety_fail "release env is not a regular file: ${env_file}"; return 1; }
  mode="$(stat -c '%a' "${env_file}")"
  [[ "${mode}" == '600' || "${mode}" == '400' ]] \
    || { release_safety_fail "release env must have mode 0600 or 0400"; return 1; }
  release_validate_release_identity "${env_file}" || return 1
  release_validate_database_target "${env_file}" || return 1
  release_validate_schema_metadata "${env_file}" || return 1
  release_validate_product_classification_reconciliation "${env_file}" \
    || return 1
  release_validate_livesklad_webhook_processing "${env_file}" || return 1
  release_validate_llm_configuration "${env_file}" || return 1
  release_validate_weekly_review_ai_configuration "${env_file}" || return 1
  release_validate_secret_files "${env_file}" || return 1
  release_validate_ca_file "${env_file}"
}
