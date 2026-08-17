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
DISCOVERY_MAX_PAGES="${DISCOVERY_MAX_PAGES:-40}"

[[ "${AUDIT_PERIOD_START}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || fail 'AUDIT_PERIOD_START must use YYYY-MM-DD'
[[ "${AUDIT_PERIOD_END_EXCLUSIVE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || fail 'AUDIT_PERIOD_END_EXCLUSIVE must use YYYY-MM-DD'
[[ "${DISCOVERY_MAX_PAGES}" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DISCOVERY_MAX_PAGES must be a positive integer'
((DISCOVERY_MAX_PAGES <= 40)) \
    || fail 'DISCOVERY_MAX_PAGES must not exceed 40'

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
TOKEN=''
trap 'rm -rf -- "${WORK_DIR}"; unset TOKEN START_MS END_MS' EXIT
: >"${TRANSACTIONS_FILE}"

TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
shops_response="$(livesklad_get "${TOKEN}" '/shops')" \
    || fail 'LiveSklad shops request failed'
cash_items_response="$(livesklad_get "${TOKEN}" '/cash-items')" \
    || fail 'LiveSklad cash-item request failed'

mapfile -t STORE_ROWS < <(jq -c '.data[] | {id, name}' <<<"${shops_response}")
last_remain_request='null'
last_expire_date='null'
for store_row in "${STORE_ROWS[@]}"; do
    store_id="$(jq -er '.id' <<<"${store_row}")"
    store_name="$(jq -er '.name' <<<"${store_row}")"
    registers_response="$(livesklad_get \
        "${TOKEN}" "/shops/${store_id}/cash-registers")" \
        || fail 'LiveSklad cash-register request failed'
    mapfile -t REGISTER_IDS < <(jq -er '.data[]?.id' <<<"${registers_response}")
    for register_id in "${REGISTER_IDS[@]}"; do
        transactions_response="$(livesklad_get_paginated \
            "${TOKEN}" "/cash-registers/${register_id}/cash" \
            --data-urlencode "date=[${START_MS},${END_MS}]" \
            --data-urlencode 'sort=date ASC')" \
            || fail 'LiveSklad cash transaction request failed'
        last_remain_request="$(jq -c '.remainRequest // null' <<<"${transactions_response}")"
        last_expire_date="$(jq -c '.expireDate // null' <<<"${transactions_response}")"
        jq -c \
            --arg storeId "${store_id}" \
            --arg storeName "${store_name}" '
            .data[]
            | {
                storeId: $storeId,
                storeName: $storeName,
                transactionId: .id,
                transactionType: (.type // null),
                date: .date,
                amount: (.money // 0),
                cashItemId: (.cashItem.id // null),
                cashItemName: (.cashItem.name // null),
                cashItemType: (.cashItem.type // null),
                isIncome: (.cashItem.isIncome // null),
                isBalance: (.cashItem.isBalance // null),
                documentId: (.document.id // null),
                documentType: (.document.type // null)
            }
        ' <<<"${transactions_response}" >>"${TRANSACTIONS_FILE}"
    done
done

python3 - \
    "${TRANSACTIONS_FILE}" \
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


def money(value):
    return str(value.quantize(decimal.Decimal("0.01")))


records = []
with open(sys.argv[1], encoding="utf-8") as source:
    for line in source:
        if line.strip():
            records.append(json.loads(
                line,
                parse_float=decimal.Decimal,
                parse_int=decimal.Decimal,
            ))

ids = collections.Counter(record["transactionId"] for record in records)
groups = collections.defaultdict(lambda: {"count": 0, "amount": ZERO})
for record in records:
    key = (
        record["storeName"],
        record["cashItemType"],
        record["cashItemName"],
        record["isIncome"],
        record["transactionType"],
        record["documentType"],
    )
    groups[key]["count"] += 1
    groups[key]["amount"] += decimal.Decimal(record["amount"])

result = {
    "periodStart": sys.argv[2],
    "periodEndExclusive": sys.argv[3],
    "timezone": sys.argv[4],
    "transactionCount": len(records),
    "duplicateTransactionIdCount": sum(1 for count in ids.values() if count > 1),
    "lastRemainRequest": json.loads(sys.argv[5]),
    "lastExpireDate": json.loads(sys.argv[6]),
    "groups": [],
}
for key, values in sorted(groups.items(), key=lambda item: tuple(
        "" if value is None else str(value) for value in item[0]
)):
    store_name, item_type, item_name, is_income, transaction_type, document_type = key
    result["groups"].append({
        "storeName": store_name,
        "cashItemType": item_type,
        "cashItemName": item_name,
        "isIncome": is_income,
        "transactionType": transaction_type,
        "documentType": document_type,
        "count": values["count"],
        "amount": money(values["amount"]),
    })

print(json.dumps(result, ensure_ascii=False, indent=2))
PY
