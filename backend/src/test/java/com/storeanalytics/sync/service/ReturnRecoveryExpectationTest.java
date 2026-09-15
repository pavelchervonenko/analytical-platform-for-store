package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnPositionPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReturnRecoveryExpectationTest {

    @Test
    void acceptsExactDocumentNumberAmountAndPositionCount() {
        ReturnRecoveryExpectation expectation = new ReturnRecoveryExpectation(
                "6a6daeadaa17fa79fe127335",
                "F000381",
                new BigDecimal("15030.00"),
                2
        );

        expectation.verify(detail());
    }

    @Test
    void acceptsZeroNetItemReturnAndRejectsNegativeExpectation() {
        ReturnRecoveryExpectation expectation = new ReturnRecoveryExpectation(
                "69c67dbf35f1a26e5b3bc638",
                "F000175",
                new BigDecimal("0.00"),
                1
        );

        expectation.verify(zeroNetDetail());

        assertThatThrownBy(() -> new ReturnRecoveryExpectation(
                "69c67dbf35f1a26e5b3bc638",
                "F000175",
                new BigDecimal("-0.01"),
                1
        )).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void rejectsAnyMismatchBeforeSynchronization() {
        assertThatThrownBy(() -> new ReturnRecoveryExpectation(
                "6a6daeadaa17fa79fe127335",
                "F000999",
                new BigDecimal("15030.00"),
                2
        ).verify(detail())).isInstanceOf(InvalidRequestException.class);

        assertThatThrownBy(() -> new ReturnRecoveryExpectation(
                "6a6daeadaa17fa79fe127335",
                "F000381",
                new BigDecimal("15029.99"),
                2
        ).verify(detail())).isInstanceOf(InvalidRequestException.class);

        assertThatThrownBy(() -> new ReturnRecoveryExpectation(
                "6a6daeadaa17fa79fe127335",
                "F000381",
                new BigDecimal("15030.00"),
                1
        ).verify(detail())).isInstanceOf(InvalidRequestException.class);
    }

    private LiveSkladReturnDetailPayload detail() {
        return new LiveSkladReturnDetailPayload(
                "6a6daeadaa17fa79fe127335",
                "F000381",
                Instant.parse("2026-08-06T12:00:00Z"),
                Instant.parse("2026-08-06T12:01:00Z"),
                "saleReturn",
                "store-1",
                "employee-1",
                "sale-1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(
                        position("position-1", "12330.00"),
                        position("position-2", "2700.00")
                ),
                null
        );
    }

    private LiveSkladReturnDetailPayload zeroNetDetail() {
        return new LiveSkladReturnDetailPayload(
                "69c67dbf35f1a26e5b3bc638",
                "F000175",
                Instant.parse("2026-03-24T12:00:00Z"),
                Instant.parse("2026-03-24T12:01:00Z"),
                "saleReturn",
                "store-1",
                "employee-1",
                "sale-1",
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                List.of(position("zero-net-position", "0.00")),
                null
        );
    }

    private LiveSkladReturnPositionPayload position(
            String externalId,
            String amount
    ) {
        BigDecimal price = new BigDecimal(amount);
        return new LiveSkladReturnPositionPayload(
                externalId,
                "original-" + externalId,
                "product-" + externalId,
                externalId,
                externalId,
                externalId,
                false,
                BigDecimal.ONE,
                price,
                price,
                BigDecimal.ZERO
        );
    }
}
