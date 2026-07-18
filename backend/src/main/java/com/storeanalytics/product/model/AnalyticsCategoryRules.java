package com.storeanalytics.product.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

public record AnalyticsCategoryRules(
        AnalyticsCategoryKind categoryKind,
        DeviceFamily deviceFamily,
        boolean countsAsPhone,
        boolean countsAsDevice,
        boolean countsAsAdditionalRevenue,
        AttachDenominatorCode attachDenominatorCode,
        boolean requiresSameDocumentForAttach
) {

    public AnalyticsCategoryRules {
        categoryKind = requireNonNull(categoryKind, "categoryKind");
        deviceFamily = requireNonNull(deviceFamily, "deviceFamily");
        require(!requiresSameDocumentForAttach || attachDenominatorCode != null,
                "attachDenominatorCode is required for same-document attach");
    }
}
