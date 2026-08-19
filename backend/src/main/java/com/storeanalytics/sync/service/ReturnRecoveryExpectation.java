package com.storeanalytics.sync.service;

import com.storeanalytics.common.exception.InvalidRequestException;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnPositionPayload;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.util.StringUtils;

record ReturnRecoveryExpectation(
        String externalId,
        String documentNumber,
        BigDecimal netAmount,
        int positionCount
) {

    ReturnRecoveryExpectation {
        if (!StringUtils.hasText(externalId)
                || !StringUtils.hasText(documentNumber)
                || netAmount == null
                || netAmount.signum() <= 0
                || positionCount <= 0) {
            throw new InvalidRequestException(
                    "Return recovery expectation is incomplete"
            );
        }
        externalId = externalId.trim();
        documentNumber = documentNumber.trim();
        try {
            netAmount = netAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new InvalidRequestException(
                    "Expected return amount must have at most two decimals",
                    exception
            );
        }
    }

    void verify(LiveSkladReturnDetailPayload detail) {
        if (!externalId.equals(detail.externalId())
                || !documentNumber.equals(detail.documentNumber())
                || positionCount != detail.positions().size()
                || netAmount.compareTo(netAmount(detail)) != 0) {
            throw new InvalidRequestException(
                    "LiveSklad return does not match recovery expectation"
            );
        }
    }

    private BigDecimal netAmount(LiveSkladReturnDetailPayload detail) {
        BigDecimal result = BigDecimal.ZERO.setScale(2);
        try {
            for (LiveSkladReturnPositionPayload position : detail.positions()) {
                result = result.add(position.unitSoldPrice()
                        .multiply(position.quantity())
                        .setScale(2, RoundingMode.UNNECESSARY));
            }
            return result;
        } catch (ArithmeticException | NullPointerException exception) {
            throw new InvalidRequestException(
                    "LiveSklad return amount cannot be verified",
                    exception
            );
        }
    }
}
