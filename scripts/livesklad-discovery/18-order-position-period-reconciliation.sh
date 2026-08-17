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
DISCOVERY_MAX_PAGES="${DISCOVERY_MAX_PAGES:-10}"
DISCOVERY_MAX_ORDER_DETAILS="${DISCOVERY_MAX_ORDER_DETAILS:-70}"

[[ "${AUDIT_PERIOD_START}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || fail 'AUDIT_PERIOD_START must use YYYY-MM-DD'
[[ "${AUDIT_PERIOD_END_EXCLUSIVE}" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] \
    || fail 'AUDIT_PERIOD_END_EXCLUSIVE must use YYYY-MM-DD'
[[ "${DISCOVERY_MAX_ORDER_DETAILS}" =~ ^[1-9][0-9]*$ ]] \
    || fail 'DISCOVERY_MAX_ORDER_DETAILS must be a positive integer'
((DISCOVERY_MAX_ORDER_DETAILS <= 70)) \
    || fail 'DISCOVERY_MAX_ORDER_DETAILS must not exceed 70'

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
OBSERVED_THROUGH_MS="$(($(date -u +%s) * 1000))"

WORK_DIR="$(mktemp -d)" || fail 'Could not create temporary audit directory'
DETAILS_FILE="${WORK_DIR}/order-details.jsonl"
TOKEN=''
trap 'rm -rf -- "${WORK_DIR}"; unset TOKEN START_MS END_MS OBSERVED_THROUGH_MS' EXIT
: >"${DETAILS_FILE}"

TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
orders_response="$(livesklad_get_paginated \
    "${TOKEN}" '/company/orders' \
    --data-urlencode "lastAction=[${START_MS},${OBSERVED_THROUGH_MS}]" \
    --data-urlencode 'sort=lastAction ASC')" \
    || fail 'LiveSklad orders request failed'

jq -e '.data | type == "array"' >/dev/null <<<"${orders_response}" \
    || fail 'Orders response does not contain a data array'
candidate_count="$(jq '[.data[] | select(
    (.summ.soldPrice // 0) != 0 or (.cash.summ // 0) != 0
)] | length' <<<"${orders_response}")"
((candidate_count <= DISCOVERY_MAX_ORDER_DETAILS)) || fail \
    "Monetary order detail count ${candidate_count} exceeds the ${DISCOVERY_MAX_ORDER_DETAILS}-request guard"

mapfile -t ORDER_ROWS < <(jq -c '.data[] | select(
    (.summ.soldPrice // 0) != 0 or (.cash.summ // 0) != 0
)' <<<"${orders_response}")
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
            orderId: $list.id,
            orderNumber: $list.number,
            orderDateCreate: $list.dateCreate,
            orderLastAction: ($list.lastAction // null),
            orderDateClose: ($list.dateClose // null),
            storeId: ($list.shop.id // null),
            storeName: ($list.shop.name // null),
            statusName: ($list.status.name // null),
            isVisible: ($list.isVisible // null),
            listSoldPrice: ($list.summ.soldPrice // 0),
            listCashSumm: ($list.cash.summ // 0),
            cashOrder: ($detail.cash.order // 0),
            cashInvoice: ($detail.cash.invoice // 0),
            cashOrderReturn: ($detail.cash.orderReturn // 0),
            positions: [
                $detail.positions[]?
                | {
                    positionId,
                    date: (.date // null),
                    count: (.count // 0),
                    soldPrice: (.soldPrice // 0),
                    purchasePriceSumm: (.purchasePriceSumm // null),
                    isWork: (.isWork // false)
                }
            ]
        }
    ' >>"${DETAILS_FILE}"
done

python3 - \
    "${DETAILS_FILE}" \
    "${AUDIT_PERIOD_START}" \
    "${AUDIT_PERIOD_END_EXCLUSIVE}" \
    "${AUDIT_TIMEZONE}" \
    "$(jq -c '.reportedTotal // null' <<<"${orders_response}")" \
    "${candidate_count}" \
    "${last_remain_request}" \
    "${last_expire_date}" <<'PY'
import collections
import datetime as dt
import decimal
import json
import sys
from zoneinfo import ZoneInfo

decimal.getcontext().prec = 38
ZERO = decimal.Decimal()
start = dt.date.fromisoformat(sys.argv[2])
end = dt.date.fromisoformat(sys.argv[3])
zone = ZoneInfo(sys.argv[4])


def value(candidate):
    return ZERO if candidate is None else decimal.Decimal(candidate)


def money(candidate):
    return str(candidate.quantize(decimal.Decimal("0.01")))


def local_date(candidate):
    if candidate is None:
        return None
    parsed = dt.datetime.fromisoformat(candidate.replace("Z", "+00:00"))
    return parsed.astimezone(zone).date()


records = []
with open(sys.argv[1], encoding="utf-8") as source:
    for line in source:
        if line.strip():
            records.append(json.loads(
                line,
                parse_float=decimal.Decimal,
                parse_int=decimal.Decimal,
            ))

groups = collections.defaultdict(lambda: {
    "orderCount": 0,
    "positionCount": 0,
    "workPositionCount": 0,
    "productPositionCount": 0,
    "positionAmount": ZERO,
    "workPositionAmount": ZERO,
    "productPositionAmount": ZERO,
})
missing_position_dates = 0
for record in records:
    order_seen = set()
    for position in record["positions"]:
        position_date = local_date(position["date"])
        if position_date is None:
            missing_position_dates += 1
            continue
        if not start <= position_date < end:
            continue
        key = (
            record.get("storeName") or record.get("storeId") or "UNKNOWN",
            record.get("statusName") or "UNKNOWN",
        )
        amount = value(position["soldPrice"]) * value(position["count"])
        target = groups[key]
        order_seen.add(key)
        target["positionCount"] += 1
        target["positionAmount"] += amount
        if position["isWork"]:
            target["workPositionCount"] += 1
            target["workPositionAmount"] += amount
        else:
            target["productPositionCount"] += 1
            target["productPositionAmount"] += amount
    for key in order_seen:
        groups[key]["orderCount"] += 1

result = {
    "scope": "monetary orders changed since period start; positions filtered by their own date",
    "periodStart": sys.argv[2],
    "periodEndExclusive": sys.argv[3],
    "timezone": sys.argv[4],
    "reportedChangedOrderCount": json.loads(sys.argv[5]),
    "monetaryCandidateCount": int(sys.argv[6]),
    "missingPositionDateCount": missing_position_dates,
    "lastRemainRequest": json.loads(sys.argv[7]),
    "lastExpireDate": json.loads(sys.argv[8]),
    "groups": [],
}
for (store_name, status_name), target in sorted(groups.items()):
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
