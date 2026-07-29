#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment
require_command date

DISCOVERY_DAYS="${DISCOVERY_DAYS:-30}"
DISCOVERY_MAX_PAGES="${DISCOVERY_MAX_PAGES:-5}"
DISCOVERY_RETURN_DETAIL_LIMIT="${DISCOVERY_RETURN_DETAIL_LIMIT:-10}"
DISCOVERY_PAGE_SIZE=50

[[ "${DISCOVERY_DAYS}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_DAYS must be a positive integer'
((DISCOVERY_DAYS <= 31)) || fail 'DISCOVERY_DAYS must not exceed 31'
[[ "${DISCOVERY_MAX_PAGES}" =~ ^[1-9][0-9]*$ ]] || fail 'DISCOVERY_MAX_PAGES must be a positive integer'
((DISCOVERY_MAX_PAGES <= 5)) || fail 'DISCOVERY_MAX_PAGES must not exceed 5'
[[ "${DISCOVERY_RETURN_DETAIL_LIMIT}" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DISCOVERY_RETURN_DETAIL_LIMIT must be a positive integer'
((DISCOVERY_RETURN_DETAIL_LIMIT <= 10)) || fail 'DISCOVERY_RETURN_DETAIL_LIMIT must not exceed 10'

END_MS="$(($(date -u +%s) * 1000))"
START_MS="$((END_MS - DISCOVERY_DAYS * 86400000))"

TOKEN=''
SHOPS_RESPONSE=''
CASH_ITEMS_RESPONSE=''
EMPLOYEES_RESPONSE=''
REGISTERS_RESPONSE=''
DETAIL_RESPONSE=''
REGISTER_RECORDS='[]'
EMPLOYEE_IDS='[]'
PAGINATED_FILE=''
RETURN_TRANSACTION_FILE=''
DETAIL_FILE=''
trap 'if ((BASH_SUBSHELL == 0)); then printf "ERROR: discovery script failed at line %s\n" "${LINENO}" >&2; fi' ERR

PAGINATED_FILE="$(mktemp)" || fail 'Could not create a temporary paginated-response file'
RETURN_TRANSACTION_FILE="$(mktemp)" || {
    rm -f -- "${PAGINATED_FILE}"
    fail 'Could not create a temporary return-transaction file'
}
DETAIL_FILE="$(mktemp)" || {
    rm -f -- "${PAGINATED_FILE}" "${RETURN_TRANSACTION_FILE}"
    fail 'Could not create a temporary return-detail file'
}
trap 'rm -f -- "${PAGINATED_FILE}" "${RETURN_TRANSACTION_FILE}" "${DETAIL_FILE}"; unset TOKEN SHOPS_RESPONSE CASH_ITEMS_RESPONSE EMPLOYEES_RESPONSE REGISTERS_RESPONSE DETAIL_RESPONSE REGISTER_RECORDS EMPLOYEE_IDS START_MS END_MS' EXIT

printf 'Authenticating and locating sale-return cash items...\n' >&2
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

mapfile -t RETURN_CASH_ITEM_IDS < <(
    jq -er '.data[]? | select(.type? == "saleReturn") | .id' <<<"${CASH_ITEMS_RESPONSE}"
)
(("${#RETURN_CASH_ITEM_IDS[@]}" > 0)) || fail 'No saleReturn cash item was found'

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

    EMPLOYEE_IDS="$(jq -cn \
        --argjson current "${EMPLOYEE_IDS}" \
        --argjson response "${EMPLOYEES_RESPONSE}" '
        ($current + [$response.data[]?.id]) | map(select(. != null)) | unique
    ')"
    REGISTER_RECORDS="$(jq -cn \
        --argjson current "${REGISTER_RECORDS}" \
        --argjson response "${REGISTERS_RESPONSE}" '
        $current + $response.data
    ')"
done

request_index=0
request_total=$(("${#RETURN_CASH_ITEM_IDS[@]}" * $(jq 'length' <<<"${REGISTER_RECORDS}")))
mapfile -t REGISTER_IDS < <(jq -er '.[].id' <<<"${REGISTER_RECORDS}")

for register_id in "${REGISTER_IDS[@]}"; do
    for cash_item_id in "${RETURN_CASH_ITEM_IDS[@]}"; do
        request_index=$((request_index + 1))
        printf 'Loading return transactions %d/%d...\n' "${request_index}" "${request_total}" >&2

        if ! livesklad_get_paginated "${TOKEN}" \
            "/cash-registers/${register_id}/cash" \
            --data-urlencode "date=[${START_MS},${END_MS}]" \
            --data-urlencode "cashItemId=${cash_item_id}" \
            --data-urlencode 'sort=date DESC' >"${PAGINATED_FILE}"; then
            fail "Return transaction request failed at index ${request_index}"
        fi

        jq -cn \
            --argjson requestIndex "${request_index}" \
            --slurpfile response "${PAGINATED_FILE}" '
            {
                requestIndex: $requestIndex,
                response: $response[0]
            }
        ' >>"${RETURN_TRANSACTION_FILE}"
    done
done

CANDIDATES="$(jq -cn \
    --slurpfile records "${RETURN_TRANSACTION_FILE}" \
    --argjson limit "${DISCOVERY_RETURN_DETAIL_LIMIT}" '
    ([$records[]?.response.data[]?
        | select(.document.id? != null)
    ]) as $transactions
    | ([
        ($transactions
            | map(select(.type == "saleReturn"))
            | group_by([.isBankTransfer, .shopId])[]?
            | .[0]
        )
    ]
        | map(select(.document.id? != null))
        | unique_by(.document.id)
        | .[:$limit]
        | map({
            documentId: .document.id,
            expectedCustomerId: (.customer.id // null),
            transactionType: (.type // null)
        })
    )
')"

mapfile -t CANDIDATE_ROWS < <(jq -c '.[]' <<<"${CANDIDATES}")
for index in "${!CANDIDATE_ROWS[@]}"; do
    detail_index=$((index + 1))
    candidate="${CANDIDATE_ROWS[${index}]}"
    document_id="$(jq -er '.documentId' <<<"${candidate}")"

    printf 'Loading return document detail %d/%d...\n' \
        "${detail_index}" "${#CANDIDATE_ROWS[@]}" >&2
    DETAIL_RESPONSE="$(livesklad_get "${TOKEN}" "/documents/${document_id}")" \
        || fail "Return document detail failed at index ${detail_index}"
    jq -e '.data | type == "object"' >/dev/null <<<"${DETAIL_RESPONSE}" \
        || fail "Return detail at index ${detail_index} has no data object"

    jq -cn \
        --argjson detailIndex "${detail_index}" \
        --argjson candidate "${candidate}" \
        --argjson response "${DETAIL_RESPONSE}" '
        {
            detailIndex: $detailIndex,
            candidate: $candidate,
            detail: $response.data,
            remainRequest: ($response.remainRequest // null),
            expireDate: ($response.expireDate // null)
        }
    ' >>"${DETAIL_FILE}"
done

jq -cn \
    --argjson days "${DISCOVERY_DAYS}" \
    --argjson detailLimit "${DISCOVERY_RETURN_DETAIL_LIMIT}" \
    --argjson employeeIds "${EMPLOYEE_IDS}" \
    --slurpfile transactionRecords "${RETURN_TRANSACTION_FILE}" \
    --slurpfile detailRecords "${DETAIL_FILE}" '
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

    ([$transactionRecords[]?.response.data[]?]) as $transactions
    | ([$detailRecords[]?.detail]) as $documents
    | ([$documents[]?.positions[]?]) as $positions
    | {
        request: {
            transactionEndpoint: "/cash-registers/{id}/cash",
            detailEndpoint: "/documents/{id}",
            cashItemFilter: "saleReturn",
            periodDays: $days,
            detailLimit: $detailLimit
        },
        returnTransactions: {
            count: ($transactions | length),
            allReportedRecordsFetched: (
                all($transactionRecords[]?;
                    (.response.reportedTotal == null)
                    or (.response.data | length) == .response.reportedTotal
                )
            ),
            typeProfile: (
                [$transactions[]? | {
                    type: (.type // null),
                    isBankTransfer: (
                        if has("isBankTransfer") then .isBankTransfer else null end
                    )
                }]
                | sort_by([.type, .isBankTransfer])
                | group_by(.)
                | map(.[0] + {count: length})
            ),
            documentPresentCount: (
                [$transactions[]? | select(.document.id? != null)] | length
            ),
            customerMatchesEmployeeCount: (
                [$transactions[]?
                    | select(.customer.id? as $id | $employeeIds | index($id))
                ] | length
            ),
            amountSigns: {
                negative: ([$transactions[]? | select((.money? // 0) < 0)] | length),
                zero: ([$transactions[]? | select((.money? // 0) == 0)] | length),
                positive: ([$transactions[]? | select((.money? // 0) > 0)] | length)
            }
        },
        returnDocuments: {
            count: ($documents | length),
            schema: fields($documents),
            nestedSchemas: {
                parentDocument: fields([
                    $documents[]? | .parentDocument? | select(type == "object")
                ]),
                customer: fields([
                    $documents[]? | .customer? | select(type == "object")
                ]),
                cash: fields([
                    $documents[]? | .cash? | select(type == "object")
                ]),
                shop: fields([
                    $documents[]? | .shop? | select(type == "object")
                ])
            },
            typeValues: ([$documents[]?.type? | select(. != null)] | unique),
            idMatchesCashDocumentCount: (
                [$detailRecords[]?
                    | select(.detail.id? == .candidate.documentId)
                ] | length
            ),
            customerMatchesTransactionCount: (
                [$detailRecords[]?
                    | select(
                        .candidate.expectedCustomerId == null
                        or .detail.customer.id? == .candidate.expectedCustomerId
                    )
                ] | length
            )
        },
        positions: {
            count: ($positions | length),
            schema: fields($positions),
            returnCountFieldPresentCount: (
                [$positions[]? | select(has("returnCount"))] | length
            ),
            quantityGreaterThanOneCount: (
                [$positions[]? | select((.count? // 0) > 1)] | length
            ),
            measureSchema: fields([
                $positions[]? | .measure? | select(type == "object")
            ]),
            batchSchema: fields([$positions[]?.batches[]?]),
            batchReturnCountFieldPresentCount: (
                [$positions[]?.batches[]? | select(has("returnCount"))] | length
            )
        },
        lastRateLimitMetadata: (
            if ($detailRecords | length) > 0
            then {
                remainRequest: ($detailRecords[-1].remainRequest // null),
                expireDate: ($detailRecords[-1].expireDate // null)
            }
            elif ($transactionRecords | length) > 0
            then {
                remainRequest: ($transactionRecords[-1].response.remainRequest // null),
                expireDate: ($transactionRecords[-1].response.expireDate // null)
            }
            else {
                remainRequest: null,
                expireDate: null
            }
            end
        )
    }
'
