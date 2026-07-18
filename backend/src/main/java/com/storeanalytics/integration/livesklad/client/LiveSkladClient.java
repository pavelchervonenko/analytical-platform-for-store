package com.storeanalytics.integration.livesklad.client;

import com.storeanalytics.integration.livesklad.dto.LiveSkladCashItemPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashRegisterPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladCashTransactionPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladEmployeePayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladReturnDetailPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladSaleSummaryPayload;
import com.storeanalytics.integration.livesklad.dto.LiveSkladStorePayload;
import java.time.Instant;
import java.util.List;

public interface LiveSkladClient {

    List<LiveSkladStorePayload> fetchStores();

    List<LiveSkladEmployeePayload> fetchEmployees(String storeExternalId);

    List<LiveSkladSaleSummaryPayload> fetchSales(
            String storeExternalId,
            Instant periodStart,
            Instant periodEnd
    );

    LiveSkladSaleDetailPayload fetchSaleDetail(String saleExternalId);
    List<LiveSkladCashItemPayload> fetchCashItems();

    List<LiveSkladCashRegisterPayload> fetchCashRegisters(String storeExternalId);

    List<LiveSkladCashTransactionPayload> fetchCashTransactions(
            String cashRegisterExternalId,
            String cashItemExternalId,
            Instant periodStart,
            Instant periodEnd
    );

    LiveSkladReturnDetailPayload fetchReturnDetail(String returnExternalId);
}
