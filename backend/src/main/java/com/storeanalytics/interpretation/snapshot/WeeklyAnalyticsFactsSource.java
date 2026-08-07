package com.storeanalytics.interpretation.snapshot;

public interface WeeklyAnalyticsFactsSource {

    WeeklyAnalyticsFacts load(WeeklyAnalyticsFactsQuery query);
}
