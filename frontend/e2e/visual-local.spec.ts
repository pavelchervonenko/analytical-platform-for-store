import { mkdir, rm } from "node:fs/promises";
import { resolve } from "node:path";
import { expect, test, type Page } from "@playwright/test";

const email = process.env.VISUAL_EMAIL?.trim() || process.env.E2E_ADMIN_EMAIL?.trim();
const password = process.env.VISUAL_PASSWORD || process.env.E2E_ADMIN_PASSWORD;
const configuredRoutes = process.env.VISUAL_ROUTES?.trim() || "/insights";
const templateEmails = new Set(["manager@example.com", "replace-with-local-email"]);

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
    animations: "disabled"
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
    await login(page);
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

      await page.goto(route, { waitUntil: "domcontentloaded" });
      await waitForStablePage(page);

      if (new URL(route, "http://local.test").pathname === "/insights") {
        const firstEmployee = page.locator(".insight-employee").first();
        await firstEmployee.locator(":scope > summary").click();
        await expect(firstEmployee).toHaveAttribute("open", "");
      }

      const screenshotDirectory = resolve(
        process.cwd(),
        "visual-artifacts",
        testInfo.project.name
      );
      await captureVisualArtifacts(page, screenshotDirectory, screenshotName(route));

      await expect(page.locator(".query-error")).toHaveCount(0);
      await expectNoHorizontalOverflow(page);
      expect(runtimeFailures).toEqual([]);
    });
  }
});
