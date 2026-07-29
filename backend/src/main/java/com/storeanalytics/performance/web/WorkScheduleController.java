package com.storeanalytics.performance.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.performance.service.EmployeeShiftView;
import com.storeanalytics.performance.service.WorkScheduleDayView;
import com.storeanalytics.performance.service.WorkScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

    @Operation(responses = @ApiResponse(
            responseCode = "200",
            useReturnTypeSchema = true,
            headers = @Header(
                    name = HttpHeaders.ETAG,
                    description = "Strong entity tag for the complete work-schedule day",
                    schema = @Schema(type = "string")
            )
    ))
    @GetMapping("/{storeId}/work-schedule/{workDate}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    ResponseEntity<WorkScheduleDayView> getDay(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate workDate
    ) {
        WorkScheduleDayView day = scheduleService.getDay(storeId, workDate);
        return ResponseEntity.ok()
                .eTag(WorkScheduleService.etag(
                        day.storeId(), day.workDate(), day.revision()
                ))
                .body(day);
    }

    @Operation(responses = @ApiResponse(
            responseCode = "200",
            useReturnTypeSchema = true,
            headers = @Header(
                    name = HttpHeaders.ETAG,
                    description = "Strong entity tag for the complete work-schedule day",
                    schema = @Schema(type = "string")
            )
    ))
    @PutMapping("/{storeId}/work-schedule/{workDate}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    ResponseEntity<WorkScheduleDayView> replaceDay(
            @PathVariable UUID storeId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate workDate,
            @Valid @RequestBody WorkScheduleRequest request,
            @Parameter(
                    required = true,
                    description = "Strong ETag returned by GET for this complete day"
            )
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
            String ifMatch,
            Authentication authentication
    ) {
        WorkScheduleDayView day = scheduleService.replaceDay(
                storeId,
                workDate,
                request.toInputs(),
                ifMatch,
                principal(authentication).getUserId()
        );
        return ResponseEntity.ok()
                .eTag(WorkScheduleService.etag(
                        day.storeId(), day.workDate(), day.revision()
                ))
                .body(day);
    }

    private AppUserPrincipal principal(Authentication authentication) {
        return (AppUserPrincipal) authentication.getPrincipal();
    }
}
