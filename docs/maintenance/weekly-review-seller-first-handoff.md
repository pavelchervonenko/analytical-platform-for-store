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

## Интеграционный checkpoint 2026-10-01

По запросу владельца весь новый код основного рабочего дерева сохранён в
`codex/release-integration-20261001`, коммит `f95af8f` (195 изменённых файлов).
Это checkpoint исходников, **не разрешение на deployment**. Родитель `f6b6e34`
содержит ранее сохранённый большой seller-first пакет. Незавершённые функции не
становятся завершёнными от включения в Git.

Отдельные ветки `codex/catalog-prod-base-20261001` (`4d1cab2`) и
`codex/seller-ai-guard-20261001` (`40e980f`) сохранены без переписывания.
Три изменённых Java-файла paid-call guard совпадают с AI-веткой. Каталожная ветка
создана от прежнего production-base и не является полным интеграционным кандидатом.
В частности, её `MigrationApplication` и `CatalogMigrationPreflight` отличаются:
там есть reviewed rollout с отпечатками исторических строк, а в общем checkpoint
сохраняется отказ миграции заполненной БД. Этот предохранитель не снимался.
Нельзя считать каталожную ветку целиком интегрированной только по совпадению UI.

### Выполненные локальные проверки

- Node 22: `npm --prefix frontend run check` — PASS: transport types, lint,
  61 файл / 309 тестов, TypeScript и production-сборка.
- `python3 -m unittest discover -s scripts/tests -p 'test_*.py'` — PASS:
  191 тест, 10 skipped; skipped не считаются выполненными проверками.
- `bash scripts/tests/catalog-release-safety-test.sh` — exit 0.
- `python3 scripts/check-documentation.py --strict` — PASS после регистрации
  новых файлов в Git: 447 inventory rows, 0 baseline warnings.
- `git diff --cached --check` — PASS перед фиксацией checkpoint.
- `python3 scripts/tests/verify-gradle-supply-chain.py` — PASS:
  449 компонентов / 840 артефактов; проверка доверенных хешей не отключалась.
- `bash scripts/tests/security-hardening-test.sh` — PASS, включая release safety,
  bounded classification correction и weekly AI release safety.
- Локальный `npm run visual:local` с fixture API на loopback, маршруты
  `/admin?adminView=catalog-reviews` и `/insights`: 6/6 PASS.
  Desktop/tablet/mobile изображения просмотрены: поля и кнопки доступны,
  наложений содержимого не обнаружено. Дополнительный маршрут
  `/insights?reviewScenario=seller-ready`: 3/3 PASS.
  Это проверка на fixtures, не end-to-end с настоящим backend;
  screenshots не включены в Git.

### Обязательные незакрытые gates

1. Полный Java 21 `:backend:check` **не прошёл**: online-запуск ожидал соединение
   с Maven и был остановлен; отдельный HTTPS probe также получил timeout.
   Offline-запуск завершился до тестов: отсутствует кэш
   `org.apache.groovy:groovy-bom:5.0.4`. Не снижать версии и не отключать
   dependency verification ради зелёного статуса. Повторить gate в окружении
   с доступом к зафиксированным зависимостям.
2. Интегрировать и проверить именно путь миграции общего кандидата, включая
   seller-инвалидации и историю состава. Проверку отдельной каталожной ветки
   нельзя выдавать за rehearsal общего дерева. Нужны fresh read-only preflight,
   backup/restore, сравнение исторических финансовых фактов и репетиция rollback.
3. Не включать постоянный seller AI planner до реализации и проверки требований
   ADR-0004. В текущем коде история состава ещё не подключена к агрегатам;
   durable backlog и historical period API остаются незавершёнными.
4. Согласованный в плане передачи способ доставки без GitHub требует реализации
   и проверки отдельной процедуры: существующий production runbook и image
   guards используют GHCR. Обход provenance/checksum-проверок недопустим.
5. До передачи разработчику заполнить фактические пути, доступы и владельцев
   по [плану передачи](project-handover-blueprint.md), подготовить проверенную
   копию исходников с Git-историей и документацией вне production-хоста.

На этом этапе не выполнялись push, deployment, обращения к production,
изменение данных или платные AI-вызовы. Текущие runtime-флаги не перепроверялись.
Дальнейшие разделы сохраняют подробные критерии; старые утверждения о
незарегистрированных файлах относятся к состоянию до этого checkpoint.

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

