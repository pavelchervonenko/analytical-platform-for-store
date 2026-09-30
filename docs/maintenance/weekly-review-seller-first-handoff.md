---
doc_schema: 1
doc_type: working
status: draft
owner: project
audience:
  - developer
  - product
  - operator
created_at: 2026-09-30
review_by: 2026-10-07
source_material:
  - docs/maintenance/weekly-review-seller-analytics-design.md
  - docs/maintenance/catalog-metrics-matrix.md
  - docs/current/ai/weekly-review.md
  - docs/current/product/business-metrics.md
  - docs/current/project-state.md
  - docs/runbooks/weekly-review-ai.md
  - docs/runbooks/production-deployment.md
  - docs/runbooks/application-rollback.md
  - backend/src/main/resources/application.yml
  - backend/src/main/java/com/storeanalytics/interpretation/web/SellerWeeklyReviewController.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/SellerWeeklyReviewSnapshotPlanner.java
  - frontend/src/insights/WeeklyReviewView.tsx
required_reviewers:
  - product
  - backend
  - frontend
  - operations
exit_target: archive
---

# Передача seller-first «ИИ-разбора»: состояние и путь до рабочего контура

## Цель и границы

Это карта передачи на 2026-09-30, а не подтверждение работающего окружения или разрешение на
deployment. Проверенное состояние production читается только из
[project-state](../current/project-state.md) после новой обезличенной runtime-проверки.
В рабочем дереве одновременно идут изменения аналитических категорий и seller-first Weekly Review.
Прежний зелёный прогон нельзя автоматически переносить на их итоговую комбинацию.

Ближайшая цель: менеджер автоматически получает отчёт за последнюю завершённую неделю
только по продавцам рейтинга. Неполный либо нестабильный источник даёт честное ожидание
или ограниченный вывод, а не ложную уверенность. Детерминированный отчёт работает без
внешнего AI. Опциональное AI-дополнение и исторический состав продавцов — отдельные
контрольные точки; их не считать готовыми вместе с запуском детерминированного отчёта.

## Что подтверждено

- Candidate-код содержит отдельные seller-v3 GET и ADMIN generate, versioned snapshot/read path,
  seller scheduler, optional seller AI path и frontend reader. Legacy v2 STORE сохранён для
  отката и подписан как результат всего магазина. Наличие кода не доказывает активацию:
  parent, seller, snapshot-planner и AI flags управляются отдельно.
- Результаты, структура, допы и команда v3 строятся из общего seller facts bundle. Этап А
  использует текущий рейтинговый roster для обеих сравниваемых недель. Отсутствующие смены
  ограничивают workload, не всю финансовую картину. Возврат без установленной связи с
  продавцом не приписывается ему; нулевая себестоимость допустима. Snapshots immutable.
- После последней коррекции А прошли полный backend test, отдельный Checkstyle, полный frontend
  check и локальные синтетические desktop/tablet/mobile снимки. Полный backend check после
  коррекции и параллельных изменений категорий не подтверждён. Обычный strict documentation
  check ожидает Git-регистрацию уже внесённых в inventory документов. Подробный журнал — в
  [seller design](weekly-review-seller-analytics-design.md).
- Production Compose передаёт отдельный seller flag с выключенным default; release-safety
  проверяет boolean и зависимость от parent. Это подготовка безопасного canary, не
  активация seller режима.
- Последняя записанная production-проверка относится к более раннему deterministic-first
  baseline. Не выводить из неё, что seller-v3 или автоматическая публикация уже работают.

Промежуточный прогон 2026-09-30 на движущемся дереве: frontend contracts/lint, 309 tests
и build прошли; release-safety и operator security shell tests прошли. Backend compile
не подтверждён: стандартная Java слишком старая, а локальная JDK 21 в offline-режиме
упирается в неполный repository cache; временное зеркало дошло до compileClasspath, где
не хватило семи зафиксированных transitive artifacts. Обычный Maven fetch не дал
результата и был остановлен. Это блокировка проверки, не доказательство дефекта
исходников. Финальный
backend check после завершения категорий по-прежнему обязателен.

