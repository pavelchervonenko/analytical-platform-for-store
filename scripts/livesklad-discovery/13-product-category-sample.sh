#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment
require_command date

DISCOVERY_DAYS="${DISCOVERY_DAYS:-30}"
DISCOVERY_PRODUCT_DETAIL_LIMIT="${DISCOVERY_PRODUCT_DETAIL_LIMIT:-60}"

[[ "${DISCOVERY_DAYS}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_DAYS must be a positive integer'
((DISCOVERY_DAYS <= 90)) || fail 'DISCOVERY_DAYS must not exceed 90'
[[ "${DISCOVERY_PRODUCT_DETAIL_LIMIT}" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DISCOVERY_PRODUCT_DETAIL_LIMIT must be a positive integer'
((DISCOVERY_PRODUCT_DETAIL_LIMIT <= 70)) \
    || fail 'DISCOVERY_PRODUCT_DETAIL_LIMIT must not exceed 70'

END_MS="$(($(date -u +%s) * 1000))"
START_MS="$((END_MS - DISCOVERY_DAYS * 86400000))"

TOKEN=''
SHOPS_RESPONSE=''
SALES_RESPONSE=''
DETAIL_RESPONSE=''
SALE_LISTS='[]'
POSITIONS_FILE=''
SCANNED_COUNT=0
LAST_REMAIN_REQUEST='null'
LAST_EXPIRE_DATE='null'
STOP_REASON='scan_limit'
trap 'if ((BASH_SUBSHELL == 0)); then printf "ERROR: discovery script failed at line %s\n" "${LINENO}" >&2; fi' ERR

POSITIONS_FILE="$(mktemp)" || fail 'Could not create a temporary product-sample file'
trap 'rm -f -- "${POSITIONS_FILE}"; unset TOKEN SHOPS_RESPONSE SALES_RESPONSE DETAIL_RESPONSE SALE_LISTS POSITIONS_FILE START_MS END_MS' EXIT

printf 'Authenticating and loading recent sale candidates...\n' >&2
TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
SHOPS_RESPONSE="$(livesklad_get "${TOKEN}" '/shops')" \
    || fail 'LiveSklad shops request failed'

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
        || fail "Sales response for shop index ${shop_index} has no data array"

    SALE_LISTS="$(jq -cn \
        --argjson current "${SALE_LISTS}" \
        --argjson shopIndex "${shop_index}" \
        --argjson response "${SALES_RESPONSE}" '
        $current + [{
            shopIndex: $shopIndex,
            data: $response.data
        }]
    ')"
done

CANDIDATES="$(jq -cn \
    --argjson lists "${SALE_LISTS}" \
    --argjson limit "${DISCOVERY_PRODUCT_DETAIL_LIMIT}" '
    [range(0; 50) as $offset
        | $lists[] as $shop
        | $shop.data[$offset]?
        | {
            shopIndex: $shop.shopIndex,
            id
        }
    ][: $limit]
')"

mapfile -t CANDIDATE_ROWS < <(jq -c '.[]' <<<"${CANDIDATES}")

for candidate in "${CANDIDATE_ROWS[@]}"; do
    shop_index="$(jq -er '.shopIndex' <<<"${candidate}")"
    sale_id="$(jq -er '.id' <<<"${candidate}")"
    SCANNED_COUNT=$((SCANNED_COUNT + 1))

    printf 'Sampling sale detail %d/%d (shop index %s)...\n' \
        "${SCANNED_COUNT}" "${#CANDIDATE_ROWS[@]}" "${shop_index}" >&2
    DETAIL_RESPONSE="$(livesklad_get "${TOKEN}" "/documents/${sale_id}")" \
        || fail "Sale detail request failed at sample ${SCANNED_COUNT}"

    jq -e '.data.positions | type == "array"' >/dev/null <<<"${DETAIL_RESPONSE}" \
        || fail "Sale detail at sample ${SCANNED_COUNT} has no positions array"

    jq -c '
        .data.positions[]?
        | {
            nomenclatureId: (.nomenclatureId // null),
            modifyId: (.modifyId // null),
            name: (.name // null),
            isWorkPresent: has("isWork"),
            isWork: (
                if has("isWork") then .isWork else null end
            ),
            isSerialPresent: has("isSerial"),
            isSerial: (
                if has("isSerial") then .isSerial else null end
            ),
            guaranteeInDay: (.guaranteeInDay // null),
            measureValue: (.measure.value // null),
            count: (.count // 0)
        }
    ' <<<"${DETAIL_RESPONSE}" >>"${POSITIONS_FILE}"

    LAST_REMAIN_REQUEST="$(jq -c '.remainRequest // null' <<<"${DETAIL_RESPONSE}")"
    LAST_EXPIRE_DATE="$(jq -c '.expireDate // null' <<<"${DETAIL_RESPONSE}")"

    remain_request="$(jq -r '.remainRequest // 999' <<<"${DETAIL_RESPONSE}")"
    if ((remain_request <= 5)); then
        STOP_REASON='rate_limit_guard'
        break
    fi
done

if ((SCANNED_COUNT < DISCOVERY_PRODUCT_DETAIL_LIMIT)) \
    && [[ "${STOP_REASON}" == 'scan_limit' ]]; then
    STOP_REASON='candidates_exhausted'
fi

jq -cn \
    --argjson days "${DISCOVERY_DAYS}" \
    --argjson detailLimit "${DISCOVERY_PRODUCT_DETAIL_LIMIT}" \
    --argjson scannedCount "${SCANNED_COUNT}" \
    --arg stopReason "${STOP_REASON}" \
    --argjson remainRequest "${LAST_REMAIN_REQUEST}" \
    --argjson expireDate "${LAST_EXPIRE_DATE}" \
    --slurpfile positions "${POSITIONS_FILE}" '
    ([$positions[]?
        | select(
            (.nomenclatureId | type) == "string"
            and (.nomenclatureId | length) > 0
        )
    ]) as $valid
    | {
        request: {
            listEndpoint: "/shops/{id}/sales",
            detailEndpoint: "/documents/{id}",
            periodDays: $days,
            detailLimit: $detailLimit
        },
        scannedDocumentCount: $scannedCount,
        stopReason: $stopReason,
        sampledPositionCount: ($positions | length),
        positionsWithoutProductIdCount: (
            [$positions[]?
                | select(
                    (.nomenclatureId | type) != "string"
                    or (.nomenclatureId | length) == 0
                )
            ] | length
        ),
        uniqueProductCount: ([$valid[].nomenclatureId] | unique | length),
        products: (
            $valid
            | sort_by(.nomenclatureId)
            | group_by(.nomenclatureId)
            | map({
                nomenclatureId: .[0].nomenclatureId,
                observedNames: ([.[].name | select(type == "string")] | unique),
                modifyIds: ([.[].modifyId | select(type == "string")] | unique),
                isWorkValues: ([.[] | select(.isWorkPresent) | .isWork] | unique),
                isSerialValues: ([.[] | select(.isSerialPresent) | .isSerial] | unique),
                guaranteeDays: ([.[].guaranteeInDay | select(type == "number")] | unique),
                measureValues: ([.[].measureValue | select(type == "string")] | unique),
                sampledPositionCount: length,
                sampledUnitCount: ([.[].count] | add // 0)
            })
            | sort_by(.observedNames[0] // "")
        ),
        lastRateLimitMetadata: {
            remainRequest: $remainRequest,
            expireDate: $expireDate
        }
    }
'
