---
doc_schema: 1
doc_type: working
status: draft
owner: project
audience:
  - developer
  - product
created_at: 2026-09-14
review_by: 2026-09-28
source_material:
  - docs/current/project-state.md
  - docs/maintenance/weekly-review-implementation-plan.md
  - docs/history/audits/2026/09/WEEKLY_REVIEW_LOCAL_PRERELEASE_2026-09-14.md
  - docs/runbooks/production-deployment.md
required_reviewers:
  - frontend
  - backend
  - product
exit_target: evidence
---

# Weekly Review release manifest

## Цель и границы

Manifest фиксирует P7-A–P7-G для минимального deterministic-first change set раздела «ИИ-разбор»,
включая честные stop-результаты незакрытых release gates.
Кандидат собирается в отдельном worktree от подтверждённой production-базы, указанной в
[`project-state.md`](../current/project-state.md). Текущая рабочая ветка с параллельными изменениями
не является базой сборки и не переносится целиком.

Этот документ не разрешает staging или production rollout. Платный AI-path, новые обращения к
LiveSklad и изменения других продуктовых разделов находятся вне границ текущего продолжения.

## Выбранный путь

`Deterministic-first`. Интерфейс и deterministic policy должны работать при выключенных AI
generation/planner. Включение AI требует отдельного P7-D gate; отсутствие provider-вызова не
трактуется как проверка AI.

## Include: runtime

### Backend Weekly Review

- `backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewAssembler.java`
- `backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewCoreProjector.java`
- `backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewPolicyV1.java`
- `backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewQualityPolicyV1.java`
- `backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewSnapshotPlanningService.java`
- `backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewStructureProjector.java`
- `backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewSummaryPresenter.java`
- `backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewTeamEmployeeProjector.java`
- `backend/src/main/java/com/storeanalytics/quality/repository/PeriodQualityIssueRepository.java`

### Frontend Weekly Review

- `frontend/src/api/weeklyReviewContract.ts`
- `frontend/src/insights/InsightsPreviewPage.tsx`
- `frontend/src/insights/WeeklyReviewView.tsx`
- `frontend/src/insights/weekly-review-presentation.ts`
- `frontend/src/insights/weekly-review/ReviewDetailPanel.tsx`
- `frontend/src/insights/weekly-review/WeeklyReviewContent.tsx`
- `frontend/src/insights/weekly-review/weeklyReviewViewModel.ts`
- `frontend/src/insights/weekly-review/weekly-review.css`
- удаление прежнего `frontend/src/insights/weekly-review.css` после переноса стилей в модульную
  директорию.

## Include: tests and fixtures

### Backend

- `backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewAssemblerTest.java`
- `backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewCoreProjectorTest.java`
- `backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewPolicyV1Test.java`
- `backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewQualityPolicyV1Test.java`
- `backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewSnapshotPlanningServiceTest.java`
- `backend/src/test/java/com/storeanalytics/interpretation/review/WeeklyReviewTeamEmployeeProjectorTest.java`
- `backend/src/test/java/com/storeanalytics/metrics/repository/StoreKpiIntegrationTest.java`
- `backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiSemanticValidatorTest.java`
- `backend/src/test/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiTestFixtures.java`

### Frontend

- `frontend/e2e/visual-local.spec.ts` — `/insights` assertions пяти состояний, keyboard/focus
  проверки и локальные снимки раскрытых состояний; plan/auth fixture-hunks не включаются.
- `frontend/e2e/weekly-review-visual-fixtures.ts` — изолированные Weekly Review fixtures для
  `ready-dense`, `ready-calm`, `ready-missing-shifts`, `partial` и `blocked`.
- `frontend/src/api/weeklyReviewContract.test.ts`
- `frontend/src/insights/placement.test.tsx` — явная manager-role fixture для ролевой ссылки на
  качество данных.
- `frontend/src/insights/WeeklyReviewView.test.tsx`
- `frontend/src/insights/weekly-review-presentation.test.ts`
- `frontend/src/insights/weekly-review/weeklyReviewViewModel.test.ts`
- `frontend/src/test/fixtures/weekly-review-v2-ready.json`

## Include: documentation

- `docs/current/ai/weekly-review.md`
- `docs/current/frontend/README.md`
- `docs/current/product/employees-and-rating.md`
- `docs/history/audits/2026/09/WEEKLY_REVIEW_LOCAL_PRERELEASE_2026-09-14.md`
- `docs/maintenance/weekly-review-manager-experience-plan.md`
- `docs/maintenance/weekly-review-implementation-plan.md`
- этот manifest и его строка в `docs/maintenance/documentation-inventory.tsv`.

