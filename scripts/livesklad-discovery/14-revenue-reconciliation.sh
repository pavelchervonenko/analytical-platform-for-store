#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment
require_command date

AUDIT_PERIOD_START="${AUDIT_PERIOD_START:?AUDIT_PERIOD_START is required (YYYY-MM-DD)}"
AUDIT_PERIOD_END_EXCLUSIVE="${AUDIT_PERIOD_END_EXCLUSIVE:?AUDIT_PERIOD_END_EXCLUSIVE is required (YYYY-MM-DD)}"
AUDIT_TIMEZONE="${AUDIT_TIMEZONE:-Europe/Kaliningrad}"
DISCOVERY_PAGE_SIZE=50
DISCOVERY_MAX_PAGES="${DISCOVERY_MAX_PAGES:-40}"

[[ "${AUDIT_PERIOD_START}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || fail 'AUDIT_PERIOD_START must use YYYY-MM-DD'
[[ "${AUDIT_PERIOD_END_EXCLUSIVE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || fail 'AUDIT_PERIOD_END_EXCLUSIVE must use YYYY-MM-DD'
[[ "${DISCOVERY_MAX_PAGES}" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DISCOVERY_MAX_PAGES must be a positive integer'
((DISCOVERY_MAX_PAGES <= 100)) \
    || fail 'DISCOVERY_MAX_PAGES must not exceed 100'

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
SALES_FILE="${WORK_DIR}/sales.jsonl"
RETURNS_FILE="${WORK_DIR}/returns.jsonl"
STORES_FILE="${WORK_DIR}/stores.json"
TOKEN=''
SHOPS_RESPONSE=''
CASH_ITEMS_RESPONSE=''
trap 'rm -rf -- "${WORK_DIR}"; unset TOKEN SHOPS_RESPONSE CASH_ITEMS_RESPONSE START_MS END_MS' EXIT

: >"${SALES_FILE}"
: >"${RETURNS_FILE}"

TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
SHOPS_RESPONSE="$(livesklad_get "${TOKEN}" '/shops')" \
    || fail 'LiveSklad shops request failed'
CASH_ITEMS_RESPONSE="$(livesklad_get "${TOKEN}" '/cash-items')" \
    || fail 'LiveSklad cash-item request failed'

jq -e '.data | type == "array" and length > 0' >/dev/null <<<"${SHOPS_RESPONSE}" \
    || fail 'Shops response does not contain stores'
jq -e '.data | type == "array"' >/dev/null <<<"${CASH_ITEMS_RESPONSE}" \
    || fail 'Cash-item response does not contain a data array'
jq -c '[.data[] | {id, name}]' <<<"${SHOPS_RESPONSE}" >"${STORES_FILE}"

mapfile -t RETURN_CASH_ITEM_IDS < <(
    jq -er '.data[]? | select(.type? == "saleReturn") | .id' \
        <<<"${CASH_ITEMS_RESPONSE}"
)
(("${#RETURN_CASH_ITEM_IDS[@]}" > 0)) \
    || fail 'No saleReturn cash item was found'

mapfile -t STORE_RECORDS < <(jq -c '.[]' "${STORES_FILE}")
for store_record in "${STORE_RECORDS[@]}"; do
    store_id="$(jq -er '.id' <<<"${store_record}")"
    store_name="$(jq -er '.name' <<<"${store_record}")"

    sales_response="$(livesklad_get_paginated \
        "${TOKEN}" "/shops/${store_id}/sales" \
        --data-urlencode "date=[${START_MS},${END_MS}]" \
        --data-urlencode 'sort=date ASC')" \
        || fail "Sales request failed for a store"
    jq -c \
        --arg storeId "${store_id}" \
        --arg storeName "${store_name}" '
        .data[]
        | {
            storeId: $storeId,
            storeName: $storeName,
            id,
            number,
            date,
            type,
            soldPrice: .summ.soldPrice,
            listPrice: .summ.price,
            purchasePrice: .summ.purchasePrice
        }
    ' <<<"${sales_response}" >>"${SALES_FILE}"

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
            jq -c \
                --arg storeId "${store_id}" \
                --arg storeName "${store_name}" '
                .data[]
                | {
                    storeId: $storeId,
                    storeName: $storeName,
                    id,
                    date,
                    type,
                    amount: .money,
                    documentId: (.document.id // null)
                }
            ' <<<"${returns_response}" >>"${RETURNS_FILE}"
        done
    done
done

python3 - \
    "${STORES_FILE}" \
    "${SALES_FILE}" \
    "${RETURNS_FILE}" \
    "${AUDIT_PERIOD_START}" \
    "${AUDIT_PERIOD_END_EXCLUSIVE}" \
    "${AUDIT_TIMEZONE}" <<'PY'
import collections
import decimal
import json
import sys

decimal.getcontext().prec = 38


def rows(path):
    with open(path, encoding="utf-8") as source:
        for line in source:
            if line.strip():
                yield json.loads(line, parse_float=decimal.Decimal, parse_int=decimal.Decimal)


def money(value):
    return str(value.quantize(decimal.Decimal("0.01")))


with open(sys.argv[1], encoding="utf-8") as source:
    stores = json.load(source)

sales_by_store = collections.defaultdict(list)
returns_by_store = collections.defaultdict(list)
sale_ids = set()
return_ids = set()
duplicate_sale_ids = set()
duplicate_return_ids = set()

for row in rows(sys.argv[2]):
    if row["id"] in sale_ids:
        duplicate_sale_ids.add(row["id"])
    sale_ids.add(row["id"])
    sales_by_store[row["storeId"]].append(row)

for row in rows(sys.argv[3]):
    if row["id"] in return_ids:
        duplicate_return_ids.add(row["id"])
    return_ids.add(row["id"])
    returns_by_store[row["storeId"]].append(row)

result = {
    "periodStart": sys.argv[4],
    "periodEndExclusive": sys.argv[5],
    "timezone": sys.argv[6],
    "duplicateSaleIdCount": len(duplicate_sale_ids),
    "duplicateReturnTransactionIdCount": len(duplicate_return_ids),
    "stores": [],
}

for store in stores:
    sales = [row for row in sales_by_store[store["id"]] if row["type"].lower() == "sale"]
    active_returns = [
        row for row in returns_by_store[store["id"]]
        if row["type"].lower() == "salereturn"
    ]
    unexpected_sales = [row for row in sales_by_store[store["id"]] if row not in sales]
    unexpected_returns = [
        row for row in returns_by_store[store["id"]]
        if row["type"].lower() not in {"salereturn", "delete"}
    ]
    sales_total = sum((row["soldPrice"] for row in sales), decimal.Decimal())
    returns_total = sum((row["amount"] for row in active_returns), decimal.Decimal())
    result["stores"].append({
        "storeName": store["name"],
        "saleCount": len(sales),
        "saleSoldPrice": money(sales_total),
        "activeReturnTransactionCount": len(active_returns),
        "activeReturnCashAmount": money(returns_total),
        "sourceNetRevenue": money(sales_total - returns_total),
        "unexpectedSaleTypeCount": len(unexpected_sales),
        "unexpectedReturnTypeCount": len(unexpected_returns),
    })

print(json.dumps(result, ensure_ascii=False, indent=2))
PY
