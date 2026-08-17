#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment

AUDIT_PERIOD_START="${AUDIT_PERIOD_START:?AUDIT_PERIOD_START is required (YYYY-MM-DD)}"
AUDIT_PERIOD_END_EXCLUSIVE="${AUDIT_PERIOD_END_EXCLUSIVE:?AUDIT_PERIOD_END_EXCLUSIVE is required (YYYY-MM-DD)}"
AUDIT_TIMEZONE="${AUDIT_TIMEZONE:-Europe/Kaliningrad}"
DISCOVERY_PAGE_SIZE=50
DISCOVERY_MAX_PAGES="${DISCOVERY_MAX_PAGES:-20}"

[[ "${AUDIT_PERIOD_START}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || fail 'AUDIT_PERIOD_START must use YYYY-MM-DD'
[[ "${AUDIT_PERIOD_END_EXCLUSIVE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || fail 'AUDIT_PERIOD_END_EXCLUSIVE must use YYYY-MM-DD'

mapfile -t PERIOD_MILLIS < <(
    python3 - "${AUDIT_PERIOD_START}" "${AUDIT_PERIOD_END_EXCLUSIVE}" "${AUDIT_TIMEZONE}" <<'PY'
import datetime as dt
import sys
from zoneinfo import ZoneInfo

start = dt.date.fromisoformat(sys.argv[1])
end = dt.date.fromisoformat(sys.argv[2])
if end <= start:
    raise SystemExit("period end must be after period start")
zone = ZoneInfo(sys.argv[3])
for value in (start, end):
    instant = dt.datetime.combine(value, dt.time.min, zone).timestamp()
    print(int(instant * 1000))
PY
)
[[ "${#PERIOD_MILLIS[@]}" == "2" ]] || fail 'Could not derive period boundaries'
START_MS="${PERIOD_MILLIS[0]}"
END_MS="${PERIOD_MILLIS[1]}"

WORK_DIR="$(mktemp -d)" || fail 'Could not create temporary audit directory'
TRANSACTIONS_FILE="${WORK_DIR}/transactions.jsonl"
DETAILS_FILE="${WORK_DIR}/details.jsonl"
CANDIDATES_FILE="${WORK_DIR}/candidates.jsonl"
TOKEN=''
trap 'rm -rf -- "${WORK_DIR}"; unset TOKEN START_MS END_MS' EXIT
: >"${TRANSACTIONS_FILE}"
: >"${DETAILS_FILE}"

TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
shops_response="$(livesklad_get "${TOKEN}" '/shops')" \
    || fail 'LiveSklad shops request failed'
cash_items_response="$(livesklad_get "${TOKEN}" '/cash-items')" \
    || fail 'LiveSklad cash-item request failed'

mapfile -t RETURN_CASH_ITEM_IDS < <(
    jq -er '.data[]? | select(.type? == "saleReturn") | .id' \
        <<<"${cash_items_response}"
)
(("${#RETURN_CASH_ITEM_IDS[@]}" > 0)) || fail 'No saleReturn cash item was found'
mapfile -t STORE_RECORDS < <(jq -c '.data[] | {id, name}' <<<"${shops_response}")

last_remain_request='null'
last_expire_date='null'
for store_record in "${STORE_RECORDS[@]}"; do
    store_id="$(jq -er '.id' <<<"${store_record}")"
    store_name="$(jq -er '.name' <<<"${store_record}")"
    registers_response="$(livesklad_get \
        "${TOKEN}" "/shops/${store_id}/cash-registers")" \
        || fail 'Cash-register request failed for a store'
    mapfile -t REGISTER_IDS < <(jq -er '.data[]?.id' <<<"${registers_response}")
    for register_id in "${REGISTER_IDS[@]}"; do
        for cash_item_id in "${RETURN_CASH_ITEM_IDS[@]}"; do
            returns_response="$(livesklad_get_paginated \
                "${TOKEN}" "/cash-registers/${register_id}/cash" \
                --data-urlencode "date=[${START_MS},${END_MS}]" \
                --data-urlencode "cashItemId=${cash_item_id}" \
                --data-urlencode 'sort=date ASC')" \
                || fail 'Return transaction request failed'
            last_remain_request="$(jq -c '.remainRequest // null' <<<"${returns_response}")"
            last_expire_date="$(jq -c '.expireDate // null' <<<"${returns_response}")"
            jq -c \
                --arg storeId "${store_id}" \
                --arg storeName "${store_name}" '
                .data[]
                | {
                    storeId: $storeId,
                    storeName: $storeName,
                    transactionId: .id,
                    transactionDate: .date,
                    type,
                    amount: .money,
                    documentId: (.document.id // null)
                }
            ' <<<"${returns_response}" >>"${TRANSACTIONS_FILE}"
        done
    done
done

jq -cs '
    map(select(.type == "saleReturn" and .documentId != null))
    | unique_by(.documentId)
    | .[]
    | {storeId, storeName, documentId}
' "${TRANSACTIONS_FILE}" >"${CANDIDATES_FILE}"

while IFS= read -r candidate; do
    [[ -n "${candidate}" ]] || continue
    document_id="$(jq -er '.documentId' <<<"${candidate}")"
    detail_response="$(livesklad_get "${TOKEN}" "/documents/${document_id}")" \
        || fail 'Return document detail request failed'
    last_remain_request="$(jq -c '.remainRequest // null' <<<"${detail_response}")"
    last_expire_date="$(jq -c '.expireDate // null' <<<"${detail_response}")"
    jq -cn \
        --argjson candidate "${candidate}" \
        --argjson detail "$(jq -c '.data' <<<"${detail_response}")" '
        $candidate + {detail: $detail}
    ' >>"${DETAILS_FILE}"
done <"${CANDIDATES_FILE}"

python3 - \
    "${TRANSACTIONS_FILE}" \
    "${DETAILS_FILE}" \
    "${AUDIT_PERIOD_START}" \
    "${AUDIT_PERIOD_END_EXCLUSIVE}" \
    "${AUDIT_TIMEZONE}" \
    "${last_remain_request}" \
    "${last_expire_date}" <<'PY'
import collections
import decimal
import json
import sys

decimal.getcontext().prec = 38
ZERO = decimal.Decimal()


def rows(path):
    with open(path, encoding="utf-8") as source:
        for line in source:
            if line.strip():
                yield json.loads(line, parse_float=decimal.Decimal, parse_int=decimal.Decimal)


def value(source, key):
    candidate = source.get(key)
    return ZERO if candidate is None else decimal.Decimal(candidate)


def money(candidate):
    return str(candidate.quantize(decimal.Decimal("0.01")))


transactions = list(rows(sys.argv[1]))
details = list(rows(sys.argv[2]))
transaction_totals = collections.defaultdict(lambda: ZERO)
transaction_counts = collections.Counter()
store_names = {}
for transaction in transactions:
    store_names[transaction["storeId"]] = transaction["storeName"]
    if transaction["type"].lower() != "salereturn" or transaction["documentId"] is None:
        continue
    key = (transaction["storeId"], transaction["documentId"])
    transaction_totals[key] += decimal.Decimal(transaction["amount"])
    transaction_counts[key] += 1

store_results = collections.defaultdict(lambda: {
    "documentCount": 0,
    "transactionCount": 0,
    "transactionAmount": ZERO,
    "detailPositionAmount": ZERO,
    "detailPaymentAmount": ZERO,
    "mismatches": [],
})

for record in details:
    detail = record["detail"]
    store_id = record["storeId"]
    key = (store_id, record["documentId"])
    position_total = sum(
        (decimal.Decimal(position["soldPrice"]) * decimal.Decimal(position["count"])
         for position in detail.get("positions", [])),
        ZERO,
    )
    cash = detail.get("cash") or {}
    payment_total = value(cash, "money") + value(cash, "bank") + value(cash, "invoice")
    transaction_total = transaction_totals[key]
    target = store_results[store_id]
    target["documentCount"] += 1
    target["transactionCount"] += transaction_counts[key]
    target["transactionAmount"] += transaction_total
    target["detailPositionAmount"] += position_total
    target["detailPaymentAmount"] += payment_total
    if transaction_total != position_total or payment_total != position_total:
        target["mismatches"].append({
            "documentNumber": detail.get("number"),
            "documentDate": detail.get("date"),
            "transactionAmount": money(transaction_total),
            "detailPositionAmount": money(position_total),
            "detailPaymentAmount": money(payment_total),
        })

result = {
    "periodStart": sys.argv[3],
    "periodEndExclusive": sys.argv[4],
    "timezone": sys.argv[5],
    "lastRemainRequest": json.loads(sys.argv[6]),
    "lastExpireDate": json.loads(sys.argv[7]),
    "stores": [],
}
for store_id, target in sorted(store_results.items(), key=lambda item: store_names[item[0]]):
    result["stores"].append({
        "storeName": store_names[store_id],
        "returnDocumentCount": target["documentCount"],
        "returnTransactionCount": target["transactionCount"],
        "transactionAmount": money(target["transactionAmount"]),
        "detailPositionAmount": money(target["detailPositionAmount"]),
        "detailPaymentAmount": money(target["detailPaymentAmount"]),
        "mismatchDocumentCount": len(target["mismatches"]),
        "mismatches": target["mismatches"],
    })
print(json.dumps(result, ensure_ascii=False, indent=2))
PY