## Dependency: минимальные общие изменения

| Файл | Почему нужен | Ограничение |
|---|---|---|
| `frontend/src/shared/QueryState.tsx` | `WeeklyReviewView` сохраняет последний успешный отчёт при ошибке фонового обновления | Добавляется только `StaleDataNote`; общая переработка ошибок не включается |
| `frontend/src/styles.css` | Стили `StaleDataNote` и раскрываемого кода обращения | Только соответствующие селекторы; plan/admin/payroll стили исключены |
| `frontend/package.json` | Patch-обновление Vitest и защищённый `js-yaml` override после полного P7-B audit | Runtime dependencies и scripts не меняются |
| `frontend/package-lock.json` | Воспроизводит проверенные patch-версии dev/build цепочки | Только совместимые patch/transitive updates; production runtime tree не расширяется |

Полная перенесённая копия `frontend/e2e/visual-local.spec.ts` была отклонена review границы: в ней
одновременно находились Weekly Review и несвязанные plan/auth fixture-hunks. В P7-B расширение
добавлено отдельным чистым test-only модулем и узкими `/insights` assertions: пять независимых
состояний, четыре KPI, закрытая по умолчанию структура, одинаковая высота decision cards на
desktop, клавиатурное раскрытие структуры, secondary actions и focus-return detail panel. Runtime
plan/auth поведение этим расширением не меняется.

## Exclude

- auth, sessions, admin, payroll, plan/shifts и их UI, API, тесты и документация;
- LiveSklad recovery, sync checkpointing, procurement и webhook changes;
- любые новые migration-файлы и тестовые ожидания чужого migration range;
- product-classification change set из параллельной ветки: это возможная предпосылка получения
  реального `READY`, но не compile/runtime-зависимость Weekly Review;
- сгенерированный OpenAPI из общего грязного worktree: публичная форма ответа в этом change set не
  менялась; P7-B обязан заново проверить контракт на чистом кандидате;
- `docs/current/project-state.md`: обновляется только после отдельного sanitized runtime release
  verification;
- `.codex-prod-recovery`, credentials, provider payloads, local database files, build outputs,
  `visual-artifacts/`, business-data screenshots, временные patches и backup/orig-файлы.

## Migration и persisted data boundary

Migration diff пуст: кандидат использует границу production-базы без добавления или изменения
migration-файлов. Существующие snapshots не переписываются. Изменённая deterministic policy
создаёт новую immutable revision только через штатный planner; это проверяется тестами и P7-C.

Если в diff появляется migration, P7-A автоматически считается незакрытым до отдельного
backend/operations review.

## OpenAPI boundary

Новая публичная DTO-форма не добавляется. Изменяется допустимое значение существующего поля и
presentation-семантика. P7-B обязан выполнить штатный OpenAPI compatibility/generation gate на
чистом worktree; ручной перенос общего сгенерированного файла запрещён.

## Контроль границы

Перед завершением P7-A:

1. `git diff --name-status` и untracked inventory совпадают с разделами `Include`/`Dependency`.
2. В diff нет migration, secrets, business screenshots, build outputs и локальных данных.
3. Все документационные файлы добавлены в настоящий index release worktree.
4. Обычный `python3 scripts/check-documentation.py --strict` проходит без временного index.
5. `git diff --check` проходит для staged change set.
6. Независимый scope review не находит файлов без явной связи с Weekly Review.

### Результат P7-A

`PASS` для локальной границы кандидата:

- настоящий index содержит 41 файл из `Include`/`Dependency`; untracked application/docs files
  отсутствуют после удаления временного dependency symlink;
- migration и OpenAPI diff отсутствуют; изображения, local data, credentials и build outputs в
  index не попали;
- targeted frontend suite прошёл `50/50`, targeted backend suite на Java 21 — `74/74`;
- локальный fixture capture `/insights` прошёл `3/3` для desktop, tablet и mobile; изображения
  просмотрены вручную, overflow, обрезки и новые композиционные дефекты не обнаружены;
- desktop assertion подтверждает одинаковую высоту decision cards с допуском один CSS-пиксель;
  tablet/mobile сохраняют естественную последовательность;
- documentation unit suite прошёл `25/25`, ordinary strict — 398 inventory rows с нулём
  предупреждений; staged `git diff --check` прошёл.

