package com.storeanalytics.integration.livesklad.client;

import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladOrderSummaryPayload;
import java.time.Instant;
import java.util.List;

public interface LiveSkladOrderClient {

    List<LiveSkladOrderSummaryPayload> fetchOrders(
            Instant changedPeriodStart,
            Instant changedPeriodEnd
    );

    LiveSkladOrderDetailPayload fetchOrderDetail(String orderExternalId);
}
