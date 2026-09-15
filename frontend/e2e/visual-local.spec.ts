import { mkdir, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { expect, test, type Page, type Route } from "@playwright/test";
import { makeWeeklyReview } from "../src/test/weeklyReviewFixture";
import { visualWeeklyReview } from "./weekly-review-visual-fixtures";

const email = process.env.VISUAL_EMAIL?.trim() || process.env.E2E_ADMIN_EMAIL?.trim();
const password = process.env.VISUAL_PASSWORD || process.env.E2E_ADMIN_PASSWORD;
const configuredRoutes = process.env.VISUAL_ROUTES?.trim() || "/insights";
const useFixtureApi = process.env.VISUAL_USE_FIXTURES === "true";
const useLiveWeeklyReview = process.env.VISUAL_USE_LIVE_WEEKLY_REVIEW === "true";
const fixtureRole = process.env.VISUAL_FIXTURE_ROLE === "ADMIN" ? "ADMIN" : "MANAGER";
const configuredFixtureFeatures = process.env.VISUAL_FIXTURE_FEATURES?.trim();
const fixtureFeatures = configuredFixtureFeatures === "NONE"
  ? []
  : (configuredFixtureFeatures?.split(",").map((value) => value.trim()).filter(Boolean)
      ?? ["PLAN", "SHIFTS", "PAYROLL"]);
const fixtureHasPlan = fixtureRole === "ADMIN" || fixtureFeatures.includes("PLAN");
const templateEmails = new Set(["manager@example.com", "replace-with-local-email"]);
const visualStoreId = "10000000-0000-4000-8000-000000000001";


async function installFixtureApi(page: Page) {
  const json = async (route: Route, body: unknown) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body)
    });
  };

  await page.route("**/api/auth/me", async (route) => json(route, {
    id: "20000000-0000-4000-8000-000000000001",
    email: fixtureRole === "ADMIN" ? "visual-admin@example.com" : "visual-manager@example.com",
    displayName: fixtureRole === "ADMIN" ? "Администратор" : "Руководитель магазина",
    role: fixtureRole,
    passwordChangeRequired: false,
    allStores: fixtureRole === "ADMIN",
    storeIds: fixtureRole === "ADMIN" ? [] : [visualStoreId],
    features: fixtureFeatures
  }));
  await page.route("**/api/admin/users**", async (route) => json(route, {
    items: [{
      id: "20000000-0000-4000-8000-000000000001",
      email: "visual-admin@example.com",
      displayName: "Администратор",
      role: "ADMIN",
      active: true,
      passwordChangeRequired: false,
      allStores: true,
      storeIds: [],
      features: ["PLAN", "SHIFTS", "PAYROLL"],
      lastLoginAt: "2026-09-10T06:30:00Z",
      version: 4
    }, {
      id: "20000000-0000-4000-8000-000000000002",
      email: "analytics-manager@example.com",
      displayName: "Руководитель без зарплаты",
      role: "MANAGER",
      active: true,
      passwordChangeRequired: false,
      allStores: false,
      storeIds: [visualStoreId],
      features: ["PLAN", "SHIFTS"],
      lastLoginAt: null,
      version: 7
    }],
    page: 0,
    size: 20,
    totalElements: 2,
    totalPages: 1,
    hasNext: false,
    hasPrevious: false
  }));
  await page.route("**/api/auth/sessions", async (route) => json(route, {
    sessions: [{
      sessionReference: "visual-current-session",
      lastSeenAt: "2026-09-08T06:30:00+03:00",
      current: true
    }]
  }));
  await page.route("**/api/notifications/channels/telegram", async (route) => json(route, {
    state: "NOT_LINKED",
    subscriptionId: null,
    version: null,
    linkExpiresAt: null,
    pendingSince: null,
    confirmedAt: null,
    blockedAt: null,
    destination: null,
    deliverySettings: null,
    allowedActions: ["LINK"],
    publicBotUrl: "https://t.me/store_analytics_visual_bot"
  }));
  await page.route("**/api/stores", async (route) => json(route, [{
    id: visualStoreId,
    name: "МАГАЗИН",
    address: null,
    timezone: "Europe/Moscow",
    businessDayStart: "06:00:00",
    opensAt: "09:00:00",
    closesAt: "21:00:00",
    active: true
  }]));
  await page.route("**/api/stores/*/data-status", async (route) => json(route, {
    storeId: visualStoreId,
    status: "CURRENT",
    expectedThroughDate: "2026-09-09",
    dataThroughDate: "2026-09-09",
    salesDataThroughDate: "2026-09-09",
    returnsDataThroughDate: "2026-09-09",
    lagDays: 0,
    updating: false,
    lastCompletedSyncAt: "2026-09-10T04:30:00Z",
    synchronization: {
      active: false,
      id: null,
      type: null,
      status: null,
      phase: null,
      startedAt: null,
      nextAttemptAt: null
    },
    openQualityIssueCount: 28,
    lastError: null,
    lastErrorAt: null,
    checkedAt: "2026-09-10T04:35:00Z"
  }));
  const periodStart = "2026-08-01";
  const periodEnd = "2026-08-31";
  const metricQuality = {
    completeCostData: true,
    includedItemCount: 24,
    missingCostItemCount: 0,
    unexpectedZeroCostItemCount: 0
  };
  const categoryMetric = (netRevenue: number, netQuantity: number, costAmount: number) => ({
    netRevenue,
    netQuantity,
    costAmount,
    grossProfit: netRevenue - costAmount,
    averageGrossProfitPerUnit: netQuantity > 0
      ? (netRevenue - costAmount) / netQuantity
      : null,
    marginPercent: netRevenue > 0 ? (netRevenue - costAmount) * 100 / netRevenue : null,
    dataQuality: metricQuality
  });
  await page.route("**/api/stores/*/overview-metrics?*", async (route) => {
    const scope = new URL(route.request().url()).searchParams.get("scope") === "STORE"
      ? "STORE"
      : "SELLERS";
    const factor = scope === "STORE" ? 1.12 : 1;
    const netRevenue = 54_800_000 * factor;
    await json(route, {
      storeId: visualStoreId,
      periodStart,
      periodEnd,
      scope,
      formulaVersion: "overview-metrics-v1",
      netRevenue,
      netQuantity: Math.round(2_240 * factor),
      costAmount: 45_700_000 * factor,
      grossProfit: 9_100_000 * factor,
      marginPercent: 16.61,
      additional: {
        netRevenue: 6_028_000 * factor,
        netQuantity: Math.round(1_640 * factor),
        sharePercent: 11
      },
      accessory: {
        netRevenue: 3_562_000 * factor,
        netQuantity: Math.round(1_020 * factor),
        sharePercent: 6.5
      },
      service: {
        netRevenue: 2_466_000 * factor,
        netQuantity: Math.round(620 * factor),
        sharePercent: 4.5
      },
      salesGroups: [
        { groupCode: "DEVICES", groupName: "Техника", metrics: categoryMetric(48_772_000 * factor, 600 * factor, 40_910_000 * factor) },
        { groupCode: "PHONES", groupName: "Телефоны", metrics: categoryMetric(43_150_000 * factor, 510 * factor, 36_410_000 * factor) },
        { groupCode: "ADDITIONAL_REVENUE", groupName: "Дополнительная выручка", metrics: categoryMetric(6_028_000 * factor, 1_640 * factor, 4_790_000 * factor) },
        { groupCode: "ACCESSORY", groupName: "Аксессуары", metrics: categoryMetric(3_562_000 * factor, 1_020 * factor, 2_930_000 * factor) },
        { groupCode: "SERVICE", groupName: "Услуги", metrics: categoryMetric(2_466_000 * factor, 620 * factor, 1_860_000 * factor) }
      ],
      dataQuality: {
        ...metricQuality,
        unmappedItemCount: 0,
        periodOpenConsistencyIssueCount: 0,
        storeOpenQualityIssueCount: 0,
        reconciliationPassed: true
      }
    });
  });
  await page.route("**/api/stores/*/kpi/categories?*", async (route) => json(route, {
    storeId: visualStoreId,
    periodStart,
    periodEnd,
    formulaVersion: "category-kpi-v1",
    groups: [
      { groupCode: "DEVICES", groupName: "Техника", metrics: categoryMetric(48_772_000, 600, 40_910_000) },
      { groupCode: "PHONES", groupName: "Телефоны", metrics: categoryMetric(43_150_000, 510, 36_410_000) },
      { groupCode: "ADDITIONAL_REVENUE", groupName: "Дополнительная выручка", metrics: categoryMetric(6_028_000, 1_640, 4_790_000) },
      { groupCode: "ACCESSORY", groupName: "Аксессуары", metrics: categoryMetric(3_562_000, 1_020, 2_930_000) },
      { groupCode: "SERVICE", groupName: "Услуги", metrics: categoryMetric(2_466_000, 620, 1_860_000) }
    ],
    categories: []
  }));
  await page.route("**/api/stores/*/performance-plans/2026-09", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: { ETag: '"visual-performance-plan-2026-09-1"' },
      body: JSON.stringify({
        id: "10000000-0000-4000-8000-000000000010",
        storeId: visualStoreId,
        planMonth: "2026-09",
        revenueTarget: 75_000_000,
        accessoryShareTarget: 5.7,
        serviceShareTarget: 4.8,
        additionalShareTarget: 10.5,
        updatedBy: "20000000-0000-4000-8000-000000000001",
        version: 1,
        updatedAt: "2026-09-01T06:00:00Z"
      })
    });
  });
  await page.route("**/api/stores/*/performance-plans/*/progress?*", async (route) => {
    const requestSearchParams = new URL(route.request().url()).searchParams;
    const planQuality = new URL(page.url()).searchParams.get("planQuality");
    expect(requestSearchParams.get("asOf")).toBe("2026-09-09");
    const scope = requestSearchParams.get("scope") === "STORE"
      ? "STORE"
      : "SELLERS";
    const factor = scope === "STORE" ? 1.12 : 1;
    const totalDays = 30;
    const elapsedDays = 9;
    const remainingDays = totalDays - elapsedDays;
    const revenue = 16_748_724 * factor;
    const completedRevenue = [
      1_500_000,
      2_100_000,
      1_750_000,
      2_300_000,
      1_900_000,
      2_000_000,
      1_084_420,
      2_656_200,
      1_458_104
    ];
    const accessoryShares = [5.6, 5.1, 5.8, 5.3, 5.5, 5.2, 5, 5.7, 5.4];
    const serviceShares = [4.6, 4.3, 5, 4.5, 4.7, 4.2, 4.1, 4.8, 4.4];
    const actualAmount = (shares: number[]) => completedRevenue.reduce(
      (total, dayRevenue, index) => total + dayRevenue * shares[index]! / 100,
      0
    ) * factor;
    const accessoryActual = actualAmount(accessoryShares);
    const serviceActual = actualAmount(serviceShares);
    const additionalActual = accessoryActual + serviceActual;
    const direction = (
      code: string,
      actualAmount: number,
      targetAmount: number,
      actualSharePercent: number | null,
      targetSharePercent: number | null
    ) => {
      const criterionCompletionPercent = targetSharePercent == null
        ? actualAmount * 100 / targetAmount
        : (actualSharePercent ?? 0) * 100 / targetSharePercent;
      const expectedAmountToDate = targetSharePercent == null
        ? targetAmount * elapsedDays / totalDays
        : targetAmount;
      const projectedAmount = actualAmount * totalDays / elapsedDays;
      return {
        code,
        criterionType: targetSharePercent == null ? "AMOUNT" : "SHARE",
        actualAmount,
        targetAmount,
        amountCompletionPercent: actualAmount * 100 / targetAmount,
        currentDailyPace: actualAmount / elapsedDays,
        expectedAmountToDate,
        paceGapAmount: actualAmount - expectedAmountToDate,
        projectedAmount,
        projectedAmountCompletionPercent: targetSharePercent == null
          ? projectedAmount * 100 / targetAmount
          : criterionCompletionPercent,
        remainingAmount: Math.max(0, targetAmount - actualAmount),
        requiredPerRemainingDay: Math.max(0, targetAmount - actualAmount) / remainingDays,
        actualSharePercent,
        targetSharePercent,
        shareGapPercentagePoints: targetSharePercent == null
          ? null
          : (actualSharePercent ?? 0) - targetSharePercent,
        criterionCompletionPercent,
        achieved: criterionCompletionPercent >= 100,
        status: criterionCompletionPercent >= 100 ? "ACHIEVED" : "AT_RISK"
      };
    };
    const directions = [
      direction("REVENUE", revenue, 75_000_000, null, null),
      direction(
        "ACCESSORY",
        accessoryActual,
        revenue * 0.057,
        accessoryActual * 100 / revenue,
        5.7
      ),
      direction(
        "SERVICE",
        serviceActual,
        revenue * 0.048,
        serviceActual * 100 / revenue,
        4.8
      ),
      direction(
        "ADDITIONAL",
        additionalActual,
        revenue * 0.105,
        additionalActual * 100 / revenue,
        10.5
      )
    ];
    let accessoryGap = 0;
    let serviceGap = 0;
    const projectedRevenueBasis = revenue / elapsedDays;
    const projectedRevenue = revenue + projectedRevenueBasis * remainingDays;
    const accessoryRequired = Math.max(projectedRevenue * 0.057 - accessoryActual, 0);
    const serviceRequired = Math.max(projectedRevenue * 0.048 - serviceActual, 0);
    const accessoryDailyBase = Math.floor(accessoryRequired * 100 / remainingDays) / 100;
    const serviceDailyBase = Math.floor(serviceRequired * 100 / remainingDays) / 100;
    const dailyTargets = Array.from({ length: totalDays }, (_, index) => {
      const day = index + 1;
      const date = `2026-09-${String(day).padStart(2, "0")}`;
      if (day > elapsedDays) {
        const futureIndex = day - elapsedDays;
        const accessoryTargetAmount = futureIndex === remainingDays
          ? accessoryRequired - accessoryDailyBase * (remainingDays - 1)
          : accessoryDailyBase;
        const serviceTargetAmount = futureIndex === remainingDays
          ? serviceRequired - serviceDailyBase * (remainingDays - 1)
          : serviceDailyBase;
        return {
          date,
          completed: false,
          revenueBasisAmount: projectedRevenueBasis,
          revenueBasisProjected: true,
          accessory: {
            actualAmount: null,
            actualSharePercent: null,
            targetAmount: accessoryTargetAmount,
            targetSharePercent: accessoryTargetAmount * 100 / projectedRevenueBasis,
            cumulativeGapAmount: null
          },
          service: {
            actualAmount: null,
            actualSharePercent: null,
            targetAmount: serviceTargetAmount,
            targetSharePercent: serviceTargetAmount * 100 / projectedRevenueBasis,
            cumulativeGapAmount: null
          }
        };
      }

      const revenueBasisAmount = completedRevenue[index]! * factor;
      const accessoryTargetAmount = revenueBasisAmount * 0.057;
      const accessoryActualAmount = revenueBasisAmount * accessoryShares[index]! / 100;
      const serviceTargetAmount = revenueBasisAmount * 0.048;
      const serviceActualAmount = revenueBasisAmount * serviceShares[index]! / 100;
      accessoryGap += accessoryActualAmount - accessoryTargetAmount;
      serviceGap += serviceActualAmount - serviceTargetAmount;

      return {
        date,
        completed: true,
        revenueBasisAmount,
        revenueBasisProjected: false,
        accessory: {
          actualAmount: accessoryActualAmount,
          actualSharePercent: accessoryShares[index]!,
          targetAmount: accessoryTargetAmount,
          targetSharePercent: 5.7,
          cumulativeGapAmount: accessoryGap
        },
        service: {
          actualAmount: serviceActualAmount,
          actualSharePercent: serviceShares[index]!,
          targetAmount: serviceTargetAmount,
          targetSharePercent: 4.8,
          cumulativeGapAmount: serviceGap
        }
      };
    });
    await json(route, {
      storeId: visualStoreId,
      periodStart: "2026-09-01",
      periodEnd: "2026-09-30",
      asOfDate: "2026-09-09",
      totalDays,
      elapsedDays,
      remainingDays,
      formulaVersion: "store-plan-progress-v3",
      plan: {
        id: "10000000-0000-4000-8000-000000000010",
        storeId: visualStoreId,
        planMonth: "2026-09",
        revenueTarget: 75_000_000,
        accessoryShareTarget: 5.7,
        serviceShareTarget: 4.8,
        additionalShareTarget: 10.5,
        updatedBy: "20000000-0000-4000-8000-000000000001",
        version: 1,
        updatedAt: "2026-09-01T06:00:00Z"
      },
      dataQuality: {
        freshnessStatus: "CURRENT",
        dataThroughDate: "2026-09-09",
        completeThroughAsOf: planQuality !== "coverage",
        classificationComplete: planQuality !== "classification",
        unmappedItemCount: 0,
        openQualityIssueCount: 0
      },
      achievedDirectionCount: directions.filter((item) => item.achieved).length,
      allDirectionsAchieved: directions.every((item) => item.achieved),
      focusDirections: [],
      directions,
      dailyTargets,
      calculatedAt: "2026-09-09T06:00:00Z"
    });
  });
  await page.route("**/api/stores/*/period-quality/*?*", async (route) => json(route, {
    storeId: visualStoreId,
    periodMonth: "2026-09",
    periodStart: "2026-09-01",
    periodEnd: "2026-09-30",
    asOfDate: "2026-09-09",
    status: "OK",
    readyForDecisions: true,
    areas: ["SOURCE_DATA", "STORE_PLAN", "EMPLOYEE_RATING", "PAYROLL"].map((code) => ({
      code,
      status: "OK",
      ready: true,
      issueCount: 0,
      errorCount: 0,
      warningCount: 0,
      infoCount: 0
    })),
    sourceData: {
      freshnessStatus: "CURRENT",
      dataThroughDate: "2026-09-09",
      completeThroughAsOf: true,
      classificationComplete: true,
      costDataComplete: true,
      includedItemCount: 128,
      unmappedItemCount: 0,
      missingCostItemCount: 0,
      unexpectedZeroCostItemCount: 0,
      openQualityIssueCount: 0
    },
    storePlan: {
      planPresent: true,
      inputDataCompleteThroughAsOf: true,
      classificationComplete: true,
      unmappedItemCount: 0,
      openQualityIssueCount: 0,
      formulaVersion: "store-plan-progress-v3"
    },
    employeeRating: {
      planCoverageComplete: true,
      employeeCount: 8,
      eligibleEmployeeCount: 7,
      employeeWithShiftCount: 7,
      rankedEmployeeCount: 7,
      salesWithoutShiftCount: 0,
      insufficientScoreCoverageCount: 0,
      historyStatus: "LIVE",
      formulaVersion: "rating-v1"
    },
    payroll: {
      readinessStatus: "READY",
      canCalculate: true,
      canApprove: true,
      planPresent: true,
      schemePresent: true,
      salesDayCount: 9,
      scheduledDayCount: 9,
      unmappedItemCount: 0,
      missingCostItemCount: 0,
      daysWithoutShift: 0,
      calculated: false,
      runStatus: null,
      freshness: null
    },
    issues: [],
    checkedAt: "2026-09-09T06:00:00Z"
  }));
  const qualityStoreSummary = {
    storeId: visualStoreId,
    storeName: "МАГАЗИН",
    status: "OK",
    freshnessStatus: "CURRENT",
    dataThroughDate: "2026-09-09",
    lagDays: 0,
    openIssueCount: 0,
    errorCount: 0,
    warningCount: 0,
    infoCount: 0,
    checkedAt: "2026-09-09T06:00:00Z"
  };
  await page.route("**/api/data-quality/summary", async (route) => json(route, {
    checkedAt: "2026-09-09T06:00:00Z",
    storeCount: 1,
    okStoreCount: 1,
    warningStoreCount: 0,
    errorStoreCount: 0,
    openIssueCount: 0,
    stores: [qualityStoreSummary]
  }));
  await page.route("**/api/stores/*/data-quality", async (route) => json(route, {
    summary: qualityStoreSummary,
    dataStatus: {
      storeId: visualStoreId,
      status: "CURRENT",
      expectedThroughDate: "2026-09-09",
      dataThroughDate: "2026-09-09",
      salesDataThroughDate: "2026-09-09",
      returnsDataThroughDate: "2026-09-09",
      lagDays: 0,
      lastCompletedSyncAt: "2026-09-09T05:30:00Z",
      synchronization: {
        active: false,
        id: null,
        type: null,
        status: null,
        phase: null,
        startedAt: null,
        nextAttemptAt: null
      },
      openQualityIssueCount: 0,
      lastError: null,
      lastErrorAt: null,
      checkedAt: "2026-09-09T06:00:00Z"
    },
    issues: []
  }));
  await page.route("**/api/stores/*/employee-ratings?*", async (route) => json(route, {
    storeId: visualStoreId,
    periodStart,
    periodEnd,
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
      proratedRevenueTarget: 47_900_000,
      accessoryShareTarget: 6.3,
      serviceShareTarget: 4.2,
      additionalShareTarget: 10.5,
      actualStoreRevenue: 54_800_000,
      revenueAchievementPercent: 114.4
    },
    employees: [],
    history: {
      status: "LIVE",
      snapshotId: null,
      finalizedAt: null,
      finalizedBy: null,
      finalizedByName: null
    }
  }));
  await page.route("**/api/stores/*/employees?*", async (route) => json(route, {
    storeId: visualStoreId,
    periodStart,
    periodEnd,
    previousPeriodStart: "2026-07-05",
    previousPeriodEnd: "2026-07-31",
    employees: []
  }));
  await page.route("**/api/stores/*/kpi/employees?*", async (route) => json(route, {
    storeId: visualStoreId,
    periodStart,
    periodEnd,
    formulaVersion: "employee-kpi-v1",
    employees: []
  }));
  await page.route("**/api/stores/*/kpi/attach-rates?*", async (route) => json(route, {
    storeId: visualStoreId,
    periodStart,
    periodEnd,
    formulaVersion: "attach-rate-v1",
    dataQuality: {
      unmatchedNumeratorItemCount: 0,
      ambiguousWarrantyItemCount: 0,
      unknownDeviceConditionItemCount: 0
    },
    rates: []
  }));
  await page.route("**/api/stores/*/reports?*", async (route) => json(route, {
    items: [],
    page: 0,
    size: 20,
    totalElements: 0,
    totalPages: 0,
    hasNext: false,
    hasPrevious: false
  }));
  await page.route("**/api/stores/*/reports/years", async (route) => json(route, []));
  await page.route("**/api/stores/*/weekly-reviews/current", async (route) => {
    const scenario = new URL(page.url()).searchParams.get("reviewScenario");
    await json(route, visualWeeklyReview(scenario));
  });
  const sellerOneId = "30000000-0000-4000-8000-000000000001";
  const sellerTwoId = "30000000-0000-4000-8000-000000000002";
  const excludedEmployeeId = "30000000-0000-4000-8000-000000000003";
  const shiftDate = "2026-09-01";
  const visualShifts = [
    {
      id: "40000000-0000-4000-8000-000000000001",
      employeeId: sellerOneId,
      employeeName: "Продавец Анна",
      workDate: shiftDate,
      workedHours: 11,
      active: true,
      version: 1
    },
    {
      id: "40000000-0000-4000-8000-000000000002",
      employeeId: sellerTwoId,
      employeeName: "Продавец Борис",
      workDate: shiftDate,
      workedHours: 8,
      active: true,
      version: 1
    }
  ];
  await page.route("**/api/stores/*/employee-rating-settings", async (route) => {
    await json(route, [
      {
        employeeId: sellerOneId,
        displayName: "Продавец Анна",
        employeeActive: true,
        assignmentActive: true,
        participatesInRanking: true,
        version: 1,
        updatedAt: "2026-09-01T03:15:00Z"
      },
      {
        employeeId: sellerTwoId,
        displayName: "Продавец Борис",
        employeeActive: true,
        assignmentActive: true,
        participatesInRanking: true,
        version: 1,
        updatedAt: "2026-09-01T03:15:00Z"
      },
      {
        employeeId: excludedEmployeeId,
        displayName: "Администратор магазина",
        employeeActive: true,
        assignmentActive: true,
        participatesInRanking: false,
        version: 1,
        updatedAt: "2026-09-01T03:15:00Z"
      }
    ]);
  });
  await page.route("**/api/stores/*/work-schedule?*", async (route) => {
    await json(route, visualShifts);
  });
  await page.route("**/api/stores/*/work-schedule/*", async (route) => {
    const date = new URL(route.request().url()).pathname.split("/").at(-1);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      headers: { ETag: `"visual-work-schedule-${date}-1"` },
      body: JSON.stringify({
        storeId: visualStoreId,
        workDate: date,
        revision: 1,
        shifts: date === shiftDate ? visualShifts : []
      })
    });
  });
  await page.route(/\/api\/stores\/[^/]+\/payroll\/[^/?]+(?:\/[^?]+)?(?:\?.*)?$/u, async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    if (pathname.endsWith("/readiness")) {
      await json(route, {
        storeId: visualStoreId,
        periodMonth: "2026-09",
        status: "BLOCKED",
        canCalculate: false,
        canApprove: false,
        planPresent: false,
        schemePresent: true,
        planResult: null,
        salesDayCount: 8,
        scheduledDayCount: 7,
        unmappedItemCount: 0,
        missingCostItemCount: 0,
        daysWithoutShift: 1,
        unmappedProducts: [],
        missingCosts: [],
        shiftIssues: [{ workDate: "2026-09-08", fundAmount: 12_400 }]
      });
      return;
    }
    if (!/\/payroll\/\d{4}-\d{2}$/u.test(pathname)) {
      throw new Error(`Unexpected payroll fixture request: ${pathname}`);
    }
    await route.fulfill({
      status: 404,
      contentType: "application/json",
      body: JSON.stringify({
        timestamp: "2026-09-10T06:00:00Z",
        status: 404,
        code: "PAYROLL_NOT_FOUND",
        message: "Payroll run not found",
        path: `/api/stores/${visualStoreId}/payroll/2026-09`,
        correlationId: "visual-payroll-not-found"
      })
    });
  });
}

