---
doc_schema: 1
doc_type: evidence
status: historical
owner: project
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved legacy evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/current/project-state.md"
original_content_sha256: 90a58ae227ada82a74ee6a13e6bb7659838142f468bed344a1212c719f8c4b5c
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/project-state.md`.

# Передача контекста проекта Store Analytics

> **Historical handoff — do not use for production operations.** This snapshot contains obsolete
> release, schema and recovery status. Current verified state:
> [current/project-state.md](../../../../current/project-state.md). The eight July returns described below were
> recovered and reconciled; **do not rerun that recovery from this file**.

Дата сверки с кодом: 2026-08-24.

Это исторический handoff, сохранённый для происхождения решений. Действующая точка входа:
[current/project-state.md](../../../../current/project-state.md). Исторический индекс: [README.md](../../../../README.md).
Состав кандидата на дату снимка:
[RELEASE_CANDIDATE_2026-08-24.md](../../../releases/2026/08/RELEASE_CANDIDATE_2026-08-24.md).

Секреты, токены, cookie, реальные персональные данные и внешние идентификаторы здесь не хранятся.
`.env` нельзя печатать, коммитить или добавлять в task context.

## 1. Исторический production-снимок и релиз-кандидат

На дату исходного снимка production имел следующее состояние; эти значения больше не текущие:

- release `v0.1.0-pilot.22`, commit `2e8f9c2`;
- schema `V44`;
- сервисы `backend-api`, `backend-worker`, `web`;
- HTTPS завершает Caddy, PostgreSQL 16 предоставляется управляемым сервисом;
- `LIVESKLAD_WEBHOOK_ENABLED=true`;
- `LIVESKLAD_WEBHOOK_WORKER_ENABLED=true`;
- `LIVESKLAD_ORDER_RETURN_WEBHOOK_WORKER_ENABLED=false`;
- production ИИ использует default `v4/schema2`; `v21/schema3` еще не активирована.

Ветка `codex/livesklad-daily-webhook-protection` содержит проверенный, но не развернутый кандидат из пяти
продуктовых и одного документационного commit.
Он меняет представление метрик, планы/смены и ИИ-разбор, а также evaluation tooling. Backend
runtime и миграции относительно `pilot.22` не меняются.

## 2. Назначение системы

Платформа:

- синхронизирует продажи, возвраты, заказы, позиции и справочники LiveSklad;
- хранит нормализованные факты и историю синхронизаций в PostgreSQL;
- рассчитывает KPI магазина, категорий и сотрудников;
- ведет планы, смены, рейтинг, payroll и immutable отчеты;
- показывает качество и полноту данных;
- строит недельные ИИ-интерпретации из server-side evidence;
- принимает возвратные webhook и идемпотентно обновляет аналитику;
- доставляет управляемые Telegram-уведомления.

Frontend никогда не обращается к LiveSklad напрямую и не пересчитывает бизнес-формулы.

## 3. Архитектурная граница

```text
Browser SPA -> HTTPS/Caddy -> backend-api -> PostgreSQL
                                      ^
