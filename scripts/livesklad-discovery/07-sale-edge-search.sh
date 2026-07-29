#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment
require_command date

DISCOVERY_DAYS="${DISCOVERY_DAYS:-30}"
DISCOVERY_DETAIL_SCAN_LIMIT="${DISCOVERY_DETAIL_SCAN_LIMIT:-30}"

[[ "${DISCOVERY_DAYS}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_DAYS must be a positive integer'
((DISCOVERY_DAYS <= 90)) || fail 'DISCOVERY_DAYS must not exceed 90'
[[ "${DISCOVERY_DETAIL_SCAN_LIMIT}" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DISCOVERY_DETAIL_SCAN_LIMIT must be a positive integer'
((DISCOVERY_DETAIL_SCAN_LIMIT <= 60)) || fail 'DISCOVERY_DETAIL_SCAN_LIMIT must not exceed 60'

END_MS="$(($(date -u +%s) * 1000))"
START_MS="$((END_MS - DISCOVERY_DAYS * 86400000))"

TOKEN=''
SHOPS_RESPONSE=''
SALES_RESPONSE=''
DETAIL_RESPONSE=''
SALE_LISTS='[]'
DETAIL_RECORDS='[]'
FOUND_WORK=false
FOUND_RETURN=false
FOUND_QUANTITY=false
STOP_REASON='scan_limit'
trap 'if ((BASH_SUBSHELL == 0)); then printf "ERROR: discovery script failed at line %s\n" "${LINENO}" >&2; fi' ERR
trap 'unset TOKEN SHOPS_RESPONSE SALES_RESPONSE DETAIL_RESPONSE SALE_LISTS DETAIL_RECORDS START_MS END_MS' EXIT

printf 'Authenticating and loading sales candidates...\n' >&2
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

    SALE_LISTS="$(jq -cn \
        --argjson current "${SALE_LISTS}" \
        --argjson response "${SALES_RESPONSE}" \
        --argjson shopIndex "${shop_index}" '
        $current + [{
            shopIndex: $shopIndex,
            data: $response.data
        }]
    ')"
done

CANDIDATES="$(jq -cn \
    --argjson lists "${SALE_LISTS}" '
    [range(0; 50) as $offset
        | $lists[] as $shop
        | $shop.data[$offset]?
        | {
            shopIndex: $shop.shopIndex,
            id
        }
    ]
')"

mapfile -t CANDIDATE_ROWS < <(jq -c '.[]' <<<"${CANDIDATES}")

for candidate in "${CANDIDATE_ROWS[@]}"; do
    scanned_count="$(jq 'length' <<<"${DETAIL_RECORDS}")"
    ((scanned_count < DISCOVERY_DETAIL_SCAN_LIMIT)) || break

    shop_index="$(jq -er '.shopIndex' <<<"${candidate}")"
    sale_id="$(jq -er '.id' <<<"${candidate}")"

    printf 'Scanning sale detail %d/%d (shop index %s)...\n' \
        "$((scanned_count + 1))" "${DISCOVERY_DETAIL_SCAN_LIMIT}" "${shop_index}" >&2
    DETAIL_RESPONSE="$(livesklad_get "${TOKEN}" "/documents/${sale_id}")" \
        || fail "Sale detail request failed for shop index ${shop_index}"
    jq -e '.data | type == "object"' >/dev/null <<<"${DETAIL_RESPONSE}" \
        || fail "Sale detail response for shop index ${shop_index} does not contain a data object"

    DETAIL_RECORDS="$(jq -cn \
        --argjson current "${DETAIL_RECORDS}" \
        --argjson response "${DETAIL_RESPONSE}" \
        --argjson shopIndex "${shop_index}" '
        $current + [{
            shopIndex: $shopIndex,
            detail: $response.data,
            remainRequest: ($response.remainRequest // null),
            expireDate: ($response.expireDate // null)
        }]
    ')"

    detail_has_work="$(jq -r 'any(.data.positions[]?; .isWork? == true)' <<<"${DETAIL_RESPONSE}")"
    detail_has_return="$(jq -r 'any(.data.positions[]?; (.returnCount? // 0) > 0)' <<<"${DETAIL_RESPONSE}")"
    detail_has_quantity="$(jq -r 'any(.data.positions[]?; (.count? // 0) > 1)' <<<"${DETAIL_RESPONSE}")"

    if [[ "${detail_has_work}" == true ]]; then
        FOUND_WORK=true
    fi
    if [[ "${detail_has_return}" == true ]]; then
        FOUND_RETURN=true
    fi
    if [[ "${detail_has_quantity}" == true ]]; then
        FOUND_QUANTITY=true
    fi

    scanned_count="$(jq 'length' <<<"${DETAIL_RECORDS}")"
    if ((scanned_count >= 10)) \
        && [[ "${FOUND_WORK}" == true ]] \
        && [[ "${FOUND_RETURN}" == true ]] \
        && [[ "${FOUND_QUANTITY}" == true ]]; then
        STOP_REASON='all_primary_edges_found'
        break
    fi

    remain_request="$(jq -r '.remainRequest // 999' <<<"${DETAIL_RESPONSE}")"
    if ((remain_request <= 5)); then
        STOP_REASON='rate_limit_guard'
        break
    fi
done

scanned_count="$(jq 'length' <<<"${DETAIL_RECORDS}")"
if [[ "${STOP_REASON}" == 'scan_limit' ]] \
    && ((scanned_count < DISCOVERY_DETAIL_SCAN_LIMIT)); then
    STOP_REASON='candidates_exhausted'
fi
printf 'Scan complete: %d document(s), stop reason: %s.\n' \
    "${scanned_count}" "${STOP_REASON}" >&2

jq -cn \
    --argjson days "${DISCOVERY_DAYS}" \
    --argjson scanLimit "${DISCOVERY_DETAIL_SCAN_LIMIT}" \
    --arg stopReason "${STOP_REASON}" \
    --argjson records "${DETAIL_RECORDS}" '
    def fields($objects):
        [$objects[]? | select(type == "object") | to_entries[]]
        | sort_by(.key)
        | group_by(.key)
        | map({
            field: .[0].key,
            observedTypes: (map(.value | type) | unique),
            presentCount: length,
            missingCount: (($objects | length) - length),
            nullCount: (map(select(.value == null)) | length)
        });

    ($records | map(.detail)) as $documents
    | ([$documents[]? | .positions[]?]) as $positions
    | ([$positions[]? | .batches[]?]) as $batches
    | ([$documents[]? | .cash.elements[]?]) as $cashElements
    |
    {
        request: {
            detailEndpoint: "/documents/{id}",
            candidatePeriodDays: $days,
            scanLimit: $scanLimit
        },
        scannedDocumentCount: ($documents | length),
        stopReason: $stopReason,
        edgeCounts: {
            documentsWithWork: (
                [$documents[]? | select(any(.positions[]?; .isWork? == true))] | length
            ),
            documentsWithReturns: (
                [$documents[]? | select(any(.positions[]?; (.returnCount? // 0) > 0))] | length
            ),
            documentsWithQuantityGreaterThanOne: (
                [$documents[]? | select(any(.positions[]?; (.count? // 0) > 1))] | length
            ),
            documentsWithGuarantee: (
                [$documents[]? | select(any(.positions[]?; (.guaranteeInDay? // 0) > 0))] | length
            ),
            documentsWithSerialItems: (
                [$documents[]? | select(any(.positions[]?; .isSerial? == true))] | length
            ),
            documentsWithMultipleBatchesInOnePosition: (
                [$documents[]?
                    | select(any(.positions[]?; (.batches? // [] | length) > 1))
                ] | length
            )
        },
        edgePositionSchemas: {
            work: fields([$positions[]? | select(.isWork? == true)]),
            returned: fields([$positions[]? | select((.returnCount? // 0) > 0)]),
            quantityGreaterThanOne: fields([$positions[]? | select((.count? // 0) > 1)])
        },
        cashElements: {
            count: ($cashElements | length),
            schema: fields($cashElements),
            typeValues: (
                [$cashElements[]?.type | select(. != null)] | unique
            )
        },
        batchChecks: {
            totalCount: ($batches | length),
            countsPerPosition: (
                [$positions[]? | (.batches? // [] | length)]
                | group_by(.)
                | map({batchCount: .[0], positionCount: length})
            ),
            purchasePriceSummMatchesBatchExtendedCostCount: (
                [$positions[]?
                    | select(
                        (.purchasePriceSumm? | type) == "number"
                        and (
                            [.batches[]? | (.purchasePrice // 0) * (.count // 0)]
                            | add // 0
                        ) == .purchasePriceSumm
                    )
                ] | length
            )
        },
        quantityChecks: {
            quantityGreaterThanOnePositionCount: (
                [$positions[]? | select((.count? // 0) > 1)] | length
            ),
            priceSemantics: (
                if ([$positions[]? | select((.count? // 0) > 1)] | length) > 0
                then "requires_targeted_semantic_review"
                else "quantity_greater_than_one_not_observed"
                end
            )
        },
        lastRateLimitMetadata: (
            $records[-1]?
            | {
                remainRequest,
                expireDate
            }
        )
    }
'
