#!/usr/bin/env bash

set -Eeuo pipefail
set +x

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=common.sh
source "${SCRIPT_DIR}/common.sh"

load_livesklad_environment
require_command date

CURRENT_YEAR="$(date -u +%Y)"
DISCOVERY_HISTORY_START_YEAR="${DISCOVERY_HISTORY_START_YEAR:-2018}"

[[ "${DISCOVERY_HISTORY_START_YEAR}" =~ ^[0-9]{4}$ ]] \
    || fail 'DISCOVERY_HISTORY_START_YEAR must be a four-digit year'
((DISCOVERY_HISTORY_START_YEAR >= 2010)) \
    || fail 'DISCOVERY_HISTORY_START_YEAR must not be earlier than 2010'
((DISCOVERY_HISTORY_START_YEAR <= CURRENT_YEAR)) \
    || fail 'DISCOVERY_HISTORY_START_YEAR must not be in the future'
((CURRENT_YEAR - DISCOVERY_HISTORY_START_YEAR <= 15)) \
    || fail 'History probe must not cover more than 16 calendar years'

TOKEN=''
SHOPS_RESPONSE=''
EDGE_RESPONSE=''
YEAR_RESPONSE=''
STORE_RESULTS='[]'
trap 'unset TOKEN SHOPS_RESPONSE EDGE_RESPONSE YEAR_RESPONSE STORE_RESULTS' EXIT

TOKEN="$(livesklad_access_token)" \
    || fail 'LiveSklad authentication failed; credentials and response body were not printed'
SHOPS_RESPONSE="$(livesklad_get "${TOKEN}" '/shops')" \
    || fail 'LiveSklad shops request failed; response body was not printed'

mapfile -t SHOP_IDS < <(jq -er '.data[]?.id' <<<"${SHOPS_RESPONSE}")
(("${#SHOP_IDS[@]}" > 0)) || fail 'LiveSklad returned no shops'

for index in "${!SHOP_IDS[@]}"; do
    shop_index=$((index + 1))
    shop_id="${SHOP_IDS[${index}]}"

    EDGE_RESPONSE="$(livesklad_get "${TOKEN}" "/shops/${shop_id}/sales" \
        --get \
        --data-urlencode 'page=1' \
        --data-urlencode 'pageSize=1' \
        --data-urlencode 'sort=date ASC')" \
        || fail "Earliest-sale request failed for shop index ${shop_index}"
    jq -e '.data | type == "array"' >/dev/null <<<"${EDGE_RESPONSE}" \
        || fail "Earliest-sale response for shop index ${shop_index} has no data array"

    earliest_date="$(jq -r '.data[0].date // empty' <<<"${EDGE_RESPONSE}")"
    unbounded_total="$(jq -c '.total // null' <<<"${EDGE_RESPONSE}")"

    EDGE_RESPONSE="$(livesklad_get "${TOKEN}" "/shops/${shop_id}/sales" \
        --get \
        --data-urlencode 'page=1' \
        --data-urlencode 'pageSize=1' \
        --data-urlencode 'sort=date DESC')" \
        || fail "Latest-sale request failed for shop index ${shop_index}"
    jq -e '.data | type == "array"' >/dev/null <<<"${EDGE_RESPONSE}" \
        || fail "Latest-sale response for shop index ${shop_index} has no data array"

    latest_date="$(jq -r '.data[0].date // empty' <<<"${EDGE_RESPONSE}")"
    yearly_counts='[]'
    completed=true

    for ((year = DISCOVERY_HISTORY_START_YEAR; year <= CURRENT_YEAR; year += 1)); do
        start_ms="$(date -u -d "${year}-01-01T00:00:00Z" +%s)000"
        next_year=$((year + 1))
        end_ms="$(( $(date -u -d "${next_year}-01-01T00:00:00Z" +%s) * 1000 - 1 ))"

        YEAR_RESPONSE="$(livesklad_get "${TOKEN}" "/shops/${shop_id}/sales" \
            --get \
            --data-urlencode "date=[${start_ms},${end_ms}]" \
            --data-urlencode 'page=1' \
            --data-urlencode 'pageSize=1' \
            --data-urlencode 'sort=date ASC')" \
            || fail "Year ${year} sales request failed for shop index ${shop_index}"
        jq -e '.data | type == "array"' >/dev/null <<<"${YEAR_RESPONSE}" \
            || fail "Year ${year} response for shop index ${shop_index} has no data array"

        year_result="$(jq -cn \
            --argjson year "${year}" \
            --argjson response "${YEAR_RESPONSE}" '
            {
                year: $year,
                reportedTotal: ($response.total // null),
                firstObservedDate: ($response.data[0].date // null)
            }
        ')"
        yearly_counts="$(jq -cn \
            --argjson current "${yearly_counts}" \
            --argjson next "${year_result}" \
            '$current + [$next]')"

        remain_request="$(jq -r '.remainRequest // 999' <<<"${YEAR_RESPONSE}")"
        if ((remain_request <= 10)); then
            completed=false
            break
        fi
    done

    store_result="$(jq -cn \
        --argjson shopIndex "${shop_index}" \
        --arg earliestDate "${earliest_date}" \
        --arg latestDate "${latest_date}" \
        --argjson unboundedTotal "${unbounded_total}" \
        --argjson yearlyCounts "${yearly_counts}" \
        --argjson completed "${completed}" \
        --argjson remainRequest "$(jq -c '.remainRequest // null' <<<"${YEAR_RESPONSE}")" \
        --argjson expireDate "$(jq -c '.expireDate // null' <<<"${YEAR_RESPONSE}")" '
        {
            shopIndex: $shopIndex,
            earliestObservedDate: (
                if $earliestDate == "" then null else $earliestDate end
            ),
            latestObservedDate: (
                if $latestDate == "" then null else $latestDate end
            ),
            unboundedReportedTotal: $unboundedTotal,
            yearlyCounts: $yearlyCounts,
            probeCompleted: $completed,
            lastRateLimitMetadata: {
                remainRequest: $remainRequest,
                expireDate: $expireDate
            }
        }
    ')"

    STORE_RESULTS="$(jq -cn \
        --argjson current "${STORE_RESULTS}" \
        --argjson next "${store_result}" \
        '$current + [$next]')"

    [[ "${completed}" == true ]] || break
done

jq -cn \
    --argjson startYear "${DISCOVERY_HISTORY_START_YEAR}" \
    --argjson endYear "${CURRENT_YEAR}" \
    --argjson stores "${STORE_RESULTS}" '
    {
        request: {
            endpoint: "/shops/{id}/sales",
            startYear: $startYear,
            endYear: $endYear,
            pageSize: 1,
            outputContainsBusinessRows: false
        },
        stores: $stores
    }
'