## Незакрытые работы — порядок для следующего разработчика

### 1. Согласовать границу категорий и seller-аналитики

Ответственные: backend и владелец категорий. Пока категории меняются, не останавливать работу
ради «чистого дерева», но не объявлять прежние тесты финальным gate.

- Для каждой новой категории/attach-роли сверить денежную группу, участие в допах и attach,
  возвраты, неполную классификацию и неизменность несвязанных формул по
  [матрице категорий](catalog-metrics-matrix.md). Категория сама по себе не доказывает attach-роль.
- На одном roster/периоде до округления сверить Overview SELLERS и seller-v3: итоги, структуру,
  допы, персональный вклад и состояния качества. Проверить сотрудников вне рейтинга,
  операции без автора, добавление/уход, нулевую себестоимость и возвраты без достоверной
  атрибуции. STORE path должен оставаться независимым.
- Аудировать позднюю переклассификацию и новые подтверждённые attach-роли: должны ли они менять
  seller source identity, formula/policy version и создавать новую immutable revision.
  Статическая находка: новые catalog_sale_role_snapshots читаются attach-проекцией, но их
  INSERT не перечислен среди триггеров seller source revision. Новые sales items могут
  инвалидировать отчёт другим триггером; для поздней вставки роли и смены семантики view
  это нужно доказать DB-тестом, иначе добавить инвалидацию/новую policy version.
  Проверить отсутствие ложного CURRENT/content reuse; дефект runtime ещё не доказан.
- Составить reviewable change set с разделением seller-first, каталога и соседних правок.
  В дереве есть незарегистрированные файлы; не применять широкое git add -A без проверки
  владельцев изменений. Если дерево меняется, фиксировать точный проверенный candidate.

Критерий: согласованные матрица показателей, parity/инвалидационные тесты и перечень файлов
candidate. Семантическое изменение persisted отчёта требует новой версии, не изменения старого.

### 2. Закрыть локальные gates этапа А на одном candidate

Ответственные: backend, frontend и независимые reviewer-ы.

- Повторить полный ./gradlew :backend:check, frontend npm run check, документационные unit tests,
  обычный python3 scripts/check-documentation.py --strict и git diff --check на одном состоянии.
  Не ослаблять timeout, source fences или inventory ради зелёного статуса. Если Gradle блокирует
  Maven/offline cache, отделить инфраструктурную причину от ошибки кода.
- Проверить OpenAPI v2/v3 compatibility, миграции с поддерживаемого baseline, source-change race,
  no-op regenerate, stale read, failure/retry, rollback v2→v3→v2, seller AI privacy/semantic
  evaluation. Повторить независимые backend code review и UI review после итоговых правок.
- При материальном UI-изменении выполнить локальный frontend npm run visual:local и вручную
  посмотреть desktop/tablet/mobile. Не использовать удалённый VISUAL_BASE_URL и не сохранять
  business-data screenshots в репозитории.
- Зарегистрировать точный набор документов и runtime artifacts в Git. Временный isolated-index
  pass помогает диагностике, но не заменяет обычный strict gate. Commit/push — отдельные действия.

Критерий: все обязательные проверки зелёные на одном candidate; targeted tests не являются
полным release gate.

### 3. Локальный end-to-end детерминированного контура

Ответственные: backend, operations и владелец продукта.

- На локальной БД через штатные authenticated API проверить обе закрытые недели и непрерывный
  SUCCESS coverage SALES/RETURNS/ORDERS, стабильность источника, READY/PARTIAL/BLOCKED,
  idempotent generate и отсутствие ложной атрибуции. В evidence сохранять только обезличенные
  состояния/счётчики, без имён, сумм и screenshots. Если реальный READY недоступен, указать
  это честно и подтвердить его отдельно синтетическим тестом.
