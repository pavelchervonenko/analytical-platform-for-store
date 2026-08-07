import { defineConfig, devices } from "@playwright/test";

const externalBaseUrl = process.env.E2E_BASE_URL?.trim();
const baseURL = externalBaseUrl || "http://127.0.0.1:4174";

export default defineConfig({
  testDir: "./e2e",
  outputDir: "./test-results",
  fullyParallel: true,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 0,
  // Credentialed checks mutate the server-side session registry. Keep them
  // sequential so parallel viewport projects do not race on the same account.
  workers: process.env.E2E_ADMIN_EMAIL || process.env.E2E_MANAGER_EMAIL
    ? 1
    : process.env.CI ? 2 : undefined,
  reporter: process.env.CI
    ? [["line"], ["html", { outputFolder: "playwright-report", open: "never" }]]
    : [["list"], ["html", { outputFolder: "playwright-report", open: "never" }]],
  use: {
    baseURL,
    locale: "ru-RU",
    timezoneId: "Europe/Kaliningrad",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off"
  },
  expect: { timeout: 10_000 },
  timeout: 30_000,
  webServer: externalBaseUrl ? undefined : {
    command: "npm run preview",
    url: baseURL,
    reuseExistingServer: !process.env.CI,
    timeout: 30_000
  },
  projects: [
    { name: "desktop-chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } } },
    { name: "tablet-chromium", use: { ...devices["Desktop Chrome"], viewport: { width: 768, height: 1024 }, hasTouch: true } },
    { name: "mobile-chromium", use: { ...devices["Pixel 7"] } }
  ]
});