function parseRoutes(value: string): string[] {
  const routes = [...new Set(value.split(",").map((route) => route.trim()).filter(Boolean))];
  if (routes.length === 0) throw new Error("VISUAL_ROUTES must include at least one local route");

  for (const route of routes) {
    if (!route.startsWith("/") || route.startsWith("//") || route.includes("://")) {
      throw new Error(`Visual route must be an application path, received: ${route}`);
    }
  }

  return routes;
}

function screenshotName(route: string): string {
  const url = new URL(route, "http://local.test");
  const pathPart = url.pathname === "/" ? "home" : url.pathname.slice(1);
  const queryPart = url.searchParams.size > 0 ? `-${url.searchParams.toString()}` : "";
  return `${pathPart}${queryPart}`.replace(/[^a-zA-Z0-9_-]+/gu, "-").replace(/^-+|-+$/gu, "") || "page";
}

async function login(page: Page) {
  if (!email || !password || templateEmails.has(email) || password === "replace-with-local-password") {
    throw new Error(
      "Replace the template values with real local credentials in .env.visual.local"
    );
  }

  await page.goto("/login");
  await page.getByLabel("Электронная почта", { exact: true }).fill(email);
  await page.getByLabel("Пароль", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Войти" }).click();
  await expect(page).toHaveURL(/\/overview(?:\?|$)/u);
  await expect(page.locator("#main-content")).toBeVisible();
}

async function waitForStablePage(page: Page) {
  await expect(page.locator("#main-content")).toBeVisible();
  await expect(page.locator(".page-loader")).toHaveCount(0);
  await page.waitForLoadState("networkidle");
  await page.evaluate(async () => {
    await document.fonts.ready;
    await new Promise<void>((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())));
  });
}

