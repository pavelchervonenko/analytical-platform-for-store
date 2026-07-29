#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment
require_command date

DISCOVERY_DAYS="${DISCOVERY_DAYS:-30}"
DISCOVERY_CASH_REGISTER_LIMIT="${DISCOVERY_CASH_REGISTER_LIMIT:-10}"

[[ "${DISCOVERY_DAYS}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_DAYS must be a positive integer'
((DISCOVERY_DAYS <= 90)) || fail 'DISCOVERY_DAYS must not exceed 90'
[[ "${DISCOVERY_CASH_REGISTER_LIMIT}" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DISCOVERY_CASH_REGISTER_LIMIT must be a positive integer'
((DISCOVERY_CASH_REGISTER_LIMIT <= 10)) || fail 'DISCOVERY_CASH_REGISTER_LIMIT must not exceed 10'

END_MS="$(($(date -u +%s) * 1000))"
START_MS="$((END_MS - DISCOVERY_DAYS * 86400000))"

TOKEN=''
SHOPS_RESPONSE=''
CASH_ITEMS_RESPONSE=''
REGISTERS_RESPONSE=''
CASH_RESPONSE=''
REGISTER_RECORDS='[]'
TRANSACTION_RECORDS='[]'
trap 'if ((BASH_SUBSHELL == 0)); then printf "ERROR: discovery script failed at line %s\n" "${LINENO}" >&2; fi' ERR
trap 'unset TOKEN SHOPS_RESPONSE CASH_ITEMS_RESPONSE REGISTERS_RESPONSE CASH_RESPONSE REGISTER_RECORDS TRANSACTION_RECORDS START_MS END_MS' EXIT

printf 'Authenticating and loading cash dictionaries...\n' >&2
TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
SHOPS_RESPONSE="$(livesklad_get "${TOKEN}" '/shops')" \
    || fail 'LiveSklad shops request failed'
CASH_ITEMS_RESPONSE="$(livesklad_get "${TOKEN}" '/cash-items')" \
    || fail 'Cash items request failed; API access may be restricted'

jq -e '.data | type == "array"' >/dev/null <<<"${SHOPS_RESPONSE}" \
    || fail 'Shops response does not contain a data array'
jq -e 'type == "array" or (.data? | type == "array")' >/dev/null <<<"${CASH_ITEMS_RESPONSE}" \
    || fail 'Cash items response has an unsupported collection shape'

mapfile -t SHOP_IDS < <(jq -er '.data[]?.id' <<<"${SHOPS_RESPONSE}")
for index in "${!SHOP_IDS[@]}"; do
    shop_index=$((index + 1))
    shop_id="${SHOP_IDS[${index}]}"

    printf 'Loading cash registers for shop %d/%d...\n' \
        "${shop_index}" "${#SHOP_IDS[@]}" >&2
    REGISTERS_RESPONSE="$(livesklad_get "${TOKEN}" "/shops/${shop_id}/cash-registers")" \
        || fail "Cash registers request failed for shop index ${shop_index}"
    jq -e '.data | type == "array"' >/dev/null <<<"${REGISTERS_RESPONSE}" \
        || fail "Cash registers response for shop index ${shop_index} has no data array"

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
    --argjson records "${REGISTER_RECORDS}" \
    --argjson limit "${DISCOVERY_CASH_REGISTER_LIMIT}" '
    [$records[]?
        | . as $record
        | $record.response.data[]?
        | {
            registerId: .id,
            declaredShopId: (.shopId // null),
            expectedShopId: $record.expectedShopId,
            shopIndex: $record.shopIndex
        }
    ][: $limit]
')"

mapfile -t REGISTER_ROWS < <(jq -c '.[]' <<<"${REGISTER_CANDIDATES}")
for index in "${!REGISTER_ROWS[@]}"; do
    register_index=$((index + 1))
    candidate="${REGISTER_ROWS[${index}]}"
    register_id="$(jq -er '.registerId' <<<"${candidate}")"

    printf 'Loading cash transactions for register %d/%d...\n' \
        "${register_index}" "${#REGISTER_ROWS[@]}" >&2
    CASH_RESPONSE="$(livesklad_get "${TOKEN}" "/cash-registers/${register_id}/cash" \
        --get \
        --data-urlencode "date=[${START_MS},${END_MS}]" \
        --data-urlencode 'page=1' \
        --data-urlencode 'pageSize=50' \
        --data-urlencode 'sort=date DESC')" \
        || fail "Cash transactions request failed for register index ${register_index}"
    jq -e '.data | type == "array"' >/dev/null <<<"${CASH_RESPONSE}" \
        || fail "Cash response for register index ${register_index} has no data array"

    TRANSACTION_RECORDS="$(jq -cn \
        --argjson current "${TRANSACTION_RECORDS}" \
        --argjson registerIndex "${register_index}" \
        --argjson candidate "${candidate}" \
        --argjson response "${CASH_RESPONSE}" '
        $current + [{
            registerIndex: $registerIndex,
            candidate: $candidate,
            response: $response
        }]
    ')"
done

jq -cn \
    --argjson days "${DISCOVERY_DAYS}" \
    --argjson registerLimit "${DISCOVERY_CASH_REGISTER_LIMIT}" \
    --argjson cashItemsResponse "${CASH_ITEMS_RESPONSE}" \
    --argjson registerRecords "${REGISTER_RECORDS}" \
    --argjson transactionRecords "${TRANSACTION_RECORDS}" '
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

    def collection($response):
        if ($response | type) == "array"
        then $response
        else $response.data
        end;

    ([$registerRecords[]? | .response.data[]?]) as $registers
    | ([$transactionRecords[]? | .response.data[]?]) as $transactions
    | (collection($cashItemsResponse)) as $cashItems
    | {
        request: {
            shopsEndpoint: "/shops",
            cashItemsEndpoint: "/cash-items",
            registersEndpoint: "/shops/{id}/cash-registers",
            transactionsEndpoint: "/cash-registers/{id}/cash",
            periodDays: $days,
            registerLimit: $registerLimit,
            page: 1,
            pageSize: 50,
            sort: "date DESC"
        },
        cashItems: {
            count: ($cashItems | length),
            schema: fields($cashItems),
            idProfile: {
                presentCount: ([$cashItems[]? | select(.id? != null)] | length),
                uniqueCount: ([$cashItems[]?.id? | select(. != null)] | unique | length)
            }
        },
        registers: {
            shopResponseCount: ($registerRecords | length),
            count: ($registers | length),
            schema: fields($registers),
            idProfile: {
                presentCount: ([$registers[]? | select(.id? != null)] | length),
                uniqueCount: ([$registers[]?.id? | select(. != null)] | unique | length)
            },
            declaredShopMatchesRequestedCount: (
                [$registerRecords[]?
                    | .expectedShopId as $expected
                    | .response.data[]?
                    | select(.shopId? == $expected)
                ] | length
            ),
            balanceFieldPresence: {
                cashMoney: ([$registers[]? | select(.cashMoney? != null)] | length),
                bankMoney: ([$registers[]? | select(.bankMoney? != null)] | length)
            }
        },
        transactions: {
            scannedRegisterCount: ($transactionRecords | length),
            countOnFirstPages: ($transactions | length),
            reportedTotalsByRegisterIndex: (
                [$transactionRecords[]? | {
                    registerIndex,
                    countOnFirstPage: (.response.data | length),
                    reportedTotal: (.response.total // null)
                }]
            ),
            schema: fields($transactions),
            idProfile: {
                presentCount: ([$transactions[]? | select(.id? != null)] | length),
                uniqueCount: ([$transactions[]?.id? | select(. != null)] | unique | length)
            },
            amountSigns: {
                negative: ([$transactions[]? | select((.money? // 0) < 0)] | length),
                zero: ([$transactions[]? | select((.money? // 0) == 0)] | length),
                positive: ([$transactions[]? | select((.money? // 0) > 0)] | length)
            },
            bankTransferProfile: (
                [$transactions[]? | .isBankTransfer? | select(type == "boolean")]
                | group_by(.)
                | map({value: .[0], count: length})
            ),
            dateChangePresentCount: (
                [$transactions[]? | select(.dateChange? != null)] | length
            ),
            nestedSchemas: {
                customer: nestedObjects($transactions; "customer"),
                counteragent: nestedObjects($transactions; "counteragent"),
                cashRegister: nestedObjects($transactions; "cashRegister"),
                cashItem: nestedObjects($transactions; "cashItem"),
                document: nestedObjects($transactions; "document"),
                moveCashRegister: nestedObjects($transactions; "moveCashRegister"),
                worker: nestedObjects($transactions; "worker")
            }
        },
        lastRateLimitMetadata: (
            if ($transactionRecords | length) > 0
            then $transactionRecords[-1].response
            else (
                if ($registerRecords | length) > 0
                then $registerRecords[-1].response
                else $cashItemsResponse
                end
            )
            end
            | {
                remainRequest: (
                    if type == "object" then (.remainRequest // null) else null end
                ),
                expireDate: (
                    if type == "object" then (.expireDate // null) else null end
                )
            }
        )
    }
'
