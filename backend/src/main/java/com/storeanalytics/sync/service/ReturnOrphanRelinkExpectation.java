package com.storeanalytics.sync.service;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnPositionPayload;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

record ReturnOrphanRelinkExpectation(
        String externalId,
        String documentNumber,
        BigDecimal netAmount,
        int positionCount,
        String currentEmployeeExternalId,
        String originalSaleExternalId,
        String originalEmployeeExternalId,
        List<ReturnRelinkPositionExpectation> positions
) implements ReturnTargetExpectation {

    ReturnOrphanRelinkExpectation {
        new ReturnRecoveryExpectation(
                externalId,
                documentNumber,
                netAmount,
                positionCount
        );
        if (!StringUtils.hasText(originalSaleExternalId)
                || !StringUtils.hasText(originalEmployeeExternalId)
                || positions == null
                || positions.size() != positionCount) {
            throw new InvalidRequestException(
                    "Existing orphan relink expectation is incomplete"
            );
        }
        externalId = externalId.trim();
        documentNumber = documentNumber.trim();
        netAmount = scale(netAmount, 2, "expected return amount");
        currentEmployeeExternalId = StringUtils.hasText(
                currentEmployeeExternalId
        ) ? currentEmployeeExternalId.trim() : null;
        originalSaleExternalId = originalSaleExternalId.trim();
        originalEmployeeExternalId = originalEmployeeExternalId.trim();

        Set<String> returnPositionIds = new HashSet<>();
        Set<String> originalPositionIds = new HashSet<>();
        positions = positions.stream()
                .map(ReturnOrphanRelinkExpectation::validatedPosition)
                .peek(position -> {
                    if (!returnPositionIds.add(
                            position.returnPositionExternalId())) {
                        throw new InvalidRequestException(
                                "Return relink expectation contains duplicate return position IDs"
                        );
                    }
                    if (!originalPositionIds.add(
                            position.originalSalePositionExternalId())) {
                        throw new InvalidRequestException(
                                "Return relink expectation reuses an original sale position"
                        );
                    }
                })
                .toList();
    }

    @Override
    public void verify(LiveSkladReturnDetailPayload detail) {
        new ReturnRecoveryExpectation(
                externalId,
                documentNumber,
                netAmount,
                positionCount
        ).verify(detail);
        if (!originalSaleExternalId.equals(detail.originalSaleExternalId())) {
            throw mismatch();
        }
        Map<String, LiveSkladReturnPositionPayload> sourcePositions =
                new HashMap<>();
        for (LiveSkladReturnPositionPayload source : detail.positions()) {
            if (sourcePositions.put(source.externalId(), source) != null) {
                throw mismatch();
            }
        }
        for (ReturnRelinkPositionExpectation expected : positions) {
            LiveSkladReturnPositionPayload source = sourcePositions.get(
                    expected.returnPositionExternalId()
            );
            if (source == null
                    || !expected.originalSalePositionExternalId().equals(
                    source.originalSalePositionExternalId())
                    || !expected.productExternalId().equals(
                    source.productExternalId())
                    || expected.quantity().compareTo(source.quantity()) != 0
                    || expected.netAmount().compareTo(netAmount(source)) != 0
                    || !sameAmount(
                    expected.costAmount(), source.costAmount())) {
                throw mismatch();
            }
        }
    }

    BigDecimal costAmount() {
        BigDecimal total = BigDecimal.ZERO.setScale(2);
        for (ReturnRelinkPositionExpectation position : positions) {
            if (position.costAmount() == null) {
                return null;
            }
            total = total.add(position.costAmount());
        }
        return total;
    }

    private static ReturnRelinkPositionExpectation validatedPosition(
            ReturnRelinkPositionExpectation value
    ) {
        if (value == null
                || !StringUtils.hasText(value.returnPositionExternalId())
                || !StringUtils.hasText(value.originalSalePositionExternalId())
                || !StringUtils.hasText(value.productExternalId())
                || value.quantity() == null
                || value.quantity().signum() <= 0
                || value.netAmount() == null
                || value.netAmount().signum() < 0
                || value.costAmount() != null
                && value.costAmount().signum() < 0) {
            throw new InvalidRequestException(
                    "Return relink position expectation is incomplete"
            );
        }
        return new ReturnRelinkPositionExpectation(
                value.returnPositionExternalId().trim(),
                value.originalSalePositionExternalId().trim(),
                value.productExternalId().trim(),
                scale(value.quantity(), 3, "expected return quantity"),
                scale(value.netAmount(), 2, "expected return net amount"),
                value.costAmount() == null ? null : scale(
                        value.costAmount(), 2, "expected return cost amount"
                )
        );
    }

    private static BigDecimal netAmount(
            LiveSkladReturnPositionPayload source
    ) {
        try {
            return source.unitSoldPrice().multiply(source.quantity())
                    .setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException | NullPointerException exception) {
            throw new InvalidRequestException(
                    "LiveSklad return position amount cannot be verified",
                    exception
            );
        }
    }

    private static BigDecimal scale(
            BigDecimal value,
            int scale,
            String label
    ) {
        try {
            return value.setScale(scale, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new InvalidRequestException(
                    label + " has unsupported precision",
                    exception
            );
        }
    }

    private static boolean sameAmount(
            BigDecimal first,
            BigDecimal second
    ) {
        return first == null ? second == null
                : second != null && first.compareTo(second) == 0;
    }

    private static InvalidRequestException mismatch() {
        return new InvalidRequestException(
                "LiveSklad return does not match orphan relink expectation"
        );
    }
}