async function expectNoHorizontalOverflow(page: Page) {
  const dimensions = await page.evaluate(() => ({
    viewportWidth: window.innerWidth,
    documentWidth: document.documentElement.scrollWidth
  }));

  expect(
    dimensions.documentWidth,
    `Page is wider than the viewport: ${JSON.stringify(dimensions)}`
  ).toBeLessThanOrEqual(dimensions.viewportWidth + 1);
}
async function captureVisualArtifacts(page: Page, directory: string, name: string) {
  await mkdir(directory, { recursive: true });
  await Promise.all(
    Array.from({ length: 8 }, (_, index) => rm(
      resolve(directory, `${name}-part-${String(index + 1).padStart(2, "0")}.png`),
      { force: true }
    ))
  );
  await page.screenshot({
    path: resolve(directory, `${name}.png`),
    fullPage: true,
    animations: "disabled",
    style: [
      ".skip-link { display: none !important; }",
      ".topbar { position: relative !important; }",
      ".sidebar { position: absolute !important; }"
    ].join(" ")
  });

  const pageSize = await page.evaluate(() => ({
    documentHeight: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight),
    viewportHeight: window.innerHeight
  }));
  if (pageSize.documentHeight <= pageSize.viewportHeight * 2) return;

  const segmentCount = Math.min(
    Math.ceil(pageSize.documentHeight / pageSize.viewportHeight),
    8
  );
  const maxScroll = pageSize.documentHeight - pageSize.viewportHeight;
  const positions = new Set(
    Array.from(
      { length: segmentCount },
      (_, index) => Math.round(maxScroll * index / (segmentCount - 1))
    )
  );

  for (const [index, scrollY] of [...positions].entries()) {
    await page.evaluate((top) => window.scrollTo({ top, behavior: "instant" }), scrollY);
    await page.evaluate(() => new Promise<void>((done) => requestAnimationFrame(() => done())));
    await page.screenshot({
      path: resolve(directory, `${name}-part-${String(index + 1).padStart(2, "0")}.png`),
      fullPage: false,
      animations: "disabled"
    });
  }
}

