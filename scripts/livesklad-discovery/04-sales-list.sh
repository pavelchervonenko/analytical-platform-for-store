#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment
require_command date

DISCOVERY_DAYS="${DISCOVERY_DAYS:-7}"
[[ "${DISCOVERY_DAYS}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_DAYS must be a positive integer'
((DISCOVERY_DAYS <= 31)) || fail 'DISCOVERY_DAYS must not exceed 31 for the sales probe'

END_MS="$(($(date -u +%s) * 1000))"
START_MS="$((END_MS - DISCOVERY_DAYS * 86400000))"

TOKEN=''
SHOPS_RESPONSE=''
SALES_RESPONSE=''
STORE_SUMMARIES='[]'
trap 'unset TOKEN SHOPS_RESPONSE SALES_RESPONSE STORE_SUMMARIES START_MS END_MS' EXIT

TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
SHOPS_RESPONSE="$(livesklad_get "${TOKEN}" '/shops')" \
    || fail 'LiveSklad shops request failed; response body was not printed'

mapfile -t SHOP_IDS < <(jq -er '.data[]?.id' <<<"${SHOPS_RESPONSE}")
(("${#SHOP_IDS[@]}" > 0)) || fail 'LiveSklad returned no shops'

for index in "${!SHOP_IDS[@]}"; do
    shop_index=$((index + 1))
    shop_id="${SHOP_IDS[${index}]}"

    SALES_RESPONSE="$(livesklad_get "${TOKEN}" "/shops/${shop_id}/sales" \
        --get \
        --data-urlencode "date=[${START_MS},${END_MS}]" \
        --data-urlencode 'page=1' \
        --data-urlencode 'pageSize=50' \
        --data-urlencode 'sort=date DESC')" \
        || fail "Sales request failed for shop index ${shop_index}"

    jq -e '.data | type == "array"' >/dev/null <<<"${SALES_RESPONSE}" \
        || fail "Sales response for shop index ${shop_index} does not contain a data array"

    STORE_SUMMARY="$(jq -cn \
        --argjson shopIndex "${shop_index}" \
        --argjson response "${SALES_RESPONSE}" '
        def fields($objects):
            [$objects[]? | select(type == "object") | to_entries[]]
            | sort_by(.key)
            | group_by(.key)
            | map({
                field: .[0].key,
                observedTypes: (map(.value | type) | unique),
                presentCount: length,
                nullCount: (map(select(.value == null)) | length)
            });

        def nestedFields($items; $field):
            fields([$items[]? | .[$field]? | select(type == "object")]);

        {
            shopIndex: $shopIndex,
            responseFields: ($response | keys | sort),
            countOnFirstPage: ($response.data | length),
            reportedTotal: ($response.total // null),
            page: ($response.page // null),
            pageSize: ($response.pageSize // null),
            sort: (
                if ($response.sort | type) == "object"
                then {
                    field: ($response.sort.field // null),
                    direction: ($response.sort.dir // null)
                }
                else null
                end
            ),
            itemSchema: fields($response.data),
            documentTypeValues: (
                [$response.data[]?.type | select(. != null)] | unique
            ),
            idProfile: {
                presentCount: ([$response.data[]? | select(.id? != null)] | length),
                uniqueCount: ([$response.data[]? | .id? // empty] | unique | length)
            },
            nestedSchemas: {
                itemSumm: nestedFields($response.data; "summ"),
                itemCash: nestedFields($response.data; "cash"),
                counteragent: nestedFields($response.data; "counteragent"),
                responseSumm: (
                    if ($response.summ | type) == "object"
                    then fields([$response.summ])
                    else []
                    end
                )
            },
            remainRequest: ($response.remainRequest // null),
            expireDate: ($response.expireDate // null)
        }
    ')"

    STORE_SUMMARIES="$(jq -cn \
        --argjson current "${STORE_SUMMARIES}" \
        --argjson next "${STORE_SUMMARY}" \
        '$current + [$next]')"
done

jq -cn \
    --argjson days "${DISCOVERY_DAYS}" \
    --argjson stores "${STORE_SUMMARIES}" '
    {
        request: {
            endpoint: "/shops/{id}/sales",
            periodDays: $days,
            dateEncoding: "[fromUnixMs,toUnixMs]",
            page: 1,
            pageSize: 50,
            sort: "date DESC"
        },
        shopCount: ($stores | length),
        stores: $stores
    }
'