Решение владельца продукта от 2026-10-01: состав аналитических категорий больше не меняется;
целевой постоянный режим включает и недельный seller-only отчёт, и платное AI-дополнение.
Это решение о целевом поведении, а не разрешение на provider call, production flags или
deployment. Фактическое применение сентябрьских назначений категорий и новые проверки
исторических sales/returns snapshots подтверждаются отдельно.
Срочный выпуск 2026-10-01 ограничен уже реализованным seller-only контуром и защитой
от повторного платного вызова; исторический состав и автоматическое восстановление
пропущенных недель остаются следующим обновлением, а не заявленной возможностью этого релиза.

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
и build прошли повторно; release-safety повторно зелёный, operator security shell tests
прошли ранее.
Documentation unit tests (25/25), обычный strict check и общий git diff --check зелёные
на текущем движущемся дереве. Synthetic seller-action/STALE visual:local
прошёл desktop/tablet/mobile (6/6), изображения просмотрены без обрезания; это не live UI.
Fixture периода приведён к реальному формату дат.
Новый V87 DB-регрессионный тест отдельно скомпилирован JDK 21 и выполнен через
JUnit/Testcontainers на локальном PostgreSQL вместе с четырьмя миграционными тестами:
5/5 на текущем наборе миграций до V88, включая upgrade со старых baseline и
least-privilege migration. Проверены однократная инвалидация двух магазинов при upgrade
и отдельная инвалидация только затронутого магазина при позднем INSERT роли.
V88 добавлена параллельной каталожной работой; её семантическая эквивалентность и
производительность остаются отдельным gate. Это targeted check, не полный gate.
Backend compile/check по-прежнему не подтверждён: offline-кеш не содержит plugin marker;
повторный сетевой Gradle resolver не завершился из-за нестабильного доступа к репозиториям.
Прежнее временное зеркало дошло до compileClasspath, где не хватило семи зафиксированных
transitive artifacts. Это блокировка проверки, не доказательство дефекта исходников.
Финальный backend check после завершения категорий по-прежнему обязателен.

Прогон 2026-10-01 после фиксации состава категорий: frontend contracts/lint, 309 tests и build,
documentation unit tests 25/25, strict check, AI offline evaluation 9/9 и git diff --check прошли.
Полный backend check под JDK 21 не дошёл до компиляции: online Gradle ждал сетевое соединение,
HEAD к Maven Central завершился timeout; offline resolver назвал отсутствующий
org.apache.groovy:groovy-bom:5.0.4. Это инфраструктурный блокер полного gate, не результат
тестирования backend-кода.

Дополнительная read-only сверка 2026-10-01 выполнена по уже сохранённому production-снимку
на 09:47 МСК: сентябрь закрыт, в двух магазинах 3604 активные позиции и 1496 документов.
Отсутствующих ссылок на категории/назначения, расхождений с явными назначениями и
различий категорий у 142 связанных позиций возвратов не найдено. Четыре позиции остаются
в `UNMAPPED`; 897 позиций без явной assignment-ссылки могут быть результатом автоматической
классификации и сами по себе не являются ошибкой. Проверка не доказывает полноту LiveSklad
и не разрешает ретроактивное применение будущих категорий. Повторный frontend gate прошёл
(309 тестов, lint/contracts/build); documentation units — 25/25, `git diff --check` — green.
Обычный strict documentation check ожидает Git-регистрацию ADR-0004. Backend check по-прежнему
не достиг компиляции: offline resolver не нашёл Groovy BOM, online повтор остановлен после
длительного ожидания сети. Production writes и provider calls не выполнялись.

## Незакрытые работы — порядок для следующего разработчика

### 1. Согласовать границу категорий и seller-аналитики

Ответственные: backend и владелец категорий. Пока категории меняются, не останавливать работу
ради «чистого дерева», но не объявлять прежние тесты финальным gate.

- Для каждой новой категории/attach-роли сверить денежную группу, участие в допах и attach,
  возвраты, неполную классификацию и неизменность несвязанных формул по
  [матрице категорий](catalog-metrics-matrix.md). Категория сама по себе не доказывает attach-роль.
- Сентябрьский catalog preview — только кандидаты, не применённый исторический пересчёт.
  По утверждённому prospective-правилу новые категории включаются с будущей датой активации:
  массово менять сохранённые сентябрьские sales/returns snapshots нельзя. Проверить фактические
  назначения и связь возвратов с исходными продажами; исправление доказанных ошибок истории —
  отдельное согласованное решение, а не часть запуска новых категорий.
- Локальный read-only snapshot на 2026-09-30 22:46 МСК: два магазина, 3501 активная строка
  сентября; отсутствующих ссылок на category и несовпадений category/assignment в нём нет,
  как и несовпадений категории у связанных возвратов. Есть 4 UNMAPPED и 2 возврата без
  исходной строки; 862 строки без явной assignment-ссылки являются автоматически
  классифицированными, а не сами по себе ошибкой. Preview с кандидатами ничего не записал.
  Снимок сделан до конца сентября и не подтверждает финальные назначения 30-го числа или
  актуальное состояние сервера; нужна новая read-only сверка после закрытия периода.
- На одном roster/периоде до округления сверить Overview SELLERS и seller-v3: итоги, структуру,
  допы, персональный вклад и состояния качества. Проверить сотрудников вне рейтинга,
  операции без автора, добавление/уход, нулевую себестоимость и возвраты без достоверной
  атрибуции. STORE path должен оставаться независимым.
