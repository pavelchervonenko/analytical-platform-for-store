import { mkdir, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { expect, test, type Page, type Route } from "@playwright/test";
import { makeWeeklyReview } from "../src/test/weeklyReviewFixture";

const email = process.env.VISUAL_EMAIL?.trim() || process.env.E2E_ADMIN_EMAIL?.trim();
const password = process.env.VISUAL_PASSWORD || process.env.E2E_ADMIN_PASSWORD;
const configuredRoutes = process.env.VISUAL_ROUTES?.trim() || "/insights";
const useFixtureApi = process.env.VISUAL_USE_FIXTURES === "true";
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
    email: "visual-manager@example.com",
    displayName: "Руководитель магазина",
    role: "MANAGER",
    passwordChangeRequired: false,
    allStores: false,
    storeIds: [visualStoreId]
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
    expectedThroughDate: "2026-08-26",
    dataThroughDate: "2026-08-26",
    salesDataThroughDate: "2026-08-26",
    returnsDataThroughDate: "2026-08-26",
    lagDays: 0,
    lastCompletedSyncAt: "2026-08-27T04:30:00Z",
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
    checkedAt: "2026-08-27T04:35:00Z"
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
  await page.route("**/api/stores/*/performance-plans/*/progress?*", async (route) => {
    const scope = new URL(route.request().url()).searchParams.get("scope") === "STORE"
      ? "STORE"
      : "SELLERS";
    const factor = scope === "STORE" ? 1.12 : 1;
    const revenue = 54_800_000 * factor;
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
      return {
        code,
        criterionType: targetSharePercent == null ? "AMOUNT" : "SHARE",
        actualAmount,
        targetAmount,
        amountCompletionPercent: actualAmount * 100 / targetAmount,
        currentDailyPace: actualAmount / 27,
        expectedAmountToDate: targetAmount * 27 / 31,
        paceGapAmount: actualAmount - targetAmount * 27 / 31,
        projectedAmount: actualAmount * 31 / 27,
        projectedAmountCompletionPercent: actualAmount * 31 * 100 / (27 * targetAmount),
        remainingAmount: Math.max(0, targetAmount - actualAmount),
        requiredPerRemainingDay: Math.max(0, targetAmount - actualAmount) / 4,
        actualSharePercent,
        targetSharePercent,
        shareGapPercentagePoints: targetSharePercent == null
          ? null
          : (actualSharePercent ?? 0) - targetSharePercent,
        criterionCompletionPercent,
        achieved: criterionCompletionPercent >= 100,
        status: criterionCompletionPercent >= 100 ? "ACHIEVED" : "ON_TRACK"
      };
    };
    const directions = [
      direction("REVENUE", revenue, 55_000_000, null, null),
      direction("ACCESSORY", revenue * 0.065, revenue * 0.063, 6.5, 6.3),
      direction("SERVICE", revenue * 0.045, revenue * 0.042, 4.5, 4.2),
      direction("ADDITIONAL", revenue * 0.11, revenue * 0.105, 11, 10.5)
    ];
    await json(route, {
      storeId: visualStoreId,
      periodStart,
      periodEnd,
      asOfDate: "2026-08-27",
      totalDays: 31,
      elapsedDays: 27,
      remainingDays: 4,
      formulaVersion: "store-plan-progress-v3",
      plan: {
        id: "10000000-0000-4000-8000-000000000010",
        storeId: visualStoreId,
        planMonth: "2026-08",
        revenueTarget: 55_000_000,
        accessoryShareTarget: 6.3,
        serviceShareTarget: 4.2,
        additionalShareTarget: 10.5,
        updatedBy: "20000000-0000-4000-8000-000000000001",
        version: 1,
        updatedAt: "2026-08-01T06:00:00Z"
      },
      dataQuality: {
        freshnessStatus: "CURRENT",
        dataThroughDate: "2026-08-27",
        completeThroughAsOf: true,
        classificationComplete: true,
        unmappedItemCount: 0,
        openQualityIssueCount: 0
      },
      achievedDirectionCount: directions.filter((item) => item.achieved).length,
      allDirectionsAchieved: directions.every((item) => item.achieved),
      focusDirections: [],
      directions,
      dailyTargets: [],
      calculatedAt: "2026-08-27T06:00:00Z"
    });
  });
  await page.route("**/api/stores/*/period-quality/*?*", async (route) => json(route, {
    storeId: visualStoreId,
    periodMonth: "2026-08",
    periodStart,
    periodEnd,
    asOfDate: "2026-08-27",
    status: "OK",
    readyForDecisions: true,
    areas: [],
    issues: [],
    checkedAt: "2026-08-27T06:00:00Z"
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
      status: "DRAFT",
      snapshotId: null,
      finalizedAt: null,
      finalizedBy: null,
      finalizedByName: null
    }
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
  await page.route("**/api/stores/*/weekly-reviews/current", async (route) => {
    await json(route, makeWeeklyReview());
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
      await installFixtureApi(page);
      await page.goto("/insights");
      await expect(page).toHaveURL(/\/insights(?:\?|$)/u);
      await expect(page.locator("#main-content")).toBeVisible();
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

      if (!useFixtureApi && new URL(route, "http://local.test").pathname === "/insights") {
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
      if (new URL(route, "http://local.test").pathname === "/insights") {
        for (const selector of [
          ".weekly-review-formula > summary",
          ".weekly-review-evidence > summary",
          ".weekly-review-secondary > summary"
        ]) {
          const summary = page.locator(selector).first();
          const details = summary.locator("..");
          await summary.focus();
          await page.keyboard.press("Enter");
          await expect(details).toHaveAttribute("open", "");
          await page.keyboard.press("Enter");
          await expect(details).not.toHaveAttribute("open", "");
        }
        const employeeSelectors = page.locator(".weekly-review-employee-selector");
        const firstEmployee = employeeSelectors.first();
        await expect(firstEmployee).toHaveAttribute("aria-pressed", "true");
        if (await employeeSelectors.count() > 1) {
          const secondEmployee = employeeSelectors.nth(1);
          await secondEmployee.click();
          await expect(secondEmployee).toHaveAttribute("aria-pressed", "true");
          await expect(firstEmployee).toHaveAttribute("aria-pressed", "false");
          await firstEmployee.click();
          await expect(firstEmployee).toHaveAttribute("aria-pressed", "true");
        }
        await expect(page.locator(".weekly-review-employee-detail")).toBeVisible();
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
        await expect(page.getByRole("heading", {
          name: "План месяца — только продавцы"
        })).toBeVisible();
        const storeScope = page.getByRole("button", { name: "Весь магазин" });
        await storeScope.click();
        await expect(storeScope).toHaveAttribute("aria-pressed", "true");
        await page.waitForLoadState("networkidle");
        await expect(page.getByRole("heading", {
          name: "Структура продаж — весь магазин"
        })).toBeVisible();
        await expect(page.getByRole("heading", {
          name: "План месяца — весь магазин"
        })).toBeVisible();
        await page.locator(".overview-summary").screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-store-scope.png"),
          animations: "disabled"
        });
        const sellerScope = page.getByRole("button", { name: "Только продавцы" });
        await sellerScope.click();
        await expect(sellerScope).toHaveAttribute("aria-pressed", "true");
        await page.waitForLoadState("networkidle");
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
      await captureVisualArtifacts(page, screenshotDirectory, screenshotName(route));
      if (new URL(route, "http://local.test").pathname === "/plan") {
        const settings = page.locator(".plan-settings-disclosure");
        if (await settings.count() > 0) {
          await settings.locator(":scope > summary").click();
          await expect(settings).toHaveAttribute("open", "");
          await settings.screenshot({
            path: resolve(screenshotDirectory, screenshotName(route) + "-settings-open.png"),
            animations: "disabled"
          });

          await settings.getByRole("button", { name: "Изменить цели" }).click();
          const editor = page.locator(".plan-settings-panel");
          await expect(editor).toBeVisible();
          await editor.screenshot({
            path: resolve(screenshotDirectory, screenshotName(route) + "-settings-editor.png"),
            animations: "disabled"
          });

          await editor.getByRole("button", { name: "Отмена" }).click();
          await expect(page.locator(".plan-settings-disclosure")).toBeVisible();
        }

        await page.getByRole("button", { name: "Смены" }).click();
        await expect(page.locator(".schedule-panel-view")).toBeVisible();
        await page.waitForLoadState("networkidle");
        await page.screenshot({
          path: resolve(screenshotDirectory, screenshotName(route) + "-schedule.png"),
          fullPage: true,
          animations: "disabled"
        });

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


      await expect(page.locator(".query-error")).toHaveCount(0);
      await expectNoHorizontalOverflow(page);
      expect(runtimeFailures).toEqual([]);
    });
  }
});
