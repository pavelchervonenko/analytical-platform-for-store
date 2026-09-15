#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
exec "${SCRIPT_DIR}/run-bounded-classification-correction.sh" \
  "${SCRIPT_DIR}/manifests/2026-03-magazin.json" "$@"
