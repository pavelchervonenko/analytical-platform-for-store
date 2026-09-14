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

Manifest фиксирует P7-A: минимальный deterministic-first change set для раздела «ИИ-разбор».
Кандидат собирается в отдельном worktree от подтверждённой production-базы, указанной в
[`project-state.md`](../current/project-state.md). Текущая рабочая ветка с параллельными изменениями
не является базой сборки и не переносится целиком.

Этот документ не разрешает staging или production rollout. Платный AI-path, миграции, синхронизация
LiveSklad и изменения других продуктовых разделов находятся вне границ кандидата.

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

## Открытые решения

- В P7-C определить, достаточно ли production-классификации для реального `READY`. Если нет,
  classification выпускается отдельным обозримым пакетом, а не подмешивается в этот manifest.
- В P7-D отдельно решить судьбу AI-enabled пути на основании privacy, cost и concurrency gates.
- Legacy cleanup не входит в этот кандидат и рассматривается только после периода наблюдения P7-I.

## Критерий закрытия

Документ закрывается после выпуска или отказа от кандидата, когда фактический change set и результат
воспроизводимой проверки извлечены в immutable release evidence. До этого manifest остаётся рабочим
контрактом границы.

## Результат извлечения

Ожидается.
