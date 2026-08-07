package com.storeanalytics.notification.web;

import com.storeanalytics.notification.operations.TelegramDeliveryOperationsQuery;
import com.storeanalytics.notification.operations.TelegramDeliveryOperationsView;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/admin/notifications/telegram/deliveries")
public class TelegramDeliveryOperationsController {

    private final TelegramDeliveryOperationsQuery query;

    public TelegramDeliveryOperationsController(TelegramDeliveryOperationsQuery query) {
        this.query = query;
    }

    @GetMapping
    TelegramDeliveryOperationsView get(
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int incidentLimit
    ) {
        return query.get(incidentLimit, Instant.now());
    }
}
