#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly SECRETS_DIR="${SECRETS_DIR:-/etc/store-analytics/secrets}"

die() {
  printf 'SECRET PROVISIONING FAILED: %s\n' "$*" >&2
  exit 1
}

[[ "$(id -u)" -eq 0 ]] || die 'run as root'
command -v openssl >/dev/null 2>&1 || die 'missing command: openssl'
install -d -o root -g root -m 0700 "${SECRETS_DIR}"

generate_url_safe_secret() {
  local target="$1"
  local temporary

  if [[ -s "${target}" ]]; then
    [[ -f "${target}" && ! -L "${target}" ]] \
      || die "refusing non-regular secret: ${target}"
    chown root:root "${target}"
    chmod 0600 "${target}"
    printf 'Keeping existing secret: %s\n' "$(basename -- "${target}")"
    return
  fi
  temporary="$(mktemp "${SECRETS_DIR}/.secret.XXXXXX")"
  openssl rand -hex 32 >"${temporary}"
  chown root:root "${temporary}"
  chmod 0600 "${temporary}"
  mv -- "${temporary}" "${target}"
  printf 'Provisioned secret: %s\n' "$(basename -- "${target}")"
}

generate_url_safe_secret "${SECRETS_DIR}/livesklad-sale-return-webhook-secret"
generate_url_safe_secret "${SECRETS_DIR}/livesklad-order-return-webhook-secret"
