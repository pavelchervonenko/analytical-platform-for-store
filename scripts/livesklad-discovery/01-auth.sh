#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment

AUTH_RESPONSE=''
trap 'unset AUTH_RESPONSE' EXIT

if ! AUTH_RESPONSE="$(livesklad_auth_response)"; then
    fail 'LiveSklad authentication failed; credentials and response body were not printed'
fi

if ! jq -e '.token | type == "string" and length > 0' >/dev/null <<<"${AUTH_RESPONSE}"; then
    fail 'LiveSklad response did not contain a non-empty token'
fi

jq '{
    authenticated: true,
    ttl,
    remainRequest,
    expireDate,
    responseFields: (keys | sort)
}' <<<"${AUTH_RESPONSE}"
