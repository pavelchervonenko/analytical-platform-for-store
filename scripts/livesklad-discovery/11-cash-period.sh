#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment
require_command date

DISCOVERY_DAYS="${DISCOVERY_DAYS:-30}"
DISCOVERY_MAX_PAGES="${DISCOVERY_MAX_PAGES:-40}"
DISCOVERY_PAGE_SIZE=50

[[ "${DISCOVERY_DAYS}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_DAYS must be a positive integer'
((DISCOVERY_DAYS <= 31)) || fail 'DISCOVERY_DAYS must not exceed 31 for the complete cash probe'
[[ "${DISCOVERY_MAX_PAGES}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_MAX_PAGES must be a positive integer'
((DISCOVERY_MAX_PAGES <= 40)) || fail 'DISCOVERY_MAX_PAGES must not exceed 40'

END_MS="$(($(date -u +%s) * 1000))"
START_MS="$((END_MS - DISCOVERY_DAYS * 86400000))"

TOKEN=''
SHOPS_RESPONSE=''
CASH_ITEMS_RESPONSE=''
EMPLOYEES_RESPONSE=''
REGISTERS_RESPONSE=''
PAGINATED_FILE=''
TRANSACTION_FILE=''
REGISTER_RECORDS='[]'
EMPLOYEE_IDS='[]'
trap 'if ((BASH_SUBSHELL == 0)); then printf "ERROR: discovery script failed at line %s\n" "${LINENO}" >&2; fi' ERR

PAGINATED_FILE="$(mktemp)" || fail 'Could not create a temporary paginated-response file'
TRANSACTION_FILE="$(mktemp)" || {
    rm -f -- "${PAGINATED_FILE}"
    fail 'Could not create a temporary transaction-records file'
}
trap 'rm -f -- "${PAGINATED_FILE}" "${TRANSACTION_FILE}"; unset TOKEN SHOPS_RESPONSE CASH_ITEMS_RESPONSE EMPLOYEES_RESPONSE REGISTERS_RESPONSE PAGINATED_FILE TRANSACTION_FILE REGISTER_RECORDS EMPLOYEE_IDS START_MS END_MS' EXIT

printf 'Authenticating and loading cash dictionaries...\n' >&2
TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
SHOPS_RESPONSE="$(livesklad_get "${TOKEN}" '/shops')" \
    || fail 'LiveSklad shops request failed'
CASH_ITEMS_RESPONSE="$(livesklad_get "${TOKEN}" '/cash-items')" \
    || fail 'Cash items request failed'

jq -e '.data | type == "array"' >/dev/null <<<"${SHOPS_RESPONSE}" \
    || fail 'Shops response does not contain a data array'
jq -e '.data | type == "array"' >/dev/null <<<"${CASH_ITEMS_RESPONSE}" \
    || fail 'Cash items response does not contain a data array'

mapfile -t SHOP_IDS < <(jq -er '.data[]?.id' <<<"${SHOPS_RESPONSE}")
for index in "${!SHOP_IDS[@]}"; do
    shop_index=$((index + 1))
    shop_id="${SHOP_IDS[${index}]}"

    printf 'Loading employees and registers for shop %d/%d...\n' \
        "${shop_index}" "${#SHOP_IDS[@]}" >&2
    EMPLOYEES_RESPONSE="$(livesklad_get "${TOKEN}" "/shops/${shop_id}/customers" \
        --get \
        --data-urlencode 'page=1' \
        --data-urlencode 'pageSize=50')" \
        || fail "Employees request failed for shop index ${shop_index}"
    REGISTERS_RESPONSE="$(livesklad_get "${TOKEN}" "/shops/${shop_id}/cash-registers")" \
        || fail "Cash registers request failed for shop index ${shop_index}"

    jq -e '.data | type == "array"' >/dev/null <<<"${EMPLOYEES_RESPONSE}" \
        || fail "Employees response for shop index ${shop_index} has no data array"
    jq -e '.data | type == "array"' >/dev/null <<<"${REGISTERS_RESPONSE}" \
        || fail "Cash registers response for shop index ${shop_index} has no data array"

    EMPLOYEE_IDS="$(jq -cn \
        --argjson current "${EMPLOYEE_IDS}" \
        --argjson response "${EMPLOYEES_RESPONSE}" '
        ($current + [$response.data[]?.id]) | map(select(. != null)) | unique
    ')"

    REGISTER_RECORDS="$(jq -cn \
        --argjson current "${REGISTER_RECORDS}" \
        --argjson shopIndex "${shop_index}" \
        --arg expectedShopId "${shop_id}" \
        --argjson response "${REGISTERS_RESPONSE}" '
        $current + [{
            shopIndex: $shopIndex,
            expectedShopId: $expectedShopId,
            response: $response
        }]
    ')"
done

REGISTER_CANDIDATES="$(jq -cn \
    --argjson records "${REGISTER_RECORDS}" '
    [$records[]?
        | . as $record
        | $record.response.data[]?
        | {
            registerId: .id,
            expectedShopId: $record.expectedShopId
        }
    ]
')"

mapfile -t REGISTER_ROWS < <(jq -c '.[]' <<<"${REGISTER_CANDIDATES}")
for index in "${!REGISTER_ROWS[@]}"; do
    register_index=$((index + 1))
    candidate="${REGISTER_ROWS[${index}]}"
    register_id="$(jq -er '.registerId' <<<"${candidate}")"

    printf 'Fetching all cash pages for register %d/%d...\n' \
        "${register_index}" "${#REGISTER_ROWS[@]}" >&2
    if ! livesklad_get_paginated "${TOKEN}" \
        "/cash-registers/${register_id}/cash" \
        --data-urlencode "date=[${START_MS},${END_MS}]" \
        --data-urlencode 'sort=date DESC' >"${PAGINATED_FILE}"; then
        fail "Complete cash request failed for register index ${register_index}"
    fi

    jq -cn \
        --argjson registerIndex "${register_index}" \
        --argjson candidate "${candidate}" \
        --slurpfile response "${PAGINATED_FILE}" '
        {
            registerIndex: $registerIndex,
            candidate: $candidate,
            response: $response[0]
        }
    ' >>"${TRANSACTION_FILE}"
done

jq -cn \
    --argjson days "${DISCOVERY_DAYS}" \
    --argjson maxPages "${DISCOVERY_MAX_PAGES}" \
    --argjson shops "${SHOPS_RESPONSE}" \
    --argjson cashItems "${CASH_ITEMS_RESPONSE}" \
    --argjson employeeIds "${EMPLOYEE_IDS}" \
    --slurpfile records "${TRANSACTION_FILE}" '
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

    ([$records[]? | .response.data[]?]) as $transactions
    | ([$cashItems.data[]?.id]) as $cashItemIds
    | ([$shops.data[]?.id]) as $shopIds
    | {
        request: {
            endpoint: "/cash-registers/{id}/cash",
            periodDays: $days,
            pageSize: 50,
            maxPagesPerRegister: $maxPages,
            allPages: true,
            sort: "date DESC"
        },
        registerProfiles: (
            [$records[]? | {
                registerIndex,
                count: (.response.data | length),
                reportedTotal: (.response.reportedTotal // null),
                pagesFetched: (.response.pagesFetched // null),
                allReportedRecordsFetched: (
                    (.response.reportedTotal == null)
                    or (.response.data | length) == .response.reportedTotal
                )
            }]
        ),
        totalTransactionCount: ($transactions | length),
        allReportedRecordsFetched: (
            all($records[]?;
                (.response.reportedTotal == null)
                or (.response.data | length) == .response.reportedTotal
            )
        ),
        idProfile: {
            presentCount: ([$transactions[]? | select(.id? != null)] | length),
            uniqueCount: ([$transactions[]?.id? | select(. != null)] | unique | length)
        },
        schema: fields($transactions),
        semanticCombinations: (
            [$transactions[]? | {
                transactionType: (.type // null),
                cashItemType: (.cashItem.type // null),
                isIncome: (
                    .cashItem
                    | if has("isIncome") then .isIncome else null end
                ),
                isBalance: (
                    if has("isBalance") then .isBalance else null end
                ),
                isBankTransfer: (
                    if has("isBankTransfer") then .isBankTransfer else null end
                ),
                documentType: (.document.type // null)
            }]
            | sort_by([
                .transactionType,
                .cashItemType,
                .isIncome,
                .isBalance,
                .isBankTransfer,
                .documentType
            ])
            | group_by(.)
            | map(.[0] + {count: length})
        ),
        directionProfile: {
            income: (
                [$transactions[]? | select(.cashItem.isIncome? == true)] | length
            ),
            outflow: (
                [$transactions[]? | select(.cashItem.isIncome? == false)] | length
            ),
            unknown: (
                [$transactions[]?
                    | select((.cashItem.isIncome? | type) != "boolean")
                ] | length
            )
        },
        amountSigns: {
            negative: ([$transactions[]? | select((.money? // 0) < 0)] | length),
            zero: ([$transactions[]? | select((.money? // 0) == 0)] | length),
            positive: ([$transactions[]? | select((.money? // 0) > 0)] | length)
        },
        relations: {
            knownCashItemCount: (
                [$transactions[]?
                    | select(.cashItem.id? as $id | $cashItemIds | index($id))
                ] | length
            ),
            knownShopCount: (
                [$transactions[]?
                    | select(.shopId? as $id | $shopIds | index($id))
                ] | length
            ),
            customerMatchesEmployeeCount: (
                [$transactions[]?
                    | select(.customer.id? as $id | $employeeIds | index($id))
                ] | length
            ),
            workerPresentCount: (
                [$transactions[]? | select(.worker? != null)] | length
            ),
            workerMatchesEmployeeCount: (
                [$transactions[]?
                    | select(.worker.id? as $id | $employeeIds | index($id))
                ] | length
            )
        },
        nestedSchemas: {
            document: fields([$transactions[]? | .document? | select(type == "object")]),
            worker: fields([$transactions[]? | .worker? | select(type == "object")]),
            moveCashRegister: fields([
                $transactions[]? | .moveCashRegister? | select(type == "object")
            ])
        },
        updateFieldPresence: {
            dateChange: ([$transactions[]? | select(.dateChange? != null)] | length)
        },
        lastRateLimitMetadata: (
            if ($records | length) > 0
            then {
                remainRequest: ($records[-1].response.remainRequest // null),
                expireDate: ($records[-1].response.expireDate // null)
            }
            else {
                remainRequest: ($cashItems.remainRequest // null),
                expireDate: ($cashItems.expireDate // null)
            }
            end
        )
    }
'
