package com.storeanalytics.salary.service;

import com.storeanalytics.salary.repository.PayrollAdjustmentRepository;
import com.storeanalytics.salary.repository.PayrollDailyAllocationRepository;
import com.storeanalytics.salary.repository.PayrollDailyPoolRepository;
import com.storeanalytics.salary.repository.PayrollEventRepository;
import com.storeanalytics.salary.repository.PayrollRunRepository;
import com.storeanalytics.salary.repository.PayrollStatementRepository;
import org.springframework.stereotype.Component;

@Component
class PayrollManagementRepositories {

    private final PayrollRunRepository runRepository;
    private final PayrollDailyPoolRepository poolRepository;
    private final PayrollDailyAllocationRepository allocationRepository;
    private final PayrollAdjustmentRepository adjustmentRepository;
    private final PayrollStatementRepository statementRepository;
    private final PayrollEventRepository eventRepository;

    PayrollManagementRepositories(
            PayrollRunRepository runRepository,
            PayrollDailyPoolRepository poolRepository,
            PayrollDailyAllocationRepository allocationRepository,
            PayrollAdjustmentRepository adjustmentRepository,
            PayrollStatementRepository statementRepository,
            PayrollEventRepository eventRepository
    ) {
        this.runRepository = runRepository;
        this.poolRepository = poolRepository;
        this.allocationRepository = allocationRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.statementRepository = statementRepository;
        this.eventRepository = eventRepository;
    }

    PayrollRunRepository runs() {
        return runRepository;
    }

    PayrollDailyPoolRepository pools() {
        return poolRepository;
    }

    PayrollDailyAllocationRepository allocations() {
        return allocationRepository;
    }

    PayrollAdjustmentRepository adjustments() {
        return adjustmentRepository;
    }

    PayrollStatementRepository statements() {
        return statementRepository;
    }

    PayrollEventRepository events() {
        return eventRepository;
    }
}
