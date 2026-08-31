import { fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";
import type {
  AttachRate,
  CategoryKpi,
  EmployeeKpi,
  EmployeeRatingEntry,
  EmployeeRatingResult,
  OverviewMetrics,
  PlanProgress
} from "../api/contracts";
import { AttachRateMatrix, EmployeePerformanceSection, ManagementSummary } from "./OverviewManagementSections";
import { formatOverviewPeriodLabel, SalesStructure } from "./OverviewPage";

const storeId = "30df06fb-71fe-4477-b6b9-bbc712b1ab25";
const annaId = "30df06fb-71fe-4477-b6b9-bbc712b1ab26";
const ilyaId = "30df06fb-71fe-4477-b6b9-bbc712b1ab27";
const hiddenId = "30df06fb-71fe-4477-b6b9-bbc712b1ab28";

function group(groupCode: string, netRevenue: number, netQuantity: number): CategoryKpi["groups"][number] {
  return {
    groupCode,
    groupName: groupCode,
    metrics: {
      netRevenue,
      netQuantity,
      costAmount: 0,
      grossProfit: netRevenue,
      averageGrossProfitPerUnit: netQuantity > 0 ? netRevenue / netQuantity : null,
      marginPercent: 100,
      dataQuality: {
        completeCostData: true,
        includedItemCount: 1,
        missingCostItemCount: 0,
        unexpectedZeroCostItemCount: 0
      }
    }
  };
}

const overviewMetrics: OverviewMetrics = {
  storeId,
  periodStart: "2026-08-01",
  periodEnd: "2026-08-08",
  scope: "SELLERS",
  formulaVersion: "overview-metrics-v1",
  netRevenue: 100000,
  netQuantity: 10,
  costAmount: 60000,
  grossProfit: 40000,
  marginPercent: 40,
  additional: {
    netRevenue: 19500,
    netQuantity: 13,
    sharePercent: 19.5
  },
  accessory: {
    netRevenue: 12000,
    netQuantity: 8,
    sharePercent: 12
  },
  service: {
    netRevenue: 7500,
    netQuantity: 5,
    sharePercent: 7.5
  },
  salesGroups: [
    group("PHONES", 90000, 2),
    group("DEVICES", 100000, 3),
    group("ACCESSORY", 12000, 8),
    group("SERVICE", 7500, 5),
    group("ADDITIONAL_REVENUE", 19500, 13)
  ],
  dataQuality: {
    completeCostData: true,
    includedItemCount: 10,
    unmappedItemCount: 0,
    missingCostItemCount: 0,
    unexpectedZeroCostItemCount: 0,
    periodOpenConsistencyIssueCount: 0,
    storeOpenQualityIssueCount: 0,
    reconciliationPassed: true
  }
};

function employee(
  employeeId: string,
  displayName: string,
  netRevenue: number,
  accessoryRevenue: number,
  serviceRevenue: number,
  participatesInRanking = true,
  attachNumerator = 2,
  attachDenominator = 5
): EmployeeRatingEntry {
  const additionalRevenue = accessoryRevenue + serviceRevenue;
  const attachRate = attachNumerator * 100 / attachDenominator;
  const attachScore = attachRate * 100 / 60;
  return {
    employeeId,
    displayName,
    employeeActive: true,
    assignmentActive: true,
    participatesInRanking,
    ratingEligible: participatesInRanking,
    shiftCount: 4,
    workedHours: 36,
    netRevenue,
    storeRevenueSharePercent: netRevenue / 1000,
    revenuePerShift: netRevenue / 4,
    revenuePerHour: netRevenue / 36,
    accessoryRevenue,
    accessorySharePercent: accessoryRevenue * 100 / netRevenue,
    serviceRevenue,
    serviceSharePercent: serviceRevenue * 100 / netRevenue,
    additionalRevenue,
    additionalSharePercent: additionalRevenue * 100 / netRevenue,
    scores: {
      contributionScore: 100,
      contributionWeightedPoints: 25,
      efficiencyScore: 100,
      efficiencyWeightedPoints: 25,
      structureScore: 100,
      structureWeightedPoints: 25,
      attachScore: 100,
      attachWeightedPoints: 25,
      coveragePercent: 100,
      overallScore: 100
    },
    ranked: participatesInRanking,
    rank: participatesInRanking ? 1 : null,
    attachRates: [{
      metricCode: "CASE_APPLE_IPHONE",
      numeratorCategoryCode: "CASE_APPLE_IPHONE",
      denominatorCode: "IPHONE_ALL",
      numeratorReceiptCount: attachNumerator,
      denominatorReceiptCount: attachDenominator,
      numeratorQuantity: attachNumerator,
      denominatorQuantity: attachDenominator,
      ratePercent: attachRate,
      storeRatePercent: 60,
      includedInScore: true,
      score: attachScore
    }]
  };
}

const rating: EmployeeRatingResult = {
  storeId,
  periodStart: "2026-08-01",
  periodEnd: "2026-08-08",
  formula: {
    version: "rating-v1",
    contributionWeight: 25,
    efficiencyWeight: 25,
    structureWeight: 25,
    attachWeight: 25,
    accessoryStructureWeight: 50,
    serviceStructureWeight: 50,
    minimumAttachDenominator: 1,
    scoreCap: 200,
    minimumCoveragePercent: 75
  },
  plan: {
    complete: true,
    coveragePercent: 100,
    proratedRevenueTarget: 100000,
    accessoryShareTarget: 6.3,
    serviceShareTarget: 4.2,
    additionalShareTarget: 10.5,
    actualStoreRevenue: 100000,
    revenueAchievementPercent: 100
  },
  employees: [
    employee(annaId, "Анна", 60000, 9000, 6000),
    employee(ilyaId, "Илья", 40000, 2000, 1000, true, 3, 4),
    employee(hiddenId, "Скрытый сотрудник", 500000, 100000, 50000, false, 1, 1)
  ],
  history: {
    status: "LIVE",
    snapshotId: null,
    finalizedAt: null,
    finalizedBy: null,
    finalizedByName: null
  }
};

const employeeKpi: EmployeeKpi = {
  storeId,
  periodStart: "2026-08-01",
  periodEnd: "2026-08-08",
  formulaVersion: "store-kpi-v1",
  employees: [
    {
      employeeId: annaId,
      displayName: "Анна",
      employeeActive: true,
      assignedToStore: true,
      assignmentActive: true,
      participatesInRanking: true,
      rankingEligible: true,
      unassigned: false,
      netRevenue: 60000,
      netQuantity: 6,
      costAmount: 35000,
      grossProfit: 25000,
      marginPercent: 41.67,
      dataQuality: {
        completeCostData: true,
        includedItemCount: 6,
        unmappedItemCount: 0,
        missingCostItemCount: 0,
        unexpectedZeroCostItemCount: 0
      }
    },
    {
      employeeId: ilyaId,
      displayName: "Илья",
      employeeActive: true,
      assignedToStore: true,
      assignmentActive: true,
      participatesInRanking: true,
      rankingEligible: true,
      unassigned: false,
      netRevenue: 40000,
      netQuantity: 4,
      costAmount: 25000,
      grossProfit: 15000,
      marginPercent: 37.5,
      dataQuality: {
        completeCostData: true,
        includedItemCount: 4,
        unmappedItemCount: 0,
        missingCostItemCount: 0,
        unexpectedZeroCostItemCount: 0
      }
    }
  ]
};

const attach: AttachRate = {
  storeId,
  periodStart: "2026-08-01",
  periodEnd: "2026-08-08",
  formulaVersion: "attach-rate-v2",
  dataQuality: {
    unmatchedNumeratorItemCount: 0,
    ambiguousWarrantyItemCount: 0,
    unknownDeviceConditionItemCount: 0
  },
  rates: [{
    metricCode: "CASE_APPLE_IPHONE",
    numeratorCategoryCode: "CASE_APPLE_IPHONE",
    denominatorCode: "IPHONE_ALL",
    numeratorReceiptCount: 6,
    denominatorReceiptCount: 10,
    numeratorQuantity: 6,
    denominatorQuantity: 10,
    ratePerHundred: 60
  }, {
    metricCode: "CHARGER_CABLE",
    numeratorCategoryCode: "CHARGER_CABLE",
    denominatorCode: "PHONE_ALL",
    numeratorReceiptCount: 0,
    denominatorReceiptCount: 0,
    numeratorQuantity: 0,
    denominatorQuantity: 0,
    ratePerHundred: null
  }]
};

describe("management overview", () => {
  it("uses the selected analytics period and capitalizes only a month label", () => {
    expect(formatOverviewPeriodLabel("MONTH", "август 2026 г.")).toBe("Август 2026 г.");
    expect(formatOverviewPeriodLabel("WEEK", "10 авг. 2026 г. — 16 авг. 2026 г.")).toBe("10 авг. 2026 г. — 16 авг. 2026 г.");
    expect(formatOverviewPeriodLabel("CUSTOM", "01 июл. 2026 г. — 18 авг. 2026 г.")).toBe("01 июл. 2026 г. — 18 авг. 2026 г.");
  });

  it("shows the customer-facing commercial metrics without average receipt", () => {
    render(
      <ManagementSummary
        metrics={overviewMetrics}
        plan={null}
        scope="SELLERS"
        onScopeChange={() => undefined}
        showMonthlyPlan
      />
    );

    expect(screen.getByText("Чистая выручка")).toBeInTheDocument();
    expect(screen.getByText("Валовая прибыль")).toBeInTheDocument();
    expect(screen.getByText("Допы")).toBeInTheDocument();
    expect(screen.getByText("Аксессуары")).toBeInTheDocument();
    expect(screen.getByText("Услуги")).toBeInTheDocument();
    expect(screen.getByText("19,5%")).toBeInTheDocument();
    expect(screen.queryByText("Средний чек")).not.toBeInTheDocument();
  });

  it("keeps selected-period facts separate from the monthly plan layer", () => {
    const plan = {
      directions: [{
        code: "ADDITIONAL",
        actualAmount: 9500,
        targetAmount: 10500,
        actualSharePercent: 9.5,
        targetSharePercent: 10.5,
        shareGapPercentagePoints: -1,
        criterionCompletionPercent: 90
      }]
    } as PlanProgress;

    render(
      <ManagementSummary
        metrics={overviewMetrics}
        plan={plan}
        scope="SELLERS"
        onScopeChange={() => undefined}
        showMonthlyPlan={false}
      />
    );

    expect(screen.getByText("19,5%")).toBeInTheDocument();
    expect(screen.queryByText("9,5%")).not.toBeInTheDocument();
    expect(screen.queryByText("План 10,5%")).not.toBeInTheDocument();
    expect(screen.queryByText(/к плану/u)).not.toBeInTheDocument();
  });

  it("switches between sellers and the whole store inside the summary block", () => {
    const onScopeChange = vi.fn();
    render(
      <ManagementSummary
        metrics={overviewMetrics}
        plan={null}
        scope="SELLERS"
        onScopeChange={onScopeChange}
        showMonthlyPlan
      />
    );

    expect(screen.getByRole("button", { name: "Только продавцы" }))
      .toHaveAttribute("aria-pressed", "true");
    fireEvent.click(screen.getByRole("button", { name: "Весь магазин" }));
    expect(onScopeChange).toHaveBeenCalledWith("STORE");
  });

  it("renders overlapping sales groups as parent totals and nested composition", () => {
    render(<SalesStructure groups={[
      group("PHONES", 90000, 2),
      group("DEVICES", 150000, 3),
      group("ACCESSORY", 12000, 8),
      group("SERVICE", 7500, 5),
      group("ADDITIONAL_REVENUE", 19500, 13)
    ]} />);

    const devices = screen.getByLabelText("Техника и её состав");
    expect(within(devices).getByText("Техника")).toBeInTheDocument();
    expect(within(devices).getByText("Телефоны")).toBeInTheDocument();
    expect(within(devices).getByText("Итого по категории")).toBeInTheDocument();
    expect(within(devices).getByText("В том числе")).toBeInTheDocument();

    const additional = screen.getByLabelText("Дополнительная выручка и её состав");
    expect(within(additional).getByText("Дополнительная выручка")).toBeInTheDocument();
    expect(within(additional).getByText("Подытог")).toBeInTheDocument();
    expect(within(additional).getByText("Аксессуары")).toBeInTheDocument();
    expect(within(additional).getByText("Услуги")).toBeInTheDocument();
    expect(within(additional).getAllByText("В том числе")).toHaveLength(2);
    expect(screen.queryByText(/Вложенные строки уже входят в итог/u)).not.toBeInTheDocument();
  });

  it("shows only active rating participants and joins their gross profit", () => {
    render(
      <MemoryRouter>
        <EmployeePerformanceSection rating={rating} employeeKpi={employeeKpi} />
      </MemoryRouter>
    );

    const table = screen.getByRole("table");
    expect(within(table).getByText("Анна")).toBeInTheDocument();
    expect(within(table).getByText("Илья")).toBeInTheDocument();
    expect(within(table).queryByText("Скрытый сотрудник")).not.toBeInTheDocument();
    expect(within(table).getByText("25 000 ₽")).toBeInTheDocument();
    expect(screen.getByText("100 000 ₽")).toBeInTheDocument();
    expect(screen.getByText("Лидер по допам")).toBeInTheDocument();
    expect(screen.getByText("Зона внимания")).toBeInTheDocument();
    expect(screen.getByLabelText("Краткие показатели по продавцам")).toHaveTextContent("Анна");
  });

  it("renders the all-store attach benchmark, residual scope and relative colors", () => {
    render(
      <MemoryRouter>
        <AttachRateMatrix attach={attach} rating={rating} storeName="Магазин" />
      </MemoryRouter>
    );

    const map = screen.getByText("Карта допродаж").closest("details");
    expect(map).not.toBeNull();
    expect(map).not.toHaveAttribute("open");
    expect(screen.getByText("14 показателей", { exact: false })).toBeInTheDocument();
    expect(screen.getByText("Все продажи")).toBeInTheDocument();
    expect(screen.getByText("Вне рейтинга")).toBeInTheDocument();
    expect(screen.getByText("и без сотрудника")).toBeInTheDocument();

    const casesRow = screen.getByText("Чехлы Apple / iPhone").closest("tr");
    expect(casesRow).not.toBeNull();
    expect(within(casesRow!).getByText("60%")).toBeInTheDocument();
    expect(within(casesRow!).getByText("6 / 10")).toBeInTheDocument();

    const outsideCell = within(casesRow!).getByTitle(
      /Вне рейтинга \/ без сотрудника: 1 \/ 1 = 100%; остаток/
    );
    expect(outsideCell).toHaveAttribute("data-tone", "context");

    const annaCell = within(casesRow!).getByTitle(
      /Анна: 2 \/ 5 = 40%; 66,7% от среднего по магазину/
    );
    expect(annaCell).toHaveAttribute("data-tone", "below");
    expect(within(annaCell).getByText("2 / 5")).toBeInTheDocument();

    const ilyaCell = within(casesRow!).getByTitle(
      /Илья: 3 \/ 4 = 75%; 125% от среднего по магазину/
    );
    expect(ilyaCell).toHaveAttribute("data-tone", "above");

    const chargerRow = screen.getByText("Зарядные устройства и кабели").closest("tr");
    expect(chargerRow).not.toBeNull();
    expect(within(chargerRow!).getAllByText("нет продаж для расчёта").length).toBeGreaterThan(0);
    expect(screen.getByText("Нет или недостаточно продаж")).toBeInTheDocument();
    expect(screen.getByText("Ниже магазина")).toBeInTheDocument();
    expect(screen.getByText("На уровне магазина")).toBeInTheDocument();
    expect(screen.getByText("Выше магазина")).toBeInTheDocument();
    expect(screen.queryByText("Скрытый сотрудник")).not.toBeInTheDocument();
  });

  it("explains when an employee cannot be compared with the store average", () => {
    const attachWithoutStoreAverage: AttachRate = {
      ...attach,
      rates: [{
        ...attach.rates[0]!,
        numeratorReceiptCount: 0,
        numeratorQuantity: 0,
        ratePerHundred: 0
      }]
    };

    render(
      <MemoryRouter>
        <AttachRateMatrix attach={attachWithoutStoreAverage} rating={rating} storeName="Магазин" />
      </MemoryRouter>
    );

    const casesRow = screen.getByText("Чехлы Apple / iPhone").closest("tr");
    expect(casesRow).not.toBeNull();
    expect(within(casesRow!).getAllByText("нет среднего по магазину")).toHaveLength(2);
    expect(within(casesRow!).getByTitle(/Анна:.*средний показатель по магазину недоступен/u))
      .toHaveAttribute("data-tone", "insufficient");
  });

  it("explains when an employee has too few sales for the rating", () => {
    const ratingWithInsufficientSales: EmployeeRatingResult = {
      ...rating,
      employees: rating.employees.map((entry) => entry.employeeId === annaId
        ? {
          ...entry,
          attachRates: entry.attachRates.map((rate) => ({ ...rate, includedInScore: false }))
        }
        : entry)
    };

    render(
      <MemoryRouter>
        <AttachRateMatrix attach={attach} rating={ratingWithInsufficientSales} storeName="Магазин" />
      </MemoryRouter>
    );

    const casesRow = screen.getByText("Чехлы Apple / iPhone").closest("tr");
    expect(casesRow).not.toBeNull();
    const annaCell = within(casesRow!).getByTitle(/Анна:.*недостаточно продаж для рейтинга/u);
    expect(annaCell).toHaveAttribute("data-tone", "insufficient");
    expect(within(annaCell).getByText("недостаточно продаж")).toBeInTheDocument();
  });
});
