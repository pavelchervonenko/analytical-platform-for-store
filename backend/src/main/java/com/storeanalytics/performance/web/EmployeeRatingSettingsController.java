package com.storeanalytics.performance.web;

import com.storeanalytics.auth.security.AppUserPrincipal;
import com.storeanalytics.performance.service.EmployeeRatingSettingView;
import com.storeanalytics.performance.service.EmployeeRatingSettingsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stores")
public class EmployeeRatingSettingsController {

    private final EmployeeRatingSettingsService settingsService;

    public EmployeeRatingSettingsController(EmployeeRatingSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    @GetMapping("/{storeId}/employee-rating-settings")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    List<EmployeeRatingSettingView> findAll(@PathVariable UUID storeId) {
        return settingsService.findAll(storeId);
    }

    @PutMapping("/{storeId}/employee-rating-settings/{employeeId}")
    @PreAuthorize("@storeAccessAuthorization.canAccess(#storeId, authentication)")
    EmployeeRatingSettingView update(
            @PathVariable UUID storeId,
            @PathVariable UUID employeeId,
            @Valid @RequestBody EmployeeRatingSettingRequest request,
            Authentication authentication
    ) {
        return settingsService.updateParticipation(
                storeId,
                employeeId,
                request.participatesInRanking(),
                request.version(),
                ((AppUserPrincipal) authentication.getPrincipal()).getUserId()
        );
    }
}
