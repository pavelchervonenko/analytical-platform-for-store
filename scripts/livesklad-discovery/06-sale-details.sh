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
DISCOVERY_SAMPLES_PER_STORE="${DISCOVERY_SAMPLES_PER_STORE:-8}"

[[ "${DISCOVERY_DAYS}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_DAYS must be a positive integer'
((DISCOVERY_DAYS <= 31)) || fail 'DISCOVERY_DAYS must not exceed 31'
[[ "${DISCOVERY_SAMPLES_PER_STORE}" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DISCOVERY_SAMPLES_PER_STORE must be a positive integer'
((DISCOVERY_SAMPLES_PER_STORE <= 10)) || fail 'DISCOVERY_SAMPLES_PER_STORE must not exceed 10'

END_MS="$(($(date -u +%s) * 1000))"
START_MS="$((END_MS - DISCOVERY_DAYS * 86400000))"

TOKEN=''
SHOPS_RESPONSE=''
SALES_RESPONSE=''
EMPLOYEES_RESPONSE=''
DETAIL_RESPONSE=''
DETAIL_RECORDS='[]'
trap 'unset TOKEN SHOPS_RESPONSE SALES_RESPONSE EMPLOYEES_RESPONSE DETAIL_RESPONSE DETAIL_RECORDS START_MS END_MS' EXIT

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

    EMPLOYEES_RESPONSE="$(livesklad_get_paginated "${TOKEN}" "/shops/${shop_id}/customers")" \
        || fail "Employees request failed for shop index ${shop_index}"

    CANDIDATES="$(jq -cn \
        --argjson response "${SALES_RESPONSE}" \
        --argjson limit "${DISCOVERY_SAMPLES_PER_STORE}" '
        [
            ($response.data[0]? | {id: .id, reason: "latest"}),
            ([$response.data[]?
                | select(.summ.price != .summ.soldPrice)
            ][0]? | {id: .id, reason: "discounted"}),
            ([$response.data[]?
                | select(.summ.purchasePrice == 0)
            ][0]? | {id: .id, reason: "zero_purchase_price"}),
            ([$response.data[]?
                | select(has("counteragent") | not)
            ][0]? | {id: .id, reason: "missing_counteragent"}),
            ([$response.data[]?
                | select(has("node") and .node != null)
            ][0]? | {id: .id, reason: "node_present"}),
            ([$response.data[]?
                | select(.cash.isBank == false and .cash.isMoney == true)
            ][0]? | {id: .id, reason: "cash_only"}),
            ([$response.data[]?
                | select(.cash.isBank == true and .cash.isMoney == false)
            ][0]? | {id: .id, reason: "bank_only"}),
            ([$response.data[]?
                | select(.cash.isBank == true and .cash.isMoney == true)
            ][0]? | {id: .id, reason: "mixed_payment"})
        ]
        | map(select(.id != null))
        | group_by(.id)
        | map({
            id: .[0].id,
            reasons: (map(.reason) | unique)
        })
        | .[0:$limit]
    ')"

    mapfile -t CANDIDATE_ROWS < <(jq -c '.[]' <<<"${CANDIDATES}")

    for candidate in "${CANDIDATE_ROWS[@]}"; do
        sale_id="$(jq -er '.id' <<<"${candidate}")"
        reasons="$(jq -c '.reasons' <<<"${candidate}")"

        DETAIL_RESPONSE="$(livesklad_get "${TOKEN}" "/documents/${sale_id}")" \
            || fail "Sale detail request failed for shop index ${shop_index}"
        jq -e '.data | type == "object"' >/dev/null <<<"${DETAIL_RESPONSE}" \
            || fail "Sale detail response for shop index ${shop_index} does not contain a data object"

        DETAIL_RECORD="$(jq -cn \
            --argjson shopIndex "${shop_index}" \
            --argjson reasons "${reasons}" \
            --argjson response "${DETAIL_RESPONSE}" \
            --argjson employees "${EMPLOYEES_RESPONSE}" \
            --arg requestedShopId "${shop_id}" '
            {
                shopIndex: $shopIndex,
                reasons: $reasons,
                customerMatchesEmployee: (
                    if ($response.data.customer.id? // null) == null
                    then null
                    else any($employees.data[]?; .id == $response.data.customer.id)
                    end
                ),
                shopMatchesRequestedStore: (
                    if ($response.data.shop.id? // null) == null
                    then null
                    else $response.data.shop.id == $requestedShopId
                    end
                ),
                detail: $response.data,
                remainRequest: ($response.remainRequest // null),
                expireDate: ($response.expireDate // null)
            }
        ')"

        DETAIL_RECORDS="$(jq -cn \
            --argjson current "${DETAIL_RECORDS}" \
            --argjson next "${DETAIL_RECORD}" \
            '$current + [$next]')"
    done
done

jq -cn \
    --argjson days "${DISCOVERY_DAYS}" \
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

    def nestedFields($items; $field):
        fields([$items[]? | .[$field]? | select(type == "object")]);

    ($records | map(.detail)) as $documents
    | ([$documents[]? | .positions[]?]) as $positions
    | ([$positions[]? | .batches[]?]) as $batches
    |
    {
        request: {
            listEndpoint: "/shops/{id}/sales",
            detailEndpoint: "/documents/{id}",
            periodDays: $days,
            selectionStrategies: [
                "latest",
                "discounted",
                "zero_purchase_price",
                "missing_counteragent",
                "node_present",
                "cash_only",
                "bank_only",
                "mixed_payment"
            ]
        },
        selectedDocumentCount: ($documents | length),
        selectionReasonCounts: (
            [$records[]?.reasons[]]
            | group_by(.)
            | map({
                reason: .[0],
                count: length
            })
        ),
        relationChecks: {
            customerMatchesEmployee: (
                [$records[]? | .customerMatchesEmployee]
                | group_by(.)
                | map({value: .[0], count: length})
            ),
            shopMatchesRequestedStore: (
                [$records[]? | .shopMatchesRequestedStore]
                | group_by(.)
                | map({value: .[0], count: length})
            )
        },
        documentSchema: fields($documents),
        documentNestedSchemas: {
            customer: nestedFields($documents; "customer"),
            shop: nestedFields($documents; "shop"),
            counteragent: nestedFields($documents; "counteragent"),
            cash: nestedFields($documents; "cash"),
            howKnow: nestedFields($documents; "howKnow")
        },
        positions: {
            totalCount: ($positions | length),
            countsPerDocument: (
                [$documents[]? | (.positions // [] | length)]
                | group_by(.)
                | map({positionCount: .[0], documentCount: length})
            ),
            schema: fields($positions),
            positionIdProfile: {
                presentCount: ([$positions[]? | select(.positionId? != null)] | length),
                uniqueCount: ([$positions[]? | .positionId? // empty] | unique | length)
            },
            nomenclatureIdProfile: {
                presentCount: ([$positions[]? | select(.nomenclatureId? != null)] | length),
                uniqueCount: ([$positions[]? | .nomenclatureId? // empty] | unique | length)
            },
            edgeCounts: {
                workCount: ([$positions[]? | select(.isWork? == true)] | length),
                serialCount: ([$positions[]? | select(.isSerial? == true)] | length),
                returnedCount: ([$positions[]? | select((.returnCount? // 0) > 0)] | length),
                quantityGreaterThanOneCount: ([$positions[]? | select((.count? // 0) > 1)] | length),
                discountedCount: (
                    [$positions[]?
                        | select(
                            (.price? | type) == "number"
                            and (.soldPrice? | type) == "number"
                            and .price != .soldPrice
                        )
                    ] | length
                ),
                zeroPurchasePriceCount: (
                    [$positions[]?
                        | select(
                            (.purchasePriceSumm? | type) == "number"
                            and .purchasePriceSumm == 0
                        )
                    ] | length
                )
            },
            nestedSchemas: {
                batches: fields($batches),
                measure: nestedFields($positions; "measure")
            }
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