Это закрывает только P7-A. Полные lockfile install/check/build и воспроизводимая упаковка относятся
к P7-B; реальные `READY`/`PARTIAL` и новая immutable revision — к P7-C.

### Результат P7-B

`PASS` для воспроизводимого локального кандидата:

- чистый lockfile install, contract check, lint, `42` frontend test files / `198` tests и production
  build прошли; полный и production-only npm audit показывают `0` известных уязвимостей;
- полный backend `:backend:check` на Java 21 прошёл `1098` tests без failures/errors/skips вместе с
  OpenAPI, Checkstyle, operator/release-safety и supply-chain gates;
- пять состояний Weekly Review прошли `15/15` local visual captures на desktop/tablet/mobile;
  основные и раскрытые состояния просмотрены вручную без новых дефектов компоновки или доступности;
- documentation unit suite прошёл `25/25`, ordinary strict — `398` inventory rows без
  предупреждений; deploy и Weekly Review AI release-safety tests, а также `git diff --check`,
  прошли;
- backend и web images локально собраны из одного reviewed commit с immutable candidate-тегами;
  их OCI revision labels совпадают. Images не публиковались и не использовались с production или
  staging;
- migration diff относительно production-базы и OpenAPI diff пусты; backend JAR содержит все `49`
  исходных migration-файлов без дополнительных migration;
- scope/security review не обнаружил secrets, local data, business screenshots, build outputs или
  изменений исключённых продуктовых и интеграционных областей.

P7-B не подтверждает реальный `READY`: это отдельная P7-C проверка, для которой требуется новое
явное разрешение на источник, магазины и диапазон дат.

### Результат P7-C

`STOP` после разрешённой локальной проверки реальных данных:

- локальная БД уже содержала непрерывное покрытие `2026-08-31..2026-09-13` для двух разрешённых
  магазинов, поэтому новый внешний read LiveSklad не выполнялся;
- штатный local authenticated admin API создал для обоих магазинов immutable revision 3 с
  `snapshotPolicy=weekly-snapshot-v12`; старые v10/v11 revisions сохранены, supersedes-цепочки и
  content hashes не противоречат immutability;
- оба v12 отчёта имеют естественный `PARTIAL`, одинаковую сводную структуру coverage
  (`2` complete / `1` partial) и core metrics (`1` ready / `3` limited). Fixture-only `READY` не
  принимается за реальное подтверждение;
- неполные смены не создали самостоятельный глобальный warning, employee priority или workload
  benchmark; ограничения остались адресными;
- live visual v12 не состоялся из-за локальной инфраструктуры: Windows loopback недоступен WSL
  browser, а Docker Desktop не смог по TLS получить browser runtime из MCR и двух Alpine mirrors.
  Пройденные в P7-B `15/15` fixture captures и прежние `6/6` live v11 это не заменяют;
- readiness стенда корректно остаётся `DOWN`: reused local DB уже на `V49`, тогда как кандидат
  упакован до `V48`. Схема не откатывалась и readiness не ослаблялся.

Production/staging не использовались. Этот результат не разрешает последующие gates автоматически:
для rollout всё ещё нужны естественный real-data `READY`, live v13 visual и закрытые release-
equivalent/production preflight условия.

### Коррекция бизнес-семантики после P7-C

Владелец продукта подтвердил, что нулевая себестоимость и отсутствие доступной исходной
продажи/позиции у части возвратов являются нормальными состояниями. Candidate поэтому переводит
эти случаи из quality limitation в диагностический контекст: ноль остаётся рассчитанным значением,
возврат остаётся учтённым в результате магазина, а недоступная связь с сотрудником объясняется
только в блоке команды. Действительно отсутствующая себестоимость и остальные проблемы
согласованности сохраняют прежнее fail-closed поведение.

Изменение версионировано как `weekly-metrics-v7`, `weekly-snapshot-v13` и `weekly-quality-v7`;
существующие v10–v12 snapshots не переписываются. Предыдущий результат P7-C выше остаётся
историческим evidence для v12.

Повторный authenticated прогон в изолированной локальной V48-копии создал revision 4 обоих
магазинов. Оба payload имеют актуальные v7/v13/v7 policy versions, полное required coverage и
`4/4` core metrics в `READY`; согласованные нормальные случаи отсутствуют в limitations. Оба
report state остались естественными `PARTIAL`: один из-за employee sales sufficiency, другой из-за
`PRODUCTS_UNCLASSIFIED`. Реальные факты не изменялись, поэтому natural `READY` не подменён fixture.

