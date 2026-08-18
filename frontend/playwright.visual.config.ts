import { defineConfig, devices } from "@playwright/test";

const localHosts = new Set(["127.0.0.1", "localhost", "[::1]"]);

function assertLocalHttpUrl(value: string, variableName: string) {
  const parsedUrl = new URL(value);
  if (parsedUrl.protocol !== "http:" && parsedUrl.protocol !== "https:") {
    throw new Error(`${variableName} must be a local http(s) URL`);
  }
  if (!localHosts.has(parsedUrl.hostname)) {
    throw new Error(`Visual checks are local-only. Refusing to use ${variableName}=${value}`);
  }
}

const configuredBaseUrl = process.env.VISUAL_BASE_URL?.trim();
const baseURL = configuredBaseUrl || "http://127.0.0.1:4174";
const apiTarget = process.env.DEV_API_TARGET?.trim() || "http://127.0.0.1:8080";
assertLocalHttpUrl(baseURL, "VISUAL_BASE_URL");
assertLocalHttpUrl(apiTarget, "DEV_API_TARGET");

export default defineConfig({
  testDir: "./e2e",
  testMatch: "visual-local.spec.ts",
  outputDir: "./test-results/visual-local",
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL,
    locale: "ru-RU",
    timezoneId: "Europe/Kaliningrad",
    colorScheme: "light",
    reducedMotion: "reduce",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off"
  },
  expect: { timeout: 15_000 },
  timeout: 60_000,
  webServer: configuredBaseUrl ? undefined : {
    command: "npm run build && npm run preview",
    url: baseURL,
    reuseExistingServer: false,
    timeout: 30_000
  },
  projects: [
    {
      name: "desktop-chromium",
      use: { ...devices["Desktop Chrome"], viewport: { width: 1440, height: 1000 } }
    },
    {
      name: "tablet-chromium",
      use: {
        ...devices["Desktop Chrome"],
        viewport: { width: 768, height: 1024 },
        hasTouch: true
      }
    },
    { name: "mobile-chromium", use: { ...devices["Pixel 7"] } }
  ]
});
