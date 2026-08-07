# Frontend acceptance Store Analytics

Дата последней полной проверки: 2026-08-06.

Документ фиксирует фактически выполненную приемку SPA. Он не заменяет staging/production launch
gates из `production-deployment-runbook.md` и не содержит учетных данных.

## Проверенный контур

- frontend: локальный Vite dev server и production preview;
- backend: локальный Spring Boot API;
- PostgreSQL: локальная тестовая база;
- браузер: Chromium через Playwright;
- размеры: desktop `1440x1000`, tablet `768x1024` с touch и mobile Pixel 7.

Для Node.js действует требование `>=22.22.0` из `frontend/package.json`.

## Автоматические проверки

Команда `npm run check` выполняет:

1. проверку generated TypeScript типов против текущего OpenAPI;
2. ESLint без предупреждений;
3. `25` Vitest files / `90` tests;
4. TypeScript production build и Vite bundle.

Итог 2026-08-06: все этапы прошли.

Полный Playwright suite против production preview собрал `15` project-tests:

- `9` применимых сценариев прошли;
- `6` сценариев были пропущены намеренно: три требуют постоянных MANAGER credentials, еще три
  защищены `E2E_MUTATING=true` и не должны менять данные по умолчанию;
- изменяющий desktop-сценарий жизненного цикла MANAGER отдельно прошел полностью.

## Покрытые пользовательские сценарии

- анонимный переход на закрытый маршрут возвращает на `/login` без раскрытия данных;
- ADMIN входит и открывает все рабочие разделы;
- проверены `/overview`, `/employees`, карточка сотрудника, `/plan`, `/payroll`, `/reports`,
  `/quality`, `/profile` и `/admin`;
- проверены восемь административных вкладок: пользователи, синхронизация, архив отчетов, правила
  расчетов, категории, импорт, ИИ-разбор и Telegram;
- проверены выбор периода, поиск сотрудника, пустой результат поиска, фильтр отчетов, вкладки плана,
  смен и зарплаты, раскрывающиеся блоки обзора, активный сеанс и открытие/закрытие формы пользователя;
- MANAGER создан через штатный UI, получил магазин, сменил временный пароль, вошел повторно,
  открыл рабочие разделы, не получил административную навигацию и был перенаправлен с `/admin`;
- после проверки тестовая учетная запись MANAGER отключена через штатный UI;
- на desktop, tablet и mobile проверены отсутствие page-level horizontal overflow, `query-error`,
  browser runtime errors, HTTP `5xx`, необработанных enum-кодов и буквы `ё` в видимом тексте.

## Запуск

```bash
cd frontend
npm run check
E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... npm run e2e
```

Для проверки постоянной MANAGER-учетки добавить `E2E_MANAGER_EMAIL` и `E2E_MANAGER_PASSWORD`.
Учетные данные передаются только через окружение/CI secrets и не сохраняются в репозитории.

Изменяющая локальная проверка разрешена только на тестовой базе:

```bash
E2E_MUTATING=true E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... \
  npx playwright test e2e/live-acceptance.spec.ts \
  --project=desktop-chromium --grep "жизненный цикл"
```

При наличии E2E credentials Playwright использует один worker: параллельные viewport-проекты не
должны одновременно изменять server-side session registry одной учетной записи.

## Что не запускается автоматически

Следующие действия требуют отдельной staging-приемки на подготовленных данных, потому что создают
необратимые финансовые/исторические записи или обращаются к внешним системам:

- финализация рейтинга;
- расчет, утверждение и отметка выплаты зарплаты;
- запуск полной синхронизации и импорт классификации;
- regenerate/cancel ИИ-задач;
- Telegram link/confirm/revoke/resend.

Их UI, маршруты, доступность и безопасные диалоги входят в обычный browser audit, но успешный
внешний side effect должен проверяться по соответствующему staging runbook.

## Оставшиеся риски

1. При трех одновременных входах одной ADMIN-учеткой один backend login однажды завершился
   `500 INTERNAL_ERROR`; последовательные входы стабильны. До production нужен отдельный backend
   concurrency regression на login/session registration.
2. Локальная приемка выполнялась в окружении, где одна диагностическая команда обнаружила
   Node.js `20.19.5`. Release/CI должны использовать заявленный Node.js `>=22.22.0`.

## Источники

- `frontend/e2e/smoke.spec.ts` — короткие anonymous/ADMIN/MANAGER проверки;
- `frontend/e2e/live-acceptance.spec.ts` — глубокий маршрутный и изменяющий lifecycle audit;
- `frontend/playwright.config.ts` — viewport-проекты и последовательный credentialed run;
- `frontend/README.md` — локальный запуск;
- `docs/production-deployment-runbook.md` — обязательные launch gates.
