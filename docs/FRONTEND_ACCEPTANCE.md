# Frontend acceptance Store Analytics

Дата последней кандидатной проверки: 2026-08-24.

Этот документ отделяет автоматический/визуальный результат текущего релиз-кандидата от старой
полной application acceptance и от production smoke.

## Кандидат

Base production: `v0.1.0-pilot.22`. Проверяемый кандидат затрагивает:

- `/overview`: иерархия структуры продаж и сравнительная карта допродаж;
- `/plan`: подписи плановых величин, будущие дни и редактор смен;
- `/insights`: evidence рядом с выводами и честные limited states.

Документационный commit не меняет UI.

## Автоматические проверки

Полный `npm run check` прошел:

1. generated transport types совпадают с OpenAPI artifact;
2. ESLint без ошибок;
3. `38` test files / `143` tests;
4. TypeScript и production Vite build.

Изолированная проверка plan commit также прошла с `142` тестами и production build.

## Локальная визуальная проверка

Выполнен `npm run visual:local` только против loopback-окружения для:

- `/overview`;
- `/plan`;
- `/insights`.

Просмотрены desktop, tablet и mobile изображения. Дополнительно проверены редактор смен и карта
допродаж. Проверка включала page overflow, browser errors, query errors и HTTP 5xx.

Screenshots находятся только в ignored `frontend/visual-artifacts/` и не являются release
артефактом.

## Что подтверждено

- структура продаж не предлагает сложить вложенные показатели как независимые итоги;
- benchmark магазина явно подписан как «Все продажи», а остаток вне roster показан отдельно;
- легенда и цвета сотрудников описывают сравнение с магазинным benchmark;
- месячный план, темп и дневная цель имеют разные подписи;
- причина пересчета «Нужно в день» видима пользователю;
- future-day значения не смешаны с фактом;
- ИИ evidence не дублируется отдельным одинаковым блоком;
- limited/unknown данные не подменяются шаблонной положительной формулировкой;
- layout остается пригодным на трех viewport.

## Что этим прогоном не подтверждено

- candidate еще не развернут и production smoke не выполнялся;
- full credentialed ADMIN/MANAGER mutation lifecycle не повторялся именно для этого UI-кандидата,
  потому что изменения не затрагивают auth/admin mutations;
- реальные YandexGPT и Telegram side effects не выполнялись;
- order-return webhook canary относится к backend operations, а не к frontend acceptance;
- восстановление июльских возвратов не входит в UI-проверку.

Историческая полная browser acceptance от 2026-08-06 проверяла ADMIN/MANAGER routes и lifecycle,
но ее старые числа тестов больше не описывают текущий suite.

## Команды

```bash
cd frontend
npm ci
npm run check
VISUAL_ROUTES='/overview,/plan,/insights' npm run visual:local
```

Credentialed local E2E:

```bash
E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... npm run e2e
```

Мутационный сценарий разрешен только для подготовленного непроизводственного контура:

```bash
E2E_MUTATING=true E2E_ADMIN_EMAIL=... E2E_ADMIN_PASSWORD=... \
  npx playwright test e2e/live-acceptance.spec.ts \
  --project=desktop-chromium --grep 'MANAGER'
```

Production acceptance выполняется после deploy по
[production-deployment-runbook.md](production-deployment-runbook.md), с тем же origin для SPA/API
и без передачи production credentials в репозиторий или screenshots.
