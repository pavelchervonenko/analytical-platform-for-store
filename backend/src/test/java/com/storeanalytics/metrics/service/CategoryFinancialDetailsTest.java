package com.storeanalytics.metrics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.storeanalytics.metrics.repository.CategoryKpiAggregate;
import com.storeanalytics.metrics.repository.EmployeeCategoryKpiAggregate;
import com.storeanalytics.product.service.CatalogCategoryRegistry;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryFinancialDetailsTest {
    private static final UUID STORE = UUID.randomUUID();
    private static final StoreKpiPeriod PERIOD = new StoreKpiPeriod(
            LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));

    @Test
    void everyDeviceCategoryIncludingLegacyIsRepresentedOnceAndChildrenReconcile() {
        var rows = CatalogCategoryRegistry.standard().definitions().stream()
                .filter(definition -> !definition.code().equals("EXCLUDE"))
                .map(definition -> row(definition.code(), "12.34", "1.250", 1, 0)).toList();
        var result = CategoryKpiService.project(STORE, PERIOD, rows);
        var children = result.groups().stream().filter(group -> group.groupCode().equals("PHONES")
                || group.groupCode().startsWith("DEVICE_CATEGORY:")).toList();
        var parent = result.groups().stream().filter(group -> group.groupCode().equals("DEVICES"))
                .findFirst().orElseThrow().metrics();
        assertThat(children.stream().map(group -> group.metrics().netRevenue())
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(parent.netRevenue());
        assertThat(children.stream().map(group -> group.metrics().netQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(parent.netQuantity());
        assertThat(children.stream().map(group -> group.metrics().costAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo(parent.costAmount());
        assertThat(children.stream().mapToLong(group -> group.metrics().dataQuality().includedItemCount()).sum())
                .isEqualTo(parent.dataQuality().includedItemCount());
        assertThat(children).extracting(CategoryKpiGroup::groupCode).doesNotHaveDuplicates();
        assertThat(children).extracting(CategoryKpiGroup::groupCode)
                .contains("DEVICE_CATEGORY:TABLET_APPLE", "DEVICE_CATEGORY:LAPTOP_OTHER",
                        "DEVICE_CATEGORY:WATCH_SAMSUNG", "DEVICE_CATEGORY:SPEAKERS",
                        "DEVICE_CATEGORY:IPAD_MAC", "DEVICE_CATEGORY:PODS_WATCH_OTHER_DEVICE")
                .doesNotContain("DEVICE_CATEGORY:ACCESSORY_IPAD", "DEVICE_CATEGORY:PACKAGING");
    }

    @Test
    void keepsReturnOnlyAndNetZeroFactsButOmitsEmptySeedsAndDoesNotInventCost() {
        var result = CategoryKpiService.project(STORE, PERIOD, List.of(
                row("SPEAKERS", "-25.00", "-1.000", 1, 0),
                row("TABLET_APPLE", "0.00", "0.000", 2, 1),
                row("LAPTOP_OTHER", "0.00", "0.000", 0, 0)));
        var details = result.groups().stream()
                .filter(group -> group.groupCode().startsWith("DEVICE_CATEGORY:")).toList();
        assertThat(details).extracting(CategoryKpiGroup::groupCode)
                .containsExactly("DEVICE_CATEGORY:SPEAKERS", "DEVICE_CATEGORY:TABLET_APPLE");
        assertThat(details.getFirst().metrics().netQuantity()).isEqualByComparingTo("-1.000");
        assertThat(details.getLast().metrics().grossProfit()).isNull();
        assertThat(details.getLast().metrics().netRevenue()).isEqualByComparingTo("0.00");
    }

    @Test
    void employeeAndStoreUseIdenticalDetailAmountsAndKeepUnassignedEmployee() {
        var raw = row("IPAD_MAC", "75.00", "0.750", 1, 0);
        var store = CategoryKpiService.project(STORE, PERIOD, List.of(raw));
        var employeeRow = new EmployeeCategoryKpiAggregate(null, null, false, false, false, false, true,
                raw.categoryCode(), raw.categoryName(), raw.categoryKind(), raw.deviceFamily(),
                raw.categoryActive(), raw.countsAsPhone(), raw.countsAsDevice(), raw.countsAsAdditionalRevenue(),
                raw.netRevenue(), raw.netQuantity(), raw.costAmount(), raw.includedItemCount(),
                raw.missingCostItemCount(), raw.unexpectedZeroCostItemCount());
        var employee = EmployeeCategoryKpiService.project(STORE, PERIOD, List.of(employeeRow)).employees().getFirst();
        var detail = employee.groups().stream().filter(group -> group.groupCode().equals("DEVICE_CATEGORY:IPAD_MAC"))
                .findFirst().orElseThrow();
        var storeDetail = store.groups().stream().filter(group -> group.groupCode().equals(detail.groupCode()))
                .findFirst().orElseThrow();
        assertThat(detail.groupName()).contains("не уточнён");
        assertThat(detail.metrics().netRevenue()).isEqualByComparingTo(storeDetail.metrics().netRevenue());
        assertThat(detail.metrics().grossProfit()).isEqualByComparingTo(storeDetail.metrics().grossProfit());
        assertThat(detail.metrics().revenueSharePercent()).isEqualByComparingTo("100.00");
        assertThat(employee.unassigned()).isTrue();
        assertThat(employee.rankingEligible()).isFalse();
    }

    private static CategoryKpiAggregate row(String code, String revenue, String quantity, long count, long missing) {
        var definition = CatalogCategoryRegistry.standard().require(code);
        return new CategoryKpiAggregate(code, definition.name(), definition.categoryKind(), definition.deviceFamily(),
                false, definition.countsAsPhone(), definition.countsAsDevice(), definition.countsAsAdditionalRevenue(),
                new BigDecimal(revenue), new BigDecimal(quantity), BigDecimal.ZERO, count, missing, 0);
    }
}
