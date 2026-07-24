package com.storeanalytics.metrics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record AnnualReportMonthId(
        @Column(name = "annual_report_id") UUID annualReportId,
        @Column(name = "month_number") short monthNumber
) implements Serializable {
}
