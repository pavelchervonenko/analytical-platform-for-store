package com.storeanalytics.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "app.resources")
public record ResourceLimitsProperties(Http http, Database database) {

    public ResourceLimitsProperties {
        if (http == null || database == null) {
            throw new IllegalArgumentException(
                    "HTTP and database resource limits are required"
            );
        }
    }

    public record Http(
            DataSize maxRequestHeaderSize,
            DataSize maxRequestBodySize,
            DataSize maxFormPostSize,
            DataSize maxSwallowSize,
            Duration connectionTimeout,
            Duration keepAliveTimeout,
            int maxConnections,
            int acceptCount,
            int maxThreads,
            int minSpareThreads,
            int maxQueueCapacity,
            int maxKeepAliveRequests,
            int maxParameterCount
    ) {

        private static final long MAX_HEADER_BYTES = 32L * 1024;
        private static final long MAX_BODY_BYTES = 16L * 1024 * 1024;

        public Http {
            requireDataSize(maxRequestHeaderSize, MAX_HEADER_BYTES,
                    "maxRequestHeaderSize");
            requireDataSize(maxRequestBodySize, MAX_BODY_BYTES,
                    "maxRequestBodySize");
            requireDataSize(maxFormPostSize, MAX_BODY_BYTES,
                    "maxFormPostSize");
            requireDataSize(maxSwallowSize, MAX_BODY_BYTES,
                    "maxSwallowSize");
            requireDuration(connectionTimeout, Duration.ofSeconds(60),
                    "connectionTimeout");
            requireDuration(keepAliveTimeout, Duration.ofMinutes(2),
                    "keepAliveTimeout");
            requireRange(maxConnections, 1, 10_000, "maxConnections");
            requireRange(acceptCount, 1, 1_000, "acceptCount");
            requireRange(maxThreads, 1, 512, "maxThreads");
            requireRange(minSpareThreads, 1, maxThreads, "minSpareThreads");
            requireRange(maxQueueCapacity, 1, 10_000, "maxQueueCapacity");
            requireRange(maxKeepAliveRequests, 1, 10_000,
                    "maxKeepAliveRequests");
            requireRange(maxParameterCount, 1, 10_000, "maxParameterCount");
            if (maxConnections < maxThreads) {
                throw new IllegalArgumentException(
                        "HTTP maxConnections must not be smaller than maxThreads"
                );
            }
            if (maxSwallowSize.compareTo(maxRequestBodySize) < 0
                    || maxSwallowSize.compareTo(maxFormPostSize) < 0) {
                throw new IllegalArgumentException(
                        "HTTP maxSwallowSize must cover request body and form limits"
                );
            }
        }
    }

    public record Database(
            int maximumPoolSize,
            int minimumIdle,
            long connectionTimeoutMs,
            long validationTimeoutMs,
            long idleTimeoutMs,
            long maxLifetimeMs,
            long keepaliveTimeMs,
            long initializationFailTimeoutMs
    ) {

        public Database {
            requireRange(maximumPoolSize, 1, 64, "maximumPoolSize");
            requireRange(minimumIdle, 0, maximumPoolSize, "minimumIdle");
            requireRange(connectionTimeoutMs, 250, 60_000,
                    "connectionTimeoutMs");
            requireRange(validationTimeoutMs, 250, connectionTimeoutMs,
                    "validationTimeoutMs");
            requireRange(idleTimeoutMs, 10_000, 30 * 60_000,
                    "idleTimeoutMs");
            requireRange(maxLifetimeMs, 30_000, 60 * 60_000,
                    "maxLifetimeMs");
            requireRange(keepaliveTimeMs, 30_000, maxLifetimeMs - 1,
                    "keepaliveTimeMs");
            requireRange(initializationFailTimeoutMs, 1, 60_000,
                    "initializationFailTimeoutMs");
        }
    }

    private static void requireDataSize(
            DataSize value,
            long maximumBytes,
            String field
    ) {
        if (value == null || value.toBytes() < 1 || value.toBytes() > maximumBytes) {
            throw new IllegalArgumentException(
                    field + " must be positive and within its safety ceiling"
            );
        }
    }

    private static void requireDuration(
            Duration value,
            Duration maximum,
            String field
    ) {
        if (value == null || value.isZero() || value.isNegative()
                || value.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    field + " must be positive and within its safety ceiling"
            );
        }
    }

    private static void requireRange(
            long value,
            long minimum,
            long maximum,
            String field
    ) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    field + " must be between " + minimum + " and " + maximum
            );
        }
    }
}
