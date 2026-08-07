import { expect, test, type Page } from "@playwright/test";

const adminEmail = process.env.E2E_ADMIN_EMAIL;
const adminPassword = process.env.E2E_ADMIN_PASSWORD;
const mutatingChecksEnabled = process.env.E2E_MUTATING === "true";

async function submitLogin(page: Page, email: string, password: string) {
  await page.goto("/login");
  await page.getByLabel("Электронная почта", { exact: true }).fill(email);
  await page.getByLabel("Пароль", { exact: true }).fill(password);
  await page.getByRole("button", { name: "Войти" }).click();
}

async function login(page: Page, email: string, password: string) {
  await submitLogin(page, email, password);
  await expect(page).toHaveURL(/\/overview(?:\?|$)/u);
  await expect(page.locator("#main-content")).toBeVisible();
}

async function expectNoPageOverflow(page: Page) {
  const overflow = await page.evaluate(() => {
    const root = document.documentElement;
    return { viewport: window.innerWidth, document: root.scrollWidth };
  });
  expect(
    overflow.document,
    "Страница шире viewport: " + JSON.stringify(overflow)
  ).toBeLessThanOrEqual(overflow.viewport + 1);
}

async function expectManagerFacingCopy(page: Page) {
  const text = await page.locator("body").innerText();
  expect(text).not.toMatch(/[Ёё]/u);
  expect(text).not.toMatch(/\b[A-Z][A-Z0-9]+(?:_[A-Z0-9]+)+\b/u);
}

function collectRuntimeFailures(page: Page) {
  const failures: string[] = [];
  page.on("pageerror", (error) => failures.push("pageerror: " + error.message));
  page.on("console", (message) => {
    if (message.type() === "error" && !message.text().startsWith("Failed to load resource:")) {
      failures.push("console: " + message.text());
    }
  });
  page.on("response", (response) => {
    if (response.status() >= 500) {
      failures.push(response.status() + " " + new URL(response.url()).pathname);
    }
  });
  return failures;
}

test("ADMIN открывает все пользовательские разделы без runtime-ошибок и переполнения", async ({ page }) => {
  test.setTimeout(90_000);
  test.skip(!adminEmail || !adminPassword, "Задайте E2E_ADMIN_EMAIL и E2E_ADMIN_PASSWORD");
  const runtimeFailures = collectRuntimeFailures(page);
  await login(page, adminEmail!, adminPassword!);

  for (const [path, heading] of [
    ["/overview", "Обзор"],
    ["/employees", "Сотрудники и рейтинг"],
    ["/plan", "План и смены"],
    ["/payroll", "Зарплата"],
    ["/reports", "Отчеты"],
    ["/quality", "Качество данных"],
    ["/profile", "Профиль и безопасность"],
    ["/admin", "Настройки"]
  ] as const) {
    await page.goto(path);
    await expect(page.getByRole("heading", { name: heading, exact: true })).toBeVisible();
    await expect(page.locator("#main-content")).toBeVisible();
    await expect(page.locator(".query-error")).toHaveCount(0);
    await expectNoPageOverflow(page);
    if (path !== "/admin") await expectManagerFacingCopy(page);
  }

  await page.goto("/overview");
  for (const details of await page.locator(".overview-details details").all()) {
    if (await details.getAttribute("open") === null) await details.locator("summary").click();
    await expect(details).toHaveAttribute("open", "");
  }
  await page.goto("/plan");
  for (const tab of ["Смены", "План магазина"]) {
    const button = page.getByRole("button", { name: new RegExp(tab, "u") });
    await button.click();
    await expect(button).toHaveAttribute("aria-current", "page");
  }
  await page.goto("/payroll");
  for (const tab of ["Дневные фонды", "История", "Ведомость"]) {
    const button = page.getByRole("button", { name: new RegExp(tab, "u") });
    await button.click();
    await expect(button).toHaveAttribute("aria-current", "page");
  }

  await page.goto("/overview");
  await page.getByLabel("Выбрать период").click();
  await page.getByRole("radio", { name: "Неделя" }).click();
  await expect(page.getByRole("radio", { name: "Неделя" })).toBeChecked();
  await page.getByRole("button", { name: "Отмена" }).click();

  await page.goto("/employees");
  const search = page.getByLabel("Найти сотрудника");
  await search.fill("__нет_такого_сотрудника__");
  await expect(page.getByText("Сотрудники не найдены", { exact: true })).toBeVisible();
  await search.clear();
  const employeeLink = page.locator(".employee-open:visible, .employee-mobile-card__actions a:visible").first();
  if (await employeeLink.count()) {
    await employeeLink.click();
    await expect(page).toHaveURL(/\/employees\/[^/?]+/u);
    await expect(page.locator(".query-error")).toHaveCount(0);
    await expectNoPageOverflow(page);
  }

  await page.goto("/reports");
  await page.getByLabel("Тип", { exact: true }).selectOption("MONTHLY");
  await expect(page.getByLabel("Тип", { exact: true })).toHaveValue("MONTHLY");
  await page.getByLabel("Тип", { exact: true }).selectOption("ALL");

  await page.goto("/profile");
  await expect(page.getByRole("heading", { name: "Активные сеансы" })).toBeVisible();
  await expect(page.getByText("Этот браузер", { exact: true })).toBeVisible();

  await page.goto("/admin");
  for (const tab of [
    "Пользователи",
    "Синхронизация",
    "Архив отчетов",
    "Правила расчетов",
    "Категории товаров",
    "Импорт категорий",
    "ИИ-разбор",
    "Telegram"
  ]) {
    const button = page.getByRole("button", { name: new RegExp(tab, "u") });
    await button.click();
    await expect(button).toHaveAttribute("aria-current", "page");
    await expectNoPageOverflow(page);
  }
  await page.getByRole("button", { name: /Пользователи/u }).click();
  await page.getByRole("button", { name: "Создать пользователя" }).click();
  const createDialog = page.getByRole("dialog", { name: "Новый пользователь" });
  await expect(createDialog).toBeVisible();
  await expectNoPageOverflow(page);
  await createDialog.getByRole("button", { name: "Закрыть" }).click();
  await expect(createDialog).toBeHidden();

  expect(runtimeFailures).toEqual([]);
});