- Сверить локальный shadow с Overview SELLERS, права менеджера, legacy fallback,
  PREPARING/STALE UX и откат на совместимом executable. Отсутствующие смены не должны скрывать
  финансы; задержка синхронизации в понедельник не разрешает публиковать неполный числовой итог.
Критерий: локальный authenticated seller GET/generate и UI показывают корректный результат
или честное ожидание; rollback проверен до обращения к серверу.

### 4. Передать проверенный candidate на сервер и провести canary

Ответственные: release owner и operations. Только после стабильного candidate и
отдельного разрешения: согласовать состав commit, выполнить push в нужный remote,
собрать и развернуть тот же проверенный commit по
[production deployment runbook](../runbooks/production-deployment.md). Push исходников
сам по себе не разворачивает приложение и не включает seller flags. Сначала ограниченный
seller read canary, затем автоматический snapshot planner. Проверить в реальном приложении
последнюю закрытую неделю, состояния ожидания/ограничения, DEFERRED/reconciliation,
повторные проходы без лишних revisions и UI менеджера. Иметь проверенный
[application rollback](../runbooks/application-rollback.md) на совместимый executable
с явно подписанным v2 STORE; не удалять v3 snapshots и не отключать source writers.
После sanitized runtime evidence обновить project-state, но не раньше.

Критерий: менеджер получает seller-only отчёт автоматически при полном источнике; при
неполном видит понятное состояние, а оператор — причину и путь восстановления.

### 5. Отдельно принять решение об автоматическом AI

Ответственные: продукт, AI/privacy и operations. Это не блокирует детерминированный отчёт.
Провести network-free seller preflight, проверить exact version/scope, allowlist, evaluation,
стоимость и rollback; затем запросить отдельное разрешение на ограниченный платный canary по
[AI runbook](../runbooks/weekly-review-ai.md). Только после успешного canary решать, включать
ли AI planner/worker автоматически. Сбой AI не должен скрывать готовый отчёт или менять KPI.
Этот handoff не разрешает provider call.

### 6. Выполнить историческое правило состава — этап Б

Ответственные: продукт и backend. Это не gate current-roster этапа А, но подтверждённое
требование целевого продукта: ручное выключение participatesInRanking действует только с даты
изменения; прежние продажи и возвраты по исходной продаже сохраняют исторический seller scope.
Этап А такой гарантии не даёт. До реализации Б закрыть продуктовые решения об ушедших
сотрудниках, backdated correction и недоказуемой предыстории, затем выполнить temporal plan,
миграции, warm-up и отдельный canary из
[seller design](weekly-review-seller-analytics-design.md). Для первого запуска А явно принять
или отклонить временное ограничение current-roster.

## Стоп-условия и завершение передачи

- Не включать seller mode, auto planner или платный AI на основании наличия классов и прежних
  targeted tests. Не показывать v2 STORE как SELLERS.
- Не объявлять READY без coverage; не оценивать по часам без подтверждённых смен и не делать
  персональный вывод из возврата с неизвестным автором.
- Не переписывать опубликованные prompts/schemas и immutable snapshots. Не менять
  production/staging или provider flags без отдельного допуска.
- Начать с этого handoff, затем читать действующий
  [Weekly Review contract](../current/ai/weekly-review.md),
  [business metrics](../current/product/business-metrics.md), подробный
  [seller design](weekly-review-seller-analytics-design.md),
  [AI runbook](../runbooks/weekly-review-ai.md) и
  [project-state](../current/project-state.md). Исторические логи design — не текущий runtime.

Закрыть handoff можно после согласованного candidate, выполненных gates, принятого
детерминированного canary и переноса действующих фактов в current/decision/runtime evidence.
Решение по AI и очереди Б фиксируется явно. Результат извлечения: ожидается.
