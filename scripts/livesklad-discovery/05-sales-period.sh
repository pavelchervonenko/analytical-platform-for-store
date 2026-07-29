#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment
require_command date

DISCOVERY_DAYS="${DISCOVERY_DAYS:-7}"
DISCOVERY_MAX_PAGES="${DISCOVERY_MAX_PAGES:-10}"
[[ "${DISCOVERY_DAYS}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_DAYS must be a positive integer'
((DISCOVERY_DAYS <= 31)) || fail 'DISCOVERY_DAYS must not exceed 31 for the sales profile'

END_MS="$(($(date -u +%s) * 1000))"
START_MS="$((END_MS - DISCOVERY_DAYS * 86400000))"

TOKEN=''
SHOPS_RESPONSE=''
SALES_RESPONSE=''
STORE_SUMMARIES='[]'
SALE_ASSIGNMENTS='[]'
trap 'unset TOKEN SHOPS_RESPONSE SALES_RESPONSE STORE_SUMMARIES SALE_ASSIGNMENTS START_MS END_MS' EXIT

TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
SHOPS_RESPONSE="$(livesklad_get "${TOKEN}" '/shops')" \
    || fail 'LiveSklad shops request failed; response body was not printed'

mapfile -t SHOP_IDS < <(jq -er '.data[]?.id' <<<"${SHOPS_RESPONSE}")
(("${#SHOP_IDS[@]}" > 0)) || fail 'LiveSklad returned no shops'

for index in "${!SHOP_IDS[@]}"; do
    shop_index=$((index + 1))
    shop_id="${SHOP_IDS[${index}]}"

    SALES_RESPONSE="$(livesklad_get_paginated "${TOKEN}" "/shops/${shop_id}/sales" \
        --data-urlencode "date=[${START_MS},${END_MS}]" \
        --data-urlencode 'sort=date DESC')" \
        || fail "Sales request failed for shop index ${shop_index}"

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
                missingCount: (($objects | length) - length),
                nullCount: (map(select(.value == null)) | length)
            });

        def nestedFields($items; $field):
            fields([$items[]? | .[$field]? | select(type == "object")]);

        ($response.data | length) as $count
        |
        {
            shopIndex: $shopIndex,
            count: $count,
            reportedTotal: $response.reportedTotal,
            pagesFetched: $response.pagesFetched,
            allReportedRecordsFetched: (
                $response.reportedTotal == null or $response.reportedTotal == $count
            ),
            responseFields: $response.responseFields,
            itemSchema: fields($response.data),
            documentTypeValues: (
                [$response.data[]?.type | select(. != null)] | unique
            ),
            idProfile: {
                presentCount: ([$response.data[]? | select(.id? != null)] | length),
                uniqueCount: ([$response.data[]? | .id? // empty] | unique | length)
            },
            optionalFields: {
                counteragentPresentCount: (
                    [$response.data[]? | select(has("counteragent") and .counteragent != null)] | length
                ),
                counteragentMissingCount: (
                    [$response.data[]? | select(has("counteragent") | not)] | length
                ),
                nodePresentCount: (
                    [$response.data[]? | select(has("node") and .node != null)] | length
                )
            },
            financialChecks: {
                discountedDocumentCount: (
                    [$response.data[]?
                        | select(
                            (.summ.price | type) == "number"
                            and (.summ.soldPrice | type) == "number"
                            and .summ.price != .summ.soldPrice
                        )
                    ] | length
                ),
                zeroPurchasePriceCount: (
                    [$response.data[]?
                        | select(
                            (.summ.purchasePrice | type) == "number"
                            and .summ.purchasePrice == 0
                        )
                    ] | length
                ),
                negativeAmountDocumentCount: (
                    [$response.data[]?
                        | select(
                            ((.summ.price // 0) < 0)
                            or ((.summ.soldPrice // 0) < 0)
                            or ((.summ.purchasePrice // 0) < 0)
                            or ((.cash.summ // 0) < 0)
                        )
                    ] | length
                ),
                cashAndSoldPriceMismatchCount: (
                    [$response.data[]?
                        | select(
                            (.cash.summ | type) == "number"
                            and (.summ.soldPrice | type) == "number"
                            and .cash.summ != .summ.soldPrice
                        )
                    ] | length
                )
            },
            paymentFlagCombinations: (
                [$response.data[]?
                    | {
                        isBank: .cash.isBank,
                        isMoney: .cash.isMoney
                    }
                ]
                | group_by([.isBank, .isMoney])
                | map({
                    isBank: .[0].isBank,
                    isMoney: .[0].isMoney,
                    count: length
                })
            ),
            nestedSchemas: {
                itemSumm: nestedFields($response.data; "summ"),
                itemCash: nestedFields($response.data; "cash"),
                counteragent: nestedFields($response.data; "counteragent")
            },
            remainRequest: $response.remainRequest,
            expireDate: $response.expireDate
        }
    ')"

    STORE_SUMMARIES="$(jq -cn \
        --argjson current "${STORE_SUMMARIES}" \
        --argjson next "${STORE_SUMMARY}" \
        '$current + [$next]')"

    SALE_ASSIGNMENTS="$(jq -cn \
        --argjson current "${SALE_ASSIGNMENTS}" \
        --argjson sales "${SALES_RESPONSE}" \
        --argjson shopIndex "${shop_index}" '
        $current + [$sales.data[]? | {shopIndex: $shopIndex, id: .id}]
    ')"
done

jq -cn \
    --argjson days "${DISCOVERY_DAYS}" \
    --argjson stores "${STORE_SUMMARIES}" \
    --argjson assignments "${SALE_ASSIGNMENTS}" '
    ($assignments | group_by(.id)) as $identities
    |
    {
        request: {
            endpoint: "/shops/{id}/sales",
            periodDays: $days,
            pageSize: 50,
            allPages: true,
            sort: "date DESC"
        },
        shopCount: ($stores | length),
        stores: $stores,
        crossStoreIdProfile: {
            assignmentCount: ($assignments | length),
            uniqueSaleIdCount: ($identities | length),
            sharedSaleIdCount: (
                [$identities[]
                    | select((map(.shopIndex) | unique | length) > 1)
                ] | length
            )
        }
    }
'
