#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly INSTALL_DIR="/opt/store-analytics/deploy"

[[ "$(id -u)" -eq 0 ]] || { printf 'Run as root\n' >&2; exit 1; }

install -d -o root -g root -m 0755 \
  /opt/store-analytics \
  "${INSTALL_DIR}" \
  "${INSTALL_DIR}/bin" \
  "${INSTALL_DIR}/systemd"
install -d -o root -g root -m 0700 \
  /etc/store-analytics \
  /etc/store-analytics/secrets
install -d -o root -g root -m 0755 /etc/store-analytics/pki
install -d -o root -g root -m 0750 \
  /var/lib/store-analytics \
  /var/lib/store-analytics/backup-tmp \
  /var/lib/store-analytics/release-state

install -o root -g root -m 0644 \
  "${SOURCE_DIR}/compose.production.yml" \
  "${SOURCE_DIR}/Caddyfile" \
  "${INSTALL_DIR}/"
install -o root -g root -m 0755 "${SOURCE_DIR}"/bin/*.sh "${INSTALL_DIR}/bin/"
install -o root -g root -m 0644 "${SOURCE_DIR}"/systemd/* \
  "${INSTALL_DIR}/systemd/"
install -o root -g root -m 0644 "${SOURCE_DIR}"/systemd/* \
  /etc/systemd/system/

systemctl daemon-reload

printf 'Host deployment artifacts installed.\n'
printf 'Timers remain disabled until release, backup and monitor configuration pass acceptance.\n'
