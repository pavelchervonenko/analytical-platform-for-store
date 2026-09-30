package com.storeanalytics.metrics.repository;

/** Category identity shared by store, employee and selected-seller financial projections. */
public interface CategorizedMetricValues extends CategoryMetricValues {
    String categoryCode();
    String categoryName();
    boolean countsAsPhone();
    boolean countsAsDevice();
}
