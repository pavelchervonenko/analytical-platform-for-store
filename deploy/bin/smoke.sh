#!/usr/bin/env bash

set -Eeuo pipefail
set +x

readonly APP_DOMAIN="${APP_DOMAIN:?APP_DOMAIN is required}"
readonly BASE_URL="https://${APP_DOMAIN}"

retry() {
  local description="$1"
  shift
  local attempt
  for attempt in $(seq 1 30); do
    if "$@"; then
      printf 'OK: %s\n' "${description}"
      return 0
    fi
    sleep 2
  done
  printf 'FAILED: %s\n' "${description}" >&2
  return 1
}

retry 'frontend HTTPS' curl --fail --silent --show-error \
  --connect-timeout 5 --max-time 15 "${BASE_URL}/"
retry 'backend liveness' curl --fail --silent --show-error \
  --connect-timeout 5 --max-time 15 "${BASE_URL}/livez"
retry 'backend readiness' curl --fail --silent --show-error \
  --connect-timeout 5 --max-time 15 "${BASE_URL}/readyz"

headers="$(curl --fail --silent --show-error --head --max-time 15 "${BASE_URL}/")"
grep -Eiq '^strict-transport-security:.*max-age=' <<<"${headers}"
grep -Eiq '^x-content-type-options:[[:space:]]*nosniff' <<<"${headers}"

if curl --fail --silent --show-error --max-time 15 \
  "${BASE_URL}/actuator/prometheus" >/dev/null; then
  printf 'FAILED: public actuator endpoint is reachable\n' >&2
  exit 1
fi

printf 'Public smoke test completed successfully\n'
