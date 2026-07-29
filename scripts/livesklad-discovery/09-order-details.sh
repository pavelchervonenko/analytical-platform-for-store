#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment
require_command date

DISCOVERY_DAYS="${DISCOVERY_DAYS:-30}"
DISCOVERY_ORDER_DETAIL_LIMIT="${DISCOVERY_ORDER_DETAIL_LIMIT:-10}"

[[ "${DISCOVERY_DAYS}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_DAYS must be a positive integer'
((DISCOVERY_DAYS <= 90)) || fail 'DISCOVERY_DAYS must not exceed 90'
[[ "${DISCOVERY_ORDER_DETAIL_LIMIT}" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DISCOVERY_ORDER_DETAIL_LIMIT must be a positive integer'
((DISCOVERY_ORDER_DETAIL_LIMIT <= 15)) || fail 'DISCOVERY_ORDER_DETAIL_LIMIT must not exceed 15'

END_MS="$(($(date -u +%s) * 1000))"
START_MS="$((END_MS - DISCOVERY_DAYS * 86400000))"

TOKEN=''
ORDERS_RESPONSE=''
ORDER_TYPES_RESPONSE=''
DETAIL_RESPONSE=''
FIELDS_RESPONSE=''
DETAIL_RECORDS='[]'
FIELD_RECORDS='[]'
trap 'if ((BASH_SUBSHELL == 0)); then printf "ERROR: discovery script failed at line %s\n" "${LINENO}" >&2; fi' ERR
trap 'unset TOKEN ORDERS_RESPONSE ORDER_TYPES_RESPONSE DETAIL_RESPONSE FIELDS_RESPONSE DETAIL_RECORDS FIELD_RECORDS START_MS END_MS' EXIT

printf 'Authenticating and loading representative order candidates...\n' >&2
TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'

ORDERS_RESPONSE="$(livesklad_get "${TOKEN}" '/company/orders' \
    --get \
    --data-urlencode "dateCreate=[${START_MS},${END_MS}]" \
    --data-urlencode 'page=1' \
    --data-urlencode 'pageSize=50' \
    --data-urlencode 'sort=dateCreate DESC')" \
    || fail 'Company orders request failed'
ORDER_TYPES_RESPONSE="$(livesklad_get "${TOKEN}" '/type-orders')" \
    || fail 'Order types request failed'

jq -e '.data | type == "array"' >/dev/null <<<"${ORDERS_RESPONSE}" \
    || fail 'Orders response does not contain a data array'
jq -e '.data | type == "array"' >/dev/null <<<"${ORDER_TYPES_RESPONSE}" \
    || fail 'Order types response does not contain a data array'

CANDIDATES="$(jq -cn \
    --argjson response "${ORDERS_RESPONSE}" \
    --argjson limit "${DISCOVERY_ORDER_DETAIL_LIMIT}" '
    ($response.data) as $orders
    | ([
        $orders[0],
        ($orders | group_by(.status.id)[]? | .[0]),
        ($orders | group_by(.typeOrder.id)[]? | .[0]),
        ($orders | group_by(.shop.id)[]? | .[0]),
        ($orders | map(select(.manager? != null)) | .[0]),
        ($orders | map(select(.manager? == null)) | .[0])
    ]
        | map(select(.id? != null))
        | unique_by(.id)
        | .[:$limit]
        | map({
            id,
            listStatusId: (.status.id // null),
            listTypeOrderId: (.typeOrder.id // null),
            listShopId: (.shop.id // null)
        })
    )
')"

mapfile -t CANDIDATE_ROWS < <(jq -c '.[]' <<<"${CANDIDATES}")
(("${#CANDIDATE_ROWS[@]}" > 0)) || fail 'No recent orders were returned'

for candidate in "${CANDIDATE_ROWS[@]}"; do
    scanned_count="$(jq 'length' <<<"${DETAIL_RECORDS}")"
    order_id="$(jq -er '.id' <<<"${candidate}")"

    printf 'Scanning order detail %d/%d...\n' \
        "$((scanned_count + 1))" "${#CANDIDATE_ROWS[@]}" >&2
    DETAIL_RESPONSE="$(livesklad_get "${TOKEN}" "/orders/${order_id}")" \
        || fail "Order detail request failed at candidate $((scanned_count + 1))"
    jq -e '.data | type == "object"' >/dev/null <<<"${DETAIL_RESPONSE}" \
        || fail "Order detail response at candidate $((scanned_count + 1)) has no data object"

    DETAIL_RECORDS="$(jq -cn \
        --argjson current "${DETAIL_RECORDS}" \
        --argjson candidate "${candidate}" \
        --argjson response "${DETAIL_RESPONSE}" '
        $current + [{
            list: $candidate,
            detail: $response.data,
            remainRequest: ($response.remainRequest // null),
            expireDate: ($response.expireDate // null)
        }]
    ')"
done

mapfile -t ORDER_TYPE_IDS < <(jq -er '.data[]?.id' <<<"${ORDER_TYPES_RESPONSE}")
for index in "${!ORDER_TYPE_IDS[@]}"; do
    type_index=$((index + 1))
    type_id="${ORDER_TYPE_IDS[${index}]}"

    printf 'Scanning custom fields for order type %d/%d...\n' \
        "${type_index}" "${#ORDER_TYPE_IDS[@]}" >&2
    FIELDS_RESPONSE="$(livesklad_get "${TOKEN}" "/type-orders/${type_id}/fields")" \
        || fail "Custom fields request failed for order type index ${type_index}"

    FIELD_RECORDS="$(jq -cn \
        --argjson current "${FIELD_RECORDS}" \
        --argjson typeIndex "${type_index}" \
        --argjson response "${FIELDS_RESPONSE}" '
        $current + [{
            typeIndex: $typeIndex,
            response: $response
        }]
    ')"
done

jq -cn \
    --argjson days "${DISCOVERY_DAYS}" \
    --argjson limit "${DISCOVERY_ORDER_DETAIL_LIMIT}" \
    --argjson records "${DETAIL_RECORDS}" \
    --argjson fieldRecords "${FIELD_RECORDS}" '
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

    def nestedObjects($items; $field):
        fields([$items[]? | .[$field]? | select(type == "object")]);

    def fieldItems($response):
        if ($response | type) == "array"
        then $response
        elif ($response.data? | type) == "array"
        then $response.data
        else []
        end;

    ($records | map(.detail)) as $orders
    | ([$orders[]? | .positions[]?]) as $positions
    | ([$orders[]? | .customFields[]?]) as $customFields
    | ([$fieldRecords[]? | fieldItems(.response)[]?]) as $typeFields
    | {
        request: {
            listEndpoint: "/company/orders",
            detailEndpoint: "/orders/{id}",
            customFieldsEndpoint: "/type-orders/{id}/fields",
            candidatePeriodDays: $days,
            detailLimit: $limit
        },
        detailCount: ($orders | length),
        detailSchema: fields($orders),
        topLevelArrayFields: (
            [$orders[]?
                | to_entries[]
                | select(.value | type == "array")
                | {
                    field: .key,
                    elementTypes: ([.value[]? | type] | unique)
                }
            ]
            | sort_by(.field)
            | group_by(.field)
            | map({
                field: .[0].field,
                observedElementTypes: (map(.elementTypes[]) | unique),
                presentCount: length
            })
        ),
        nestedSchemas: {
            status: nestedObjects($orders; "status"),
            typeOrder: nestedObjects($orders; "typeOrder"),
            shop: nestedObjects($orders; "shop"),
            createManager: nestedObjects($orders; "createManager"),
            manager: nestedObjects($orders; "manager"),
            closeManager: nestedObjects($orders; "closeManager"),
            master: nestedObjects($orders; "master"),
            customer: nestedObjects($orders; "customer"),
            cash: nestedObjects($orders; "cash"),
            summ: nestedObjects($orders; "summ"),
            counteragent: nestedObjects($orders; "counteragent")
        },
        positions: {
            count: ($positions | length),
            schema: fields($positions),
            batchSchema: fields([$positions[]? | .batches[]?]),
            measureSchema: nestedObjects($positions; "measure"),
            customerSchema: nestedObjects($positions; "customer")
        },
        cashElements: {
            count: ([$orders[]? | .cash.elements[]?] | length),
            schema: fields([$orders[]? | .cash.elements[]?])
        },
        detailCustomFields: {
            count: ($customFields | length),
            schema: fields($customFields)
        },
        typeCustomFields: {
            typeCount: ($fieldRecords | length),
            supportedResponseCount: (
                [$fieldRecords[]?
                    | select(
                        (.response | type) == "array"
                        or (.response.data? | type) == "array"
                    )
                ] | length
            ),
            countsByTypeIndex: (
                [$fieldRecords[]? | {
                    typeIndex,
                    fieldCount: (fieldItems(.response) | length),
                    responseShape: (
                        if (.response | type) == "array"
                        then "array"
                        elif (.response.data? | type) == "array"
                        then "data_array"
                        else "unsupported"
                        end
                    )
                }]
            ),
            schema: fields($typeFields)
        },
        relationChecks: {
            detailIdMatchesListCount: (
                [$records[]? | select(.detail.id? == .list.id)] | length
            ),
            statusIdMatchesListCount: (
                [$records[]?
                    | select(
                        .list.listStatusId == null
                        or .detail.status.id? == .list.listStatusId
                    )
                ] | length
            ),
            typeOrderIdMatchesListCount: (
                [$records[]?
                    | select(
                        .list.listTypeOrderId == null
                        or .detail.typeOrder.id? == .list.listTypeOrderId
                    )
                ] | length
            ),
            shopIdMatchesListCount: (
                [$records[]?
                    | select(
                        .list.listShopId == null
                        or .detail.shop.id? == .list.listShopId
                    )
                ] | length
            )
        },
        employeeFieldPresence: {
            manager: ([$orders[]? | select(.manager? != null)] | length),
            closeManager: ([$orders[]? | select(.closeManager? != null)] | length),
            master: ([$orders[]? | select(.master? != null)] | length),
            customer: ([$orders[]? | select(.customer? != null)] | length)
        },
        lastRateLimitMetadata: (
            (
                if ($fieldRecords | length) > 0
                then $fieldRecords[-1].response
                else ($records[-1] // {})
                end
            )
            | {
                remainRequest: (.remainRequest // null),
                expireDate: (.expireDate // null)
            }
        )
    }
'
