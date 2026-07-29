#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
input="${1:-${PROJECT_DIR}/outputs/category-review-approved/product-category-assignments-v1.json}"
output="${2:-${OUTPUT_FILE:-}}"

[[ -f "${input}" && -r "${input}" ]] || {
    printf 'Input is not a readable regular file: %s\n' "${input}" >&2
    exit 1
}

render_review() {
    jq '
      def normalized_name: .productName | ascii_downcase;
      def candidate:
        if .categoryCode == "IPAD_MAC" and (normalized_name | contains("macbook")) then
          . + {payrollCategoryCode: "TECH_TIER_1", rule: "MacBook"}
        elif .categoryCode == "IPAD_MAC" and (normalized_name | contains("ipad")) then
          . + {payrollCategoryCode: "TECH_TIER_2", rule: "iPad"}
        elif (.categoryCode == "IPAD_MAC" or .categoryCode == "PODS_WATCH_OTHER_DEVICE")
            and (normalized_name | contains("dyson")) then
          . + {payrollCategoryCode: "TECH_TIER_1", rule: "Dyson"}
        elif .categoryCode == "PODS_WATCH_OTHER_DEVICE"
            and (normalized_name | test("playstation[[:space:]]*5|(^|[^a-z0-9])ps5([^a-z0-9]|$)")) then
          . + {payrollCategoryCode: "TECH_TIER_1", rule: "PlayStation 5 console"}
        else
          empty
        end;

      [.assignments[] | candidate]
      | sort_by(.rule, .productName, .externalProductId)
      | {
          artifactType: "PAYROLL_CLASSIFICATION_REVIEW",
          sourceRuleVersion: "customer-approved-2026-07-20-v1",
          proposedValidFrom: "2026-01-01",
          changeReason: "Customer-confirmed payroll classification v1",
          candidateCount: length,
          candidates: map({
            externalProductId,
            productName,
            analyticsCategoryCode: .categoryCode,
            conditionType,
            payrollCategoryCode,
            rule
          })
        }
    ' "${input}"
}

if [[ -z "${output}" ]]; then
    render_review
    exit 0
fi

output_directory="$(cd -- "$(dirname -- "${output}")" && pwd)" || {
    printf 'Output directory does not exist: %s\n' "$(dirname -- "${output}")" >&2
    exit 1
}
output_path="${output_directory}/$(basename -- "${output}")"
[[ ! -e "${output_path}" && ! -L "${output_path}" ]] || {
    printf 'Refusing to overwrite existing output: %s\n' "${output_path}" >&2
    exit 1
}

temporary_output="$(mktemp "${output_directory}/.payroll-classification-review.XXXXXX")"
cleanup() {
    [[ -z "${temporary_output:-}" ]] || rm -f -- "${temporary_output}"
}
trap cleanup EXIT

render_review >"${temporary_output}"
chmod 600 "${temporary_output}"
if ! ln "${temporary_output}" "${output_path}"; then
    printf 'Could not publish output without overwriting: %s\n' "${output_path}" >&2
    exit 1
fi
rm -f -- "${temporary_output}"
temporary_output=''

printf 'Wrote confidential classification review: %s\n' "${output_path}" >&2
