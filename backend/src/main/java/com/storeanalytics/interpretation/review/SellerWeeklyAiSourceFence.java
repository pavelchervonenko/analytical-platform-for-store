package com.storeanalytics.interpretation.review;

import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronization;

/** Short writable fence only: never hold these locks around a provider call or free preparation. */
@Component
public class SellerWeeklyAiSourceFence {
    private final JdbcTemplate jdbc;
    private final WeeklyReviewSnapshotStore snapshots;
    private final SellerWeeklyV3ReadService current;
    private final SellerWeeklyHistoricalReadService historical;

    SellerWeeklyAiSourceFence(JdbcTemplate jdbc, WeeklyReviewSnapshotStore snapshots,
            SellerWeeklyV3ReadService current, SellerWeeklyHistoricalReadService historical) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.current = current;
        this.historical = historical;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public boolean lockAndIsCurrent(UUID snapshotId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
            throw new IllegalStateException("Seller AI fence requires a writable READ_COMMITTED transaction");
        }
        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        jdbc.execute("SET LOCAL statement_timeout = '30s'");
        var saved = snapshots.findV3ById(requireNonNull(snapshotId, "snapshotId"));
        if (saved.isEmpty()) {
            return false;
        }
        var snapshot = saved.orElseThrow();
        var stores = jdbc.queryForList("SELECT id FROM stores WHERE id=? AND is_active FOR UPDATE",
                UUID.class, snapshot.storeId());
        if (stores.isEmpty()) {
            return false;
        }
        jdbc.update("INSERT INTO store_analytics_source_state(store_id,revision) VALUES (?,0) "
                + "ON CONFLICT DO NOTHING", snapshot.storeId());
        jdbc.queryForObject("SELECT revision FROM store_analytics_source_state WHERE store_id=? FOR UPDATE",
                Long.class, snapshot.storeId());
        var locked = new LockedSource(snapshot.storeId());
        // Joined readers deliberately see READ_COMMITTED after the locks, not an earlier RR snapshot.
        var report = snapshot.response();
        var assessment = SellerWeeklyHistoricalMembership.BASIS.equals(report.membership().basis())
                ? historical.assessFenced(locked, report.period().current().start())
                : current.assessFenced(locked);
        return assessment.state() == SellerWeeklyV3ReadResult.State.CURRENT
                && assessment.snapshot().filter(value -> snapshotId.equals(value.id())
                    && (value.response().reportState() == WeeklyReviewResponse.ReportState.READY
                        || value.response().reportState() == WeeklyReviewResponse.ReportState.PARTIAL)).isPresent();
    }

    /** Unforgeable package token: usable only in the transaction that acquired store/source locks. */
    static final class LockedSource {
        private final UUID storeId;
        private final Thread owner = Thread.currentThread();
        private final TransactionSynchronization marker = new TransactionSynchronization() { };

        private LockedSource(UUID storeId) {
            this.storeId = storeId;
            TransactionSynchronizationManager.registerSynchronization(marker);
        }

        UUID storeId() {
            requireCurrent();
            return storeId;
        }

        void requireCurrent() {
            if (owner != Thread.currentThread() || !TransactionSynchronizationManager.isSynchronizationActive()
                    || !TransactionSynchronizationManager.getSynchronizations().contains(marker)
                    || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                    || !Integer.valueOf(java.sql.Connection.TRANSACTION_READ_COMMITTED).equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())) {
                throw new IllegalStateException("Seller AI source token is not owned by this writable transaction");
            }
        }
    }
}
