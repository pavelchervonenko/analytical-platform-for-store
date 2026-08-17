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
DISCOVERY_MAX_PAGES="${DISCOVERY_MAX_PAGES:-4}"
DISCOVERY_MAX_ORDER_DETAILS="${DISCOVERY_MAX_ORDER_DETAILS:-90}"

[[ "${AUDIT_PERIOD_START}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || fail 'AUDIT_PERIOD_START must use YYYY-MM-DD'
[[ "${AUDIT_PERIOD_END_EXCLUSIVE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || fail 'AUDIT_PERIOD_END_EXCLUSIVE must use YYYY-MM-DD'
[[ "${DISCOVERY_MAX_ORDER_DETAILS}" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DISCOVERY_MAX_ORDER_DETAILS must be a positive integer'
((DISCOVERY_MAX_ORDER_DETAILS <= 90)) \
    || fail 'DISCOVERY_MAX_ORDER_DETAILS must not exceed 90'

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
DETAILS_FILE="${WORK_DIR}/order-details.jsonl"
TOKEN=''
trap 'rm -rf -- "${WORK_DIR}"; unset TOKEN START_MS END_MS' EXIT
: >"${DETAILS_FILE}"

TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
orders_response="$(livesklad_get_paginated \
    "${TOKEN}" '/company/orders' \
    --data-urlencode "dateCreate=[${START_MS},${END_MS}]" \
    --data-urlencode 'sort=dateCreate ASC')" \
    || fail 'LiveSklad orders request failed'

order_count="$(jq -er '.data | length' <<<"${orders_response}")"
((order_count <= DISCOVERY_MAX_ORDER_DETAILS)) || fail \
    "Order detail count ${order_count} exceeds the deliberate ${DISCOVERY_MAX_ORDER_DETAILS}-request guard"

mapfile -t ORDER_ROWS < <(jq -c '.data[]' <<<"${orders_response}")
last_remain_request="$(jq -c '.remainRequest // null' <<<"${orders_response}")"
last_expire_date="$(jq -c '.expireDate // null' <<<"${orders_response}")"
for order_row in "${ORDER_ROWS[@]}"; do
    order_id="$(jq -er '.id' <<<"${order_row}")"
    detail_response="$(livesklad_get "${TOKEN}" "/orders/${order_id}")" \
        || fail 'LiveSklad order detail request failed'
    last_remain_request="$(jq -c '.remainRequest // null' <<<"${detail_response}")"
    last_expire_date="$(jq -c '.expireDate // null' <<<"${detail_response}")"
    jq -cn \
        --argjson list "${order_row}" \
        --argjson detail "$(jq -c '.data' <<<"${detail_response}")" '
        {
            list: {
                id: $list.id,
                number: $list.number,
                dateCreate: $list.dateCreate,
                lastAction: ($list.lastAction // null),
                dateClose: ($list.dateClose // null),
                storeId: ($list.shop.id // null),
                storeName: ($list.shop.name // null),
                statusId: ($list.status.id // null),
                statusName: ($list.status.name // null),
                typeOrderId: ($list.typeOrder.id // null),
                typeOrderName: ($list.typeOrder.name // null),
                isVisible: ($list.isVisible // null),
                soldPrice: ($list.summ.soldPrice // 0),
                cashSumm: ($list.cash.summ // 0)
            },
            detail: {
                id: $detail.id,
                lastAction: ($detail.lastAction // null),
                dateClose: ($detail.dateClose // null),
                storeId: ($detail.shop.id // null),
                statusId: ($detail.status.id // null),
                isVisible: ($detail.isVisible // null),
                cashOrder: ($detail.cash.order // 0),
                cashInvoice: ($detail.cash.invoice // 0),
                cashOrderReturn: ($detail.cash.orderReturn // 0),
                positions: [
                    $detail.positions[]?
                    | {
                        fieldNames: (keys | sort),
                        count: (.count // 0),
                        soldPrice: (.soldPrice // 0),
                        purchasePriceSumm: (.purchasePriceSumm // null),
                        isWork: (.isWork // false)
                    }
                ]
            }
        }
    ' >>"${DETAILS_FILE}"
done

python3 - \
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


def value(candidate):
    return ZERO if candidate is None else decimal.Decimal(candidate)


def money(candidate):
    return str(candidate.quantize(decimal.Decimal("0.01")))


records = []
with open(sys.argv[1], encoding="utf-8") as source:
    for line in source:
        if line.strip():
            records.append(json.loads(
                line,
                parse_float=decimal.Decimal,
                parse_int=decimal.Decimal,
            ))

totals = collections.defaultdict(lambda: {
    "orderCount": 0,
    "visibleOrderCount": 0,
    "positionCount": 0,
    "workPositionCount": 0,
    "productPositionCount": 0,
    "listSoldPrice": ZERO,
    "listCashSumm": ZERO,
    "positionAmount": ZERO,
    "workPositionAmount": ZERO,
    "productPositionAmount": ZERO,
    "detailCashOrder": ZERO,
    "detailCashInvoice": ZERO,
    "detailCashOrderReturn": ZERO,
})
position_field_names = set()
relation_mismatches = []

for record in records:
    listed = record["list"]
    detail = record["detail"]
    key = (
        listed.get("storeName") or listed.get("storeId") or "UNKNOWN",
        listed.get("statusName") or listed.get("statusId") or "UNKNOWN",
    )
    target = totals[key]
    positions = detail["positions"]
    position_amount = sum(
        (value(position["soldPrice"]) * value(position["count"])
         for position in positions),
        ZERO,
    )
    work_amount = sum(
        (value(position["soldPrice"]) * value(position["count"])
         for position in positions if position["isWork"]),
        ZERO,
    )
    target["orderCount"] += 1
    target["visibleOrderCount"] += int(listed.get("isVisible") is True)
    target["positionCount"] += len(positions)
    target["workPositionCount"] += sum(1 for position in positions if position["isWork"])
    target["productPositionCount"] += sum(1 for position in positions if not position["isWork"])
    target["listSoldPrice"] += value(listed["soldPrice"])
    target["listCashSumm"] += value(listed["cashSumm"])
    target["positionAmount"] += position_amount
    target["workPositionAmount"] += work_amount
    target["productPositionAmount"] += position_amount - work_amount
    target["detailCashOrder"] += value(detail["cashOrder"])
    target["detailCashInvoice"] += value(detail["cashInvoice"])
    target["detailCashOrderReturn"] += value(detail["cashOrderReturn"])
    for position in positions:
        position_field_names.update(position["fieldNames"])
    if listed.get("storeId") != detail.get("storeId") \
            or listed.get("statusId") != detail.get("statusId"):
        relation_mismatches.append(listed.get("number"))

result = {
    "scope": "orders filtered by dateCreate; not an exact reproduction of the configurable report",
    "periodStart": sys.argv[2],
    "periodEndExclusive": sys.argv[3],
    "timezone": sys.argv[4],
    "orderCount": len(records),
    "positionFieldNames": sorted(position_field_names),
    "relationMismatchCount": len(relation_mismatches),
    "lastRemainRequest": json.loads(sys.argv[5]),
    "lastExpireDate": json.loads(sys.argv[6]),
    "groups": [],
}
for (store_name, status_name), target in sorted(totals.items()):
    result["groups"].append({
        "storeName": store_name,
        "statusName": status_name,
        **{
            key: money(candidate) if isinstance(candidate, decimal.Decimal) else candidate
            for key, candidate in target.items()
        },
    })

print(json.dumps(result, ensure_ascii=False, indent=2))
PY
