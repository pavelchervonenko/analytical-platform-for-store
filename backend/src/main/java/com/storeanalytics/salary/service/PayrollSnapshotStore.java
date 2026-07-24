package com.storeanalytics.salary.service;

import com.storeanalytics.salary.model.PayrollAdjustment;
import com.storeanalytics.salary.model.PayrollDailyAllocation;
import com.storeanalytics.salary.model.PayrollDailyPool;
import com.storeanalytics.salary.model.PayrollEvent;
import com.storeanalytics.salary.model.PayrollRun;
import com.storeanalytics.salary.model.PayrollStatement;
import com.storeanalytics.salary.repository.PayrollAdjustmentRepository;
import com.storeanalytics.salary.repository.PayrollDailyAllocationRepository;
import com.storeanalytics.salary.repository.PayrollDailyPoolRepository;
import com.storeanalytics.salary.repository.PayrollEventRepository;
import com.storeanalytics.salary.repository.PayrollStatementRepository;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class PayrollSnapshotStore {

    private final PayrollDailyPoolRepository poolRepository;
    private final PayrollDailyAllocationRepository allocationRepository;
    private final PayrollAdjustmentRepository adjustmentRepository;
    private final PayrollStatementRepository statementRepository;
    private final PayrollEventRepository eventRepository;

    PayrollSnapshotStore(
            PayrollDailyPoolRepository poolRepository,
            PayrollDailyAllocationRepository allocationRepository,
            PayrollAdjustmentRepository adjustmentRepository,
            PayrollStatementRepository statementRepository,
            PayrollEventRepository eventRepository
    ) {
        this.poolRepository = poolRepository;
        this.allocationRepository = allocationRepository;
        this.adjustmentRepository = adjustmentRepository;
        this.statementRepository = statementRepository;
        this.eventRepository = eventRepository;
    }

    void replaceCalculatedSnapshot(
            PayrollRun run,
            List<PayrollDailyPool> pools,
            List<PayrollDailyAllocation> allocations,
            List<PayrollStatement> statements
    ) {
        statementRepository.deleteAllByPayrollRunId(run.getId());
        allocationRepository.deleteAllByPayrollRunId(run.getId());
        poolRepository.deleteAllByPayrollRunId(run.getId());
        poolRepository.flush();
        poolRepository.saveAll(pools);
        poolRepository.flush();
        allocationRepository.saveAll(allocations);
        statementRepository.saveAll(statements);
        statementRepository.flush();
    }

    List<PayrollAdjustment> activeAdjustments(PayrollRun run) {
        return adjustmentRepository.findAllByPayrollRunIdAndActiveTrue(run.getId());
    }

    void saveAdjustments(List<PayrollAdjustment> adjustments) {
        adjustmentRepository.saveAllAndFlush(adjustments);
    }

    void appendEvent(PayrollEvent event) {
        eventRepository.save(event);
    }
}
