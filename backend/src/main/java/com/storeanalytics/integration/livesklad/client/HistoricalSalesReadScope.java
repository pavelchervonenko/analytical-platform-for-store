package com.storeanalytics.integration.livesklad.client;

import com.storeanalytics.sync.exception.HistoricalSalesReadBudgetException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A thread-confined bound; every real HTTP attempt, including auth/retry, uses the interceptor. */
public final class HistoricalSalesReadScope implements AutoCloseable {
    private static final ThreadLocal<HistoricalSalesReadScope> CURRENT = new ThreadLocal<>();
    private final Context context;
    private final int limit;
    private final Runnable charge;
    private int attempts;

    private HistoricalSalesReadScope(Context context, int limit, Runnable charge) {
        this.context = Objects.requireNonNull(context);
        this.limit = limit;
        this.charge = Objects.requireNonNull(charge);
    }

    public static HistoricalSalesReadScope open(Context context, int limit, Runnable charge) {
        if (CURRENT.get() != null || limit < 1) {
            throw new IllegalStateException("Historical sales read scope cannot be nested or unbounded");
        }
        HistoricalSalesReadScope scope = new HistoricalSalesReadScope(context, limit, charge);
        CURRENT.set(scope);
        return scope;
    }

    public static Context current() {
        HistoricalSalesReadScope scope = CURRENT.get();
        return scope == null ? null : scope.context;
    }

    public static void beforeRequest() {
        HistoricalSalesReadScope scope = CURRENT.get();
        if (scope == null) {
            return;
        }
        if (scope.attempts >= scope.limit) {
            throw new HistoricalSalesReadBudgetException(Duration.ofMinutes(1), false);
        }
        scope.charge.run();
        scope.attempts++;
    }

    @Override
    public void close() {
        if (CURRENT.get() != this) {
            throw new IllegalStateException("Historical sales read scope ownership changed");
        }
        CURRENT.remove();
    }

    public record Context(UUID jobId, String leaseOwner, int attempt, Instant start, Instant end) {
        public Context {
            Objects.requireNonNull(jobId);
            Objects.requireNonNull(leaseOwner);
            Objects.requireNonNull(start);
            Objects.requireNonNull(end);
            if (leaseOwner.isBlank() || attempt < 0 || !end.isAfter(start)) {
                throw new IllegalArgumentException("Invalid historical sales execution context");
            }
        }
    }
}
