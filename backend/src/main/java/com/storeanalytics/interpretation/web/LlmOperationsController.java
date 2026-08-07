package com.storeanalytics.interpretation.web;

import com.storeanalytics.interpretation.operations.LlmOperationsQuery;
import com.storeanalytics.interpretation.operations.LlmOperationsView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/admin/llm/operations")
public class LlmOperationsController {

    private final LlmOperationsQuery query;

    public LlmOperationsController(LlmOperationsQuery query) {
        this.query = query;
    }

    @GetMapping
    LlmOperationsView get(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int incidentLimit
    ) {
        return query.get(incidentLimit);
    }
}
