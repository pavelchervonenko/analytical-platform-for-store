#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment

TOKEN=''
SHOPS_RESPONSE=''
EMPLOYEES_RESPONSE=''
MANAGERS_RESPONSE=''
MASTERS_RESPONSE=''
STORE_SUMMARIES='[]'
EMPLOYEE_ASSIGNMENTS='[]'
trap 'unset TOKEN SHOPS_RESPONSE EMPLOYEES_RESPONSE MANAGERS_RESPONSE MASTERS_RESPONSE STORE_SUMMARIES EMPLOYEE_ASSIGNMENTS' EXIT

TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
SHOPS_RESPONSE="$(livesklad_get "${TOKEN}" '/shops')" \
    || fail 'LiveSklad shops request failed; response body was not printed'

mapfile -t SHOP_IDS < <(jq -er '.data[]?.id' <<<"${SHOPS_RESPONSE}")
(("${#SHOP_IDS[@]}" > 0)) || fail 'LiveSklad returned no shops'

for index in "${!SHOP_IDS[@]}"; do
    shop_index=$((index + 1))
    shop_id="${SHOP_IDS[${index}]}"

    EMPLOYEES_RESPONSE="$(livesklad_get_paginated "${TOKEN}" "/shops/${shop_id}/customers")" \
        || fail "Employees request failed for shop index ${shop_index}"
    MANAGERS_RESPONSE="$(livesklad_get_paginated "${TOKEN}" "/shops/${shop_id}/customers/managers")" \
        || fail "Managers request failed for shop index ${shop_index}"
    MASTERS_RESPONSE="$(livesklad_get_paginated "${TOKEN}" "/shops/${shop_id}/customers/masters")" \
        || fail "Masters request failed for shop index ${shop_index}"

    STORE_SUMMARY="$(jq -cn \
        --argjson shopIndex "${shop_index}" \
        --argjson employees "${EMPLOYEES_RESPONSE}" \
        --argjson managers "${MANAGERS_RESPONSE}" \
        --argjson masters "${MASTERS_RESPONSE}" '
        def profile($response):
            {
                count: ($response.data | length),
                pagesFetched: $response.pagesFetched,
                reportedTotal: $response.reportedTotal,
                responseFields: $response.responseFields,
                itemSchema: (
                    [$response.data[]? | to_entries[]]
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
                    presentCount: ([$response.data[]? | select(.id? != null)] | length),
                    uniqueCount: ([$response.data[]? | .id? // empty] | unique | length)
                },
                remainRequest: $response.remainRequest,
                expireDate: $response.expireDate
            };

        {
            shopIndex: $shopIndex,
            employees: profile($employees),
            managers: profile($managers),
            masters: profile($masters),
            roleChecks: {
                managersAreEmployees: (
                    ([$managers.data[]?.id] - [$employees.data[]?.id]) | length == 0
                ),
                mastersAreEmployees: (
                    ([$masters.data[]?.id] - [$employees.data[]?.id]) | length == 0
                )
            }
        }
    ')"

    STORE_SUMMARIES="$(jq -cn \
        --argjson current "${STORE_SUMMARIES}" \
        --argjson next "${STORE_SUMMARY}" \
        '$current + [$next]')"

    EMPLOYEE_ASSIGNMENTS="$(jq -cn \
        --argjson current "${EMPLOYEE_ASSIGNMENTS}" \
        --argjson employees "${EMPLOYEES_RESPONSE}" \
        --argjson shopIndex "${shop_index}" '
        $current + [$employees.data[]? | {shopIndex: $shopIndex, id: .id}]
    ')"
done

jq -cn \
    --argjson stores "${STORE_SUMMARIES}" \
    --argjson assignments "${EMPLOYEE_ASSIGNMENTS}" '
    ($assignments
        | group_by(.id)
        | map({
            shopCount: (map(.shopIndex) | unique | length)
        })
    ) as $identities
    |
    {
        request: {
            endpoints: [
                "/shops/{id}/customers",
                "/shops/{id}/customers/managers",
                "/shops/{id}/customers/masters"
            ],
            pageSize: 50
        },
        shopCount: ($stores | length),
        stores: $stores,
        crossStoreIdentity: {
            assignmentCount: ($assignments | length),
            uniqueEmployeeIdCount: ($identities | length),
            sharedEmployeeIdCount: (
                [$identities[] | select(.shopCount > 1)] | length
            )
        }
    }
'
