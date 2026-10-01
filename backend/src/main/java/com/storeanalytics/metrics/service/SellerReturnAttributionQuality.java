package com.storeanalytics.metrics.service;

import static com.storeanalytics.common.validation.ModelValidation.require;

/** Store-wide uncertainty that may affect seller returns; never included in seller totals. */
public record SellerReturnAttributionQuality(
        long orphanReturnDocumentCount,
        long unattributedOriginalReturnDocumentCount
) {
    public static final SellerReturnAttributionQuality COMPLETE = new SellerReturnAttributionQuality(0, 0);

    public SellerReturnAttributionQuality {
        require(orphanReturnDocumentCount >= 0, "orphan return count must not be negative");
        require(unattributedOriginalReturnDocumentCount >= 0,
                "unattributed original return count must not be negative");
    }

    public boolean complete() {
        return orphanReturnDocumentCount == 0 && unattributedOriginalReturnDocumentCount == 0;
    }
}
