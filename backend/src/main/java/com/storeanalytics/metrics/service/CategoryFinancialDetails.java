package com.storeanalytics.metrics.service;

import com.storeanalytics.metrics.repository.CategorizedMetricValues;
import java.util.List;

/** Detail rows are views of the same signed facts, never additional amounts to add to totals. */
final class CategoryFinancialDetails {
    static final String DEVICE_PREFIX = "DEVICE_CATEGORY:";

    private CategoryFinancialDetails() { }

    static <T extends CategorizedMetricValues> List<T> devices(List<T> rows) {
        // Count facts rather than positive balances: a return-only period or a fully returned sale
        // must remain visible. Empty inactive catalog seeds do not create empty widgets.
        return rows.stream().filter(row -> row.countsAsDevice() && !row.countsAsPhone()
                && row.includedItemCount() > 0).toList();
    }

    static String name(CategorizedMetricValues row) {
        return switch (row.categoryCode()) {
            case "IPAD_MAC" -> "iPad и Mac — вид устройства не уточнён";
            case "PODS_WATCH_OTHER_DEVICE" -> "Наушники, часы и другая техника — вид не уточнён";
            default -> row.categoryName();
        };
    }
}
