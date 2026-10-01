package com.storeanalytics.product.service;

import java.util.Objects;
import java.util.Optional;

/** Proposed catalog taxonomy, not a replacement for effective-dated assignments. */
public final class CatalogDeviceCategoryPolicy {

    private CatalogDeviceCategoryPolicy() {
    }

    public enum DeviceType {
        TABLET,
        LAPTOP,
        WATCH
    }

    public static Optional<String> category(DeviceType type, String brandCode) {
        Objects.requireNonNull(type, "type");
        return CatalogCategoryRegistry.standard().deviceCategory(type.name(), brandCode);
    }
}
