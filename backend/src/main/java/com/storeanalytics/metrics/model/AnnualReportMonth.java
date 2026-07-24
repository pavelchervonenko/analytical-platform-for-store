package com.storeanalytics.metrics.model;

import static com.storeanalytics.common.validation.ModelValidation.require;
import static com.storeanalytics.common.validation.ModelValidation.requireNonNull;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.YearMonth;

@Entity
@Table(name = "annual_report_months")
public class AnnualReportMonth {

    @EmbeddedId
    private AnnualReportMonthId id;

    @MapsId("annualReportId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "annual_report_id", nullable = false, updatable = false)
    private ReportSnapshot annualReport;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "monthly_report_id", nullable = false, updatable = false)
    private ReportSnapshot monthlyReport;

    protected AnnualReportMonth() {
    }

    public AnnualReportMonth(
            ReportSnapshot annualReport,
            ReportSnapshot monthlyReport,
            int monthNumber
    ) {
        require(monthNumber >= 1 && monthNumber <= 12,
                "monthNumber must be between 1 and 12");
        this.annualReport = requireNonNull(annualReport, "annualReport");
        this.monthlyReport = requireNonNull(monthlyReport, "monthlyReport");
        require(annualReport.getReportType() == ReportType.ANNUAL,
                "annualReport must have ANNUAL type");
        require(monthlyReport.getReportType() == ReportType.MONTHLY,
                "monthlyReport must have MONTHLY type");
        require(annualReport.getStatus() == ReportStatus.FINALIZED
                        && monthlyReport.getStatus() == ReportStatus.FINALIZED,
                "annual report provenance requires finalized reports");
        require(annualReport.getStore().getId().equals(monthlyReport.getStore().getId()),
                "annual and monthly reports must belong to one store");
        YearMonth sourceMonth = YearMonth.from(monthlyReport.getPeriodStart());
        require(sourceMonth.getMonthValue() == monthNumber
                        && monthlyReport.getPeriodStart().equals(sourceMonth.atDay(1))
                        && monthlyReport.getPeriodEnd().equals(sourceMonth.atEndOfMonth()),
                "monthly report period and month number must match");
        require(!monthlyReport.getPeriodStart().isBefore(annualReport.getPeriodStart())
                        && !monthlyReport.getPeriodEnd().isAfter(annualReport.getPeriodEnd()),
                "monthly report must be inside annual report period");
        this.id = new AnnualReportMonthId(annualReport.getId(), (short) monthNumber);
    }

    public ReportSnapshot getAnnualReport() {
        return annualReport;
    }

    public ReportSnapshot getMonthlyReport() {
        return monthlyReport;
    }

    public int getMonthNumber() {
        return id.monthNumber();
    }
}