const visualRoutes = parseRoutes(configuredRoutes);

test.describe("local frontend visual review", () => {
  test.beforeEach(async ({ page }) => {
    if (useFixtureApi) {
      await page.clock.setFixedTime(new Date("2026-09-10T09:00:00+03:00"));
      await installFixtureApi(page);
    } else {
      await login(page);
    }
  });

  for (const route of visualRoutes) {
    test(`capture ${route}`, async ({ page }, testInfo) => {
      const runtimeFailures: string[] = [];
      page.on("pageerror", (error) => runtimeFailures.push(`pageerror: ${error.message}`));
      page.on("console", (message) => {
        if (message.type() === "error" && !message.text().startsWith("Failed to load resource:")) {
          runtimeFailures.push(`console: ${message.text()}`);
        }
      });
      page.on("response", (response) => {
        if (response.status() >= 500) {
          runtimeFailures.push(`${response.status()} ${new URL(response.url()).pathname}`);
        }
      });

      if (
        !useFixtureApi
        && !useLiveWeeklyReview
        && new URL(route, "http://local.test").pathname === "/insights"
      ) {
        await page.route("**/api/stores/*/weekly-reviews/current", async (requestRoute) => {
          await requestRoute.fulfill({
            status: 200,
            contentType: "application/json",
            body: JSON.stringify(makeWeeklyReview())
          });
        });
      }

      await page.goto(route, { waitUntil: "domcontentloaded" });
      await waitForStablePage(page);

      let capturePeriodDialog = false;
      let weeklyReviewBlocked = false;
      const routeUrl = new URL(route, "http://local.test");
      if (routeUrl.pathname === "/insights") {
        const scenario = routeUrl.searchParams.get("reviewScenario") ?? "ready-dense";
        if (useLiveWeeklyReview) {
          await expect.poll(async () => (
            await page.getByRole("heading", { name: "Для разбора не хватает данных" }).count()
            + await page.getByRole("heading", { name: "Результаты недели", exact: true }).count()
          )).toBe(1);
          weeklyReviewBlocked = await page.getByRole("heading", {
            name: "Для разбора не хватает данных"
          }).count() === 1;
        } else {
          weeklyReviewBlocked = scenario === "blocked";
        }
        if (weeklyReviewBlocked) {
          await expect(page.getByRole("heading", { name: "Для разбора не хватает данных" }))
            .toBeVisible();
          await expect(page.getByRole("heading", { name: "Результаты недели", exact: true }))
            .toHaveCount(0);
        } else {
          await expect(page.getByRole("heading", { name: "Результаты недели", exact: true }))
            .toBeVisible();
          await expect(page.locator(".weekly-review-metric")).toHaveCount(4);
          await expect(page.locator(".weekly-review-structure-section")).not.toHaveAttribute("open", "");
          if (!useLiveWeeklyReview && scenario === "ready-calm") {
            await expect(page.getByText(/Дополнительная проверка не требуется/u)).toBeVisible();
          } else {
            await expect(page.getByText("Что проверить на этой неделе", { exact: true })).toBeVisible();
          }
          if (!useLiveWeeklyReview && scenario === "partial") {
            await expect(page.getByText("Часть выводов ограничена", { exact: true })).toBeVisible();
          }
          if (!useLiveWeeklyReview && scenario === "ready-missing-shifts") {
            await expect(page.getByText("Часть выводов ограничена", { exact: true })).toHaveCount(0);
            await expect(page.getByText(
              "Часть смен не заполнена — оценка по часам недоступна",
              { exact: true }
            )).toBeVisible();
          }
          if (!useLiveWeeklyReview && scenario === "ready-dense") {
            await expect(page.locator(".weekly-review-factor")).toHaveCount(2);
            await expect(page.locator(".weekly-review-exception")).toHaveCount(3);
            await expect(page.getByText("Ковзель Ангелина Александровна", { exact: true }))
              .toBeVisible();
            await expect(page.locator(".weekly-review-exception").first())
              .toContainText("Ориентир: не ниже 28 ₽/ч");
            await expect(page.locator(".weekly-review-secondary-actions > summary"))
              .toContainText("Ещё проверки 2");
            const mobile = testInfo.project.name === "mobile-chromium";
            await expect(page.locator(".weekly-review-factor:visible"))
              .toHaveCount(mobile ? 1 : 2);
            await expect(page.locator(".weekly-review-exception:visible"))
              .toHaveCount(mobile ? 1 : 3);
            const changesToggle = page.getByRole("button", { name: "Ещё 1 изменение" });
            const employeesToggle = page.getByRole("button", { name: "Ещё 2 сотрудника" });
            if (mobile) {
              await expect(changesToggle).toBeVisible();
              await expect(employeesToggle).toBeVisible();
            } else {
              await expect(changesToggle).toBeHidden();
              await expect(employeesToggle).toBeHidden();
            }
            if (testInfo.project.name === "desktop-chromium") {
              const summaryBox = await page.locator(".weekly-review-summary").boundingBox();
              const actionBox = await page.locator(".weekly-review-primary-action").boundingBox();
              expect(summaryBox).not.toBeNull();
              expect(actionBox).not.toBeNull();
              expect(Math.abs(summaryBox!.height - actionBox!.height)).toBeLessThanOrEqual(1);
            }
          }
        }
      }
      if (routeUrl.pathname === "/payroll") {
        await expect(page.locator(".payroll-skeleton")).toHaveCount(0);
        await expect(page.locator(".payroll-overview")).toBeVisible();
      }
      if (routeUrl.pathname === "/quality") {
        await expect(page.locator('.quality-page[aria-busy="true"]')).toHaveCount(0);
        await expect(page.getByRole("heading", { name: "Качество данных", exact: true }))
          .toBeVisible();
      }

      const periodSelector = page.getByRole("button", { name: "Выбрать период" });
      if (await periodSelector.count() > 0) {
        await page.evaluate(() => window.scrollTo({ top: 0, behavior: "instant" }));
        await periodSelector.click();
        capturePeriodDialog = true;
        await expect(
          page.getByRole("dialog", { name: "Выбор периода" })
        ).toBeVisible();
      }

      const screenshotDirectory = resolve(
        process.cwd(),
        "visual-artifacts",
        testInfo.project.name
      );
      await mkdir(screenshotDirectory, { recursive: true });
      if (routeUrl.pathname === "/insights" && weeklyReviewBlocked) {
        const correctionTrigger = page.getByRole("button", { name: "Что нужно исправить" });
        await correctionTrigger.click();
        const correctionPanel = page.getByRole("dialog", { name: "Что нужно исправить" });
        await expect(correctionPanel).toContainText(
          "Исправление выполняет администратор или ответственный за загрузку данных."
        );
        const currentUserRole = await page.evaluate(async () => {
          const response = await fetch("/api/auth/me", { credentials: "include" });
          const currentUser = await response.json() as { role?: string };
          return currentUser.role;
        });
        const qualityLink = correctionPanel.getByRole("link", {
          name: "Открыть качество данных"
        });
        if (currentUserRole === "ADMIN") {
          await expect(qualityLink).toBeVisible();
          await expect(qualityLink).toHaveAttribute(
            "href",
            `/quality?store=${routeUrl.searchParams.get("store")}`
          );
        } else {
          await expect(qualityLink).toHaveCount(0);
        }
        await page.screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-correction-panel.png"),
          animations: "disabled"
        });
        await page.keyboard.press("Escape");
      } else if (routeUrl.pathname === "/insights") {
        const detailTrigger = page.getByRole("button", { name: "Почему такой вывод" });
        await detailTrigger.click();
        const detailPanel = page.getByRole("dialog", { name: "Основание главного вывода" });
        await expect(detailPanel).toBeVisible();
        await page.screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-detail-panel.png"),
          animations: "disabled"
        });
        await page.keyboard.press("Escape");
        await expect(detailPanel).toHaveCount(0);
        await expect(detailTrigger).toBeFocused();
        await page.getByRole("heading", { name: "ИИ-разбор", exact: true }).click();

        const reviewScenario = routeUrl.searchParams.get("reviewScenario") ?? "ready-dense";
        if (!useLiveWeeklyReview && reviewScenario === "partial") {
          const limitationTrigger = page.getByRole("button", {
            name: "Подробнее об ограничениях"
          });
          await limitationTrigger.click();
          const limitationPanel = page.getByRole("dialog", { name: "Ограничения данных" });
          await expect(limitationPanel).toBeVisible();
          await expect(limitationPanel).toContainText(
            "Исправление выполняет администратор или ответственный за классификацию товаров."
          );
          await page.screenshot({
            path: resolve(screenshotDirectory, screenshotName(route) + "-limitations.png"),
            animations: "disabled"
          });
          await page.keyboard.press("Escape");
          await expect(limitationPanel).toHaveCount(0);
          await expect(limitationTrigger).toBeFocused();
        }
        if (!useLiveWeeklyReview && reviewScenario === "ready-dense") {
          if (testInfo.project.name === "mobile-chromium") {
            const changesToggle = page.locator(
              'button[aria-controls="weekly-review-changes-list"]'
            );
            await expect(changesToggle).toHaveText("Ещё 1 изменение");
            await changesToggle.click();
            await expect(changesToggle).toHaveAttribute("aria-expanded", "true");
            await expect(page.locator(".weekly-review-factor:visible")).toHaveCount(2);
            await page.locator(".weekly-review-factor-list").screenshot({
              path: resolve(screenshotDirectory, screenshotName(route) + "-more-changes.png"),
              animations: "disabled"
            });
            await page.getByRole("button", {
              name: "Скрыть дополнительные изменения"
            }).click();

            const employeesToggle = page.locator(
              'button[aria-controls="weekly-review-employees-list"]'
            );
            await expect(employeesToggle).toHaveText("Ещё 2 сотрудника");
            await employeesToggle.click();
            await expect(employeesToggle).toHaveAttribute("aria-expanded", "true");
            await expect(employeesToggle).toBeFocused();
            await expect(page.locator(".weekly-review-exception:visible")).toHaveCount(3);
            await page.locator(".weekly-review-exception-list").screenshot({
              path: resolve(screenshotDirectory, screenshotName(route) + "-more-employees.png"),
              animations: "disabled"
            });
            await page.getByRole("button", {
              name: "Скрыть дополнительных сотрудников"
            }).click();
          }

          const secondaryActions = page.locator(".weekly-review-secondary-actions > summary");
          await secondaryActions.focus();
          await page.keyboard.press("Enter");
          await expect(page.locator(".weekly-review-secondary-actions")).toHaveAttribute("open", "");
          await page.locator(".weekly-review-primary-action").screenshot({
            path: resolve(screenshotDirectory, screenshotName(route) + "-secondary-actions.png"),
            animations: "disabled"
          });
          await page.keyboard.press("Enter");
          await expect(page.locator(".weekly-review-secondary-actions"))
            .not.toHaveAttribute("open", "");

          const employeeTrigger = page.getByRole("button", {
            name: "Почему сотрудник в списке: Ковзель Ангелина Александровна"
          });
          await employeeTrigger.click();
          const employeePanel = page.getByRole("dialog", {
            name: "Ковзель Ангелина Александровна"
          });
          await expect(employeePanel).toContainText("Ориентир: не ниже 28 ₽/ч");
          await page.screenshot({
            path: resolve(screenshotDirectory, screenshotName(route) + "-employee-detail.png"),
            animations: "disabled"
          });
          await page.keyboard.press("Escape");
          await expect(employeePanel).toHaveCount(0);
          await expect(employeeTrigger).toBeFocused();

          const structureSummary = page.locator(".weekly-review-structure-section > summary");
          await structureSummary.focus();
          await page.keyboard.press("Enter");
          await expect(page.locator(".weekly-review-structure-section")).toHaveAttribute("open", "");
          await page.locator(".weekly-review-structure-section__body").screenshot({
            path: resolve(screenshotDirectory, screenshotName(route) + "-structure-open.png"),
            animations: "disabled",
            style: ".skip-link, .topbar { visibility: hidden !important; }"
          });
          await page.keyboard.press("Enter");
          await expect(page.locator(".weekly-review-structure-section"))
            .not.toHaveAttribute("open", "");
        }
      }
      if (capturePeriodDialog) {
        await page.locator(".range-period__popover").screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-period-selector.png"),
          animations: "disabled"
        });
        await page.keyboard.press("Escape");
        await expect(
          page.getByRole("dialog", { name: "Выбор периода" })
        ).toHaveCount(0);
      }
      if (new URL(route, "http://local.test").pathname === "/overview") {
        await expect(page.getByText("Замечаний по данным: 28")).toHaveCount(0);
        await expect(page.getByRole("heading", {
          name: "Структура продаж — только продавцы"
        })).toBeVisible();
        if (fixtureHasPlan) {
          await expect(page.getByRole("heading", {
            name: "План месяца — только продавцы"
          })).toBeVisible();
        } else {
          await expect(page.getByText(/План месяца/u)).toHaveCount(0);
          await expect(page.getByRole("link", { name: "План", exact: true })).toHaveCount(0);
        }
        const storeScope = page.getByRole("button", { name: "Весь магазин" });
        await storeScope.click();
        await expect(storeScope).toHaveAttribute("aria-pressed", "true");
        await page.waitForLoadState("networkidle");
        await expect(page.getByRole("heading", {
          name: "Структура продаж — весь магазин"
        })).toBeVisible();
        if (fixtureHasPlan) {
          await expect(page.getByRole("heading", {
            name: "План месяца — весь магазин"
          })).toBeVisible();
          const planPanel = page.locator(".plan-panel");
          await expect(planPanel.getByRole("link", { name: "Открыть план" })).toHaveAttribute(
            "href",
            `/plan?store=${visualStoreId}&month=2026-09`
          );
          await expect(planPanel).toContainText("% плана");
          if (routeUrl.searchParams.get("planQuality") === "classification") {
            await expect(planPanel).not.toContainText("Прогноз суммы");
            await expect(planPanel).toContainText("Прогнозы по направлениям обновятся");
          } else {
            await expect(planPanel).toContainText("Прогноз суммы");
          }
          await expect(planPanel).not.toContainText("критерия");
          await planPanel.screenshot({
            path: resolve(screenshotDirectory, screenshotName(route) + "-plan-summary.png"),
            animations: "disabled"
          });
        }
        await page.locator(".overview-summary").screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-store-scope.png"),
          animations: "disabled"
        });
        const sellerScope = page.getByRole("button", { name: "Только продавцы" });
        await sellerScope.click();
        await expect(sellerScope).toHaveAttribute("aria-pressed", "true");
        await page.waitForLoadState("networkidle");
        const teamPanel = page.locator(".overview-team-panel");
        const categoriesPanel = page.locator(".overview-details .overview-disclosure");
        await expect(teamPanel).toHaveAttribute("open", "");
        await expect(categoriesPanel).not.toHaveAttribute("open", "");
        await teamPanel.locator(":scope > summary").click();
        await expect(teamPanel).not.toHaveAttribute("open", "");
        const collapsedHeights = await Promise.all(
          [teamPanel, categoriesPanel].map(async (panel) => (
            await panel.boundingBox()
          )?.height ?? 0)
        );
        expect(collapsedHeights.every((height) => height > 0)).toBe(true);
        collapsedHeights.slice(1).forEach((height) => {
          expect(Math.abs(height - collapsedHeights[0]!)).toBeLessThanOrEqual(1);
        });
        await page.screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-collapsed-sections.png"),
          fullPage: true,
          animations: "disabled"
        });
        await teamPanel.locator(":scope > summary").click();
        const attachMap = page.locator(".attach-map-panel");
        if (await attachMap.count() > 0) {
          await attachMap.locator(":scope > summary").click();
          await expect(attachMap).toHaveAttribute("open", "");
          await attachMap.screenshot({
            path: resolve(screenshotDirectory, screenshotName(route) + "-attach-map.png"),
            animations: "disabled",
            style: ".topbar, .skip-link { visibility: hidden !important; }"
          });
        }
      }
      const routePath = new URL(route, "http://local.test").pathname;
      if (routePath === "/plan" || routePath === "/plan/settings") {
        await expect(page.getByRole("link", { name: "Обзор плана", exact: true })).toBeVisible();
        await expect(page.getByRole("link", { name: "Настройка плана", exact: true })).toBeVisible();
      }
      if (routePath === "/plan") {
        await expect(page.locator(".plan-panel-view")).toBeVisible();
        const expectedScope = routeUrl.searchParams.get("planScope") === "STORE"
          ? "Весь магазин"
          : "Только продавцы";
        await expect(page.getByRole("button", { name: expectedScope }))
          .toHaveAttribute("aria-pressed", "true");
        await expect(page.getByRole("group", { name: "Охват плана" })).toBeVisible();
        if (routeUrl.searchParams.get("planQuality") === "coverage") {
          await expect(page.getByText("Ожидает данных", { exact: true })).toBeVisible();
        }
        await expect(page.getByText("Ориентир на ближайший день", { exact: true })).toBeVisible();
      }
      if (routePath === "/plan/settings") {
        await expect(page.locator(".plan-settings-view")).toBeVisible();
      }
      if (routePath === "/shifts") {
        await expect(page.locator(".schedule-panel-view")).toBeVisible();
      }
      await captureVisualArtifacts(page, screenshotDirectory, screenshotName(route));
      if (routePath === "/employees") {
        const participants = page.locator("#rating-participants");
        await expect(participants).not.toHaveAttribute("open", "");
        await participants.locator(":scope > summary").click();
        await expect(participants).toHaveAttribute("open", "");
        await participants.screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-participants-open.png"),
          animations: "disabled"
        });
      }
      if (routePath === "/plan") {
        const structureRow = page.locator(".plan-structure-row").first();
        await structureRow.locator(":scope > summary").click();
        await expect(structureRow).toHaveAttribute("open", "");
        await structureRow.screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-structure-details.png"),
          animations: "disabled"
        });

        const historyDay = page.locator(".daily-plan-day").first();
        await historyDay.locator(":scope > summary").click();
        await expect(historyDay).toHaveAttribute("open", "");
        await historyDay.screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-history-details.png"),
          animations: "disabled"
        });
      }
      if (routePath === "/plan/settings") {
        const editButton = page.getByRole("button", { name: "Изменить цели" });
        if (await editButton.count() > 0) {
          await editButton.click();
          const editor = page.locator(".plan-settings-panel");
          await expect(editor).toBeVisible();
          await editor.screenshot({
            path: resolve(screenshotDirectory, screenshotName(route) + "-editor.png"),
            animations: "disabled"
          });

          await editor.getByRole("button", { name: "Отмена" }).click();
          await expect(editButton).toBeVisible();
        }
      }

      if (new URL(route, "http://local.test").pathname === "/shifts") {
        const schedule = page.locator(".schedule-panel-view");
        await expect(schedule).toBeVisible();

        const dayButton = page.locator("button.schedule-day:not([disabled])").first();
        await dayButton.click();
        const shiftEditor = page.getByRole("dialog");
        await expect(shiftEditor).toBeVisible();
        await expect(shiftEditor.getByRole("button", { name: "Закрыть редактор" })).toBeFocused();
        if (useFixtureApi) {
          await expect(shiftEditor.getByText("Продавец Анна", { exact: true })).toBeVisible();
          await expect(shiftEditor.getByText("Продавец Борис", { exact: true })).toBeVisible();
          await expect(shiftEditor.getByText("Администратор магазина", { exact: true })).toHaveCount(0);
        }
        await shiftEditor.screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-shift-editor.png"),
          animations: "disabled"
        });
        await page.keyboard.press("Escape");
        await expect(shiftEditor).toHaveCount(0);
        await expect(dayButton).toBeFocused();
      }

      if (routePath === "/admin") {
        await expect(page.getByRole("heading", { name: "Пользователи", exact: true })).toBeVisible();
        const managerRow = page.locator(".admin-user-list article", {
          hasText: "Руководитель без зарплаты"
        });
        await expect(managerRow).toContainText("План, Смены");
        await managerRow.getByTitle("Изменить пользователя и доступ").click();
        const accessEditor = page.getByRole("dialog", { name: "Пользователь и доступ" });
        await expect(accessEditor.getByRole("checkbox", { name: /План/u })).toBeChecked();
        await expect(accessEditor.getByRole("checkbox", { name: /Смены/u })).toBeChecked();
        await expect(accessEditor.getByRole("checkbox", { name: /Зарплата/u })).not.toBeChecked();
        await accessEditor.screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-user-access-editor.png"),
          animations: "disabled"
        });
        if (testInfo.project.name === "mobile-chromium") {
          const saveButton = accessEditor.getByRole("button", { name: "Сохранить" });
          await saveButton.scrollIntoViewIfNeeded();
          await expect(saveButton).toBeVisible();
          await accessEditor.screenshot({
            path: resolve(
              screenshotDirectory,
              screenshotName(route) + "-user-access-editor-bottom.png"
            ),
            animations: "disabled"
          });
        }
        await accessEditor.getByRole("button", { name: "Закрыть" }).click();
      }

      await expect(page.locator(".query-error, .inline-query-error, .stale-data-note"))
        .toHaveCount(0);
      await expectNoHorizontalOverflow(page);
      expect(runtimeFailures).toEqual([]);
    });
  }
});