- Аудировать позднюю переклассификацию и новые подтверждённые attach-роли: должны ли они менять
  seller source identity, formula/policy version и создавать новую immutable revision.
  Статическая находка о поздней вставке catalog_sale_role_snapshots закрывается candidate-миграцией
  V87: INSERT помечает seller source затронутого магазина, а однократное событие при upgrade
  переводит старые checkpoint-ы из CURRENT в STALE после смены attach-проекции в V86.
  Двухмагазинный DB-регрессионный тест прошёл локально; интегрированная проверка отсутствия
  ложного CURRENT/content reuse в полном backend gate ещё обязательна. Это не доказанный production-дефект.
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
- Проверить недельную непрерывность: scheduler сейчас выбирает только последнюю закрытую неделю.
  Если источник остаётся неготовым до следующего понедельника, пропущенный период автоматически
  не вернётся в очередь. Владелец подтвердил автоматическое восстановление. Реализация требует
  durable ограниченного backlog со статусами/повторами, проверки полного coverage именно
  пропущенной недели и API/истории для чтения восстановленного отчёта. Нельзя просто итерировать
  недели с нынешним current-roster reader: состав на прошлую дату неизвестен. Восстанавливать
  только периоды начиная с достоверной даты включения temporal membership; до неё показывать
  «исторический состав неизвестен», не выдавать текущий состав за исторический. Продавца, который
  позже ушёл или исключён из рейтинга, оставлять в отчёте его недели с пометкой
  «не в текущей команде». Тестировать задержку через две и более границы недель.
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
Миграции V87/V88 повышают live schema: до выпуска проверить, что previous image runtime schema
range принимает точную новую версию. Если нет, прежний image-rollback будет штатно отклонён; подготовить совместимый
rollback executable либо forward-fix. Выключение seller flag на том же executable возвращает
v2-read preference, но не является откатом миграции или всего приложения.
После sanitized runtime evidence обновить project-state, но не раньше.

Критерий: менеджер получает seller-only отчёт автоматически при полном источнике; при
неполном видит понятное состояние, а оператор — причину и путь восстановления.

### 5. Подготовить отдельно автоматическое платное AI-дополнение

Ответственные: продукт, AI/privacy и operations. Целевой режим с автоматическим платным AI
подтверждён владельцем продукта 2026-10-01; это не блокирует детерминированный отчёт и
не является разрешением на сетевой canary или включение planner/worker.
Провести network-free seller preflight, проверить exact version/scope, allowlist, evaluation,
стоимость и rollback; затем запросить отдельное разрешение на ограниченный платный canary по
[AI runbook](../runbooks/weekly-review-ai.md). Только после успешного canary решать, включать
ли AI planner/worker автоматически. Сбой AI не должен скрывать готовый отчёт или менять KPI.
Владелец подтвердил не более одного автоматического платного вызова на магазин и неделю,
включая исправленные immutable revisions; повторный вызов возможен только через exact approved
operator path. Candidate теперь сериализует auto-enqueue блокировкой магазина, проверяет
существующий seller job любого статуса за эту неделю и ограничивает job одной попыткой провайдера.
После ограничения попыток JDK 21 компиляция прошла, а целевые интеграционные тесты
повторно завершились 11/11, включая одновременный enqueue разных revisions. Первый повтор
сорвался на timeout локального Testcontainers PostgreSQL; следующий повтор прошёл без правок кода. Это не включение flags и не разрешение на provider call; полный backend check
остаётся обязательным. Terminal FAILED job
не должен автоматически создавать новый платный job.
Этот handoff не разрешает provider call.

### 6. Выполнить историческое правило состава — этап Б

Локальный промежуточный статус 2026-10-01: V91, lookup, forward-only writer, ручной
переключатель и атомарный employee batch добавлены без активации baseline. Проверены clean
migration, restricted upgrade, ручные unit-тесты и адресный sync/departure/rehire/no-op
PostgreSQL integration (7 + 5 tests). Весь P2 ещё не закрыт: нужны race/lease и latency
проверки. Отдельная P3 document-level eligibility projection прошла synthetic PostgreSQL test:
продажи до/после выключения, связанный возврат, orphan, неизвестная история и удалённый
оригинал. Она пока не питает денежные/attach агрегаты; P3 в целом, P4–P11, period API,
durable backlog и UI остаются невыполненными. Нельзя включать автоматическое
восстановление недель или называть seller-v3 исторически точным.

Ответственные: продукт и backend. Это не gate current-roster этапа А, но подтверждённое
требование целевого продукта: ручное выключение participatesInRanking действует только с даты
изменения; прежние продажи и возвраты по исходной продаже сохраняют исторический seller scope.
Этап А такой гарантии не даёт. Владелец подтвердил: до достоверной даты начала записи
истории состава не восстанавливать старые недели как точные; ушедшего/исключённого позднее
продавца сохранять в историческом отчёте с пометкой «не в текущей команде».
Backdated correction остаётся отдельным открытым вопросом. Выполнить temporal plan,
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
