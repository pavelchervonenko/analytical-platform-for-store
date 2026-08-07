#!/usr/bin/env bash

set -Eeuo pipefail
set +x
umask 077

readonly DB_CERT_HOST="${DB_CERT_HOST:?required}"
readonly DB_HOST_ADDRESS="${DB_HOST_ADDRESS:?required}"
readonly DB_PORT="${DB_PORT:-5432}"
readonly DB_NAME="${DB_NAME:?required}"
readonly DB_BACKUP_USER="${DB_BACKUP_USER:-store_backup_reader}"
readonly POSTGRES_CA_FILE="${POSTGRES_CA_FILE:?required}"
readonly POSTGRES_BACKUP_PASSWORD_FILE="${POSTGRES_BACKUP_PASSWORD_FILE:?required}"
readonly BACKUP_ENCRYPTION_PASSPHRASE_FILE="${BACKUP_ENCRYPTION_PASSPHRASE_FILE:?required}"
readonly AWS_SHARED_CREDENTIALS_FILE="${AWS_SHARED_CREDENTIALS_FILE:?required}"
readonly AWS_CONFIG_FILE="${AWS_CONFIG_FILE:?required}"
readonly AWS_PROFILE="${AWS_PROFILE:-timeweb-backup}"
readonly S3_ENDPOINT="${S3_ENDPOINT:?required}"
readonly S3_BUCKET="${S3_BUCKET:?required}"
readonly BACKUP_CLASS="${BACKUP_CLASS:-daily}"
readonly TMP_ROOT="${TMP_ROOT:-/var/lib/store-analytics/backup-tmp}"

for command_name in pg_dump pg_restore gpg aws sha256sum stat; do
  command -v "${command_name}" >/dev/null 2>&1 \
    || { printf 'Missing command: %s\n' "${command_name}" >&2; exit 1; }
done

for secret_file in \
  "${POSTGRES_BACKUP_PASSWORD_FILE}" \
  "${BACKUP_ENCRYPTION_PASSPHRASE_FILE}" \
  "${AWS_SHARED_CREDENTIALS_FILE}" \
  "${AWS_CONFIG_FILE}"; do
  [[ -r "${secret_file}" ]] || { printf 'Unreadable file: %s\n' "${secret_file}" >&2; exit 1; }
done

work_dir="$(mktemp -d "${TMP_ROOT}/backup.XXXXXXXX")"
cleanup() {
  rm -rf -- "${work_dir}"
}
trap cleanup EXIT

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
day_path="$(date -u +%Y/%m/%d)"
base_name="store-analytics-${timestamp}"
dump_file="${work_dir}/${base_name}.dump"
encrypted_file="${dump_file}.gpg"
manifest_file="${encrypted_file}.manifest"
pgpass_file="${work_dir}/pgpass"
export GNUPGHOME="${work_dir}/gnupg"
mkdir -m 0700 "${GNUPGHOME}"

password="$(<"${POSTGRES_BACKUP_PASSWORD_FILE}")"
printf '%s:%s:%s:%s:%s\n' \
  "${DB_CERT_HOST}" "${DB_PORT}" "${DB_NAME}" "${DB_BACKUP_USER}" "${password}" \
  >"${pgpass_file}"
unset password
chmod 0600 "${pgpass_file}"

connection="host=${DB_CERT_HOST} hostaddr=${DB_HOST_ADDRESS} port=${DB_PORT} dbname=${DB_NAME} user=${DB_BACKUP_USER} sslmode=verify-full sslrootcert=${POSTGRES_CA_FILE} application_name=logical-backup"

PGPASSFILE="${pgpass_file}" pg_dump \
  --dbname="${connection}" \
  --format=custom \
  --compress=9 \
  --no-owner \
  --no-acl \
  --schema=app \
  --serializable-deferrable \
  --file="${dump_file}"

pg_restore --list "${dump_file}" >/dev/null

gpg --batch --yes --quiet \
  --symmetric --cipher-algo AES256 \
  --pinentry-mode loopback \
  --passphrase-file "${BACKUP_ENCRYPTION_PASSPHRASE_FILE}" \
  --output "${encrypted_file}" "${dump_file}"
rm -f -- "${dump_file}" "${pgpass_file}"

checksum="$(sha256sum "${encrypted_file}" | awk '{print $1}')"
size="$(stat -c '%s' "${encrypted_file}")"
{
  printf 'format=store-analytics-postgres-backup-v1\n'
  printf 'created_at=%s\n' "${timestamp}"
  printf 'database=%s\n' "${DB_NAME}"
  printf 'schema=app\n'
  printf 'encrypted_sha256=%s\n' "${checksum}"
  printf 'encrypted_size=%s\n' "${size}"
  printf 'pg_dump_version=%s\n' "$(pg_dump --version | tr ' ' '_')"
} >"${manifest_file}"

object_prefix="postgres/${BACKUP_CLASS}/${day_path}/${base_name}"
aws_args=(--profile "${AWS_PROFILE}" --endpoint-url "${S3_ENDPOINT}")

aws "${aws_args[@]}" s3 cp "${encrypted_file}" \
  "s3://${S3_BUCKET}/${object_prefix}.dump.gpg" \
  --only-show-errors \
  --metadata "sha256=${checksum},backup-class=${BACKUP_CLASS}"
aws "${aws_args[@]}" s3 cp "${manifest_file}" \
  "s3://${S3_BUCKET}/${object_prefix}.dump.gpg.manifest" \
  --only-show-errors

remote_size="$(aws "${aws_args[@]}" s3api head-object \
  --bucket "${S3_BUCKET}" --key "${object_prefix}.dump.gpg" \
  --query ContentLength --output text)"
[[ "${remote_size}" == "${size}" ]] \
  || { printf 'Uploaded size mismatch: local=%s remote=%s\n' "${size}" "${remote_size}" >&2; exit 1; }

printf 'Backup uploaded and verified: s3://%s/%s.dump.gpg\n' \
  "${S3_BUCKET}" "${object_prefix}"
