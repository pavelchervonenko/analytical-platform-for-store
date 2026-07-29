CREATE INDEX ix_report_snapshots_archive_page
    ON report_snapshots (store_id, period_end DESC, revision DESC, id DESC)
    WHERE status = 'FINALIZED';

CREATE INDEX ix_app_users_admin_page
    ON app_users (lower(display_name), id);

COMMENT ON INDEX ix_report_snapshots_archive_page IS
    'Supports bounded report archive summaries without reading JSON payload columns.';
