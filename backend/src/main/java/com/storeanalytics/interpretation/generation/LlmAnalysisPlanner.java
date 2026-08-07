package com.storeanalytics.interpretation.generation;

import com.storeanalytics.common.config.ApplicationRole;
import com.storeanalytics.common.config.ConditionalOnApplicationRole;
import com.storeanalytics.interpretation.config.LlmAnalysisPlanningSchedulingConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnApplicationRole({ApplicationRole.WORKER, ApplicationRole.COMBINED})
@ConditionalOnProperty(
        prefix = "app.interpretation.generation-planner",
        name = "enabled",
        havingValue = "true"
)
public class LlmAnalysisPlanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            LlmAnalysisPlanner.class
    );

    private final LlmAnalysisPlanningService planningService;

    public LlmAnalysisPlanner(LlmAnalysisPlanningService planningService) {
        this.planningService = planningService;
    }

    @Scheduled(
            fixedDelayString = "${app.interpretation.generation-planner.scan-delay:1m}",
            scheduler = LlmAnalysisPlanningSchedulingConfiguration
                    .LLM_ANALYSIS_PLANNING_SCHEDULER
    )
    public void reconcile() {
        try {
            LlmAnalysisPlanningResult result = planningService.plan();
            if (result.jobsCreated() > 0) {
                LOGGER.info("LLM analysis planning completed; result={}", result);
            }
        } catch (RuntimeException exception) {
            LOGGER.error("LLM analysis planning iteration failed", exception);
        }
    }
}
