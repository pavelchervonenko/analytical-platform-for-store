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
    status: "READY",
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
    openQualityIssueCount: 0,
    lastError: null,
    lastErrorAt: null,
    checkedAt: "2026-08-27T04:35:00Z"
  }));
  await page.route("**/api/stores/*/weekly-reviews/current", async (route) => {
    await json(route, makeWeeklyReview());
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
        const firstEmployee = page.locator(".insight-employee").first();
        await firstEmployee.locator(":scope > summary").click();
        await expect(firstEmployee).toHaveAttribute("open", "");
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
