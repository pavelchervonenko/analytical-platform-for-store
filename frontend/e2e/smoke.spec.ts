import { expect, test, type Page } from "@playwright/test";

const adminEmail = process.env.E2E_ADMIN_EMAIL;
const adminPassword = process.env.E2E_ADMIN_PASSWORD;
const managerEmail = process.env.E2E_MANAGER_EMAIL;
const managerPassword = process.env.E2E_MANAGER_PASSWORD;

async function login(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("Email").fill(email);
  await page.getByLabel("Пароль").fill(password);
  await page.getByRole("button", { name: "Войти" }).click();
  await expect(page).toHaveURL(/\/overview(?:\?|$)/u);
  await expect(page.locator("#main-content")).toBeVisible();
}

test("закрытый маршрут не раскрывает данные без сессии", async ({ page }) => {
  await page.goto("/overview");
  await expect(page).toHaveURL(/\/login$/u);
  await expect(page.getByRole("heading", { name: "Вход в кабинет" })).toBeVisible();
});

test("ADMIN видит ключевые разделы и новые операционные экраны", async ({ page }) => {
  test.skip(!adminEmail || !adminPassword, "Задайте E2E_ADMIN_EMAIL и E2E_ADMIN_PASSWORD");
  await login(page, adminEmail!, adminPassword!);

  for (const name of ["Сотрудники", "План и смены", "Зарплата и аудит", "Отчёты", "Качество данных"]) {
    await expect(page.getByRole("link", { name })).toBeVisible();
  }

  await page.getByRole("link", { name: "Администрирование" }).click();
  await expect(page.getByRole("heading", { name: "Администрирование" })).toBeVisible();
  await page.getByRole("button", { name: /Синхронизация/u }).click();
  await expect(page.getByRole("heading", { name: "Ручная синхронизация" })).toBeVisible();
  await page.getByRole("button", { name: /Импорт категорий/u }).click();
  await expect(page.getByRole("heading", { name: "Импорт справочника категорий" })).toBeVisible();
});

test("MANAGER не получает административную навигацию", async ({ page }) => {
  test.skip(!managerEmail || !managerPassword, "Задайте E2E_MANAGER_EMAIL и E2E_MANAGER_PASSWORD");
  await login(page, managerEmail!, managerPassword!);
  await expect(page.getByRole("link", { name: "Администрирование" })).toHaveCount(0);
  await page.goto("/admin");
  await expect(page).toHaveURL(/\/overview(?:\?|$)/u);
});
