#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment
require_command date

DISCOVERY_DAYS="${DISCOVERY_DAYS:-30}"
[[ "${DISCOVERY_DAYS}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_DAYS must be a positive integer'
((DISCOVERY_DAYS <= 90)) || fail 'DISCOVERY_DAYS must not exceed 90 for the orders probe'

END_MS="$(($(date -u +%s) * 1000))"
START_MS="$((END_MS - DISCOVERY_DAYS * 86400000))"

TOKEN=''
STATUSES_RESPONSE=''
ORDER_TYPES_RESPONSE=''
ORDERS_RESPONSE=''
trap 'if ((BASH_SUBSHELL == 0)); then printf "ERROR: discovery script failed at line %s\n" "${LINENO}" >&2; fi' ERR
trap 'unset TOKEN STATUSES_RESPONSE ORDER_TYPES_RESPONSE ORDERS_RESPONSE START_MS END_MS' EXIT

printf 'Authenticating and loading order dictionaries...\n' >&2
TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'

STATUSES_RESPONSE="$(livesklad_get "${TOKEN}" '/statuses')" \
    || fail 'Statuses request failed; API access may be restricted'
ORDER_TYPES_RESPONSE="$(livesklad_get "${TOKEN}" '/type-orders')" \
    || fail 'Order types request failed; API access may be restricted'

printf 'Loading the first page of orders for the last %d day(s)...\n' "${DISCOVERY_DAYS}" >&2
ORDERS_RESPONSE="$(livesklad_get "${TOKEN}" '/company/orders' \
    --get \
    --data-urlencode "dateCreate=[${START_MS},${END_MS}]" \
    --data-urlencode 'page=1' \
    --data-urlencode 'pageSize=50' \
    --data-urlencode 'sort=dateCreate DESC')" \
    || fail 'Company orders request failed; API order access may be restricted'

if ! jq -e '.data | type == "object" and all(.[]; (.elements? | type) == "array")' \
    >/dev/null <<<"${STATUSES_RESPONSE}" \
    || ! jq -e 'type == "array" or (.data? | type == "array")' >/dev/null <<<"${ORDER_TYPES_RESPONSE}" \
    || ! jq -e '.data | type == "array"' >/dev/null <<<"${ORDERS_RESPONSE}"; then
    jq -cn \
        --argjson statuses "${STATUSES_RESPONSE}" \
        --argjson orderTypes "${ORDER_TYPES_RESPONSE}" \
        --argjson orders "${ORDERS_RESPONSE}" '
        def shape($response):
            {
                topLevelType: ($response | type),
                topLevelSchema: (
                    if ($response | type) == "object"
                    then [
                        $response
                        | to_entries[]
                        | {field: .key, type: (.value | type)}
                    ]
                    else []
                    end
                ),
                topLevelArrayLength: (
                    if ($response | type) == "array"
                    then ($response | length)
                    else null
                    end
                ),
                arrayPaths: (
                    [$response
                        | paths(type == "array")
                        | map(tostring)
                        | join(".")
                    ]
                )
            };

        {
            discoveryStatus: "unsupported_collection_shape",
            diagnosticNote: "Only structural field names, types, array paths, and counts are shown.",
            statuses: shape($statuses),
            orderTypes: shape($orderTypes),
            orders: shape($orders)
        }
    '
    exit 0
fi

jq -cn \
    --argjson days "${DISCOVERY_DAYS}" \
    --argjson statuses "${STATUSES_RESPONSE}" \
    --argjson orderTypes "${ORDER_TYPES_RESPONSE}" \
    --argjson orders "${ORDERS_RESPONSE}" '
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

    def dictionaryItems($response):
        if ($response | type) == "array"
        then $response
        else $response.data
        end;

    def responseFields($response):
        if ($response | type) == "object"
        then ($response | keys | sort)
        else []
        end;

    def idProfile($items):
        {
            presentCount: ([$items[]? | select(.id? != null)] | length),
            uniqueCount: ([$items[]?.id? | select(. != null)] | unique | length)
        };

    ($statuses.data | to_entries) as $statusGroups
    | ([$statusGroups[]? | .value.elements[]?]) as $statusItems
    | {
        request: {
            dictionaryEndpoints: ["/statuses", "/type-orders"],
            ordersEndpoint: "/company/orders",
            periodDays: $days,
            page: 1,
            pageSize: 50,
            sort: "dateCreate DESC"
        },
        statuses: {
            responseShape: "grouped_object",
            responseFields: responseFields($statuses),
            groupCount: ($statusGroups | length),
            groups: (
                [$statusGroups[]? | {
                    key: .key,
                    statusCount: (.value.elements | length)
                }]
            ),
            groupSchema: fields([$statusGroups[]? | .value]),
            statusCount: ($statusItems | length),
            itemSchema: fields($statusItems),
            idProfile: idProfile($statusItems),
            roles: {
                elementTypes: (
                    [$statusItems[]?.roles[]? | type] | unique
                ),
                objectSchema: fields([
                    $statusItems[]?.roles[]?
                    | select(type == "object")
                ])
            }
        },
        orderTypes: {
            responseShape: ($orderTypes | type),
            responseFields: responseFields($orderTypes),
            count: (dictionaryItems($orderTypes) | length),
            itemSchema: fields(dictionaryItems($orderTypes)),
            idProfile: idProfile(dictionaryItems($orderTypes))
        },
        orders: {
            responseFields: ($orders | keys | sort),
            countOnFirstPage: ($orders.data | length),
            reportedTotal: ($orders.total // null),
            page: ($orders.page // null),
            pageSize: ($orders.pageSize // null),
            itemSchema: fields($orders.data),
            idProfile: idProfile($orders.data),
            nestedSchemas: {
                status: nestedObjects($orders.data; "status"),
                typeOrder: nestedObjects($orders.data; "typeOrder"),
                shop: nestedObjects($orders.data; "shop"),
                manager: nestedObjects($orders.data; "manager"),
                closeManager: nestedObjects($orders.data; "closeManager"),
                master: nestedObjects($orders.data; "master"),
                summ: nestedObjects($orders.data; "summ"),
                cash: nestedObjects($orders.data; "cash"),
                counteragent: nestedObjects($orders.data; "counteragent")
            },
            booleanProfiles: {
                isVisible: (
                    [$orders.data[]? | .isVisible? | select(type == "boolean")]
                    | group_by(.)
                    | map({value: .[0], count: length})
                ),
                isUrgent: (
                    [$orders.data[]? | .isUrgent? | select(type == "boolean")]
                    | group_by(.)
                    | map({value: .[0], count: length})
                )
            }
        },
        lastRateLimitMetadata: {
            remainRequest: ($orders.remainRequest // null),
            expireDate: ($orders.expireDate // null)
        }
    }
'