LiveSklad -> webhook receiver --------|
LiveSklad <- sync/webhook workers ----+
YandexGPT/Telegram <- background workers
```

Runtime-роли:

- `API` — HTTP, auth, read/mutation API; не владеет scheduler;
- `WORKER` — sync, webhook, LLM, Telegram и maintenance workers;
- `MIGRATION` — one-shot Flyway с lock/statement timeout;
- `COMBINED` — только для локального/специального контура.

Неизвестная роль должна завершать startup. Production API и worker используют один immutable
backend image digest, но разные role/env.

Основные пакеты:

- `auth`, `store`, `employee`, `product`, `sales`;
- `sync`, `integration.livesklad`;
- `metrics`, `performance`, `salary`, `report`, `quality`;
- `interpretation`, `notification`, `maintenance`.

## 4. Данные и миграции

Актуальная схема строится Flyway `V1–V44`. Последний блок миграций:

| Миграция | Назначение |
| --- | --- |
| V33 | нормализация ожидаемой нулевой себестоимости |
| V34 | подтвержденные зарплатные категории техники |
| V35 | исправление денежной классификации товаров заказчика |
| V36 | разделение стекла/камеры для телефона |
| V37 | сохранение фактического provider input ИИ |
| V38 | единицы и методология attach-rate |
| V39/V39.1 | завершение классификации и совместимость webhook inbox |
| V40 | синхронизация позиций выданных заказов |
| V41 | утвержденный roster рейтинга |
| V42 | durable inbox возвратных webhook |
| V43 | processable state/lease/retry для inbox |
| V44 | validated recovery известных возвратов |

Полный schema oracle и migration tests являются обязательной частью backend check. Миграции вперед
предпочтительнее ручного rollback SQL; перед несовместимой миграцией обязателен проверенный
forward-fix/restore сценарий.

Ключевые инварианты:

- продажа и возврат сохраняют финансовый и классификационный snapshot;
- возврат влияет на период по дате возврата и, когда связь известна, относится к продавцу исходной
  продажи;
- повторная доставка webhook или recovery не должна создавать второй финансовый эффект;
- finalized rating, approved/paid payroll и finalized reports неизменяемы;
- `null`/недостаточные данные не подменяются нулем;
- frontend не выводит итог из raw vendor payload;
- текущий незавершенный день не считается обязательным полным покрытием периода.

## 5. LiveSklad sync, webhook и recovery

### Плановая/ручная синхронизация

Sync jobs durable, имеют lease, retries, progress и отмену. Backfill восстанавливает доступные через
API факты периода, но не гарантирует нахождение документов, которые vendor API не возвращает в
обычных списках.

### Возвратные webhook

Есть два endpoint: возврат продажи и возврат заказа. Receiver:

- скрыт из публичного OpenAPI;
- включается feature flag;
- требует отдельный exact secret с поддержкой previous secret;
- принимает только POST/JSON и ограниченный body;
- отвечает на LiveSklad URL verification точным verification value;
- сохраняет raw payload после validation, deduplicate event и delivery metadata.

Sale-return worker получает полный документ по `data.id`, ищет исходную продажу и обновляет факты
идемпотентно. Order-return worker отделен собственным флагом и остается выключенным до canary
настоящего события. `action.id` не используется как ID бизнес-документа.

Подробности: [livesklad-webhook-receiver.md](../../../../archive/legacy-contracts/livesklad-webhook-receiver.md).

### Подтвержденные пропуски

Штатный validated recovery endpoint сверяет external ID, номер, сумму и количество позиций до
изменения данных. Для июльской сверки были известны восемь пропущенных возвратов с общей разницей
`716 750 ₽`. **Errata:** операция впоследствии завершена, а сверка с CRM сошлась; повторный запуск
запрещён. Итог: [current/project-state.md](../../../../current/project-state.md#data-and-return-recovery).

Временный каталог `.codex-prod-recovery/` намеренно не входит в Git. Источник истины:
[validated-return-recovery-runbook.md](../../../../archive/legacy-contracts/validated-return-recovery-runbook.md).

## 6. Метрики и правила представления

Backend является источником формул. Текущий UI должен соблюдать:

- чистая выручка учитывает продажи и возвраты; движение денег не подменяет выручку;
- «Телефоны» — вложенная часть «Техники», а не независимый итог;
- «Аксессуары» и «Услуги» — состав «Дополнительной выручки»;
- проценты структуры имеют явно подписанный знаменатель;
- benchmark карты допродаж — итог по всем документам магазина;
- разница между магазином и показанными участниками рейтинга выводится как «Вне рейтинга»;
- цвета сотрудников сравнительные относительно магазинного benchmark, а не абсолютные;
- месячный план, темп на текущую дату, остаток и будущая дневная цель — разные значения;
- дневная цель пересчитывается после изменения факта, плана, даты среза или числа оставшихся дней;
- сотрудник вне рейтинга не получает rank; его продажи входят в store benchmark и видны в
  контекстном остатке;
- evidence ИИ показывается рядом с выводом; отсутствующее evidence не заменяется шаблонной фразой.

Актуальные детали: [analytics-business-rules-draft.md](../../../../archive/discoveries/analytics-business-rules-draft.md),
[frontend-actions.md](../../../../archive/legacy-contracts/frontend-actions.md), [store-plan-progress-api.md](../../../../archive/legacy-contracts/store-plan-progress-api.md),
[attach-rate-api.md](../../../../archive/legacy-contracts/attach-rate-api.md).

## 7. Роли и безопасность

Публичной регистрации нет. ADMIN создает пользователей с временным паролем.

- `MANAGER` видит назначенные магазины и рабочие разделы;
- `ADMIN` видит все магазины и раздел «Система»;
- обязательная смена временного пароля ограничивает доступ к остальным API;
- cookie session, CSRF и store authorization проверяются backend;
- Swagger/OpenAPI и административные операции защищены;
- webhook secrets отделены от пользовательской auth и ротируются current/previous;
- production secrets передаются только через `/etc/store-analytics/release.env` или secret
  mechanism, не через Git.

## 8. ИИ-контур

Недельная интерпретация строится по immutable snapshot и evidence catalog. Модель не должна
самостоятельно изобретать факты или идентификаторы сотрудников.

`v21/schema3`:

- прошла `58` unit tests;
- прошла `26/26` автоматических и ручных семантических кейсов;
- получила среднюю оценку `4.8/5`;
- не стала production default автоматически.

Для включения требуется отдельный rollout: точный image/config, staging/canary, проверка generated
payload, UI и Telegram, затем управляемое переключение и rollback plan.

## 9. Проверка текущего кандидата

Последняя зафиксированная проверка:

- backend `925` tests, без failures/errors/skipped;
- frontend `38` files / `143` tests;
- ESLint, generated contract, TypeScript/Vite build;
- Checkstyle, OpenAPI compatibility, supply-chain, security и release-safety;
- visual-local для `/overview`, `/plan`, `/insights` на трех viewport;
- отдельная проверка schedule editor и attach map.

Команды:

```bash
./gradlew :backend:check
cd frontend
npm ci
npm run check
VISUAL_ROUTES=/overview,/plan,/insights npm run visual:local
```

Visual-проверка только локальная. Screenshots могут содержать бизнес-данные и не коммитятся.

## 10. Открытые задачи и порядок

1. Проверить состав documentation commit и рабочее дерево.
2. С рабочим SSH agent выполнить `git fetch` и проверить divergence перед push.
3. Развернуть текущий frontend-кандидат отдельным release change.
4. [x] Июльские возвраты восстановлены и сверены с CRM — **completed, do not rerun**.
5. Дождаться настоящего `ORDER_RETURN`, проверить `data.id` и только затем canary order worker.
6. Продолжать backfill исторических месяцев с независимой сверкой каждого магазина/месяца.
7. Исправлять внешние data-quality проблемы (смены, классификация, несвязанные возвраты) только на
   основании подтвержденных исходных данных.
8. Активировать `v21/schema3` отдельным контролируемым релизом, не смешивая его с UI-кандидатом.

Production deployment, recovery и backfill не объединяются в одну необратимую операцию.
