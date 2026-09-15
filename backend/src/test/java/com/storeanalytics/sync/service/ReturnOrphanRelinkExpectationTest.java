package com.storeanalytics.sync.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnPositionPayload;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReturnOrphanRelinkExpectationTest {

    @Test
    void acceptsOnlyExactOriginalDocumentAndPositionFacts() {
        ReturnOrphanRelinkExpectation expectation = expectation();

        expectation.verify(detail(
                "sale-original",
                "sale-position-original",
                "product-1",
                "1.000",
                "50.00",
                "20.00"
        ));

        assertThat(expectation.costAmount()).isEqualByComparingTo("20.00");
    }

    @Test
    void rejectsEverySourceLinkOrMetricMismatch() {
        assertMismatch(detail(
                "sale-other",
                "sale-position-original",
                "product-1",
                "1.000",
                "50.00",
                "20.00"
        ));
        assertMismatch(detail(
                "sale-original",
                "sale-position-other",
                "product-1",
                "1.000",
                "50.00",
                "20.00"
        ));
        assertMismatch(detail(
                "sale-original",
                "sale-position-original",
                "product-other",
                "1.000",
                "50.00",
                "20.00"
        ));
        assertMismatch(detail(
                "sale-original",
                "sale-position-original",
                "product-1",
                "2.000",
                "50.00",
                "20.00"
        ));
        assertMismatch(detail(
                "sale-original",
                "sale-position-original",
                "product-1",
                "1.000",
                "49.99",
                "20.00"
        ));
        assertMismatch(detail(
                "sale-original",
                "sale-position-original",
                "product-1",
                "1.000",
                "50.00",
                "19.99"
        ));
    }

    @Test
    void rejectsDuplicateReturnOrOriginalPositionMappings() {
        ReturnRelinkPositionExpectation first = position(
                "return-position-1",
                "sale-position-1"
        );
        ReturnRelinkPositionExpectation duplicateReturn = position(
                "return-position-1",
                "sale-position-2"
        );
        ReturnRelinkPositionExpectation duplicateOriginal = position(
                "return-position-2",
                "sale-position-1"
        );

        assertThatThrownBy(() -> expectation(List.of(
                first,
                duplicateReturn
        ))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> expectation(List.of(
                first,
                duplicateOriginal
        ))).isInstanceOf(InvalidRequestException.class);
    }

    private void assertMismatch(LiveSkladReturnDetailPayload detail) {
        assertThatThrownBy(() -> expectation().verify(detail))
                .isInstanceOf(InvalidRequestException.class);
    }

    private ReturnOrphanRelinkExpectation expectation() {
        return expectation(List.of(position(
                "return-position-1",
                "sale-position-original"
        )));
    }

    private ReturnOrphanRelinkExpectation expectation(
            List<ReturnRelinkPositionExpectation> positions
    ) {
        return new ReturnOrphanRelinkExpectation(
                "return-1",
                "F000001",
                new BigDecimal("50.00"),
                positions.size(),
                null,
                "sale-original",
                "employee-original",
                positions
        );
    }

    private ReturnRelinkPositionExpectation position(
            String returnPositionId,
            String originalPositionId
    ) {
        return new ReturnRelinkPositionExpectation(
                returnPositionId,
                originalPositionId,
                "product-1",
                new BigDecimal("1.000"),
                new BigDecimal("50.00"),
                new BigDecimal("20.00")
        );
    }

    private LiveSkladReturnDetailPayload detail(
            String originalSaleId,
            String originalPositionId,
            String productId,
            String quantity,
            String netAmount,
            String costAmount
    ) {
        BigDecimal quantityValue = new BigDecimal(quantity);
        BigDecimal netValue = new BigDecimal(netAmount);
        LiveSkladReturnPositionPayload position =
                new LiveSkladReturnPositionPayload(
                        "return-position-1",
                        originalPositionId,
                        productId,
                        "CODE",
                        "SKU",
                        "Fixture product",
                        false,
                        quantityValue,
                        netValue.divide(quantityValue),
                        netValue.divide(quantityValue),
                        new BigDecimal(costAmount)
                );
        return new LiveSkladReturnDetailPayload(
                "return-1",
                "F000001",
                Instant.parse("2026-07-01T12:00:00Z"),
                Instant.parse("2026-07-01T12:01:00Z"),
                "saleReturn",
                "store-1",
                "return-processor",
                originalSaleId,
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                BigDecimal.ZERO.setScale(2),
                List.of(position),
                null
        );
    }
}
