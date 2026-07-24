package com.storeanalytics.performance.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.performance.service.EmployeeShiftView;
import com.storeanalytics.performance.service.WorkScheduleService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class WorkScheduleController {

    private final WorkScheduleService scheduleService;

    public WorkScheduleController(WorkScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/{storeId}/work-schedule")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    List<EmployeeShiftView> find(
            @PathVariable UUID storeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate periodEnd
    ) {
        return scheduleService.find(storeId, periodStart, periodEnd);
    }

    @PutMapping("/{storeId}/work-schedule/{workDate}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    List<EmployeeShiftView> replaceDay(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate workDate,
            @Valid @RequestBody WorkScheduleRequest request,
            Authentication authentication
    ) {
        return scheduleService.replaceDay(
                storeId,
                workDate,
                request.toInputs(),
                principal(authentication).getUserId()
        );
    }

    private AppUserPrincipal principal(Authentication authentication) {
        return (AppUserPrincipal) authentication.getPrincipal();
    }
}
