#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly SOURCE_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
readonly IMAGE_ARCHIVE="${1:-/home/pavel/store-analytics-images-v0.1.0-pilot.2.tar.gz}"
readonly SECRETS_DIR="/etc/store-analytics/secrets"
readonly RELEASE_ENV="/etc/store-analytics/release.env"
readonly BACKUP_ENV="/etc/store-analytics/backup.env"
readonly MONITOR_ENV="/etc/store-analytics/monitor.env"

die() {
  printf 'FIRST INSTALL FAILED: %s\n' "$*" >&2
  exit 1
}

[[ "$(id -u)" -eq 0 ]] || die 'run as root'
[[ -f "${IMAGE_ARCHIVE}" ]] || die "image archive not found: ${IMAGE_ARCHIVE}"

for command_name in docker gzip openssl curl pg_dump pg_restore gpg aws; do
  command -v "${command_name}" >/dev/null 2>&1 || die "missing command: ${command_name}"
done

"${SOURCE_DIR}/bin/install-host.sh"

for required_file in \
  "${SECRETS_DIR}/postgres-runtime-password" \
  "${SECRETS_DIR}/postgres-migrator-password" \
  "${SECRETS_DIR}/postgres-backup-password" \
  "${SECRETS_DIR}/s3-backup-credentials" \
  "${SECRETS_DIR}/s3-backup-config"; do
  [[ -s "${required_file}" ]] || die "required provisioned secret is missing: ${required_file}"
done

prompt_secret() {
  local target="$1"
  local prompt="$2"
  local min_length="$3"
  local value
  if [[ -s "${target}" ]]; then
    printf 'Keeping existing secret: %s\n' "$(basename -- "${target}")"
    return
  fi
  read -r -s -p "${prompt}: " value </dev/tty
  printf '\n' >/dev/tty
  (( ${#value} >= min_length )) || die "value for $(basename -- "${target}") is too short"
  printf '%s' "${value}" >"${target}"
  chmod 0600 "${target}"
  unset value
}

generate_secret() {
  local target="$1"
  if [[ ! -s "${target}" ]]; then
    openssl rand -base64 48 | tr -d '\n' >"${target}"
    chmod 0600 "${target}"
  fi
}

prompt_secret "${SECRETS_DIR}/livesklad-login" 'LiveSklad login' 1
prompt_secret "${SECRETS_DIR}/livesklad-password" 'LiveSklad password' 8
prompt_secret "${SECRETS_DIR}/yandex-ai-api-key" 'Yandex AI API key' 16
prompt_secret "${SECRETS_DIR}/telegram-bot-token" 'Telegram bot token' 32
prompt_secret "${SECRETS_DIR}/bootstrap-admin-password" 'Initial developer admin password' 12

generate_secret "${SECRETS_DIR}/telegram-webhook-secret"
generate_secret "${SECRETS_DIR}/security-telemetry-pseudonym-key"
generate_secret "${SECRETS_DIR}/prometheus-scrape-token"
generate_secret "${SECRETS_DIR}/backup-encryption-passphrase"

touch "${SECRETS_DIR}/telegram-tech-alert-chat-id"
chmod 0600 "${SECRETS_DIR}"/*

printf 'Loading prebuilt application images\n'
gzip -dc "${IMAGE_ARCHIVE}" | docker load
backend_image_id="$(docker image inspect --format '{{.Id}}' store-analytics-backend:v0.1.0-pilot.2)"

release_tmp="$(mktemp)"
cp "${SOURCE_DIR}/env.production.example" "${release_tmp}"
sed -i \
  -e 's#^ACME_EMAIL=.*#ACME_EMAIL=pavel.chervonenko.97@gmail.com#' \
  -e 's#^BACKEND_IMAGE=.*#BACKEND_IMAGE=store-analytics-backend:v0.1.0-pilot.2#' \
  -e 's#^WEB_IMAGE=.*#WEB_IMAGE=store-analytics-web:v0.1.0-pilot.2#' \
  -e 's#^RELEASE_ID=.*#RELEASE_ID=v0.1.0-pilot.2#' \
  -e "s#^BACKEND_IMAGE_DIGEST=.*#BACKEND_IMAGE_DIGEST=${backend_image_id}#" \
  -e 's#^SKIP_IMAGE_PULL=.*#SKIP_IMAGE_PULL=true#' \
  -e 's#^BOOTSTRAP_ADMIN_EMAIL=.*#BOOTSTRAP_ADMIN_EMAIL=pavel.chervonenko.97@gmail.com#' \
  "${release_tmp}"
install -o root -g root -m 0600 "${release_tmp}" "${RELEASE_ENV}"
rm -f -- "${release_tmp}"

install -o root -g root -m 0600 "${SOURCE_DIR}/backup.env.example" "${BACKUP_ENV}"
install -o root -g root -m 0600 "${SOURCE_DIR}/monitor.env.example" "${MONITOR_ENV}"

"/opt/store-analytics/deploy/bin/deploy.sh" "${RELEASE_ENV}"

set -a
# shellcheck disable=SC1090
source "${BACKUP_ENV}"
set +a
"/opt/store-analytics/deploy/bin/backup-postgres.sh"

systemctl enable --now store-analytics-backup.timer
printf 'Pilot foundation installed. Health timer remains disabled until technical chat ID acceptance.\n'