### Проверки завершения функциональной части

После коррекции v13 targeted backend-набор прошёл `44/44`. В него входят `4/4` проверки
`StoreKpiIntegrationTest`, выполненные с реальным локальным PostgreSQL Testcontainer и
`skipped=0`. Полный frontend check прошёл contracts, lint, `199/199` tests и production build.
Локальный `visual:local` повторно прошёл на desktop/tablet/mobile; актуальные READY/PARTIAL
captures просмотрены вручную, включая исправленное выравнивание мобильного заголовка. Скриншоты
остались ignored runtime artifacts и в репозиторий не добавляются.

Composite backend gate дополнительно подтвердил supply-chain и checkstyle; shell-security и
OpenAPI compatibility выполнены отдельными штатными скриптами из-за различий локальных runner
images. Полный production-equivalent rerun остаётся обязательным преддеплойным действием и не
объявляется завершённым этим feature-closeout.

### Результат P7-D

`DEFERRED`. Deterministic-first release остаётся единственным разрешённым вариантом: AI planner,
worker и generation выключены. AI release-safety, targeted backend tests, offline shadow-plan и
локальный eval прошли без paid provider request. Staging canary, privacy/cost approval и production
AI endpoint не выполнялись и не заявляются.

### Результат P7-E

`PASS_WITH_LIMITS`. После пользовательского review исправления интерфейса приняты, повторный
локальный capture пяти сценариев прошёл `15/15` на desktop/tablet/mobile и был просмотрен вручную.
Иерархия «итог → действие → основание → сотрудник», раскрытие плотных списков и нейтральное
объяснение незаполненных смен соответствуют согласованному baseline. Отдельного таймированного
исследования с назначенным менеджером без подсказок не проводилось.

### Результат P7-F

`STOP` после полезной, но неполной локальной репетиции. В изолированной Docker-сети чистый target
успешно получил ожидаемую migration boundary; candidate API, worker и web прошли health/read path.
Локальные OCI revision backend/web совпадают с reviewed runtime commit. Custom dump прошёл
шифрование, неизменный checksum, restore в новый PostgreSQL target и сверку Flyway/schema/
технических агрегатов; candidate API и worker запустились поверх восстановленного target.

Gate не закрыт: это был пустой локальный rehearsal target, а не fresh production backup; staging,
production deploy path, ACL/HTTPS/Prometheus/real queues и измеренные RPO/RTO отсутствуют. Exact
previous production runtime не доступен локально, registry pull завершился сетевым timeout, поэтому
двухшаговый rollout и application rollback на совместимой exact pair не подтверждены. Статический
deploy release-safety test прошёл, но runtime rehearsal не заменяет.

### Результат P7-G

`STOP` до production read-only обращения. Candidate images не опубликованы по immutable registry
coordinates; отсутствуют exact host/release-env доступ, свежие live Flyway/health/queue данные,
production backup checkpoint и operations/security sign-off. P7-C также не дал естественный
real-data `READY`, а P7-F не доказал exact previous-runtime rollback. `project-state.md` не
обновлялся и его прежнее наблюдение не выдаётся за fresh preflight.

P7-H/P7-I имеют статус `NOT STARTED`: production rollout требует нового точного подтверждения
конкретного release plan после закрытия stop-условий, а post-release observation возможно только
после фактического rollout.

## Открытые решения

- Отдельно диагностировать, какие реальные data-quality/classification ограничения препятствуют
  `READY`; возможный classification-пакет не подмешивать в этот manifest.
- Если AI понадобится после deterministic-first выпуска, открыть отдельный `VERIFIED` путь с
  privacy, cost, concurrency и paid staging gates; текущий verdict — `DEFERRED`.
- Получить natural `READY` и локальный live v13 visual без расширения разрешённого data scope либо
  отдельно согласовать новый scope.
- Опубликовать reviewed candidate images и выполнить release-equivalent rehearsal с exact previous
  runtime и свежим backup/restore evidence.
- Legacy cleanup не входит в этот кандидат и рассматривается только после периода наблюдения P7-I.

## Критерий закрытия

Документ закрывается после выпуска или отказа от кандидата, когда фактический change set и результат
воспроизводимой проверки извлечены в immutable release evidence. До этого manifest остаётся рабочим
контрактом границы.

## Результат извлечения

Ожидается.
