#!/usr/bin/env bash
set -Eeuo pipefail
set +x

readonly PROJECT_ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)"
# Local/CI validator only. Not an application runtime image or production monitoring installation.
readonly PROMTOOL_IMAGE='prom/prometheus:v3.2.1'
command -v docker >/dev/null 2>&1 || { printf 'Docker is required for promtool validation.\n' >&2; exit 1; }
promtool_args=(--rm --network none --read-only --cap-drop ALL
  --security-opt no-new-privileges --tmpfs /tmp:rw,noexec,nosuid,size=64m
  --entrypoint /bin/promtool --workdir /rules
  --mount "type=bind,source=${PROJECT_ROOT}/monitoring/prometheus,target=/rules,readonly")
docker run "${promtool_args[@]}" "${PROMTOOL_IMAGE}" check rules weekly-review-ai-alerts.yml
docker run "${promtool_args[@]}" "${PROMTOOL_IMAGE}" test rules tests/weekly-review-ai-alerts-test.yml
