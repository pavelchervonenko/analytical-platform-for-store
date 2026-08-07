#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=lib/shell-security.sh
source "${SCRIPT_DIR}/lib/shell-security.sh"

YANDEX_AI_FOLDER_ID="${YANDEX_AI_FOLDER_ID:-}"
YANDEX_AI_MODEL_URI="${YANDEX_AI_MODEL_URI:-}"
YANDEX_AI_API_KEY_FILE="${YANDEX_AI_API_KEY_FILE:-}"
CONFIRM_YANDEX_LLM_CALL="${CONFIRM_YANDEX_LLM_CALL:-}"
ENDPOINT='https://ai.api.cloud.yandex.net/v1/chat/completions'

usage() {
    printf '%s\n' \
        'Usage: scripts/yandexgpt-staging-acceptance.sh verify' \
        '' \
        'Required:' \
        '  YANDEX_AI_FOLDER_ID=<staging-folder-id>' \
        '  YANDEX_AI_MODEL_URI=gpt://<staging-folder-id>/<versioned-model>' \
        '  YANDEX_AI_API_KEY_FILE=/secure/path/api-key' \
        '  CONFIRM_YANDEX_LLM_CALL=CALL_STAGING_MODEL' \
        '' \
        'The command performs one billable request with synthetic data.'
}

validate_configuration() {
    [[ "${YANDEX_AI_FOLDER_ID}" =~ ^[A-Za-z0-9_-]{4,100}$ ]] \
        || security_fail 'YANDEX_AI_FOLDER_ID is invalid'
    [[ "${YANDEX_AI_MODEL_URI}" == "gpt://${YANDEX_AI_FOLDER_ID}/"* ]] \
        || security_fail 'YANDEX_AI_MODEL_URI must belong to YANDEX_AI_FOLDER_ID'
    [[ "${YANDEX_AI_MODEL_URI}" =~ ^gpt://[A-Za-z0-9_-]{4,100}/[A-Za-z0-9._/-]{2,160}$ ]] \
        || security_fail 'YANDEX_AI_MODEL_URI is invalid'
    [[ "${CONFIRM_YANDEX_LLM_CALL}" == 'CALL_STAGING_MODEL' ]] \
        || security_fail 'Set CONFIRM_YANDEX_LLM_CALL=CALL_STAGING_MODEL'
    security_require_readable_regular_file \
        'YANDEX_AI_API_KEY_FILE' "${YANDEX_AI_API_KEY_FILE}"
    [[ "$(wc -c <"${YANDEX_AI_API_KEY_FILE}")" -le 512 ]] \
        || security_fail 'YANDEX_AI_API_KEY_FILE is overlong'
}

create_request() {
    jq -n \
        --arg model "${YANDEX_AI_MODEL_URI}" \
        '{
          model: $model,
          messages: [
            {role: "system", content: "Верни только JSON по заданной схеме."},
            {role: "user", content: "Это синтетическая staging-проверка. Верни status=ok."}
          ],
          temperature: 0,
          max_tokens: 64,
          stream: false,
          store: false,
          response_format: {
            type: "json_schema",
            json_schema: {
              name: "store_analytics_staging_preflight_v1",
              description: "Synthetic connectivity and structured-output probe",
              strict: false,
              schema: {
                type: "object",
                additionalProperties: false,
                required: ["status"],
                properties: {status: {type: "string", const: "ok"}}
              }
            }
          }
        }' >"${REQUEST_FILE}"
    chmod 600 "${REQUEST_FILE}"
}

create_headers() {
    local api_key
    api_key="$(<"${YANDEX_AI_API_KEY_FILE}")"
    [[ -n "${api_key}" ]] || security_fail 'Yandex API key is empty'
    [[ "${api_key}" != *$'\r'* && "${api_key}" != *$'\n'* ]] \
        || security_fail 'Yandex API key contains CR or LF'
    security_write_header_file 'Authorization' "Api-Key ${api_key}" "${AUTH_HEADER_FILE}"
    unset api_key
    security_write_header_file \
        'OpenAI-Project' "${YANDEX_AI_FOLDER_ID}" "${PROJECT_HEADER_FILE}"
}

call_provider() {
    local status
    status="$(curl --proto '=https' --tlsv1.2 --connect-timeout 5 --max-time 120 \
        --max-filesize 1048576 --silent --show-error \
        --header 'Content-Type: application/json' \
        --header 'Accept: application/json' \
        --header 'Accept-Encoding: identity' \
        --header 'x-data-logging-enabled: false' \
        --header "x-client-request-id: $(python3 -c 'import uuid; print(uuid.uuid4())')" \
        --header "@${AUTH_HEADER_FILE}" \
        --header "@${PROJECT_HEADER_FILE}" \
        --data-binary "@${REQUEST_FILE}" \
        --output "${RESPONSE_FILE}" --write-out '%{http_code}' \
        "${ENDPOINT}")"
    [[ "${status}" == '200' ]] \
        || security_fail "Yandex AI Studio returned HTTP ${status}; response is not printed"
}

verify_response() {
    jq -e --arg model "${YANDEX_AI_MODEL_URI}" '
      (.id | type == "string" and length > 0)
      and .model == $model
      and (.choices | type == "array" and length == 1)
      and .choices[0].index == 0
      and .choices[0].finish_reason == "stop"
      and (.choices[0].message.refusal // "" | length == 0)
      and (.choices[0].message.content | fromjson | .status == "ok")
      and (.usage.prompt_tokens | type == "number" and . >= 0)
      and (.usage.completion_tokens | type == "number" and . >= 0)
      and (.usage.total_tokens | type == "number" and . >= 0)
    ' "${RESPONSE_FILE}" >/dev/null \
        || security_fail 'Yandex response violates the expected structured-output contract'
    printf 'YandexGPT staging contract: verified. input=%s, output=%s, total=%s tokens.\n' \
        "$(jq -er '.usage.prompt_tokens' "${RESPONSE_FILE}")" \
        "$(jq -er '.usage.completion_tokens' "${RESPONSE_FILE}")" \
        "$(jq -er '.usage.total_tokens' "${RESPONSE_FILE}")"
}

main() {
    [[ "${1:-}" == 'verify' ]] || { usage; exit 2; }
    for command_name in curl jq python3 wc; do
        security_require_command "${command_name}"
    done
    validate_configuration
    TEMPORARY_DIRECTORY="$(mktemp -d)"
    REQUEST_FILE="${TEMPORARY_DIRECTORY}/request.json"
    RESPONSE_FILE="${TEMPORARY_DIRECTORY}/response.json"
    AUTH_HEADER_FILE="${TEMPORARY_DIRECTORY}/authorization.header"
    PROJECT_HEADER_FILE="${TEMPORARY_DIRECTORY}/project.header"
    trap 'rm -rf -- "${TEMPORARY_DIRECTORY}"' EXIT
    create_request
    create_headers
    call_provider
    verify_response
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
