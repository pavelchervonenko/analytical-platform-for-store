#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment

TOKEN=''
SHOPS_RESPONSE=''
trap 'unset TOKEN SHOPS_RESPONSE' EXIT

if ! TOKEN="$(livesklad_access_token)"; then
    fail 'LiveSklad authentication failed; credentials and response body were not printed'
fi

if ! SHOPS_RESPONSE="$(livesklad_get "${TOKEN}" '/shops')"; then
    fail 'LiveSklad shops request failed; response body was not printed'
fi

if ! jq -e '.data | type == "array"' >/dev/null <<<"${SHOPS_RESPONSE}"; then
    fail 'LiveSklad shops response does not contain a data array'
fi

jq '{
    request: {
        method: "GET",
        path: "/shops"
    },
    responseFields: (keys | sort),
    count: (.data | length),
    itemSchema: (
        [.data[]? | to_entries[]]
        | sort_by(.key)
        | group_by(.key)
        | map({
            field: .[0].key,
            observedTypes: (map(.value | type) | unique),
            presentCount: length,
            nullCount: (map(select(.value == null)) | length)
        })
    ),
    idProfile: {
        presentCount: ([.data[]? | select(.id? != null)] | length),
        uniqueCount: ([.data[]? | .id? // empty] | unique | length),
        allPresentIdsUnique: (
            ([.data[]? | .id? // empty] | length)
            == ([.data[]? | .id? // empty] | unique | length)
        )
    },
    version,
    remainRequest,
    expireDate
}' <<<"${SHOPS_RESPONSE}"
