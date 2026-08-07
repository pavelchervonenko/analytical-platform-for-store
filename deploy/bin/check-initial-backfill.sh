#!/usr/bin/env bash

set -Eeuo pipefail
set +x

readonly STATE_FILE="${STATE_FILE:-/var/lib/store-analytics/release-state/initial-backfill-job.json}"
readonly PASSWORD_FILE="${POSTGRES_MIGRATOR_PASSWORD_FILE:-/etc/store-analytics/secrets/postgres-migrator-password}"

job_id="$(jq -r '.id' "${STATE_FILE}")"
[[ "${job_id}" =~ ^[0-9a-f-]{36}$ ]] || { printf 'Invalid backfill job id\n' >&2; exit 1; }

export PGPASSWORD="$(< "${PASSWORD_FILE}")"
psql \
  "host=managed-631415-8744455 hostaddr=10.20.0.20 port=5432 dbname=store_analytics user=store_migrator sslmode=verify-full sslrootcert=/etc/store-analytics/pki/postgresql-ca.crt application_name=deployment-backfill-monitor" \
  -X -v ON_ERROR_STOP=1 -P pager=off -c \
  "SELECT id, status, phase, cursor_start, current_window_end,
          completed_steps, attempt_count, total_retries, next_attempt_at, lease_until, error_summary, updated_at
     FROM sync_jobs
    WHERE id = '${job_id}'"
