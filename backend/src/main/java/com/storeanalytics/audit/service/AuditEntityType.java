package com.storeanalytics.audit.service;

public final class AuditEntityType {

    public static final String PERFORMANCE_PLAN = "PERFORMANCE_PLAN";
    public static final String WORK_SCHEDULE_DAY = "WORK_SCHEDULE_DAY";
    public static final String EMPLOYEE_STORE_ASSIGNMENT = "EMPLOYEE_STORE_ASSIGNMENT";
    public static final String EMPLOYEE_RATING_SNAPSHOT = "EMPLOYEE_RATING_SNAPSHOT";
    public static final String RATING_SCHEME = "RATING_SCHEME";
    public static final String PAYROLL_SCHEME = "PAYROLL_SCHEME";
    public static final String PRODUCT_CATEGORY_ASSIGNMENT = "PRODUCT_CATEGORY_ASSIGNMENT";
    public static final String PRODUCT_PAYROLL_CATEGORY_ASSIGNMENT =
            "PRODUCT_PAYROLL_CATEGORY_ASSIGNMENT";
    public static final String PAYROLL_RUN = "PAYROLL_RUN";
    public static final String PAYROLL_ADJUSTMENT = "PAYROLL_ADJUSTMENT";
    public static final String USER = "USER";
    public static final String SYNC_RUN = "SYNC_RUN";
    public static final String SYNC_JOB = "SYNC_JOB";
    public static final String REPORT_SNAPSHOT = "REPORT_SNAPSHOT";
    public static final String REPORT_BACKFILL = "REPORT_BACKFILL";

    private AuditEntityType() {
    }
}