test("ADMIN проводит MANAGER через полный жизненный цикл доступа", async ({ page }, testInfo) => {
  test.setTimeout(90_000);
  test.skip(testInfo.project.name !== "desktop-chromium", "Изменяющий сценарий выполняется один раз");
  test.skip(!mutatingChecksEnabled, "Задайте E2E_MUTATING=true только для локальной тестовой БД");
  test.skip(!adminEmail || !adminPassword, "Задайте E2E_ADMIN_EMAIL и E2E_ADMIN_PASSWORD");

  const suffix = Date.now() + "-" + testInfo.workerIndex;
  const managerEmail = "e2e.manager." + suffix + "@example.com";
  const temporaryPassword = "Temp!" + suffix + "Aa7";
  const permanentPassword = "Ready!" + suffix + "Bb9";

  await login(page, adminEmail!, adminPassword!);
  await page.goto("/admin");
  await page.getByRole("button", { name: "Создать пользователя" }).click();

  const dialog = page.getByRole("dialog", { name: "Новый пользователь" });
  await dialog.getByLabel("Email", { exact: true }).fill(managerEmail);
  await dialog.getByLabel("Имя", { exact: true }).fill("Тестовый руководитель");
  await dialog.getByRole("combobox", { name: "Роль" }).selectOption("MANAGER");
  const stores = dialog.locator('input[name="storeIds"]');
  expect(await stores.count(), "Для MANAGER нужен хотя бы один магазин").toBeGreaterThan(0);
  await stores.first().check();
  await dialog.locator('input[name="temporaryPassword"]').fill(temporaryPassword);
  await dialog.getByRole("button", { name: "Сохранить" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText(managerEmail, { exact: true })).toBeVisible();

  await page.locator(".sidebar__footer").getByRole("button", { name: "Выйти" }).click();
  await expect(page).toHaveURL(/\/login$/u);
  await submitLogin(page, managerEmail, temporaryPassword);
  await expect(page).toHaveURL(/\/change-password$/u);

  await page.getByLabel("Временный пароль", { exact: true }).fill(temporaryPassword);
  await page.getByLabel(/^Новый пароль/u).fill(permanentPassword);
  await page.getByLabel("Повторите новый пароль", { exact: true }).fill(permanentPassword);
  await page.getByRole("button", { name: "Сменить пароль" }).click();
  await expect(page).toHaveURL(/\/login$/u);

  await login(page, managerEmail, permanentPassword);
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
    await expectNoPageOverflow(page);
  }
  await page.goto("/admin");
  await expect(page).toHaveURL(/\/overview(?:\?|$)/u);

  await page.locator(".sidebar__footer").getByRole("button", { name: "Выйти" }).click();
  await login(page, adminEmail!, adminPassword!);
  await page.goto("/admin");
  const managerRow = page.locator(".admin-user-list article").filter({ hasText: managerEmail });
  await managerRow.getByTitle("Изменить").click();
  const editDialog = page.getByRole("dialog", { name: "Профиль и роль" });
  await editDialog.getByRole("checkbox", { name: /Активная учетная запись/u }).uncheck();
  await editDialog.getByRole("button", { name: "Сохранить" }).click();
  await expect(editDialog).toBeHidden();
  await expect(managerRow.getByText("Отключен", { exact: true })).toBeVisible();
});
