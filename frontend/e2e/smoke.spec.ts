import { expect, test, type Page } from "@playwright/test";

const adminEmail = process.env.E2E_ADMIN_EMAIL;
const adminPassword = process.env.E2E_ADMIN_PASSWORD;
const managerEmail = process.env.E2E_MANAGER_EMAIL;
const managerPassword = process.env.E2E_MANAGER_PASSWORD;

async function login(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("Электронная почта", { exact: true }).fill(email);
  await page.getByLabel("Пароль", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Войти" }).click();
  await expect(page).toHaveURL(/\/overview(?:\?|$)/u);
  await expect(page.locator("#main-content")).toBeVisible();
}

async function openNavigationOnMobile(page: Page) {
  const menuButton = page.getByRole("button", { name: "Открыть меню" });
  if (await menuButton.isVisible()) await menuButton.click();
}

test("закрытый маршрут не раскрывает данные без сессии", async ({ page }) => {
  await page.route("**/api/auth/me", (route) => route.fulfill({
    status: 401,
    contentType: "application/problem+json",
    body: JSON.stringify({ code: "AUTHENTICATION_REQUIRED" })
  }));
  await page.goto("/overview");
  await expect(page).toHaveURL(/\/login$/u);
  await expect(page.getByRole("heading", { name: "Вход в кабинет" })).toBeVisible();
  const hasHorizontalOverflow = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
  expect(hasHorizontalOverflow).toBe(false);
});

test("ADMIN видит ключевые разделы и новые операционные экраны", async ({ page }) => {
  test.skip(!adminEmail || !adminPassword, "Задайте E2E_ADMIN_EMAIL и E2E_ADMIN_PASSWORD");
  await login(page, adminEmail!, adminPassword!);
  await openNavigationOnMobile(page);

  for (const name of ["Сотрудники", "План и смены", "Зарплата", "Отчеты", "Качество данных"]) {
    await expect(page.getByRole("link", { name })).toBeVisible();
  }

  await page.getByRole("link", { name: "Настройки", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Настройки", exact: true })).toBeVisible();
  await page.getByRole("button", { name: /Синхронизация/u }).click();
  await expect(page.getByRole("heading", { name: "Синхронизировать данные" })).toBeVisible();
  await page.getByRole("button", { name: /Импорт категорий/u }).click();
  await expect(page.getByRole("heading", { name: "Импорт справочника категорий" })).toBeVisible();
});

test("MANAGER не получает административную навигацию", async ({ page }) => {
  test.setTimeout(90_000);
  test.skip(!managerEmail || !managerPassword, "Задайте E2E_MANAGER_EMAIL и E2E_MANAGER_PASSWORD");
  await login(page, managerEmail!, managerPassword!);
  await openNavigationOnMobile(page);
  await expect(page.getByRole("link", { name: "Настройки", exact: true })).toHaveCount(0);

  for (const [path, heading] of [
    ["/overview", "Обзор"],
    ["/employees", "Сотрудники и рейтинг"],
    ["/plan", "План и смены"],
    ["/payroll", "Зарплата"],
    ["/reports", "Отчеты"],
    ["/quality", "Качество данных"],
    ["/profile", "Профиль и безопасность"]
  ] as const) {
    await page.goto(path);
    await expect(page.getByRole("heading", { name: heading, exact: true })).toBeVisible();
  }

  const adminApiRequests: string[] = [];
  page.on("request", (request) => {
    if (new URL(request.url()).pathname.startsWith("/api/admin/")) {
      adminApiRequests.push(request.url());
    }
  });

  await page.goto("/admin");
  await expect(page).toHaveURL(/\/overview(?:\?|$)/u);
  await expect(page.locator("#main-content")).toBeVisible();
  await expect(page.getByRole("heading", { name: "Обзор", exact: true })).toBeVisible();
  expect(adminApiRequests).toEqual([]);
});
