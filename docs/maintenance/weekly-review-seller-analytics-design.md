---
doc_schema: 1
doc_type: working
status: draft
owner: product
audience:
  - product
  - developer
created_at: 2026-09-20
review_by: 2026-10-04
source_material:
  - customer-review:conversation-2026-09-20
  - docs/maintenance/weekly-review-manager-experience-plan.md
  - docs/maintenance/weekly-review-implementation-plan.md
  - docs/current/ai/weekly-review.md
  - docs/current/product/business-metrics.md
  - docs/current/product/employees-and-rating.md
  - docs/current/frontend/overview.md
  - docs/decisions/ADR-0002-overview-period-scope.md
  - backend/src/main/java/com/storeanalytics/metrics/service/OverviewMetricsService.java
  - backend/src/main/java/com/storeanalytics/metrics/service/EmployeeKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/service/EmployeeCategoryKpiService.java
  - backend/src/main/java/com/storeanalytics/metrics/repository/EmployeeKpiRepository.java
  - backend/src/main/java/com/storeanalytics/performance/repository/StorePlanDailyActualRepository.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewFactsSource.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewEmployeeFactsReader.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewSnapshotStore.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewSnapshotCodec.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewSnapshotPlanningService.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiInputCompactor.java
  - backend/src/main/java/com/storeanalytics/employee/model/EmployeeStoreAssignment.java
  - backend/src/main/java/com/storeanalytics/performance/service/EmployeeRatingSettingsService.java
  - backend/src/main/java/com/storeanalytics/sync/service/EmployeeSyncPersistence.java
  - backend/src/main/java/com/storeanalytics/sync/service/ReturnSyncPersistence.java
required_reviewers:
  - product
  - backend
  - frontend
  - ai-semantic
exit_target: decision-and-current-contracts
---

# Seller-first аналитика для «ИИ-разбора»: архитектура и краевые случаи

## Статус и назначение

Это рабочий архитектурный документ: подтверждённые решения этапа A реализованы в candidate-коде
и локально проверены, но режим не включён в production/staging. Действующие API, AI и UI контракты
описаны в `docs/current/`. Предложения temporal-этапа B ниже остаются проектом, не реализованным
поведением. Исторические записи проверок сохранены для аудита; актуальный статус — в последнем
closure pass этого раздела.

## Цель

### Промежуточный пакет A5/A6 — 2026-09-29, до closure pass

Цель пакета остаётся вкладкой «ИИ-разбор», не temporal-переходом B. Добавлены отдельные seller
API/read/write adapters и feature flag, bounded snapshot scheduler, version-isolated AI input5/
prompt v26, current-only AI gates и frontend reader v3 с отдельным cache key. STORE «Обзора» и
legacy v2 read/codec сохраняются. История участия и forward-only **не внедрены**.

Code review обнаружил и исправил конфликт OpenAPI имён v2/v3 карточек, выбор v3 snapshot прежним
STORE AI planner, отсутствие current-source gate в seller AI worker и некорректный terminal
transition receipt при stale ответе. Отдельный v3 coverage DTO показывает ORDERS без изменения
v2 enum. Изменение формы seller coverage отражено в новой snapshot policy identity, чтобы прежний
checkpoint не выдавался за расчёт по новой проекции. Immutable snapshots не обновляются.

Проверенные frontend gates: 48 выбранных contract/UI tests; полный `npm run check` — 298 tests,
lint/contracts/build green. Первый `visual:local` — 9/9 synthetic seller-partial/negative/stale
scenarios на desktop/tablet/mobile; снимки просмотрены. После UI review исправлены склонение
количества продавцов и объяснение пустого рейтинга; финальный visual повтор также прошёл 9/9.
Fixture сформирован backend assembler на synthetic data, а не получен из бизнес-данных.

Последний завершённый полный backend run: 1628 tests, два failures — query timeout в synthetic
load и устаревший OpenAPI assertion из-за конфликта имени карточки. Последующий адресный run:
119 tests, один failure в **новом** mixed-version AI fixture: он ошибочно пытался создать
revision2 без supersedes и изменить immutable snapshot. Fixture исправлен на additive INSERT
следующей revision без снятия DB triggers. Повтор финального addressed пакета: 28 tests,
0 failures/errors/skips, включая реальную session/CSRF аутентификацию, seller API/Overview parity,
manager access/write restrictions, идемпотентный generate и STALE→CURRENT после изменения состава.
Отдельный повтор Checkstyle main/test прошёл без замечаний. Authenticated OpenAPI generator и
compatibility check с versioned baseline также прошли; current artifact и transport types
перегенерированы. Эти результаты не означают зелёный полный backend gate.

Открытые A6 gates: повтор полной проверки финального backend source, воспроизводимое разрешение
load timeout, complete seller AI regressions/evaluation и rollout/rollback режима целиком.
Documentation unit checks: 25/25; `git diff --check` green. Strict documentation gate также
должен учитывать зарегистрированные, но ещё untracked документы; их нельзя скрывать исключением
из inventory: сейчас восемь таких ошибок, без других documentation violations. Automatic
publication, внешние provider calls, commit/push/deploy не выполнялись. Финальный frontend check
после regenerated OpenAPI и latest backend synthetic fixture также прошёл: contracts/lint,
298 tests и build green. Полное закрытие A6 по-прежнему не утверждается.

Фактический статус не считать закрытием всего этапа A. Контрактные детали API/UI/AI вынесены в
`docs/current/ai/weekly-review.md` и rollout/rollback — в `docs/runbooks/weekly-review-ai.md`.

### A6 closure pass — 2026-09-29, локальный кодовый gate пройден

Адресный повтор `fullReadsStayBoundedAndCardLimitPreservesAllSellerTotals` прошёл на прежнем
synthetic объёме с неизменённым SQL timeout; прежний сбой полного run не объявлен исправленным
до успешного повторения всего suite. Для offline Gradle понадобились только временные init scripts
в `/tmp`: в прежнем локальном Maven mirror потерялись три POM symlink targets, публичный Maven
из этой среды недоступен. Уже кэшированные implementation jars используются без изменения
dependency locks, репозитория, глобальных настроек WSL/Gradle и production.

Добавлены network-free seller v26 semantic cases: допустимый mixed/PARTIAL выбор и отклонение
чужого focus, недопустимого selector, пропуска/перестановки factor и неверного направления
summary. Worker regressions проверяют stop до provider, fail-closed freshness read и сохранение
оплаченного receipt без публикации при source change после ответа. Отказ optional AI storage
теперь оставляет deterministic seller report `CURRENT` с AI `UNAVAILABLE`; адресные тесты и
Checkstyle main/test прошли. Повторный полный frontend `npm run check`: 61 suites / 298 tests,
contracts/lint/build green. Documentation units: 25/25; strict inventory проходит в отдельном
временном Git index после `intent-to-add` восьми уже зарегистрированных документов. Настоящий
index не менялся: до Git-регистрации этих файлов обычный strict command закономерно сообщает
восемь `inventory expects tracked file`. `git diff --check` green.

Первый полный `:backend:check` этого прохода остановлен после failure
`SellerWeeklySourceIdentityTest.lightweightProjectionPreservesOriginalCanonicalBytes`: test golden
ещё фиксировал hash для snapshot policy v14, тогда как новая форма seller coverage намеренно
использует v15. Байты пересчитаны независимо: прежний expected hash точно соответствует v14;
обновлён только synthetic golden v15, адресный тест и Checkstyle прошли. Seller-focused
regression прошёл: 144 tests / 20 classes, включая прежний load scenario; повторный полный
`:backend:check` завершился успешно: 381 classes / 1659 tests, 0 failures/errors/skips.
Нагрузочный класс выполнен в общем suite (11 tests), в том числе ранее проблемный full read.
Таким образом, один и тот же synthetic load прошёл адресно, в seller-срезе и в полном suite без
изменения statement timeout или объёма fixture. Причина единичного старого timeout не доказана,
поэтому эти измерения не объявляются production SLA. XML подтвердил также authenticated seller API/
Overview parity и v2/v3 rollback chain. Local visual 9/9 из
предыдущего пакета остаётся применимым: frontend UI в closure pass не менялся. Автоматическая
публикация, provider call, production/staging, commit/push/deploy не выполнялись.

**Вердикт:** реализация и локальные code/frontend/backend/AI проверки этапа A завершены.
Формальный release gate A6 не закрыт только из-за обычного strict documentation check:
восемь уже зарегистрированных документов (три этого этапа и пять соседней работы) ещё
untracked в реальном Git index. Проверка с изолированным intent-to-add index проходит
без нарушений; нельзя скрывать эти файлы исключением из inventory или трогать чужой index.
При подготовке согласованного commit все восемь должны войти в отслеживаемый набор, после
чего повторяется обычный strict check. Включение автоматической публикации/платного AI
и production rollout — отдельное решение, не следствие локального green gate.

### Коррекция по ревью этапа A — 2026-09-30

Предыдущий closure pass подтверждал общие gates, но два расхождения с принятым контрактом
потребовали адресной коррекции. Неизвестная полнота смен больше не переводит весь seller-v3
отчёт в `PARTIAL`: при полном source coverage и отсутствии иных проблем основной результат и
команда могут быть `READY`. Смены, часы, выручка в час и peer benchmark остаются недоступными;
менеджеру показывается локальное объяснение. При минимум шести завершённых продажах **в каждой**
из двух недель существенное снижение подтверждённой личной финансовой метрики может создать
персональное действие. Неполная классификация ограничивает только выводы по допам; недоступная
атрибуция возвратов ограничивает return/net-зависимые метрики и исключает персональное финансовое
действие до выяснения связи. Это сохраняет возможность оценивать продавцов без дисциплины
заполнения смен, но не выдаёт оценку по часам без доказательств.

RETURN без доступной исходной продажи (`ORPHAN_RETURN`) и RETURN, привязанный к продаже без
автора (`RETURN_ORIGINAL_AUTHOR_UNKNOWN`), теперь считаются отдельно по неделям. Их нельзя
автоматически распределять по продавцам рейтинга. Они дают nonblocking seller-scoped предупреждения,
`PARTIAL` и `LIMITED` только у зависимых показателей; продажная выручка и число продаж не
скрываются. Общий заголовок не объявляет неделю лучше или хуже по одной оставшейся метрике,
пока чистый итог предварителен. Поздняя привязка исходной продажи должна создать новую
ревизию snapshot, а не изменить старую. Существующие правила формул, temporal fence и legacy v2 не менялись.

Для отличия нового решения от старых неизменяемых snapshots версия seller-v3 snapshot policy
обновлена до `weekly-snapshot-v16`, quality policy — до `weekly-quality-v10`; metrics policy
сохраняется. Добавлены synthetic unit и DB-integration regressions для `READY`, `PARTIAL`,
двух типов возврата, поздней привязки, отсутствующих смен, малой выборки и неполной классификации.
Адресный backend run с Checkstyle и полный frontend check прошли. Прежний A6 closure
не следует использовать как доказательство для изменённого source.

Проверки коррекции в локальном worktree: полный `:backend:test` — 384 класса,
1719 тестов, 0 failures/errors/skips; адресный DB writer integration — 23/23, включая
v2→v3→v2 rollback и source fence. Адресные seller-тесты прошли. Полный frontend
`npm run check` — 61 suites / 299 tests, contracts/lint/build green; после дополнения
synthetic visual fixture lint повторён. `npm run visual:local` для seller-action прошёл 3/3
на desktop/tablet/mobile, все три снимка строки продавца просмотрены; удалённые URL и реальные
данные не использовались. Documentation unit checks — 25/25, `git diff --check` green.

Во время первого полного `:backend:check` параллельная работа каталога временно добавила семь
`LineLength` нарушений вне этого этапа. Поздний повтор `checkstyleMain` и `checkstyleTest`
уже прошёл, но общий `:backend:check` не повторялся после дальнейших соседних изменений
исходников: его нельзя объявлять зелёным на движущемся worktree. Обычный strict documentation
check ожидает Git-регистрацию файлов, уже внесённых в inventory, включая этот план и документы
соседних работ. Проверка содержимого на изолированном временном index с intent-to-add всех
таких файлов прошла: 434 inventory rows, 0 warnings; настоящий Git index не изменялся.
Перед релизом нужно согласовать и зафиксировать дерево, зарегистрировать документы и повторить
обычные `:backend:check` и strict documentation gate. Автопубликация, provider calls,
production/staging, commit/push/deploy здесь не выполнялись.

Weekly Review должен отвечать на вопрос, как сработали продавцы, включённые в рейтинг, как
изменился их общий результат и какие сотрудники повлияли на изменение. Операции сотрудников вне
рейтинга и операции без сотрудника не должны искажать управленческий вывод о продавцах.

Полный магазин остаётся внутренним reconciliation-контуром и отдельным пользовательским scope в
«Обзоре». Он не является вторым видимым контуром Weekly Review.

## Уточнение границ: первый этап и отложенная история участия

После уточнения основной проблемы выбран более узкий первый этап: устранить смешение STORE и
SELLERS, сохранив текущие правила отбора «Обзора». Не требуется сразу менять всю модель истории
сотрудников. Настоящий раздел задаёт порядок дальнейшей реализации; прежний P0–P11 ниже — проект
второго, temporal-этапа, а не обязательные предварительные работы для первого.

- **Этап A — общий текущий seller-контур.** Внутренний `SellerPeriodAnalyticsService` читает
  нормализованную локальную БД. Один неизменяемый набор employee IDs выбирается по текущим
  `employee.is_active && assignment.is_active && participates_in_ranking` для конкретного
  магазина. Этот набор применяется к обеим сравниваемым неделям. «Обзор» и Weekly Review получают
  отдельные проекции результата, а не вызывают API друг друга. Это внутренний сервис существующего
  backend, не отдельный микросервис, хранилище или новый процесс синхронизации.
- **Этап B — история участия.** Исторический состав недели, действие переключения только вперёд,
  историческое участие автора исходной продажи при возврате и decomposition JOINED/LEFT требуют
  отдельной временной модели. Эти требования сохраняются, но этап A их **не реализует**.
- **Явное ограничение A.** Выключение продавца не меняет уже сохранённый snapshot. Но новый расчёт
  старого периода по текущему составу исключит его операции и за даты до выключения — точно как
  текущий SELLERS в «Обзоре». Неизменяемость snapshot не равна исторической точности состава.
  Если forward-only нужен уже в первом релизе, A нельзя выпускать как полное решение: необходим B.
- **Граница сравнимости.** Совпадение обещается для одинаковых магазина, периода, состава, исходных
  данных и определения метрики. Старый опубликованный snapshot и живой «Обзор» после корректировок
  могут различаться. У snapshot показываются период и время формирования, основание состава
  доступно в деталях. Выбранная структура продаж в текущем Overview сохраняет selected scope;
  attach-map и отдельные store-category запросы не становятся SELLERS вслед за этим переходом.

### Контрольный прогон этапа A: краевые случаи и инварианты

Ревью основано на текущих `OverviewMetricsService`, `WeeklyReviewFactsSource`,
`WeeklyReviewRevenueRepository`, `WeeklyReviewEmployeeFactsReader`, `EmployeeAttachRateRepository`,
`ReturnSyncPersistence`, `WeeklyReviewSnapshotPlanningService` и snapshot codec/response.
Это проверка проекта решения по коду, а не выполненная runtime-проверка новой реализации.

| Случай | Правило первого этапа / обязательная проверка |
|---|---|
| Сотрудник принят, уволен, переведён или выключен | Один текущий набор IDs для обеих недель, включая нулевой результат выбранного продавца. Не выдавать изменение такого отчёта за точную историю состава или доказанное влияние найма/увольнения. |
| Совпадающие имена, несколько магазинов, повторный найм | Выбор по стабильному employee ID и целевому store ID, не по имени. Новый ID не объединять автоматически со старым. Старый ID после повторного найма использует текущую семантику, без выдуманного разделения стажей. |
| Нет продавцов / нет операций / не загружены данные | Три разных состояния. Пустой состав — понятное сообщение о настройке, без fallback на STORE; полное покрытие без операций — нулевые суммы; неполное покрытие — ограничения, не «продаж не было». |
| Неполная предыдущая неделя или нулевой baseline | Можно показать доказанные текущие суммы; нельзя рассчитывать надёжную динамику или бесконечный процент. Отсутствие сравнения не скрывает доступный текущий результат. |
| Возврат, включая return-only неделю | Сохраняются нормализованная атрибуция `document.employee_id`, дата возврата и знаки. Не использовать сотрудника, оформившего возврат, вместо автора продажи. Текущий неeligible автор исключён и в Overview, и в Weekly Review; historical return rule относится к B. |
| Нет исходной продажи / автора | Не назначать продавца произвольно и не включать неизвестный факт в seller total. Отразить ограничение атрибуции отдельно; не делать допустимый orphan безусловной причиной BLOCKED. Поздняя привязка может дать новую revision, не переписать старую. |
| Не заполнены смены | Продажи и вклад доступны. Нагрузка и показатели на смену недоступны; отсутствие смен не означает отсутствие работы или плохую работу продавца. |
| Себестоимость нулевая / отсутствует | Ноль остаётся допустимым значением. Отсутствующее значение не заменяется нулём; ограничиваются зависящие показатели, а не вся выручка. |
| Допы и attach-rate | Денежная доля допов и количественный attach — разные метрики с явными знаменателями. Сначала суммируются исходные величины выбранных продавцов, потом считается процент; нельзя усреднять личные проценты или суммировать уже обрезанные attach numerators. |
| Возвраты больше продаж, отрицательные суммы | Сохранить знаки и действующие formula-specific правила деления/ограничения. Не вводить единый «удобный» clamp для всех метрик. Неподходящую для круговой диаграммы структуру показывать числами с пояснением. |
| Различающиеся определения количества продаж | Weekly average использует своё число SALE-документов; completed-sales sample имеет дополнительные source/item фильтры. Общий сервис хранит оба факта явно, не подменяет один другим. |
| Неполный справочник или параллельный sync | Согласованный DB snapshot не доказывает завершённость многотранзакционного sync. Проверять пригодность источника и полноту обеих недель; не публиковать промежуточный состав как доказанно окончательный. |
| Состав изменён без новой синхронизации продаж | Planner учитывает roster fingerprint дополнительно к source/policy identity. Текущего сравнения `completedAt` недостаточно. Повторный запуск с тем же содержимым не создаёт новую revision. |
| Старый отчёт или более 100 продавцов | Старый STORE snapshot сохраняет свой смысл и hash. Лимит отображаемых карточек не ограничивает суммы, benchmark и расчёт вклада; скрытые карточки обозначаются явно. |

### Архитектурные ограничения этапа A

1. Один `SellerCohortSnapshot` выбирается внутри уже существующего `REPEATABLE_READ` чтения
   `WeeklyReviewFactsSource.load`; все money/category/revenue-count/attach/team readers используют
   именно его. Не делать новый выбор eligible отдельно в каждом reader или после лимита карточек.
   Сохранение revision остаётся отдельной короткой транзакцией с сериализацией генераторов.
2. Seller scope распространяется на summary, четыре core KPI, допы, структуру, decomposition,
   benchmark, evidence, actions и вход AI. Нельзя заменить лишь верхние карточки и оставить
   store-level причины изменения. Ограничения качества разделяются на seller-метрики и общую
   полноту источника; проблема себестоимости у исключённого сотрудника не обнуляет seller profit.
3. Действующие формулы не унифицируются насильно. В частности, weekly average, Overview ratios,
   sample sufficiency и attach имеют разные основания. Канонический слой переиспользует исходные
   суммы/counts и проверенные вычисления; consumer policy определяет свою интерпретацию.
4. Для текущего состава в attach facts уже есть store/date/employee и raw quantities.
   Для A не нужна temporal-миграция attach view или перенос классификации из нормализации.
   Использовать активную методику (legacy v3 либо attribution v4), а не закреплять seller reader
   на старом classifier. Правила гарантий и возвратов из ADR-0003 сохраняются; денежный автор и
   автор attach — разные измерения. Store-wide attribution quality не означает включение
   store-wide сумм/количеств. Не менять методику в рамках scope-перехода.
5. Не менять STORE, месячный target, алгоритм рейтинга, зарплату и ingestion. Scope SELLERS
   месячного плана остаётся по действующим правилам; месячный target не превращается в недельный.
   Общий сервис предоставляет именованные проекции/пакеты фактов: простой запрос Overview не должен
   автоматически запускать всю недельную аналитику, workload и AI или создавать N+1 запросы.
6. Для новой семантики нужен versioned snapshot/contract, но не membership-history migration.
   Сохранить точный v2 decode/hash и обеспечить отдельный новый codec, совместимые DB constraints,
   API/frontend readers и глобальную последовательность revisions. Новые DTO не должны менять
   сериализацию вложенных типов старого hash. Scope и основание текущего состава фиксируются в
   новом snapshot; roster fingerprint входит в semantic identity, timestamps не создают ревизию
   сами по себе. Название «Результаты продавцов» не применяется к старому STORE payload.
7. Факты фиксируются на момент согласованного чтения; не обещать, что snapshot включает запись,
   закоммиченную после этого момента. Проверить обнаружение последующих изменений состава,
   классификации, себестоимости, смен и атрибуции возвратов, включая изменения без sales sync.
   Пока этот invalidation-аудит не закрыт, автоматическую публикацию не включать. Конкретный
   минимальный механизм выбирается по действующим writer paths, без автоматического переноса
   полной инфраструктуры source fence из B. Повторное вычисление без изменения content должно
   иметь операционный checkpoint, чтобы не повторяться бесконечно на каждом тике планировщика.
8. Optional AI получает только разрешённые seller-агрегаты новой версии. Ответ/кэш прежнего STORE
   входа нельзя присоединять к SELLERS snapshot. AI не рассчитывает цифры, не расширяет состав и
   не придумывает причины; без него сохраняется полноценный детерминированный разбор.

### Порядок реализации A и контрольные точки

Каждый пакет завершается code review, тестами и сверкой с настоящими границами. Фактический статус
пакетов ведётся отдельно ниже; наличие внутреннего расчётного слоя не означает переключение
публичного Weekly Review или разрешение на автопубликацию.

1. **A0 — baseline и договорённости.** Зафиксировать CURRENT-roster семантику и её отличие от
   forward-only, точные определения метрик и quality states. Снять golden fixtures Overview
   SELLERS/STORE и старого snapshot; согласовать новую версию и совместимое чтение. Из открытых
   вопросов ниже для A нужны только решения о доступности/атрибуции фактов, не temporal defaults.
2. **A1 — общий расчёт без переключения.** Добавить текущий cohort selector и seller period facts;
   передавать один набор в оба периода. Переиспользовать существующие формулы/classifier, сохранить
   два вида sale count и raw attach. Доказать reconciliation сумм, категорий и персонального вклада
   до округления; измерить количество запросов и время полного чтения.
3. **A2 — parity с Overview.** Перевести только соответствующий SELLERS metrics path на проекцию
   сервиса при неизменном контракте. Проверить week/month/custom и отрицательные/пустые периоды,
   STORE, plan, rating и payroll regression. Не выдавать store-wide quality counters за seller-only.
4. **A3 — недельный контур.** Перевести все зависимые facts/evidence/AI input на общий результат.
   Реализовать versioned snapshot/read path; проверить старый hash, revisions, fallback без AI,
   отсутствие STORE-фактов в новом отчёте и отсутствие урезания сумм по лимиту карточек.
5. **A4 — стабильность и планировщик.** Проверить eligibility/source invalidation, no-op retry,
   параллельные генераторы, исправление источника во время чтения, частичный/failed employee sync
   и отложенную готовность второй недели. Не снимать проблему частичной синхронизации одним RR:
   нужен проверяемый stable-source gate либо отдельно согласованное исправление writer path.
6. **A5 — пользовательская совместимость.** Показать seller scope и дату snapshot без повторных
   предупреждений во всех блоках; различать старый формат, отсутствие состава и неполные данные.
   Проверить local desktop/tablet/mobile, включая детализацию допов и отрицательную структуру.
7. **A6 — ограниченный rollout.** Пройти полный backend/frontend/doc gates, shadow parity на
   одинаковом источнике и локальный authenticated прогон; доказать совместимый rollback чтения и
   записи разных версий. Только затем отдельно включать автоматическую публикацию с coverage
   gate и наблюдаемыми причинами пропуска. Production actions требуют отдельного разрешения.

**Готовность A:** одинаковые seller-факты у соответствующих потребителей, отсутствие store leakage,
сохранённые формулы/старые snapshots и пройденные проверки A0–A6. История участия и forward-only
не могут отмечаться выполненными в этом Definition of Done.

### Ход реализации первого кодового пакета (историческое наблюдение)

- **A0, baseline:** исходные Overview service, weekly policy/response tests прошли до изменения
  расчётов. Добавлен `WeeklyReviewV2CodecBaselineTest`, фиксирующий прежний content hash существующей
  синтетической frontend golden fixture и round-trip v2. Публичный новый контракт ещё не внедрён.
- **A1, внутренний baseline:** добавлены `SellerCohortSnapshot`, текущий SQL selector,
  `SellerPeriodAnalyticsService`, лёгкий `readMetrics` и расширенный `readComparison`. Сервис
  пока не подключён к Overview/Weekly Review. Оба периода читаются в одной read-only
  `REPEATABLE_READ` транзакции с одним набором IDs. Отсутствующие в наборе продавцы не входят в
  деньги, категории, document counts и attach; сотрудник без операций остаётся в составе.
- Финансовый расчёт переиспользует `CategoryKpiMetricsCalculator`; существующий category projector
  выделен в чистый метод `CategoryKpiService.project` без изменения формул или STORE-контракта.
  Сверяются финансовые и category-проекции, quality counts, unmapped и инвариант допов.
  Расширенная проекция дополнительно сверяет net revenue с отдельным sales/returns breakdown.
- `SellerDocumentRepository` сохраняет независимые all-SALE counts и completed-sale sample;
  `SellerAttachRateRepository` пока реализует **только baseline classifier v3**, суммирует raw
  quantities до clamp и не зависит от текущего состава повторно. Это не поддержка параллельно
  разрабатываемой attribution v4. До согласования общего attach-контракта нельзя подключать
  расширенный расчёт к пользовательскому отчёту с новой атрибуцией.
- Количество обращений ограничено: лёгкий путь — exists, cohort и две aggregate queries;
  сравнение — exists, cohort и четыре aggregate queries на период, без запросов на каждого
  продавца. Unit test контролирует число вызовов readers. Нагрузочный performance gate A6
  этим не заменяется.
- PostgreSQL integration проверяет parity со старым Overview до его переключения, разделение
  all-SALE/completed counts, чужой магазин и состав, нулевые операции, return-only и отрицательные
  значения, orphan attribution, signed attach aggregation и новый расчёт после переключения флага.
  Unit tests проверяют отсутствие дополнительных readers в лёгком пути, null/zero cost,
  неизменяемость состава, fail-closed reconciliation и отсутствие лимита 100 в суммах.
- **A2–A6 ещё не выполнены.** Нет переключения UI/API, новой persisted schema, изменения planner,
  включения автопубликации или production actions. История участия также не внедрялась.

Во время этого пакета в общем рабочем дереве параллельно изменились attach repositories/DTO,
атрибуция возвратов и weekly structure projector. Чужие изменения не переписываются. До A2/A3
требуется стабильный общий baseline: согласовать источник v3/v4 и правила seller scope для новой
атрибуции, затем повторить parity и v2 hash gates. В частности, нельзя молча подменить финансового
автора возврата новым автором attach или оставить seller attach на старой методике при новой
методике остальных потребителей. Финальный cutover не считается разрешённым из-за успешных
тестов изолированного пакета.

Локальная проверка первого пакета:

- Расширенная выборочная регрессия: **72 tests, 0 failures, 0 errors, 0 skipped**, 15 suites.
  Включены metrics services, PostgreSQL Employee/Category/Store KPI integration, weekly assembler,
  response contract и v2 codec baseline. Это не полный `:backend:check` и не release gate A6.
- Проверка стиля не нашла ошибок в seller-пакете и его тестах. Общие `checkstyleMain/Test` пока
  не проходят на параллельных файлах warranty/attach: 3 main и 4 test violations в последнем
  наблюдении. Эти файлы данным пакетом не исправлялись.
- `python3 -m unittest scripts/tests/test_documentation_check.py`: 25 tests, OK.
  Strict documentation check пока сообщает об ожидаемых inventory tracked paths, ещё не
  добавленных в Git: этот дизайн, warranty design и attach implementation plan. Проверка
  пробелов `git diff --check` проходит; отдельный whitespace check применяется и к новым файлам.
- Локальная сборка использует Java 21 и отдельный build directory в `/tmp`: штатный
  `backend/build` недоступен для записи. Dependency locks и verification metadata не изменялись.
- UI не менялся, visual-проверка не запускалась. Production/staging, внешние поставщики и
  реальные бизнес-данные не использовались; integration fixtures находятся в Testcontainers.

### Второй кодовый пакет: Overview и совместимость attach

- Реализован SELLERS-путь A2 через `SellerPeriodAnalyticsService.readForOverview`. Внешний DTO,
  версия формул и STORE readers сохранены. Один набор сырых employee/category rows используется
  для seller metrics и полных reconciliation-проекций; повторных aggregate queries нет.
  Из `EmployeeKpiService` и `EmployeeCategoryKpiService` выделены чистые projectors без смены
  формул. Прежнее дублирование seller group aggregation внутри Overview удалено.
- Сверка полного employee total с STORE остаётся обязательной, включая пустой seller roster.
  Полные проекции названы `reconciliationEmployees/Categories` и доступны только в
  Overview-пакете, не в weekly `SellerPeriodMetrics`. Store-wide quality counters сохраняют
  прежнее значение и не называются seller-only.
- Внешняя транзакция Overview и все публичные entry points месячного plan progress переведены
  на read-only `REPEATABLE_READ`: более слабая вызывающая транзакция не должна ослаблять общий
  snapshot. Формулы, месячные targets, рейтинг, зарплата и ingestion не менялись.
- Параллельная реализация гарантий уже добавила выбор v3/v4 в seller attach reader. Проверки
  дополнены поздней гарантией у продавца устройства, отличающимся финансовым/attach автором
  обычного возврата, неизвестным attach-автором и пустым составом. Нельзя возвращаться к v3
  при включённой новой методике или использовать финансового автора как fallback.
- Оценка количества SQL первого пакета относится к legacy-режиму. При v4 seller attach
  дополнительно вызывает store attribution-quality reader (у него несколько SQL); это не N+1
  по сотрудникам, но измерение полного числа SQL и времени остаётся gate A1/A6.
- Добавлены week/month/custom/empty-period integration checks; сохранены negative/missing-cost,
  current roster, all-SALE/completed sample и v2 hash gates.
- **A2 принят по выборочной локальной регрессии; A3–A6 не завершены.** UI и публичный weekly
  source не переключены, автопубликация не включена, новых persisted schemas нет.

Проверка второго пакета ведётся локально. Исходный выборочный прогон перед правками успешно
завершился; последующая сборка встретила незавершённый параллельный enum/switch
`CASE_ATTACH_DECIDED` / `AuditRetentionPolicy`. Это отдельная ошибка компиляции, не sandbox.
Эти файлы seller-пакет не исправляет. Подтверждённая проблема изоляции WSL обходится точечными
разрешёнными командами, без глобальных изменений безопасности/системы; build output — в `/tmp`.

Промежуточные gates второго пакета:

- Повторный `:backend:test` (seller/Overview/employee/warranty/v2-codec, plan/rating/payroll
  regression) остановлен на том же `compileJava` до запуска тестов. Новые тесты не считаются
  пройденными. Исходный успешный прогон не подменяет проверку новых изменений.
- `:backend:checkstyleMain :backend:checkstyleTest` проходят с исключёнными compilation tasks;
  это проверка стиля, а не подтверждение собираемости backend.
- Documentation unit tests: 25/25; `git diff --check`: без ошибок. Strict documentation gate
  сообщает о пяти зарегистрированных, но ещё untracked документах: seller design, warranty
  design, attach implementation plan, ADR-0003, attach-attribution runbook. Индекс Git ради
  искусственного прохождения проверки не менялся.
- UI не изменён: visual-проверка для этого пакета не запускалась. Чужие изменения UI/категорий,
  audit policy, миграций и конфигурации не исправлялись. Commit/push/deploy не выполнялись.

После завершения параллельной audit-правки повторная сборка и выборочная регрессия прошли:
`EmployeeKpiIntegrationTest` 11/11, `WarrantyAttributionIntegrationTest` 18/18,
`SellerPeriodAnalyticsServiceTest` 12/12 и `OverviewMetricsServiceTest` 4/4, без пропусков.
Тот же успешный запуск включал v2 codec, plan progress, rating и payroll computation.
Это подтверждает A2 на текущем наборе synthetic fixtures, но не заменяет полный gate A6,
локальное сравнение на свежих данных или нагрузочное измерение.

### A3: начало внутреннего недельного контура

Добавлены отдельные `SellerWeeklyReviewFactsSource` и `SellerWeeklyReviewFacts`. Они в одном
read-only `REPEATABLE_READ` читают статус источника и обе закрытые недели из общего seller
service; тип проверяет магазин, границы недель и общий cohort. Существующий v2 generator,
snapshot schema, API и AI ещё не используют этот тип. Общий `WeeklyReviewCoreProjector`
теперь принимает отдельно seller facts и переиспользует действующие формулы для четырёх карточек,
average sale и revenue decomposition. Старые STORE IDs/evidence сохраняются; seller projection
использует `SELLERS.*`. Seller path требует явный результат проверки полноты revenue, чтобы
не превращать неполное покрытие в READY. По-прежнему нужны structure/team/quality/evidence,
отдельный v3 DTO/codec и совместимое хранение/чтение.

`SellerWeeklyAdditionalSalesProjector` готовит компактную seller-сводку допов: сумму и изменение,
долю от полной seller-выручки, деньги аксессуаров/услуг и их доли внутри допов. Он проверяет
`Допы = Аксессуары + Услуги`, оставляет signed значения возвратов, не ограничивает mix доли
диапазоном 0–100 и помечает обычную диаграмму состава непригодной при отрицательной части,
неположительной базе или неполном качестве. Нулевой seller denominator не превращается в
достоверный процент; если база прошлой недели отсутствует, доказанный текущий процент остаётся
доступным без ложной динамики. При блокировке источника числовые значения не публикуются.

`WeeklyReviewQualityPolicyV1.decideSellers` сохраняет общий fence покрытия sales/returns, но
классификацию и missing cost берёт только из выбранного seller cohort. Эти предупреждения имеют
`SELLERS` scope и затрагивают лишь зависимые блоки; нулевая себестоимость не считается пропуском.
Пока seller source не измеряет возвраты с неизвестным автором, его нельзя выдавать за проверенный
`EMPLOYEE_ATTRIBUTION` coverage: v3 источник пока публикует только обязательные sales/returns.
Недельная seller-структура использует тот же расчёт дерева и attach-rate, что v2, но с seller
category totals, seller attach aggregates и `SELLERS.*` evidence. Версия attach v3/v4 передаётся
в seller facts явно; округление, signed raw counts и clamp rate переиспользуются из общего
`AttachRateService.project`. Store-wide v2 путь и его идентификаторы остаются прежними.
Внутренний `SellerWeeklyReviewProjector` собирает quality, core, structure и допы из одного
seller facts bundle; при `BLOCKED` core/structure/допы не читают частичные числовые категории и
не публикуют мнимые нули. Командный projector v2 сюда намеренно не подключён: он считает сводку
после ограничения отображения 100 карточками и фильтрует часть historical сотрудников по текущей
активности. Для v3 нужны полные totals до UI limit и отдельная политика actionability.
Внутренний `SellerWeeklyTeamFactsProjector` теперь готовит финансовые факты **всего** выбранного
cohort: по employee ID сверяет money с документами, отдельно хранит все SALE-документы и более
узкий completed-sales sample, сохраняет signed возвраты и суммирует допы по действующему
`countsAsAdditionalRevenue`. Проверки выполняются и на уровне каждого employee, и на уровне
полного team total. Метод `displayWindow` применяет лимит 100 только после расчёта всех фактов,
требует не терять активных финансовых участников и возвращает точный remainder по выручке и
допам. Это пока не user-facing team block: смены, benchmark, действия, display ordering и v3
evidence ещё предстоит добавить; отсутствие смен не снижает достоверность денежных фактов.
Код-ревью источника смен подтвердило: `employee_work_shifts` содержит отдельные записи, но не
признак полноты недели. Даже несколько внесённых смен сами по себе не доказывают, что менеджер
заполнил все смены. До подключения `revenuePerHour` к v3 benchmark/action нужна явная политика
полноты или консервативное ограничение; финансовые факты команды от неё не зависят.
`SellerWeeklyReviewProjector` включает эти полные team facts только после обязательного source
coverage fence; при `BLOCKED` возвращает отсутствие командных чисел, а не нулевой результат.
Targeted tests и Checkstyle проверяют обе ветки, включая v2 codec baseline; полный gate A6 впереди.
Пока отсутствующие v3 части не готовы, v2 STORE отчёт нельзя выдавать за seller-first.

Подготовлен безопасный для v2 первый шаг совместимого snapshot read path: старые методы
`findLatest` и `findById` выбирают только `report_contract_version = 2` до десериализации, а
`persist` определяет следующую ревизию и `supersedes_snapshot_id` по абсолютной последней
записи периода **любой** версии. Повторное использование по content hash допустимо только когда
абсолютная последняя запись тоже v2: после будущей v3-записи откат к v2 обязан создать новую
ревизию, а не вернуть старый v2 ID. Публичная выдача и запись по-прежнему только v2; это не
выполнение полной контрольной точки v3. Начальный SQL-filter и v2 regression gate прошёл
локально (15 тестов, без ошибок и пропусков).

Следующий изолированный пакет добавил отдельный типизированный `WeeklyReviewV3Response`,
минимальный sealed header interface, самостоятельный v3 codec/hash и явный dispatch по
`report_contract_version`. Старый v2 codec/`Content` не изменены; golden hash прошёл повторно.
V3 semantic hash не зависит от `provenance`, `sourceIdentityHash` и времени
`actionabilityAsOf`, но зависит от состава. Миграция V73 допускает v2/v3 в одной immutable
таблице и требует для v3 `report_scope=SELLERS`, SHA-256 source identity и согласованных
seller-cohort hashes в payload; v2 строки сохраняют nullable новые колонки. PostgreSQL-тест
проверил v2→синтетический v3→v2: старое чтение пропускает v3, а откат создаёт ревизию 3 со
ссылкой на абсолютную ревизию 2 даже при равном v2 content hash. Синтетический v3 в этом тесте
проверяет DB header/revision совместимость, **не** seller-корректность содержимого отчёта.
Отдельный codec test проверяет v3 round-trip и hash; полноценный v3 assembler, доказательство
seller-происхождения чисел, публичный version negotiation, AI и автопубликация ещё не реализованы.
Пока эти проверки не готовы, включать v3 writer нельзя.
Адресный повторный прогон на текущем рабочем дереве с параллельно добавленной V74 прошёл:
11/11 tests без ошибок/пропусков, включая PostgreSQL mixed-version и v2 golden; оба Checkstyle
gate прошли. Полный `:backend:check` пока **не прошёл**: первый запуск пересёкся по времени с
появлением V74 и получил устаревшие скомпилированные ожидания `73` против `74`; отдельно
`MigrationLeastPrivilegeIntegrationTest` подтвердил ошибку самой параллельной V74 (`42501`,
запрещено создавать temporary tables для restricted migrator). Это не исправляется seller-пакетом
без вмешательства в чужую миграцию. До общего release gate требуется исправление V74 её владельцем
и новый полный прогон. Documentation unit tests 25/25 и `git diff --check` прошли; strict checker
пока блокируют пять ранее зарегистрированных, но ещё untracked документов.

Продолжение A3 добавило отдельные `findLatestV3`/`findV3ById` и version-specific persisted view.
V3 read декодирует по версии из DB row и сверяет immutable header, scope, source identity и
semantic content hash; v2 методы по-прежнему не видят v3. PostgreSQL-тест теперь использует
настоящий v3 codec payload и проверяет чтение v3, откатную v2-цепочку и отказ при неверном hash.
`WeeklyReviewV3ScopeValidator` рекурсивно запрещает legacy `STORE`-идентификаторы/scopes в
summary, core/decomposition, допах, структуре, команде, сотрудниках, действиях, ограничениях и
evidence при v3 encode/decode/hash. Это защита от явной утечки старых ссылок, **не** доказательство
того, что переименованные показатели действительно посчитаны по продавцам: synthetic fixtures
лишь проверяют контракт и DB. Источник фактов и assembler должны пройти отдельное сквозное
reconciliation без STORE input. Адресный повторный прогон этого шага: 16/16 tests, ноль
ошибок/пропусков, оба Checkstyle gate прошли. Полный gate и указанный выше дефект V74 остаются
открытыми; пользовательская выдача v3 не включена.

Следующий пакет A3 добавил отдельный `SellerWeeklyV3Assembler`: seller core/structure/additional,
финансовые карточки всей выбранной команды, `TEAM`-действия только по доказанным агрегатным
изменениям, seller/employee evidence и рекурсивный scope gate. Лимит 100 карточек применяется
после полного team reconciliation; remainder и общая численность не теряются. Неподтверждённая
полнота смен не подменяется нулями: shift/hour metrics недоступны, peer benchmark и личные
действия отключены, report остаётся `PARTIAL`. При `BLOCKED` core/допы/структура маскируют
числа, команда не публикует карточки. Source coverage дополнительно ограничивает seller metrics
и не допускает персональные сравнения при неполном источнике. Сборщик пока внутренний и принимает
`sourceIdentityHash` от будущего проверенного planner: подставлять фиктивный hash в writer нельзя.
Локальные синтетические тесты проходят v3 serialization/hash, v2 golden regression, seller-only
refs, BLOCKED masking, пустую активность и 100+ карточек. Это **не** означает завершение P6:
историческое membership stage B, проверенный source identity/invalidation, сменная полнота,
public version negotiation, writer/read mode, AI и автопубликация ещё не готовы; v3 не включать.
Полный локальный `:backend:check` запущен на общем грязном дереве: OpenAPI compatibility,
supply-chain и оба Checkstyle gate прошли, но `:backend:test` завершился с 16 падениями из 1276
тестов. Один seller quality test ожидал старый `STORE` scope у source limitation — его ожидание
исправлено, и отдельный seller test bundle после этого прошёл. Остальные падения пришлись на
параллельные category/warranty integration tests и завершающиеся `NoClassDefFoundError` в sync
test worker; общий gate **не засчитывается**. Документный unit gate 25/25 прошёл; strict gate
по-прежнему требует занести пять уже зарегистрированных untracked документов в commit, включая
этот план. Не объявлять пакет готовым к release, пока общий backend gate и strict docs gate не
завершатся успешно на стабилизированном дереве.

Внутренний P6 persistence-пакет добавил `persistV3Candidate` только внутри snapshot store.
Метод собирает реальный v3 response из seller facts, проверяет v3 codec/hash, в отдельной
`READ_COMMITTED` write-транзакции под общим store lock продолжает абсолютную immutable revision
chain и повторно использует равный последний v3 content. Вызов без write-транзакции отвергается.
PostgreSQL-тест проверил v2→v3→v2→v3, совместимые latest-read для обеих версий, supersession и
отсутствие чисел у `BLOCKED`; targeted Checkstyle и v2 golden gate прошли. В main-коде нет
вызовов этого метода вне его объявления: публичная выдача, planner и автопубликация остались v2.
Это **не** source-consistent production writer: пока нет P7 semantic source revision и lock-fence,
параметр `sourceIdentityHash` нельзя считать проверенным. Включение вызова метода до P7 запрещено.
При ревью обнаружено и исправлено расхождение с P7: `ContentV3` больше не хеширует
`displayName` сотрудника. Payload сохраняет имя для представления, но переименование не создаёт
новую semantic revision. Адресный тест меняет только имя и сверяет v3 hash; v3 writer/PostgreSQL,
v2 golden и Checkstyle после изменения прошли повторно.

Первый защитный шаг P7: seller facts теперь в той же read-only `REPEATABLE_READ` транзакции читают
source-stability по **всем** релевантным `sync_jobs`/`sync_runs` за две сравниваемые недели. Один
`synchronization` из общего status widget не годится: он показывает только последнюю активность
и может скрыть параллельный run. Активные sales/returns/membership/product sync переводят v3
candidate в `BLOCKED` и маскируют финансовые и персональные значения, даже если предыдущий
успешный sync оставил формально полное coverage. FAILED/PARTIAL_SUCCESS требуют более позднего
полного SUCCESS, покрывающего затронутый интервал; sales другого магазина не блокирует отчёт.
`ORDERS` тоже релевантен: `OrderSyncPersistence` записывает `SALE` документы, участвующие в seller
KPI, поэтому активный/failed order sync блокирует отчёт при пересечении недель. Для job в фазе
STORES/EMPLOYEES действует fail-closed gate даже
при непересекающемся финансовом периоде, потому что он может менять текущий roster. Это
ограниченная защита **полноты sync**, а не transactional revision fence: изменение источника после
этого чтения пока не сериализовано с insert snapshot. Пока не закрыты полный writer inventory,
monotonic source revision, fence/retry и generation checkpoint, v3 writer нельзя подключать к
планировщику или пользовательскому API.
Следующий защитный шаг P7 заменил в seller v3 прежний `max(period_end)`-watermark на проверку
непрерывного объединения только `SUCCESS`-интервалов `sync_runs` отдельно для SALES, RETURNS и
ORDERS и для каждой из двух недель. PostgreSQL `range_agg(tstzrange(...))` не считает период
покрытым при дыре между окнами или одном `PARTIAL_SUCCESS`; время границ строится по timezone
магазина. Отсутствие полной недели любого источника создаёт `BLOCKING` limitation, состояние
`BLOCKED` и маскирование цифр; предыдущая неделя теперь тоже обязательна для сравнения. Это
исправляет именно доказательство coverage, но не заменяет проверку source revision под write lock.
`ORDERS` пока представлен отдельным внутренним limitation `ORDERS_COVERAGE_INCOMPLETE`, а не
новым значением общего `WeeklyReviewResponse.SourceCode`: это enum опубликованного v2 OpenAPI,
где изменение набора значений считается breaking. Перед публичным v3 потребуется отдельный
versioned source-coverage contract, в котором ORDERS будет показан явно, без дрейфа v2 схемы.

### A4: синтетическая нагрузка и отдельная attribution quality

Продолжение 2026-09-28 не переводит проект на историю membership: текущая очередь — A4–A6,
история и forward-only остаются отдельным B. Public v2, scheduler, AI enqueue, frontend,
published migrations/views и production/staging не изменялись этим пакетом.

`SellerWeeklyV3LoadIntegrationTest` использует отдельный PostgreSQL Testcontainer и полностью
синтетические fixtures: 3/130 выбранных продавцов, 1/30 сотрудников вне рейтинга, две недели,
8/6400 документов и 24/19200 item rows. Связанные возвраты, допустимая нулевая себестоимость
услуг и денежные допы входят в reconciliation; UI limit не ограничивает финансовые факты.
Проверены continuous coverage обеих недель, STABLE, CURRENT после генерации, ровно 100 карточек
из 130, точный остаток 30 продавцов, три UNCHANGED scan без нового snapshot/checkpoint,
coalescing source revision на два магазина и no-op metadata update без её продвижения.

Нагрузка выявила конкретный bottleneck: seller reader вызывал полный `AttachRateRepository`
для store-wide attribution quality, хотя store numerators/denominators ему не нужны.
EXPLAIN старого пути на маленьком fixture: 24,874 s execution, 23,466 s JIT и 2454 JIT functions.
Одного выделения quality-only запроса оказалось недостаточно: сложные warranty views продолжали
тратить около 14 s на JIT. Новый `AttachAttributionQualityRepository` сохраняет старые predicates
pending warranties/unassigned ordinary returns и materializes unassigned rows один раз. Его
read-only transaction временно применяет `SET LOCAL jit = off`, восстанавливает caller value
при успехе; rollback сбрасывает local setting при ошибке. Глобальные/session настройки и
магазинный attach path не менялись. Это целевая оптимизация подтверждённого SQL, не увеличение
таймаута или изменение формул. Получатель рисков — отдельный тип без store quantities/money.

Успешный локальный synthetic load run, 5 tests без failures/errors/skips:

- SQL round-trips полного facts read: 22 для 3 продавцов и 22 для 130. Счётчик включает
  query-local JIT setup/restore, исключает introspection, pool validation и transaction-control.
- Warm facts read: 4,058 s / 5,674 s; первая полная planner evaluation — 14,402 s.
  Предыдущий промежуточный вариант без runtime JIT guard занимал около 33 s на facts bundle;
  его трёхминутный scenario timeout не засчитывался и не повышался.
- UNCHANGED scans: 26 SQL round-trips, 6,764–7,210 s. Они не генерируют новую revision, но
  пока перечитывают все facts для identity: это измеренный остаточный overhead, а не cheap path.
- EXPLAIN document reader: 130 result rows, 1 outer loop, 22,9 ms execution; quality-only
  projection на маленьком fixture — 9,3 ms, JIT отсутствует.
- Bulk UPDATE 19224 item rows на двух магазинах: no-op commit 1,550 s, semantic commit
  3,729 s; item-trigger time 1,117/3,202 s. Revision increments 0/1 на магазин, deferred events
  после commit отсутствуют. Замер включает commit, не только statement execution.
- Quality-only counters сверены со старым store path, включая pending warranty return вне
  текущей недели, numerator/denominator risks и незатронутый iPhone metric. JIT restoration
  проверен для caller on/off и rollback на том же физическом connection: startup допускает
  два соединения для Flyway, одно резервируется только на время этой проверки.

Это локальные observations, не production SLA и не закрытие A6. Объём большего числа магазинов,
память/бюджет planner batch, конкурентный bulk sync и более тяжёлые ручные warranty allocations
ещё требуют оценки. Исторические range lookups B этим current-roster test не проверяются.
Автопубликация/публичный v3 не включаются. Следующий пакет должен оценить дешёвую проверку
неизменного checkpoint без тяжёлого повторного facts read, сохранив fail-closed identity, timezone,
clock-boundary и runtime attach-version gates; после этого — совместимые public/AI/UI adapters.

Расширенный targeted run: 108 tests, 107 passed; единственное падение — существующий
`WarrantyAttributionIntegrationTest.decisionInvalidatesCurrentReviewAndUnchangedContentIsAcknowledged`,
legacy attribution acknowledgement после content reuse остался изменённым. Тот же класс проходил
предыдущий отдельный/расширенный запуск. Его service/checkpoint код данным пакетом не менялся;
причина ещё не установлена. Отдельный повтор всех 19 warranty tests тоже дал одно падение в
этом методе, но уже на предыдущем assertion «изменение должно быть обнаружено» (строка 493),
а не «изменение должно быть подтверждено» (строка 497). Сопоставление application clock с
DB `changed_at` — гипотеза для дальнейшей диагностики, не доказанная причина и не исправление.
До проверки этого legacy gate расширенный targeted run не считается зелёным. Первый запуск
нового теста с pool size 1 не прошёл Flyway bootstrap; тест исправлен на два startup connections
и reservation вместо изменения рабочей конфигурации. Отдельное ready-log timeout существующего
PostgreSQL suite прошло при повторе; глобальные WSL/Docker settings не менялись.

Отдельные финальные gates этого пакета: Checkstyle main/test, supply-chain integrity и OpenAPI
compatibility прошли; generated API не имеет drift/breaking changes. Documentation unit tests
25/25 и `git diff --check` проходят. Strict checker выдаёт только шесть ранее зарегистрированных
untracked MD-документов; index для обхода не менялся. Полный `:backend:check` на новом пакете
не засчитывается и повторно не запускался: targeted legacy failure остаётся открытым.
Frontend не менялся этим пакетом, local visual verification не запускалась и не заявляется.
Commit/push/deploy не выполнялись.

### A4 продолжение: legacy acknowledgement и cheap canonical identity

Следующий пакет 2026-09-28 закрывает два обнаруженных ограничения A4, не начинает temporal B
и не подключает public v3/scheduler/AI enqueue. Формулы, опубликованные payload/hash, migrations,
frontend и production/staging не изменяются. Широкий rollout A6 остаётся отдельным gate.

**Legacy attribution.** Детерминированный synthetic red test доказал дефект сравнения DB
`changed_at` с application `calculatedAt`: при marker раньше application time изменение
не обнаруживается. Исходный warranty scenario в диагностическом повторе прошёл; измерены только
обезличенные разницы времени, не данные магазина. Конкретные timestamps прежних двух падений
не были сняты, поэтому нельзя задним числом утверждать измеренный clock offset тех запусков.
Статически обнаруженная cross-clock уязвимость воспроизведена отдельно и исправлена.

`WeeklyReviewAttributionRepository` читает точный marker в одной `REPEATABLE_READ` транзакции
с facts через `WeeklyReviewFactsSource.loadForGeneration`. Acknowledgement сохраняет наблюдавшийся
marker, а не начало/конец расчёта в JVM; отсутствие marker — `-infinity`. Сверка на равенство
не пропускает clock rollback и не требует `greatest` со временем immutable snapshot. Решение
после чтения facts остаётся неподтверждённым. Старый wall-clock checkpoint при несовпадении
требует повторной проверки; одинаковое содержимое сохраняет прежний snapshot ID/hash/revision.
Проверены оба направления clock offset, старый checkpoint, content reuse, изменение между
facts read и acknowledgement, отсутствие marker, изоляция магазина и service wiring/порядок вызовов.
Это исправление legacy v2; v3 продолжает использовать отдельный monotonic revision fence.

**Cheap identity.** `SellerWeeklyIdentityFacts` хранит полный прежний canonical input без money,
attach quantities и карточек. `SellerWeeklyIdentityFactsSource` читает текущий cohort тем же
repository, status, continuous coverage, source stability, revision и runtime attach version в RR.
`SellerWeeklyV3ReadService` больше не вызывает полный facts reader для CURRENT assessment,
но сохраняет проверки latest compatible snapshot/checkpoint, дня, revision, hash и temporal fence.
Revision не заменяет остальные поля identity. Full и metadata projections используют один hash
encoder; отдельный golden фиксирует прежние canonical bytes. Проверена parity attach v4 на
синтетических 130 продавцах, unit — runtime v4→v3 при неизменной revision. Дополнительно добавлены
integration parity attach v3/empty roster и concurrent roster commit под RR: эти последние cases
войдут в последующий полный прогон, а не в приведённые ниже 101 targeted tests.

Успешный расширенный targeted run до трёх последних дополнительных cases: **101 tests / 13 suites,
0 failures/errors/skips**, включая все 19 warranty tests, v2 contract baseline, snapshot chain,
planner/read/candidate и 5 synthetic load tests. Checkstyle main/test и operator script security
также прошли. Новые локальные observations:

- UNCHANGED: **9 SQL** вместо 26, **52–72 ms** вместо 6,764–7,210 s; три scan без новых
  snapshot/checkpoint writes. Это конкретный synthetic fixture, не production SLA.
- Warm full facts: **22/22 SQL**, **4,016/5,977 s** для 3/130 продавцов; full facts path не урезан.
- First planner: **7,472 s** вместо 14,402 s; financial totals/100-card cap/exact hidden remainder
  по-прежнему reconcile по полным 130 продавцам.
- Quality EXPLAIN: 10,7 ms, 0 JIT; document reader: 130 rows, 24,8 ms.
- Bulk 19224 item rows: no-op/semantic commit 1,601/3,741 s, trigger 1,123/3,237 s,
  revision increments 0/1 на магазин, deferred events 0.

Рабочий Maven transport из WSL в этом продолжении недоступен; официальный Maven Central через
Windows доступен. В локальный `/tmp` mirror получены 115 отсутствовавших `.module` и 18 POM/JAR,
каждый проверен по существующему SHA256 manifest. Init script включает Gradle metadata/POM;
dependency lock и verification не отключались и не переписывались. Execution policy, права,
WSL/mounts, global security/system configuration не менялись. Неудачные cache/bootstrap attempts
не засчитываются как tests и не являются ошибками проекта. Все 839 POM/JAR/module artifacts
в mirror дополнительно сверены с manifest: missing/mismatch = 0; wrapper source archive не нужен
этим tasks и не скачивался.

Первый полный `:backend:check`: **1592 tests / 374 suites, 1590 passed, 2 failures**. Все текущие
Weekly Review tests прошли: 23 snapshot integration, 19 warranty, 6 load, включая три последних
cases, отсутствовавших в targeted 101. Два отдельных падения: startup ready-log timeout
`RemainingKeephoneIphoneCaseMigrationIntegrationTest` до migration assertions и chronological
invariant `resolvedAt must not be before detectedAt` в одном `ReturnSyncIntegrationTest`.
Последний использует application Clock для quality events; точные времена падения не сняты,
поэтому это не доказанный DB/application clock offset и не исправленный дефект данного пакета.
Quality model/ReturnSync code, таймауты и система не изменялись для обхода этих падений.
Полный отдельный повтор обоих классов на том же коде: **18 tests / 2 suites, 0 failures/errors/skips**.
OpenAPI, supply-chain integrity, Checkstyle и operator script security прошли и в полном запуске.
Первый full gate не считается зелёным. Второй полный **unfiltered** `:backend:check` на том же
коде завершился успешно: **1592 tests / 374 suites, 0 failures/errors/skips**, BUILD SUCCESSFUL
за 17m15s. OpenAPI, supply-chain integrity, Checkstyle и operator script security также прошли.
Успешные повторы не доказывают причину и исправление двух временных падений первого запуска;
эта история сохранена для следующего предрелизного прогона.

Дополнительный `frontend/npm run check` через уже установленный Node 22 прошёл: generated OpenAPI
types без drift, lint, **286 tests / 60 files**, TypeScript и Vite build. Это совместимость на текущем
локальном `node_modules`, не `npm ci`/locked CI reproduction, не браузерная visual-проверка.
Frontend code и dependencies данным пакетом не менялись. Documentation unit tests 25/25,
`git diff --check` проходят; strict checker сохраняет только прежние шесть зарегистрированных
untracked MD-файлов. Index для обхода не менялся.

Следующие adapters нельзя делать простой подменой scope в v2: действующий
`WeeklyReviewAiInputCompactor` явно допускает только STORE actions/evidence, а public controller
типизирован `WeeklyReviewResponse` v2. Для seller path нужны отдельные versioned контракты,
privacy-safe AI input и совместимое UI-чтение; существующие STORE guards/published prompt/schema
не ослаблять и не переписывать. Автопубликацию это не включает.

Остаются A4/A6 multi-store batch/memory budget, более тяжёлые allocations и конкурентный bulk sync,
затем A5 совместимые public/AI/UI adapters с явным seller scope. Нельзя выдавать закрытый cheap
CURRENT path за готовый rollout или реализованный historical membership. Frontend не менялся,
local visual verification этим backend-пакетом не запускалась. Commit/push/deploy не выполнялись.

### A4/A6 продолжение: bounded batch и конкурентная синтетическая нагрузка

Пакет 2026-09-28—2026-09-29 продолжает current-roster этап A. Temporal B, публичный v2/v3,
scheduler, AI enqueue, frontend, formulas, production/staging не переключаются и не изменяются.
Добавлена только новая миграция оптимизации warranty view, описанная ниже; прежние migrations
не переписываются.

Добавлены внутренние `SellerWeeklyV3BatchPlanningService`/`SellerWeeklyV3BatchPlanningResult`.
Batch использует существующий `WeeklySnapshotPlanningStore.activeStoresAfter`: одна страница
активных LiveSklad targets, последовательный вызов защищённого одиночного planner, только counters
и last-processed cursor в результате. Ограничения задаются явно: maxStores 1–100, pageSize 1–25,
elapsed time budget 1 ms–5 min. Это ограничение объёма работы, не heap quota и не hard deadline:
последний store/retry может закончиться после бюджета, его транзакция не прерывается.
Монотонное время не зависит от application Clock/перевода часов. Внешняя транзакция запрещена.
Точный cap возвращает STORE_LIMIT без лишнего fetch; следующий resume может быть EXHAUSTED.
DEFERRED наследует только source/coverage deferral; ошибки БД/конфигурации передаются вызывающему коду.
Уже завершённые магазины могут сохранить локальные snapshots до failure; повтор старого cursor
безопасен через неизменный content/no-op paths. Sweep не фиксирует targets на всё время:
активация/добавление UUID до cursor требует следующего полного sweep с null.
Service никем не вызывается вне тестов: наличие Spring bean не включает автоматический обход.

Unit checks проверяют exact cap, уменьшение последней страницы, resume, short/empty pages,
deadline до первого store и внутри страницы, nanoTime wraparound, fail-fast infrastructure,
недопустимые бюджеты, NEVER и отсутствие snapshot graph в типе результата.

Первый targeted run: **12 tests / 2 suites, 0 failures/errors**, Checkstyle main/test и operator
script security прошли. Он включает 10 batch unit tests и две новые integration checks:

- Восемь synthetic stores: семь готовы к внутренней generation, один без ORDERS coverage;
  среди готовых — 130 sellers/100 displayed и пустой roster. Три batch по 3/3/2 stores с pageSize 2
  завершаются без пропусков/дубликатов. Повтор: 7 UNCHANGED, 1 DEFERRED, snapshot/checkpoint
  неизменны. Cold 45092 ms / warm 4761 ms, warm 97 read SQL; allocated bytes текущего потока
  147833288 / 22686952. Это allocations всего прохода, не retained/peak heap и не production SLA.
- Worker меняет 19200 item rows, RR reader до commit видит прежнюю revision и согласованные
  суммы обеих недель. Commit coalesces одну revision; writer отклоняет старый bundle до snapshot.
  Повтор planner создаёт один CURRENT snapshot, новые затраты reconcile, revenue не изменяется.
  Latches/futures ограничены 30 s; глобальные clocks/settings и существующие timeouts не менялись.

Добавлен отдельный heavy manual warranty scenario: 130 выбранных/30 внешних sellers,
320 решений и 2560 allocations на две недели. Fixture использует опубликованные fingerprint views,
валидирует source/target fingerprints и полный allocated quantity, сохраняет документные/item суммы;
это synthetic SQL fixture,
не ручной authenticated операторский прогон. Проверяет raw quantities, financial reconciliation,
100-card cap и точный остаток. В targeted 12 он не включён. Первый отдельный run остановился
на fixture verification через общий `warranty_attach_cases`: SQL timeout 30 s, до seller facts read.
Это не успешный gate и не доказательство времени seller reader; fixture verification заменена
узкой проверкой тех же published source validity/allocated quantities. Таймауты не увеличены,
views/formulas не переписаны; повтор должен отдельно проверить рабочий seller reader.
Второй run выявил ошибку самого synthetic fixture: гарантия была размещена в первом документе
недели, часть target devices продана позже. Deferred constraint правильно отклонил commit
(`Invalid warranty device allocation`). Fixture исправлен: source — последняя SALE недели,
все allocations с device date <= warranty date. Constraint не обходился и не ослаблялся.
Третий run ещё не дошёл до facts read: read-only `pg_stat_activity` disposable Testcontainer
подтвердил длительный COMMIT synthetic fixture, после 284 s отменён только этот backend/transaction.
Все её изменения откатились; пользовательские БД не затронуты. Method timeout 3 min сработал,
этот run не засчитывается. 320 decisions + 2560 allocations ставят 2880 deferred validation events,
каждый повторно валидирует полное решение. Это fixture provisioning/write cost, не reader latency.
Подготовка перенесена в отдельный tagged BeforeEach с собственным новым бюджетом 10 min;
бюджет test body остаётся 3 min, SQL/session/validator настройки не менялись. Все deferred constraints
выполняются при commit. Это не оптимизация bulk writer и не доказательство его production SLA.
Отдельный 10-minute provisioning run также не дошёл до reader: COMMIT 629 s, отменён только
подтверждённый synthetic backend; rollback, lifecycle timeout, gate не засчитывается.
Следующая гипотеза — stale/пустая planner statistics до commit нового fixture: autovacuum не видит
uncommitted строки. Fixture теперь ANALYZE базовые таблицы/decisions/allocations до deferred
validation, а не только после commit. Это обычная подготовка benchmark, не session/global tuning,
не изменение published validators и не уменьшение объёма. Подтверждение гипотезы ещё требуется.
Следующий targeted run: **36 tests, 35 passed, 1 failed**. Все **25 candidate/preflight** и
**10 batch unit** прошли; heavy setup снова не дошёл до reader, timeout 30 s в bulk fixture
validity SELECT. Сбор statistics сам по себе не закрыл extreme seed gate. Published validators
при commit уже проверяют те же source/target fingerprints и completeness, поэтому redundant
bulk validity SELECT удалён: реальные deferred constraints остаются включены.
Для reader validation теперь задан mixed workload при прежних 130 selected/30 excluded sellers
и 320 decisions: 304 single-device решения и 16 fanout по восьми устройствам, всего 432 allocations.
Fanout применяется к восьми выбранным продавцам в обеих неделях; денежные суммы проверяются
по всему cohort, hidden 30 — независимо по полным employee facts и IDs отображённых карточек.
Этот набор не заменяет доказательство extreme 2560-allocation bulk write budget: тот остаётся
неподтверждённым, без утверждения исправленной причины/production SLA.
Mixed fixture успешно прошёл commit с включёнными constraints: provisioning 554744 ms.
После этого actual reader упал на SQL timeout 30 s в `AttachAttributionQualityRepository.read`.
Это подтвердило проблему рабочего read path, а не только fixture provisioning; run не засчитан.

Добавлена `V80__bound_warranty_fingerprint_context_to_document.sql`: source и target document
context вычисляются LATERAL агрегатом для конкретного document_id вместо глобального GROUP BY
в каждом correlated validity check. Сохраняются ordered MD5, все поля/предикаты V55, deferred
constraints и формулы; таблицы/индексы/права не изменяются. View общая для действующих v4 readers,
поэтому это не изолированная seller-only оптимизация: требуется full compatibility gate.
Первый targeted run после миграции: **37 tests / 4 suites, 0 failures/errors** (heavy load,
25 candidate/preflight, 10 batch unit, packaged schema resolution). Mixed fixture provisioning
22381 ms, full facts read 7974 ms / 22 SQL, allocated current-thread bytes 54526096;
snapshot CURRENT, 100 displayed/30 hidden, quantities и financial reconciliation проходят.
Не менялись body/SQL budgets. Это synthetic mixed-load результат, не production SLA; extreme
2560-allocation case этим run не проверялся. Причины всех прежних timeout не объявляются исправленными.

Добавлен `WarrantyFingerprintContextMigrationIntegrationTest`: baseline до оптимизации против
новой миграции с полным сравнением source/cases/effective/attach projection JSON в памяти теста,
без вывода payload в evidence. Сценарии: valid manual/auto, mixed/unknown/deleted devices, Care,
связанные/неизвестные возвраты, EXCLUDE/DEFER; повторные сравнения при изменении target quantity,
deletion/condition/document/store, Care-context и новой ревизии original warranty.
Проверяются неизменяемость history и отклонение incomplete allocation при commit.
Следующий общий targeted run: **45 tests / 4 suites, 44 passed, 1 failed**. Migration parity,
25 candidate, 10 batch и восемь load cases прошли. Mixed load не дошёл до reader: SQL timeout
30 s в synthetic `INSERT warranty_attach_allocations` на накопленных fixtures общей test DB.
Cold batch 34084 ms / warm 293 ms, warm 80 SQL, семь unchanged/один deferred и без дубликатов.
Bulk concurrency и actual snapshot profit/identity reconciliation проходят. Одиночный mixed pass
не объявляется прохождением всего shared-DB набора.
Synthetic allocation INSERT сужен до item/latest-decision views и document-local context:
он не вычисляет ненужный source decision_valid до вставки allocations. Source fingerprints
по-прежнему берутся из published view; target fingerprint formula и реальные deferred constraints
не изменены. Это адресная оптимизация provisioning, не production writer implementation.
Дополнительно восстановлен отдельный extreme load: 320 fanout decisions / 2560 allocations,
по восемь devices на каждого selected/excluded seller. Оба warranty сценария сохраняют body 3 min,
SQL 30 s и отдельный provisioning budget 10 min; 130 selected/30 excluded, 100 displayed/30 hidden,
quantities, financial reconciliation и exact hidden totals проверяются одинаково.
Общий targeted run с обоими сценариями: **46 tests / 4 suites, 45 passed, 1 failed**.
Extreme provisioning 50076 ms, full facts 22651 ms; mixed provisioning 37272 ms, facts 27604 ms.
Оба сохраняют CURRENT, reconciliation и hidden totals; migration parity и unit checks проходят.
Но общий gate не засчитан: strict same-query-count assertion получил 24 для small и 22 для large.
Во время benchmark выполнялись диагностические SELECT в той же disposable DB; database-wide
pg_stat_statements counter включает эти два запроса. Это загрязнение измерения, не основание
ослаблять assertion или увеличивать SQL budget. Extra queries могли затронуть и прочие timings/counts,
поэтому это наблюдения contaminated run, а не чистый benchmark. Следующий unfiltered backend check
повторяет все десять load cases без запросов в measured DB; monitoring допустим только из другой
database/по процессам. Чистый pass должен подтвердить вывод, а не просто удалить историю failure.

Первый unfiltered `:backend:check` на этом пакете завершён: **1618 tests / 376 suites,
1617 passed, 1 failed, 0 errors/skips**, 46m08s. Единственное падение — пропущенное schema-version
ожидание в `SecurityHardeningIntegrationTest.exposesSafePublicHealthAndBuildInformation`:
runtime правильно сообщает новую packaged schema, а test ожидал прежнюю. Обновлено только
ожидаемое значение, остальные health/access/security assertions сохранены. Все остальные старые
и новые tests прошли; OpenAPI compatibility, supply-chain integrity, Checkstyle main/test и operator
script security прошли. Это не чистый full gate: запущен второй unfiltered check финального кода.
В measured DB не выполнялись диагностические SELECT. Все десять load cases прошли, включая
строгое равенство 22/22 SQL для 3/130 sellers (19200 items), identity/profit reconciliation после
19200-row bulk commit и exact hidden totals. Batch: cold 34638 ms / warm 413 ms, warm 80 SQL,
7 unchanged + 1 deferred, без дубликатов. Extreme: provisioning 50631 ms / facts 23047 ms / 22 SQL;
mixed: provisioning 37106 ms / facts 23132 ms / 22 SQL. Обе warranty fixtures сохраняют real
constraints и CURRENT. Это закрывает данные synthetic scenarios, не real-data shadow, peak heap
или production SLA; исходные contaminated/failed runs выше не удалены.

Второй unfiltered `:backend:check` завершён: **1618 tests / 376 suites, 1617 passed,
1 failed, 0 errors/skips**, 45m30s. Исправленное health/schema expectation проходит.
Единственное падение теперь — существующий
`ReportBackfillJobPersistenceIntegrationTest.persistsIdempotentClaimsRetriesAndCancellation`:
первый `claimNext` вернул empty, `orElseThrow` на строке 115 завершился `NoSuchElementException`.
Изолированный повтор этого класса без изменений backfill/test кода прошёл: **1 test, 0 failures**,
Gradle 1m26s. Причина общего failure не установлена; изолированный pass не превращает общий
check в зелёный и не доказывает, что это ошибка среды. Production backfill, Clock и SQL claim
не менялись, sleeps/retries для сокрытия ошибки не добавлялись.
Все десять seller load cases вновь прошли без диагностических SELECT в measured DB:
small/large — 22/22 SQL; batch cold 34017 ms / warm 343 ms, warm 80 SQL,
7 unchanged + 1 deferred и без дубликатов. Extreme warranty — provisioning 52390 ms,
facts 16295 ms / 22 SQL; mixed — provisioning 39065 ms, facts 19494 ms / 22 SQL.
Оба сценария подтвердили CURRENT, quantities, financial reconciliation и точный hidden remainder.
Конкурентный 19200-item commit снова увеличил revision один раз, stale candidate отклонён,
свежий snapshot CURRENT. Unit, migration parity, старые warranty/API/security tests,
OpenAPI compatibility, supply-chain и operator script checks прошли; Checkstyle main/test —
0 violations. Это повторное synthetic подтверждение нового пакета, не закрытие full release gate.

Batch observation также обнаружил лишний full facts read для заведомо incomplete DEFERRED store.
В `SellerWeeklyV3CandidateService.generateStableCandidate` добавлен cheap metadata preflight перед
каждой попыткой. Unstable/incomplete metadata откладывают generation без финансового чтения;
успешный preflight не заменяет повторный gate полного RR bundle и RC writer fences. Hash строится
только из полных facts. Diagnostic `generateCandidate` не получает preflight и сохраняет прежнее
поведение. Добавлены 11 unit cases: каждый из шести coverage gaps/все gaps, два unstable states,
смена metadata после conflict и infra failure; прежние full-gate race cases сохранены. Новый
общий regression run повторяет их вместе с batch измерением; 25 candidate cases уже прошли
в targeted 37, в первоначальном targeted 12 их не было.

Остаточные gates: выяснить неповторившийся отдельно backfill failure и получить зелёный полный
backend check нового пакета, локальный authenticated shadow parity
и A5 versioned public/AI/UI adapters, затем полная local visual verification и ограниченный rollout.
Thread allocations не доказывают peak/retained heap на production scale; дальнейший memory/SLA
бюджет выбирается по реальной нагрузке, не по synthetic timings. Автопубликация не включена.
Документация обновлена в том же пакете; 25 documentation unit tests и `git diff --check` проходят.
Strict checker сохраняет прежние шесть registered untracked MD files, новых нарушений нет,
index для обхода не менялся. Commit/push/deploy не выполнялись.

## Подтверждённые решения целевой модели (A и последующий B)

Заказчик подтвердил 20 сентября 2026 года следующие требования. Пункты 6, 7 и историческое
правило пункта 11 требуют этапа B; уточнение последовательности выше не отменяет их:

1. Основной и единственный пользовательский scope Weekly Review — продавцы, включённые в рейтинг.
2. Все действующие правила расчётов, знаков, возвратов, себестоимости, округления, attach-rate и
   достаточности сохраняются. Изменяется cohort, а не формулы.
3. В том числе четыре показателя «Результатов недели» должны считаться по продавцам рейтинга, а не
   по всему магазину.
4. Нужен отдельный внутренний сервис с полным каноническим набором показателей продавцов за период.
5. «Обзор», Weekly Review и последующие потребители используют общий сервис, но получают разные
   внешние контракты.
6. Основные итоги каждой недели должны отражать фактический состав продавцов соответствующей
   недели, а не молча применять текущий состав ко всей истории.
7. Ручное выключение `participatesInRanking` действует с момента изменения и не исключает
   сотрудника из более ранних фактов задним числом.
8. Смены остаются необязательным источником: их отсутствие ограничивает только workload-метрики.
9. Нулевая себестоимость остаётся допустимым бизнес-значением; отсутствующая себестоимость
   ограничивает только зависимые показатели.
10. Возврат без доступной исходной продажи остаётся допустимым состоянием и не приписывается
    произвольному продавцу.
11. Если исходная продажа доступна, возврат относится к seller-контуру по статусу продавца на
    дату исходной продажи. Последующее увольнение или выключение `participatesInRanking` не
    превращает такой возврат в операцию вне seller-контура.

## Рекомендуемые, но ещё не подтверждённые решения

Следующие правила являются рекомендацией для обсуждения:

1. Изменение состава (`CONTINUING`, `JOINED`, `LEFT`) рассчитывать всегда, чтобы математически
   объяснить изменение команды, но показывать менеджеру только при существенном влиянии.
2. Ушедшего продавца сохранять в исторических итогах периода, в котором он работал, но не создавать
   для него действие на будущую неделю и не включать в benchmark текущей команды.
3. Влияние состава не выделять постоянным крупным блоком. При несущественном эффекте оно остаётся
   в evidence; при существенном становится одним фактором «На итог повлияло изменение состава».
4. Персональный вклад показывать прежде всего абсолютной суммой изменения. Процент вклада
   показывать только при безопасном ненулевом знаменателе и однозначной интерпретации.
5. Внешнему AI-провайдеру передавать только разрешённые агрегаты seller-cohort. Имена и полные
   персональные показатели оставлять внутри детерминированного контура.

## Открытые продуктовые решения

До реализации соответствующего поведения необходимо подтвердить следующие решения. Исторические
пункты 1–4 и 6 относятся к B; политика ограничений атрибуции из 5 и 7 нужна и для A, причём ниже
приведены рекомендации, а не уже согласованное ужесточение состояний отчёта:

1. Должен ли ушедший продавец оставаться видимым в исторической детализации с пометкой
   «Больше не входит в текущую команду».
2. Какой порог делает влияние нового/ушедшего состава существенным: абсолютная сумма, доля общего
   изменения, доля выручки либо комбинированное правило.
3. Нужна ли администратору отдельная операция backdated correction, если флаг рейтинга ранее был
   настроен ошибочно. Обычное переключение флага подтверждено как не ретроактивное.
4. Как обработать периоды до появления достоверной истории состава продавцов. Их нельзя объявлять
   исторически точными без доказуемого источника.
5. Должен ли orphan return понижать seller core metrics до `LIMITED` либо оставаться отдельным
   attribution limitation без понижения чисел. Он не блокирует отчёт и не приписывается продавцу
   при любом варианте. Рекомендация: ненулевой orphan делает только потенциально затронутые
   денежные/структурные метрики `LIMITED` и весь отчёт `PARTIAL`, но не `BLOCKED`; предупреждение
   остаётся компактным и раскрывается в detail.
6. Должен ли повторно нанятый сотрудник автоматически наследовать прежний
   `participatesInRanking=true`. Текущий sync сохраняет флаг; рекомендация — сохранить это
   поведение, но открыть новый membership interval.
7. Как non-zero `UNATTRIBUTED_SALE` влияет на report state. Факт всегда исключён из seller total;
   рекомендация — `PARTIAL` и metric-scoped attribution limitation, но не `BLOCKED`, потому что
   нельзя доказать, был ли это продавец рейтинга или служебная операция.

Далее описан расширенный проект этапа B. Его temporal prerequisites не блокируют A сверх явно
перечисленных выше контрольных точек. Перечисленные решения не требуют менять фундаментальную
архитектуру B. P0 можно закрыть принятием либо
изменением рекомендованных defaults; runtime migration/hidden writer начинается только после
закрытия контрольной точки P0, а P4/P6 и cutover — после фиксации зависимых policies.

## Текущее расхождение

Текущий Weekly Review смешивает scope:

- четыре core KPI, revenue decomposition и структура продаж считаются по всему магазину;
- персональные карточки используют только активных сотрудников с активным назначением и
  `participatesInRanking=true`;
- store attach-rate не зависит от employee attribution;
- optional AI работает поверх store-level deterministic facts.

Из-за этого верхний итог нельзя математически объяснить суммой показанных продавцов. Операции
сотрудников вне рейтинга и факты без сотрудника могут выглядеть как результат команды продавцов.

«Обзор» уже имеет scope `SELLERS`, но его DTO не является достаточным источником Weekly Review: в
нём нет полного document breakdown, seller-only средней продажи, полного персонального вклада и
всех данных для недельного evidence. Weekly Review не должен вызывать frontend/API «Обзора» или
зависеть от его transport DTO.

## Ограничение текущей модели состава

`employee_store_assignments` хранит текущее состояние назначения, `assigned_at`, `updated_at` и
version, но не хранит полные интервалы `valid_from`/`valid_to`. Синхронизация деактивирует
отсутствующих сотрудников и назначения, а ручная настройка обновляет текущий
`participates_in_ranking`. Аудит ручного переключения не заменяет полную временную историю
активности сотрудника и назначения.

Следовательно, текущая схема не может надёжно восстановить все прошлые составы после нескольких
добавлений, увольнений, повторных активаций и переводов. Одного `updated_at` недостаточно.

## Целевая архитектура этапа B

```text
sales documents/items + returns + category/cost facts
                         +
       versioned seller membership for the store
                         ↓
               SellerPeriodAnalyticsService
               полный факт одного периода
                  ↙          ↓          ↘
         Overview mapper  Weekly pair  reconciliation
                              ↓
                   deterministic comparison
                              ↓
          Weekly Review snapshot/evidence/actions
                              ↓
              allowlisted compact AI projection
```

### Итог повторного архитектурного ревью

Выделенный seller analytics service остаётся оправданным решением. Он добавляет temporal storage
и migration cost, но удаляет более опасную сложность: разные фильтры продавцов, формулы и
исторические трактовки в «Обзоре», плане и Weekly Review. Сервис не должен становиться новым
универсальным API или заменять все employee-модули; это bounded internal analytics context с
отдельными consumer projections.

Это модуль существующего backend и той же PostgreSQL, без отдельного сетевого сервиса, очереди,
второй базы продаж или копирования транзакционных сумм. Новые таблицы хранят историю участия и
служебное состояние расчёта; финансовые факты остаются в существующих documents/items. Полный
event sourcing, bitemporal corrections и перевод payroll в эту модель в первый этап не входят.

Архитектура считается безопасной при четырёх обязательных границах:

1. Membership history публикуется только после успешного employee sync; незавершённый sync не
   меняет seller-историю.
2. Все коммерческие агрегаты получают eligibility из одной document-level projection, а не
   повторяют текущий assignment predicate.
3. V2 snapshot/codec остаётся замороженным; seller-first выпускается отдельным v3 контрактом.
4. Read/cutover выполняется через ограниченные режимы `LEGACY`, `SHADOW`, `CANONICAL`, поэтому
   расчёт можно сверить до изменения пользовательского ответа.

Без этих границ отдельный сервис действительно добавил бы лишний слой. С ними он является местом,
где сложность состава команды концентрируется один раз и проверяется независимо.

### Границы компонентов

#### `SellerMembershipHistory`

Хранит интервалы принадлежности сотрудника к seller-cohort конкретного магазина. Изменение
активности, назначения или участия в рейтинге закрывает предыдущий интервал и открывает новый.

Минимальная семантика интервала:

- `employeeId`;
- `storeId`;
- `employeeActive`;
- `assignmentActive`;
- `participatesInRanking`;
- `validFrom`;
- `validTo`;
- источник и тип изменения;
- audit/provenance без секретов и персональных payload.

Обычное ручное изменение получает `validFrom=changedAt` и не переписывает прошлые интервалы.
Отсутствие подходящего интервала всегда означает `UNKNOWN`, а не `NOT_ELIGIBLE`. Последнее
доказывается только явным интервалом, в котором хотя бы один из трёх признаков выключен.

#### `SellerCohortResolver`

Разрешает seller-membership для требуемого периода. Он не рассчитывает деньги и не решает, какие
показатели материальны. Результат должен позволять определить:

- продавцов периода;
- продолжающих работать продавцов;
- новых продавцов;
- ушедших продавцов;
- cohort hash и rule version.

Roster периода и eligibility коммерческого факта — связанные, но не одинаковые понятия. Roster
описывает, кто состоял в seller-cohort в течение периода. Eligibility каждого документа
определяет, должен ли его результат войти в seller-аналитику:

- продажа проверяет membership продавца в момент `sale.occurredAt`;
- связанный возврат наследует продавца и membership исходной продажи и поэтому проверяет
  `originalSale.occurredAt`, а не момент возврата;
- orphan return без исходной продажи и доказуемого автора не входит в seller-итоги и остаётся
  отдельным quality/reconciliation-фактом;
- сотрудник, уже не входящий в текущий roster, может появиться только как исторический автор
  возврата. Такой факт влияет на seller total, но не возвращает сотрудника в benchmark и не
  создаёт ему future action.

Это правило нельзя реализовывать фильтрацией документов по текущему
`employee_store_assignments.participates_in_ranking`: требуется temporal join, а для возврата —
join через `original_document_id`.

#### `SellerPeriodAnalyticsService`

Считает полный типизированный набор фактов одного периода. Он не содержит текстов интерфейса,
недельной интерпретации, плана, provider-вызовов или правил публикации.

#### `SellerPeriodComparisonService`

Сравнивает два результата, раскладывает изменение по сотрудникам и изменению состава, применяет
существующие правила delta, sufficiency и materiality. Он не формирует transport DTO.

#### Проекторы потребителей

- `Overview` получает актуальное подмножество для выбранного периода и своего API-контракта.
- Weekly Review получает два закрытых периода, evidence и immutable snapshot-контракт.
- AI input compactor получает только allowlisted агрегаты и уже проверенные факторы.

Внутренняя модель не должна иметь JSON-аннотации публичного API и не должна становиться одним
«универсальным endpoint» со всеми рассчитанными полями.

## Канонический набор фактов периода

Набор должен быть полным, но ограниченным утверждёнными бизнес-показателями. «Максимальный» не
означает произвольные комбинации ради количества.

### Контекст и provenance

- store ID;
- start/end периода и timezone;
- calculated at;
- seller-membership rule/version и cohort hash;
- formula versions финансов, категорий, attach и workload;
- источник и свежесть sales/returns/classification/cost;
- отдельная свежесть последней успешной membership publication.

### Финансы продавцов

- sales revenue;
- return revenue;
- net revenue;
- net quantity;
- cost amount;
- gross profit;
- margin percent;
- sale document count;
- return document count;
- average sale;
- return share при допустимом знаменателе.

Для потребителей с графиком/планом сервис также возвращает дневные buckets в timezone магазина:
sales revenue, return revenue, net revenue, accessory, service и additional. Итог buckets обязан
сходиться с итогом периода; отдельная формула для графика не допускается.

### Дополнительные продажи и структура

- additional revenue и quantity;
- additional share;
- accessory revenue/quantity, действующая доля в net revenue и доля во внутренних продажах допов;
- service, warranty and protection revenue/quantity, действующая доля в net revenue и доля во
  внутренних продажах допов;
- residual дополнительных категорий как контроль целостности; при действующей классификации
  ожидается ноль, потому что утверждено `Допы = Аксессуары + Услуги`;
- category-level revenue, quantity, cost, gross profit и margin;
- data-quality counters для классификации и себестоимости.

### Attach

Для каждого действующего attach-кода:

- numerator quantity;
- denominator quantity;
- rate per hundred;
- sample sufficiency;
- classification limitations.

Итог seller-cohort считается как отношение суммы числителей к сумме баз, а не как среднее
процентов сотрудников. Единого среднего по разным attach-кодам не создаётся.

### Сотрудники

Для каждого сотрудника периода:

- те же доступные финансовые и коммерческие факты;
- completed sales;
- средняя продажа;
- attach numerator/denominator/rate;
- shifts, hours, revenue per shift/hour как optional workload;
- active/current-team markers отдельно от исторического участия.

## Сохраняемые формулы и инварианты

Действующие формулы не переносятся во frontend или AI. Общий сервис переиспользует утверждённые
правила знака, классификации, округления и quality.

```text
NetRevenue = SalesRevenue - ReturnRevenue
GrossProfit = NetRevenue - CostAmount
MarginPercent = GrossProfit / NetRevenue * 100
WeeklyAverageSale = SalesRevenue / SaleDocumentCount
AdditionalShare = AdditionalRevenue / NetRevenue * 100
AccessoryShare = AccessoryRevenue / NetRevenue * 100
ServiceShare = ServiceRevenue / NetRevenue * 100
AccessoryMixShare = AccessoryRevenue / AdditionalRevenue * 100
ServiceMixShare = ServiceRevenue / AdditionalRevenue * 100
AttachRate = max(0, Sum(RawNumeratorQuantity)) / Sum(RawDenominatorQuantity) * 100
```

Null/недоступность сохраняются при недопустимом знаменателе или неполной обязательной базе.
Маржа сотрудников не усредняется: сначала суммируются деньги, затем рассчитывается общая маржа.
`AdditionalShare`, `AccessoryShare` и `ServiceShare` сохраняют действующий denominator net
revenue. Mix shares отвечают на отдельный вопрос «из чего состояли сами допы». При
`AdditionalRevenue <= 0` mix percentages недоступны, но абсолютные суммы не скрываются. Это новые
производные поля, а не изменение существующих формул.

`SaleDocumentCount` в weekly average сохраняет все неудалённые SALE-документы seller scope,
включая all-EXCLUDE документ. `CompletedSalesSample` — отдельный счётчик: текущий team reader
дополнительно требует `source_document_type='sale'` и хотя бы одну неудалённую не-EXCLUDE позицию.
`AverageReceipt` в других действующих API также не переименовывается в `WeeklyAverageSale`:
его числитель включает возвраты. Отдельные formula IDs сохраняют эти различия.

Attach denominator должен быть строго положительным. Внутренние numerator/denominator сохраняют
знак; clamp выполняется после суммирования на запрошенном уровне. Например, raw numerators
двух сотрудников `-2` и `5` дают team numerator `3`, а не `5`. Аддитивность проверяется для raw
фактов; проценты и clamped employee numerators не суммируются.

Обязательные reconciliation:

```text
sum(seller employee facts) = seller cohort total
sales revenue - return revenue = net revenue
additional = accessory + service
service includes service/warranty/protection categories
sum(category facts including unmapped) = seller cohort total
sum(employee attach numerator/denominator) = seller attach numerator/denominator
```

Полный store-контур сохраняет внутреннюю проверку по каждому поддержанному типу величины:

```text
store analytic facts
= seller cohort
 + known outside seller cohort
 + unknown membership history
 + orphan returns
```

Этот остаток не показывается как второй пользовательский Weekly Review scope.

## Сравнение периодов при изменении состава

Подтверждено, что основные итоги используют фактический состав каждой недели. Для объяснения
изменения comparison классифицирует сотрудников:

```text
CONTINUING = присутствует в обоих seller cohorts
JOINED = присутствует только в current cohort
LEFT = присутствует только в previous cohort
```

Математическая декомпозиция:

```text
CurrentTeam - PreviousTeam
= sum(Current - Previous for CONTINUING)
 + sum(Current for JOINED)
 - sum(Previous for LEFT)
 + sum(Current facts for HISTORICAL_RETURN_ONLY)
 - sum(Previous facts for HISTORICAL_RETURN_ONLY)
```

Декомпозицию рекомендуется рассчитывать всегда. Решение о постоянном отображении не принято:
предпочтительный UX показывает её только при материальном влиянии либо в detail/evidence.
`HISTORICAL_RETURN_ONLY` — роль employee в конкретном периоде, а не четвёртая взаимоисключающая
группа людей в паре недель. `LEFT` может одновременно иметь current return-only fact; `JOINED`
после повторного найма — previous return-only fact. Каждый факт учитывается ровно один раз.
Return-only вклад объясняет возвраты и не входит в composition materiality.

Декомпозиция аддитивна для денег и raw quantities, но не для процентов, margin и average sale.
`CONTINUING` означает присутствие хотя бы части обеих недель, а не одинаковую занятость. Без
сопоставимых смен нельзя называть его delta доказанным ростом/снижением эффективности или
причинным влиянием найма. Формулировка вывода — «вклад в изменение результата».

## Краевые случаи и предлагаемые правила

| Случай | Расчёт | Основной интерфейс | Статус решения |
|---|---|---|---|
| Новый продавец без продаж | Не влияет на суммы | Не создавать негативную карточку | рекомендовано |
| Новый продавец с продажами | Входит в current total, отдельный `JOINED` effect | Показывать влияние только если существенно | формула подтверждена, UX открыт |
| Ушёл в течение недели | Факты до effective end сохраняются | Историческая карточка без будущего action | открыто |
| Ушёл между неделями | В previous cohort как `LEFT` | Фактор состава только при существенности | открыто |
| Ручное выключение рейтинга | Действует с changedAt, прошлые суммы не переписывает | Revision также возможна из-за изменения будущих действий | подтверждено для сумм, actionability рекомендована |
| Ручное включение рейтинга | По умолчанию действует с changedAt | Backdated correction требует отдельного решения | частично открыто |
| Повторный найм | Новый membership interval, тот же employee ID | Не смешивать интервалы молча | рекомендовано |
| Перевод магазина | Факты остаются в store документа | Не объединять магазины | рекомендовано |
| Переименование | Старый snapshot сохраняет имя на момент генерации; name-only change не меняет финансовую revision | Отдельная presentation correction только при явной потребности | рекомендовано |
| Есть смена, нет продаж | Денежные факты нулевые | Нейтральный контекст, не автоматический `ATTENTION` | рекомендовано |
| Нет продаж и нет смен | Нельзя доказать работу в периоде | Не показывать персональную оценку | рекомендовано |
| Неполные смены | Sales-метрики доступны | Ограничить только workload | подтверждено |
| Малая sales/attach база | Значение хранится с sufficiency | Не выдавать нестабильный процент за вывод | действующее правило |
| Нулевая выручка | Сумма доступна, доли/маржа по правилам знаменателя | Показать абсолютные значения | действующее правило |
| Отрицательная выручка | Возвраты сохраняют знак | Не показывать вводящий в заблуждение contribution percent | рекомендовано |
| Встречные employee deltas | Абсолютные вклады сохраняются | Не делить на почти нулевой team delta | рекомендовано |
| Все продавцы новые | Current total доступен, like-for-like отсутствует | Объяснить изменение состава, не рост эффективности | рекомендовано |
| Нет roster и seller-фактов | Store fallback запрещён; пустой доказанный состав отличается от неизвестной истории | Нейтральное empty состояние либо `BLOCKED` при недоказанной базе | рекомендовано |
| Нет roster, но есть доказанные seller-возвраты | Возвраты остаются в итогах | Return-only отчёт без оценки действующей команды | рекомендовано |
| Missing cost | Revenue готова, profit/margin unavailable | Адресное ограничение | подтверждено |
| Zero cost | Допустимое значение | Без limitation | подтверждено |
| Unmapped category | Total сохраняется, структура limited | Ограничить только зависимые выводы | действующее правило |
| Orphan return | Не приписывать продавцу | Не ухудшать произвольную карточку | подтверждено |
| Return после ухода | Связать с автором исходной продажи; membership определить на дату исходной продажи | Не создавать future action ушедшему | подтверждено |

Для недельной декомпозиции return-only автор, уже не состоящий в roster недели возврата, хранится
отдельно от `CONTINUING/JOINED/LEFT` как исторический автор возврата. Его сумма участвует в
reconciliation seller total, но не в сравнении эффективности действующей команды.

## Snapshot и invalidation

Новая seller-first версия snapshot должна явно сохранять:

- `scope=SELLERS`;
- current и previous cohort hashes;
- membership rule version;
- группы `CONTINUING/JOINED/LEFT` либо достаточные facts для их доказательства;
- formula/policy versions;
- source sync provenance;
- metric-scoped quality и evidence.

Новая immutable revision создаётся, если изменились:

- продажи/возвраты или их attribution;
- классификация или себестоимость;
- membership intervals/cohort hash;
- формула или Weekly Review policy.

Planner должен учитывать не только новый successful sync, но и изменение seller cohort. Чтение
старого snapshot не должно динамически фильтровать сотрудников по текущему roster.

Существующие store-wide snapshots остаются историческими store-wide snapshots. Их нельзя молча
переименовать в seller-first отчёты; после выпуска должен быть создан новый snapshot новой policy.

## Контрактные границы

### «Обзор»

Использует проекцию нужного подмножества `SellerPeriodAnalytics`. Transport shape меняется только
при отдельной необходимости. Результат за совпадающий период и cohort обязан совпадать с Weekly
Review facts до snapshot-specific comparison/presentation.

### Weekly Review

Использует два закрытых периода и сохраняет comparison, factors, actions, team и evidence. Все
видимые подписи должны однозначно обозначать seller scope. `STORE.*` evidence refs нельзя
переиспользовать с новой seller-семантикой без versioned migration.

### AI input

Полный внутренний результат не является provider payload. Compactor допускает только
allowlisted aggregate seller facts со стабильными IDs, units, states и evidence. Employee names,
public IDs и произвольные персональные строки остаются запрещёнными до отдельного privacy decision.
AI не вычисляет KPI, cohort, contribution или materiality.

## Транзакционность и конкурентность

Weekly pair должен читаться в одной read-only `REPEATABLE_READ` транзакции. Состав, current facts,
previous facts и quality относятся к одному согласованному DB snapshot. Конкурентное изменение
рейтинга либо полностью попадает в следующий расчёт, либо полностью остаётся за границей текущего.

Snapshot persistence повторно проверяет cohort/source/policy identity либо создаёт следующую
revision. Частично смешивать старый cohort с новыми facts нельзя.

## Проверки будущей реализации

До переключения Weekly Review обязательны:

1. Golden parity: при неизменном roster новый сервис совпадает с текущим `Overview SELLERS` по
   существующим формулам и округлению.
2. Repository integration: sale, partial/full return, orphan return, missing/zero cost, unmapped,
   deleted facts и каждый attach denominator.
3. Cohort integration: join, leave, toggle off/on, rehire, transfer и concurrent change.
4. Reconciliation: employee sums, category sums, additional hierarchy, attach weighted aggregate и
   store residual.
5. Snapshot immutability и новая revision при cohort hash change.
6. Contract compatibility: старый store-wide snapshot не парсится как новый seller-first scope.
7. AI privacy/semantic gate: employee scope отсутствует, числа и действия backend-owned.
8. Local authenticated real-data checks для обоих настроенных магазинов без вывода business values.
9. Desktop/tablet/mobile visual review после material UI change.
10. Полные backend/frontend/documentation gates перед выпуском.

## Аудит логики перед реализацией

Повторная проверка текущего кода выявила границы, которые нельзя потерять при реализации.

### Состав, eligibility и actionability — разные состояния

Нельзя использовать один boolean `rankingEligible` сразу для трёх задач:

1. `period roster` — сотрудник имел хотя бы один eligible-интервал внутри отчётной недели;
2. `fact eligibility` — конкретная продажа или исходная продажа возврата произошла внутри
   eligible-интервала;
3. `actionable now` — сотрудник входит в действующую команду на момент формирования revision и
   ему можно назначить действие на следующую неделю.

Продавец может находиться в period roster, но уже не быть actionable. Бывший продавец может не
находиться в period roster недели возврата, однако его связанный возврат остаётся seller-фактом.
Эти признаки должны храниться раздельно во внутренних моделях и snapshot.

### Двухнедельный baseline недостаточен

Возврат отчётной недели может ссылаться на продажу значительно старше двух сравниваемых недель.
Поэтому baseline только на current/previous period не доказывает seller-eligibility такого
возврата. Нужна явная `membershipCoverageStart` для магазина:

- lookup до этой границы возвращает `UNKNOWN`, а не `false`;
- текущий assignment запрещено использовать как молчаливый исторический fallback;
- неизвестные факты входят в reconciliation bucket и адресно ограничивают seller-метрики;
- baseline можно расширить назад только доказуемым источником или отдельно утверждённой
  backdated correction.

Без утверждённого исторического источника рекомендуемый rollout — baseline с момента cutover,
затем shadow warm-up до накопления двух полных закрытых недель. Это откладывает первый
полноценный seller-first comparison, зато не выдаёт текущий roster за прошлую истину. Возвраты к
продажам до coverage boundary и после warm-up остаются `UNKNOWN` и ограничивают только метрики,
которые они потенциально меняют. Если заказчику нужен немедленный cutover, требуется отдельный
approved baseline с доказуемой датой, а не технический backfill из текущего assignment.

### Время операции и timezone

Период выбирается по `business_date` магазина, но membership проверяется по `occurred_at`:

```text
SALE reporting period = sale.business_date
SALE membership instant = sale.occurred_at

RETURN reporting period = return.business_date
RETURN membership instant = original_sale.occurred_at
```

Интервалы membership хранятся как half-open `[validFrom, validTo)` в UTC. Граница ручного
изменения берётся из server `Clock`. Для sync без доверенного source timestamp effective time —
момент наблюдения системой; это должно быть отмечено в provenance и не выдаваться за фактическую
дату увольнения.

Текущая normalization вычисляет `business_date` через общий `TimeConfig.businessZone`
(`Europe/Kaliningrad`), а часть planning/reporting читает `store.timezone`. До seller cutover нужно
доказать их совпадение для каждого target store. Если зоны расходятся, это отдельная prerequisite
миграция бизнес-даты; seller-first код не должен одновременно и незаметно менять границы периодов.

### Смены не имеют времени внутри дня

`employee_work_shifts` хранит `work_date` и количество часов, но не точные начало/конец смены.
Если membership изменился внутри этого business day, нельзя корректно сопоставить только часть
выручки с полной сменой. Денежные показатели остаются точными, но `revenuePerHour` и
`revenuePerShift` за такой период получают limitation и не используются для action/benchmark.

### Document counts имеют разные текущие семантики

Текущий Weekly Review считает SALE/RETURN documents независимо от наличия не-`EXCLUDE` строки,
а employee completed-sales sample считает только документы с такой строкой. Эти определения нельзя
случайно объединить во время рефакторинга. Каноническая модель хранит отдельно:

- decomposition document counts, сохраняющие текущую Weekly Review семантику;
- employee completed-sales sample, сохраняющий текущую team семантику.

Golden tests должны доказать совпадение до отдельного продуктового решения об унификации.

### Unattributed sale и orphan return — разные buckets

SALE без employee attribution известным образом не принадлежит seller-cohort: по принятому scope
он относится к `KNOWN_OUTSIDE_SELLER_COHORT`. RETURN без найденной исходной продажи отличается:
его seller-membership пока неизвестен и late link может перенести его в seller facts. Поэтому
repository и quality policy не должны объединять эти случаи. Linked return исходной продажи без
employee также является known outside, а не orphan.

При этом `KNOWN_OUTSIDE_SELLER_COHORT` не должен терять причину. Минимальные reason codes:
`EXPLICITLY_INELIGIBLE_EMPLOYEE`, `UNATTRIBUTED_SALE` и
`LINKED_TO_UNATTRIBUTED_ORIGINAL`. Первый — доказанный non-seller факт, два последних — исключённые
по строгому seller scope факты с отдельным attribution-quality сигналом. Это позволяет не включать
их в seller total, но и не скрывать потенциальную проблему источника данных.

### Старые snapshot требуют versioned codec

Добавление новых полей в существующий `WeeklyReviewResponse` и общий пересчёт hash изменят hash
старого v2 payload даже при неизменном JSON. Это сделает сохранённые snapshots нечитаемыми.
Поэтому обязательны:

- v2 decoder и точный прежний алгоритм `ContentV2` hash;
- v3 decoder и отдельный `ContentV3` hash;
- dispatch по persisted `report_contract_version` до проверки integrity;
- чтение v2 и v3, но запись после cutover только v3;
- integration test существующего v2 fixture до изменения production path.

### Planner сейчас не видит изменение roster

`WeeklyReviewSnapshotPlanningService` сравнивает source sync time и policy versions. Ручное
изменение `participatesInRanking` без новой sales sync сейчас не создаст revision. Новая identity
должна учитывать:

- membership revision/high-watermark;
- current и previous period cohort hashes;
- current actionability roster hash;
- source sync provenance;
- formula, contract, snapshot и quality policy versions.

`completedAt` успешного sync недостаточно считать semantic identity: no-op sync иначе будет снова
планировать тот же отчёт после каждого запуска. Нужна транзакционная domain revision с полным
writer inventory из P7. Планировщик сравнивает semantic identity, а
sync run хранится как provenance. Если вычисление было выполнено, но content не изменился,
операционный planning checkpoint должен запомнить проверенную identity и не запускать одну и ту же
работу каждые пять минут.

### Employee sync сейчас коммитит изменения частями

`EmployeeSyncService` вызывает несколько отдельных transactional persistence methods. Ошибка после
части записей может оставить изменённые employee/assignment при FAILED sync run. Предыдущая
редакция предлагала оставить частичные записи и публиковать history в конце. Этого недостаточно:
конкурентный run может изменить current rows перед publisher, а manual writer — перенести в
history неопубликованное состояние.

Уточнённый вариант для ограниченного справочника сотрудников: получить и проверить весь пакет
вне транзакции, затем одним `EmployeeSyncBatchApplier` атомарно применить employee/assignment,
raw normalization, missing deactivation, history и `SUCCESS`. Никаких network calls под lock.
Объём/длительность этой транзакции измеряются в P2; если batch превышает принятый бюджет,
требуется отдельное staging/promote решение с привязкой к run, а не возврат к чтению произвольных
частично записанных current rows.

### Автопубликация должна учитывать свежесть membership

Sales/returns coverage не доказывает актуальность состава. Source identity и quality сохраняют
последний successful employee membership publication time/run отдельно от sales sync. Для
понедельничной автоматической публикации рекомендуется дождаться успешной employee publication
после окончания недели. Если она не появилась до preparation SLA, допустим только `PARTIAL`
report: исторические доказанные деньги остаются видимыми, `actionableNow` считается ограниченным,
а новые персональные future actions не создаются. FAILED sync никогда не используется как
признак свежести.

### Один внешний REPEATABLE_READ вокруг generate недостаточен

Внешняя `REPEATABLE_READ` транзакция не должна объединять тяжёлое чтение facts с поздним
выбором последней revision: snapshot чтения мог быть создан до commit другого генератора.
Нужны две транзакции. Но lock между генераторами сам по себе не блокирует sync/manual writers:
между повторной проверкой identity и insert источник всё ещё может измениться. Поэтому P7
дополнительно вводит общий source revision fence, участвующий в commit всех влияющих writers.

### AI scope нельзя оставить `STORE`

Seller aggregate не должен маскироваться evidence/action scope `STORE`. В v3 report scope имеет
значение `SELLERS`, а агрегированные evidence и root actions используют существующий scope `TEAM`.
Это разделяет предмет отчёта и тип цели действия без расширения существующего evidence enum.
Employee evidence остаётся запрещённым provider payload. Старое `STORE.*` evidence нельзя
переиспользовать с новым смыслом.

## Реестр основных рисков

| Риск | Последствие | Архитектурная защита | Release gate |
|---|---|---|---|
| Нет достоверной прошлой истории roster | Текущий состав ошибочно применяется к прошлым продажам | `UNKNOWN`, approved baseline и warm-up без backdating | P0, P11 |
| FAILED employee sync частично изменил current rows | Ложное включение/исключение продавца | Atomic batch apply после fetch; current и history коммитятся вместе | P2 |
| К понедельнику нет свежей employee publication | Устаревшие current-team actions | Отдельная membership freshness, SLA и запрет новых actions в fallback | P4, P8, P11 |
| Manual toggle пересёкся с sync batch | Потерян flag или overlap intervals | Единый lock order, typed transitions и optimistic conflict | P2, P10 |
| Return относится к старой продаже | Двух недель history недостаточно | Lookup по original sale instant; coverage limitation | P1, P3 |
| V3 ломает hash старого v2 | Старые отчёты становятся нечитаемыми | Frozen v2 type, versioned decoder и `ContentV2` | P6 |
| Rollback после появления v3 | Absolute latest несовместим с v2 | Latest-compatible query по contract mode | P6, P11 |
| No-op sync вызывает вечную regeneration | Лишняя нагрузка и шум revisions | Semantic identity и mutable generation checkpoint | P7 |
| Temporal join выполняется на item rows | Резкий рост времени SQL | Membership lookup на document cardinality, индексы и query-plan gate | P3, P10 |
| Один quality flag скрывает полезные числа | Менеджер не видит доступную часть отчёта | Metric dependency graph и адресные limitations | P4, P6 |
| Новый сервис расходится с «Обзором» | Два ответа на один вопрос | Общая внутренняя модель, parity и reconciliation tests | P4, P5 |
| Seller-агрегаты или PII уходят в AI/logs | Privacy и semantic risk | Allowlist compactor и sanitized shadow telemetry | P8, P11 |
| Timezone normalization не совпадает с store | Продажа попадает не в ту неделю | Отдельный prerequisite gate до seller cutover | P0 |
| Две недели history переключены на месячный Overview | Неполный факт выглядит полным месяцем | Coverage в Overview/plan API, отдельный gate historical ranges | P5 |
| Source writer закоммитился после identity check | Snapshot ошибочно выглядит актуальным | Общий transactional revision fence, source stability gate | P7 |
| Старый executable возвращён после включения history | Новые toggles не записываются в историю | Rollback только до совместимого writer/reader baseline | P11 |

## Решение о готовности этапа B

К реализации можно переходить поэтапно, начиная с P0. Архитектурный фундамент достаточно
определён, чтобы завершить ADR и product defaults; schema migration, baseline tooling и hidden
membership writer начинаются после этой контрольной точки. Нельзя одним релизом сразу менять
Overview, Weekly Review, AI и автоматическую публикацию: это лишит нас сравнимого baseline и
надёжного rollback.

До пользовательского cutover обязательно закрыть открытые product defaults, пройти warm-up либо
утвердить доказуемый historical baseline, получить parity в `SHADOW` и доказать чтение/откат v2.
Таким образом, решение увеличивает локальную инфраструктурную сложность, но снижает общую
сложность продукта и риск расхождения показателей.

### Дополнительное контрольное ревью: границы влияния

Ревью сверено с `OverviewMetricsService`, `WeeklyReviewRevenueRepository`,
`WeeklyReviewPolicyV1`, `WeeklyReviewEmployeeFactsReader`, `EmployeeSyncService`,
`EmployeeSyncPersistence`, `WeeklyReviewSnapshotStore/Codec`, attach view V38 и ADR-0002.
Это design/code inspection, а не доказательство будущего runtime-поведения. Следующие проверки
должны быть выполнены на реализации:

| Потребитель | Что меняется | Что доказывается до переключения |
|---|---|---|
| Weekly Review | Seller факты, temporal cohort и v3 snapshot | Средняя продажа, return-only, v2/v3 integrity, source stability |
| Overview SELLERS | Выбор eligible facts и membership coverage | Week/month/custom, единый cohort в amount/share, quality UI |
| План SELLERS | Дневной факт и полнота месячного факта | Неизменный target, bounded forecast при неполном coverage |
| Overview STORE, store structure/attach | Пользовательская семантика сохраняется | Regression и parity shared classifier после extraction |
| Рейтинг сотрудников | Temporal переход пока не выполняется | Сохранены текущие формулы/кандидаты; различие исторических сумм явно объяснено |
| Зарплата | Собственный PayrollSalesRepository сохраняется | Golden payroll fixtures и отсутствие обходных изменений через shared sync/views |
| Настройка участия и employee sync | Атомарная запись current/history | Conflicts, rollback batch, повторный найм, несколько stores, потеря lease |

Критические уточнения этого прогона: temporal coverage проверяется для каждого запрошенного
периода; classifier attach получает ключи документа; weekly average и completed-sales sample
не объединяются; snapshot persistence координируется с source writers; безопасный rollback
сохраняет запись истории. До реализации этих мер фраза «ничего не сломается» не подтверждена.

## Глубокий план реализации по коду: отложенный этап B

P0–P11 не являются текущей очередью первого этапа A. Перед возвратом к ним требуется повторный
review относительно реализованного A: повторно не добавлять уже введённый общий сервис и не
занимать уже использованную версию snapshot другим контрактом. План B выполняется последовательно.
Переход к следующему пакету разрешён только после code review,
проверки тестов пакета, сверки с этим документом и фиксации обнаруженных отклонений.

### P0. Закрыть решения и зафиксировать контракты

До изменения runtime-кода:

1. Закрыть оставшиеся продуктовые решения из этого документа.
2. Принять ADR с temporal membership и следующими инвариантами:
   - seller eligible только при одновременных `employeeActive`, `assignmentActive` и
     `participatesInRanking`;
   - обычное изменение действует только вперёд;
   - linked return использует membership исходной продажи;
   - отсутствие покрывающего исторического интервала всегда означает `UNKNOWN`, не `false`;
     `authoritative_from` задаёт нижнюю границу доказуемого baseline, но не заполняет gaps;
   - store fallback для Weekly Review запрещён.
3. Зафиксировать точные formula IDs и названия двух разновидностей document count.
4. Зафиксировать v3 transport fields, scope vocabulary и backward compatibility.
5. Выбрать политику initial baseline отдельно для каждого действующего магазина.
6. Выполнить read-only preflight на локальной копии данных без сохранения business values:
   - timezone магазина совпадает с normalization business zone;
   - оценены counts original sales до предполагаемой coverage boundary;
   - подтверждено, какой источник доказывает начальный roster;
   - существующий v2 snapshot сохранён как sanitized golden fixture для codec test.
7. Зафиксировать рекомендуемые default-решения: orphan и unattributed sale дают только
   metric-scoped `LIMITED`, rehire наследует rating flag и открывает новый interval, backdated
   correction не входит в первый релиз.
8. Утвердить writer inventory/revision fence и atomic employee batch budget. Зафиксировать
   политику historical month/custom Overview при отсутствии baseline; Week warm-up её не заменяет.

Контрольная точка P0:

- ADR принят и зарегистрирован в documentation inventory;
- нет неявного backdating;
- baseline имеет владельца, источник, coverage boundary и способ проверки;
- известна первая календарная неделя, для которой допустим seller-first read cutover;
- timezone prerequisite закрыт либо выделен в отдельную предшествующую миграцию;
- API/schema draft проходит backend/frontend review до написания projector-кода.

### P1. Добавить temporal membership storage

Следующая свободная Flyway migration создаёт:

1. `seller_membership_history`:
   - surrogate ID;
   - `employee_id`, `store_id`;
   - `employee_active`, `assignment_active`, `participates_in_ranking`;
   - `valid_from`, `valid_to` с `[)` semantics;
   - `change_source`: `BASELINE`, `SYNC`, `MANUAL`; backdated correction не резервируется как
     якобы реализованный path и при будущем решении получает отдельную версию контракта;
   - `effective_time_source`: `SOURCE`, `OBSERVED`, `APPROVED_BASELINE`;
   - nullable actor/sync provenance и bounded reason;
   - created timestamp.
2. GIST exclusion constraint против пересекающихся интервалов одного employee/store.
3. Partial unique/index для открытого интервала и индекс period lookup.
4. `store_seller_membership_state`:
   - `store_id`;
   - monotonic `membership_revision`;
   - `authoritative_from`;
   - `updated_at`;
   - baseline provenance без персональных payload.
5. Отдельные FK на `employees` и `stores` с поведением, сохраняющим историю. History не зависит
   FK от mutable `employee_store_assignments`: удаление/пересоздание current projection не должно
   уничтожать или делать недоступными прошлые интервалы.
6. Database checks: `valid_to > valid_from`, допустимые enums, bounded provenance и согласованный
   store/employee source scope.
7. Source revision state из P7 создаётся до подключения writer hooks. Список semantic writers
   и общий lock order утверждаются в P0, а не обнаруживаются после включения generation.

История является append-mostly: закрытый интервал нельзя переписать или удалить обычным
application path. Разрешено только атомарно закрыть текущий открытый интервал и добавить следующий.
Если два изменения получили одинаковую DB precision времени, writer должен применить
детерминированную monotonic boundary policy и не создавать нулевой интервал.

DB trigger разрешает update history row только как однократный переход `valid_to: NULL -> value`
при неизменности остальных semantic/provenance полей; повторное изменение закрытого интервала и
DELETE запрещены. Это строже одного application-level соглашения и сохраняет audit trail.

Baseline migration не должна автоматически объявлять `assigned_at` или текущий flag полной
исторической истиной. Seed выполняется отдельной проверяемой операцией после P0 decision и создаёт
явный state interval для каждого доказанного employee/store assignment, включая inactive и
`participatesInRanking=false`, а не только для eligible продавцов. Store `authoritative_from`
совпадает с утверждённой нижней границей baseline; employees без доказанного assignment остаются
`UNKNOWN`.

Baseline seed выполняется под тем же publication guard, что и manual/sync writes: read текущего
состояния, intervals и включение history writer составляют одну согласованную bootstrap операцию.
Повторный seed идемпотентен и не стирает уже накопленную историю. Legacy-режим выдачи не означает
отключения writer после bootstrap: история продолжает накапливаться и при shadow, и при rollback.

Контрольная точка P1:

- Flyway clean migration и upgrade на копии текущей схемы проходят;
- DB запрещает overlap и второй open interval;
- lookup различает `ELIGIBLE`, `NOT_ELIGIBLE`, `UNKNOWN`;
- отсутствие интервала после `authoritative_from` также остаётся `UNKNOWN`, если нет явного
  покрывающего state interval;
- rollback приложения не требует удаления history tables.

### P2. Ввести единый writer membership

Добавить внутренние типы и сервисы:

- `SellerMembershipState`;
- `SellerMembershipChangeSource`;
- `SellerMembershipHistoryRepository`;
- `SellerMembershipHistoryWriter`;
- `SellerMembershipConsistencyChecker`.

Writer в одной транзакции:

1. Использует единый lock order: connection publication guard, store membership states по
   `store_id`, employees/assignments по стабильным ID, затем open intervals. Guard нужен до
   изменений current rows, включая создание первого interval, когда блокировать interval ещё нечего.
2. Принимает typed transition intent. Sync transition публикует все три итоговых признака, manual
   transition меняет только `participatesInRanking`.
3. Не пишет событие при семантически одинаковом состоянии.
4. При изменении закрывает interval, открывает новый и увеличивает store revision.
5. Не сохраняет имя, email, raw provider payload или другие лишние персональные данные.

Ручной path подключает writer непосредственно к
`EmployeeRatingSettingsService.updateParticipation`: assignment update, audit record и membership
interval коммитятся атомарно. Если assignment появился только в незавершённом sync и ещё не имеет
published baseline/open interval, ручная операция завершается контролируемым conflict, а не
создаёт историю из недоказанного состояния.

После завершения всех LiveSklad reads и validation отдельный `EmployeeSyncBatchApplier` вызывается
через Spring transactional proxy и в одной DB-транзакции:

1. Берёт publication guard и проверяет run/lease fencing: просроченный worker или пакет старше
   уже применённого employee observation не может записать состояние поверх нового.
2. Сохраняет весь validated batch и применяет deactivation отсутствующих assignments/employees.
   Вложенные persistence methods присоединяются к этой транзакции; per-row `REQUIRES_NEW` запрещён.
3. Сравнивает membership history с итоговым состоянием именно этого пакета. При изменении
   глобального `employee_active` учитывает все затронутые назначения, не только первый магазин.
4. Закрывает/открывает нужные intervals с единым observed effective time.
5. Увеличивает revisions только затронутых магазинов.
6. Переводит employee sync run в `SUCCESS` в той же транзакции.

Сбой откатывает current rows и history вместе; FAILED фиксируется отдельно после rollback.
Уже закоммиченный `SUCCESS` нельзя заменить FAILED из-за ошибки последующего формирования
ответа/telemetry. Seller analytics и `actionableNow` читают history; current projection остаётся
совместимой для rating/payroll/settings. No-op run обновляет observation freshness, но не
membership revision. Неполный fetch/пагинация/ошибка одного магазина не трактуются как пустой roster.

Batch applier и manual update используют один lock order. Если manual toggle закоммитился первым,
batch обязан сохранить его flag; если первым закоммитился batch, manual update открывает
следующий interval. Оптимистическая версия assignment остаётся пользовательским conflict guard,
но не заменяет DB serialization membership writer.

Effective time берётся после получения guard; его нельзя вычислять до ожидания lock. Timestamp
нормализуется до точности PostgreSQL, monotonic policy документируется и покрывается boundary
тестом. При проверке старого периода intervals обрезаются его границами: закрытие open interval
сегодня не меняет cohort hash прошлой недели. История original sales до периода учитывается
отдельно в fact eligibility/source identity.

Будущий manual employee update path также обязан атомарно обновить current state, audit и history.
Database trigger не генерирует membership transitions: их пишет явный application writer.
Защитный trigger неизменяемости из P1 при этом остаётся допустимым.

Контрольная точка P2:

- no-op sync не увеличивает revision;
- off/on, deactivate/reactivate, перенос между магазинами и повторный найм создают правильные
  интервалы;
- optimistic-lock conflict не создаёт history;
- manual update во время sync finalization сериализуется без deadlock и не теряется;
- FAILED/PARTIAL sync не меняет ни current employee state, ни published membership;
- concurrent/recovered workers не применяют более старый пакет поверх нового;
- partial fetch одного магазина не деактивирует roster;
- rollback всего batch и latency/lock budget подтверждены PostgreSQL integration tests;
- закрытие interval за границей периода не меняет его cohort hash.

### P3. Реализовать единый seller fact selection

Создать repository projection, которая один раз определяет eligibility и используется всеми
seller-агрегациями. Логика документа:

```text
effectiveEmployee =
  SALE   -> sale.employee_id
  RETURN -> original_sale.employee_id

membershipAt =
  SALE   -> sale.occurred_at
  RETURN -> original_sale.occurred_at

reportingDate = document.business_date
```

Membership lookup выполняется один раз на документ до join с items: сначала выбираются документы
двух периодов, затем attribution/original, затем interval lookup, и только после этого позиции.
Это исключает GIST/range lookup для каждой строки товара.

Для каждого документа repository возвращает один из buckets:

- `SELLER_ELIGIBLE`;
- `KNOWN_OUTSIDE_SELLER_COHORT`;
- `UNKNOWN_MEMBERSHIP_HISTORY`;
- `ORPHAN_RETURN`.

`KNOWN_OUTSIDE_SELLER_COHORT` дополнительно возвращает reason code, минимум
`EXPLICITLY_INELIGIBLE_EMPLOYEE`, `UNATTRIBUTED_SALE` или
`LINKED_TO_UNATTRIBUTED_ORIGINAL`. Eligibility bucket отвечает на вопрос включения в сумму, а
reason code — на вопрос качества attribution; смешивать их в один boolean нельзя.

SALE без employee и linked RETURN исходной продажи без employee относятся к
`KNOWN_OUTSIDE_SELLER_COHORT`, потому что seller scope требует доказанную employee attribution.
Только RETURN без доступной исходной продажи получает `ORPHAN_RETURN`.

Отсутствие membership interval для известного employee даёт `UNKNOWN_MEMBERSHIP_HISTORY` даже
после store coverage start. `NOT_ELIGIBLE` допустим только при явном покрывающем interval. Это
важно для нового employee, впервые замеченного позже baseline: его неизвестное прошлое нельзя
автоматически считать временем вне рейтинга.

Позднее связывание orphan return должно автоматически переводить факт в доказанный bucket при
следующей revision. Обработчик возврата не используется как fallback. Original другого магазина
не считается допустимой связью.

На основе одной eligibility projection построить отдельные агрегаты:

- financial/item facts;
- category and additional facts;
- decomposition document counts;
- employee completed-sales samples;
- attach numerator/denominator;
- daily buckets;
- reconciliation/quality counters.

Attach требует отдельной подготовительной migration: действующий `attach_rate_item_facts_v3`
содержит store/date/employee, но не document ID, item ID и occurred-at. Join по employee/date
не восстанавливает eligibility при toggle внутри дня. Новый внутренний item-context должен
сохранять document/item IDs и использовать общий classifier существующей attach methodology;
seller predicate подключается по document ID. При извлечении classifier legacy v3 view остаётся
совместимым адаптером с прежними колонками/типами и теми же строками. V38 не переписывается —
изменение оформляется следующей migration с полным parity по каждому attach-коду, ambiguity и
device role. Если parity не доказана, переключать shared view нельзя.

Для linked return проверяются same-store/same-connection и тип original `SALE`. Soft-deleted
original не исчезает из attribution join автоматически: действующий return normalizer может
сохранять такую связь. Удаление исходной продажи, изменение её seller/occurred-at и исправление
return link покрываются отдельными fixtures; аналитический рефакторинг не меняет тихо эти правила.

Запрещено копировать temporal predicate в несколько несогласованных SQL-запросов. Если projection
оформляется SQL view, имя и семантика versioned; если CTE/repository helper — integration tests
должны доказывать одинаковый bucket во всех агрегатах.

Контрольная точка P3:

- sale до/после toggle попадает в разные buckets;
- return после toggle наследует bucket исходной продажи;
- original до `authoritative_from` даёт `UNKNOWN`;
- gap после `authoritative_from` без явного state interval также даёт `UNKNOWN`;
- unattributed sale не маскируется как orphan;
- membership range lookup выполняется на document cardinality, не item cardinality;
- attach item-context сохраняет ключи документа; legacy attach v3 parity проходит;
- store reconciliation выполняется до копейки и до quantity precision;
- EXCLUDE, deleted items и late link сохраняют действующие правила.

### P4. Собрать `SellerPeriodAnalyticsService`

Внутренняя модель не имеет JSON/API annotations и включает:

- `SellerPeriodAnalytics`;
- `SellerCohortSnapshot` и interval summaries;
- `SellerFinancialFacts`;
- `SellerCommercialFacts`;
- `SellerCategoryFacts`;
- `SellerAttachFacts`;
- `SellerEmployeeFacts`;
- `SellerDailyFacts`;
- `SellerDataQuality`;
- formula/source/membership provenance.

Сервис композирует repositories и переиспользует существующие calculators. Необходимо извлечь
общие функции округления, margin/share/attach и reconciliation, а не создать вторые формулы.
Рекомендуемая dependency direction: отдельный внутренний пакет `selleranalytics` не зависит от
`overview`, `interpretation/review`, AI, controllers или frontend contracts; эти модули зависят от
его query ports и строят свои projections. Общие calculators переносятся в нейтральный metrics
package, чтобы seller service не начал зависеть от performance/Weekly Review orchestration.

`SellerEmployeeFacts` хранит стабильный employee ID и числа, но не делает display name частью
cohort/source identity. Имя разрешается локальным consumer projector при сборке response и затем
фиксируется immutable snapshot; provider projection его не получает.

Обязательные свойства результата:

- total считается суммой eligible employee facts, включая доказанные historical return authors;
- category total и daily total сходятся с financial total;
- attach total считается из сумм числителей/знаменателей, не средним employee rates;
- missing cost не подменяется нулём для GP/margin;
- zero cost остаётся валидным;
- unknown membership хранит count/amount, но не приписывается seller или non-seller;
- orphan return хранится отдельно от known outside и может ограничивать только attribution-dependent
  metrics согласно решению P0;
- stale/failed membership publication не переписывает историю и ограничивает `actionableNow`;
- workload limitations не понижают sales-only metrics.

Quality применяется по dependency graph, а не единым глобальным флагом: missing cost ограничивает
profit/margin, classification — structure/attach, unknown membership и orphan — только те
денежные/структурные факты, которые они потенциально могли изменить. Ненулевой unknown/orphan не
делает отчёт `BLOCKED`; `BLOCKED` используется, когда seller cohort или обязательная база вообще
не доказаны. `UNATTRIBUTED_SALE` не включается в seller total, но при рекомендованной policy
создаёт отдельный metric-scoped employee-attribution limitation.

Reconciliation одного периода имеет полную форму:

```text
store analytic facts
= seller eligible
 + known outside seller cohort
 + unknown membership history
 + orphan returns
```

`EXCLUDE` остаётся вне денежных/item quantities обеих сторон reconciliation. Document counts
имеют отдельно описанную семантику и могут учитывать all-EXCLUDE документы. Для count, money,
quantity, cost и category используются отдельные typed identities. При missing cost сверяются
known-cost subtotal и missing counters; `null` итог GP нельзя подменять нулём ради равенства.

Добавить `SellerPeriodComparisonService`, который читает current и previous периоды в одной
read-only `REPEATABLE_READ` транзакции и классифицирует:

- `CONTINUING` — period roster в обеих неделях;
- `JOINED` — только current period roster;
- `LEFT` — только previous period roster;
- `HISTORICAL_RETURN_ONLY` — для конкретного периода нет period roster, но есть eligible return
  fact исходной продажи;
- `actionableNow` — отдельный current-state marker, а не cohort class.

При нескольких membership intervals внутри недели сотрудник остаётся одним employee, но facts
включаются только на eligible участках. Hash строится из отсортированных semantic interval fields,
rule version и coverage boundary; имя сотрудника в hash не входит.

Сервис является facade над небольшими typed repositories/projectors, а не одним монолитным SQL и
не универсальным внешним DTO. В первый релиз входят только показатели, уже нужные Overview,
plan SELLERS и Weekly Review; speculative metrics не добавляются «на будущее».

Контрольная точка P4:

- golden parity с текущим `Overview SELLERS` при неизменном roster;
- все reconciliation identities выполняются;
- сервис не зависит от Weekly Review DTO, frontend text, AI или публикации;
- query plan использует индексы на реалистичном объёме данных.

### P5. Перевести seller-потребителей «Обзора»

Изменить только ветки `SELLERS`:

1. `OverviewMetricsService` получает projection из `SellerPeriodAnalyticsService`.
2. `StorePlanDailyActualRepository`/`StorePlanProgressService` получают daily seller buckets вместо
   проверки текущего assignment для исторических документов.
3. `STORE` продолжает использовать действующие store services.
4. Внешний Overview response по возможности сохраняет форму; formula version увеличивается из-за
   temporal cohort semantics.

P5 обязан расширить quality context Overview и plan API: `cohortBasis`, membership coverage и
полнота результата. Существующий `OverviewMetricsDataQuality` не умеет описывать неизвестную
историю. Показывать исключённые UNKNOWN-факты как полный результат или нулевой месяц запрещено.
Frontend этого пакета показывает краткое ограничение, а при неполном month actual не формирует
уверенный plan forecast/achievement. Это material UI change с собственным visual gate уже в P5.

Две закрытые недели history не доказывают полный месяц, прошлый месяц или произвольный период
«Обзора». До cutover тестируются отдельно current month, диапазон через baseline и полностью
исторический диапазон. Для CANONICAL запросов за непокрытый период доступны только явно
ограниченные доказанные суммы либо unavailable; не смешивать legacy и temporal куски в одну сумму.
Если такой UX неприемлем для заказчика, CANONICAL Overview откладывается до approved historical
baseline. Legacy поведение допускается только в явно выбранном LEGACY/SHADOW режиме.

Scope применяется к конкретным блокам страницы: согласно ADR-0002 category structure и attach map
«Обзора» сейчас остаются STORE, а employee rating запрашивается отдельным API. Их нельзя молча
перевести на SELLERS или объявить все блоки совпадающими с Weekly Review. Для сравнимых метрик
равенство требуется при одинаковых period, scope, formula и source revision; исторический
snapshot может закономерно отличаться от live Overview после поздней коррекции данных.

`EmployeeKpiService`, rating, payroll и Daily Store Pulse не переводятся автоматически: каждый
такой consumer требует отдельного contract review. Общий сервис можно подключать к ним позже, но
seller-first Weekly Review не должен незаметно изменить payroll или legacy notification.

Контрольная точка P5:

- STORE fixture не изменился;
- SELLERS fixture совпадает на стабильном roster;
- исторический off/on test доказывает temporal отличие от текущего flag;
- totals Overview и `SellerPeriodAnalytics` совпадают для одинакового периода;
- plan daily buckets суммируются в period total;
- monthly/custom запросы через baseline имеют явную coverage и не показывают ложный полный факт;
- frontend P5 прошёл desktop/tablet/mobile visual review и обработку нового quality context;
- rating, payroll, STORE structure/attach и плановые target values проходят regression tests.

### P6. Выпустить Weekly Review contract v3

V3 добавляет без переименования старой семантики:

- `scope=SELLERS`;
- membership rule/version;
- current/previous cohort hashes;
- actionability roster hash и `actionabilityAsOf`;
- composition counts/effects;
- seller-scoped quality;
- `additionalSales` block;
- versioned `TEAM`/`SELLERS` evidence refs.

Четыре core cards остаются `NET_REVENUE`, `GROSS_PROFIT`, `MARGIN_PERCENT`, `AVERAGE_SALE`.
`additionalSales` — отдельный компактный блок:

- additional revenue и share;
- change к previous week;
- accessory revenue и `accessoryMixShare` от additional revenue;
- service revenue и `serviceMixShare` от additional revenue;
- integrity residual.

Это не пятая случайная карточка и не копия раскрываемой структуры. Верхний блок даёт итог, а
`salesStructure` — подробное объяснение категорий и attach.

Mix-проценты получают собственные названия знаменателя. Возвраты могут дать отрицательную
категорию и долю другой категории выше 100% даже при положительной сумме допов. Такие суммы
сохраняются, но не рисуются как обычная круговая диаграмма/полоса частей целого; используются
числа с пояснением возвратов. Не ограничивать проценты диапазоном 0–100 скрытым clamp.

Внутренние employee totals вычисляются до ограничения числа карточек. Текущий projector имеет
лимит 100; в v3 при превышении лимита нужны `totalCount`, `displayedCount` и remainder evidence,
чтобы «сумма показанных карточек» не выдавалась за полный cohort. History и return-only authors
не отбрасываются из финансового итога ради UI limit.

Backend changes:

1. Заменить store/category/attach/revenue inputs `WeeklyReviewFactsSource` на period comparison.
2. Перевести core, structure, factor, team, employee, quality и evidence projectors.
3. Root action scope сделать `TEAM`; employee action остаётся `EMPLOYEE`.
4. Добавить factor kind изменения состава.
5. Запретить future action для `actionableNow=false`.
6. Не включать `HISTORICAL_RETURN_ONLY` в peer benchmark.
7. При отсутствии seller cohort не использовать store fallback. Нулевой roster не скрывает
   доказанные return-only facts: доступны отрицательный итог и объяснение возвратов, но нет
   персональной оценки действующей команды. Empty known cohort и unknown history — разные states.

Snapshot compatibility:

1. Ослабить DB constraint с v2-only до допустимых v2/v3 и добавить conditional v3 checks.
2. Существующий `WeeklyReviewResponse` заморозить как v2 concrete type. Добавить отдельный v3
   response и общий минимальный sealed contract, а не набор nullable v3-полей внутри v2.
3. В snapshot table достаточно `report_scope` и `source_identity_hash`: для legacy v2 они nullable
   или равны legacy semantics, для v3 обязательны `SELLERS` и SHA-256. Подробные cohort hashes и
   provenance остаются в payload; дублировать каждый hash отдельной колонкой не требуется.
4. Реализовать versioned codec/hash: dispatch выполняется по DB
   `report_contract_version` до deserialize/integrity check, а `ContentV2` остаётся
   byte-semantically прежним.
5. Snapshot store читает обе concrete версии, но v3 assembler пишет только v3.
6. V3 revision может supersede v2 того же периода; v2 row остаётся immutable.
7. Read path выбирает latest snapshot, совместимый с активным contract mode, а не абсолютную
   latest revision. После v3 rollback в `V2` обязан найти последнюю v2 revision и явно показать
   legacy/store-wide semantics, не пытаться распарсить v3 как v2.
8. AI enrichment совместим только с той версией snapshot/prompt, для которой создан.

Read preference и revision allocation — разные запросы. При вставке successor всегда ссылается
на абсолютную последнюю revision всех версий под lock, сохраняя существующую DB chain. Иначе
последовательность v2→v3→v2 при rollback нарушит unique/previous-revision constraints. При
отсутствии совместимого snapshot read возвращает явное preparing/unavailable, без незаметной
смены seller scope. Rolling deployment сначала выпускает совместимые readers, затем включает v3.

Открытая вкладка со старым frontend всё ещё парсит только v2. Поэтому включение глобального
режима v3 не должно внезапно менять ответ для старого клиента: P0 фиксирует version negotiation
или отдельный v3 route. Предпочтительный простой вариант — явный запрос поддерживаемого contract
version новым frontend, legacy default v2 для запроса без версии; server mode ограничивает
доступную новую выдачу. Это transport compatibility, не право клиента менять seller cohort.
При недоступном v3 новый UI показывает preparing/явный legacy fallback по контракту.

`source_identity_hash` строится из canonical sorted semantic identity без имён, денежных значений,
секретов и времени текущего вызова. DB conditional check связывает v3 payload header, `scope` и hash с
колонками. Имя сотрудника остаётся presentation field snapshot и не входит ни в cohort hash, ни в
source identity. `ContentV3` сохраняет semantic scope/cohort/actions/quality, но исключает технические
provenance/source identity и меняющиеся времена проверки `calculatedAt`/`actionabilityAsOf`.
Эти времена относятся к provenance и не должны делать каждый повтор новым content. Если новый
input доказанно дал тот же content, snapshot переиспользуется, а факт проверки сохраняется в
planning state; исходный snapshot продолжает хранить свой первоначальный provenance.

Контрольная точка P6:

- сохранённый v2 golden payload читается и проходит прежний hash;
- v3 round-trip и DB integrity проходят;
- v2→v3 создаёт следующую revision, а не изменяет row;
- contract-mode rollback читает последнюю совместимую v2, даже если absolute latest уже v3;
- core/additional/structure/team происходят из одной seller-модели;
- BLOCKED/PARTIAL masking не раскрывает недоказанные значения.

### P7. Исправить транзакции и invalidation

Генерация использует двухфазный протокол:

1. `WeeklyReviewFactsSource` читает current/previous facts в одной read-only `REPEATABLE_READ`
   транзакции и возвращает `SellerFactsSourceIdentity` вместе с результатом.
2. `WeeklyReviewSnapshotStore.persist` в отдельной короткой `READ_COMMITTED` транзакции
   сериализует генераторы одного магазина, блокирует source revision fence и только после
   получения lock повторно читает current identity одним согласованным запросом.
3. При несовпадении identity insert не выполняется; `WeeklyReviewService` делает ограниченное число
   полных повторов с новым facts snapshot.
4. При совпадении store читает последнюю revision под lock, проверяет content hash и вставляет
   следующую revision либо переиспользует неизменный content.

P7 создаёт `store_analytics_source_state` с monotonic semantic revision. Каждый commit, меняющий
входы seller analytics, увеличивает revision затронутого магазина в той же транзакции. Persistence
держит совместимый fence lock до commit insert/checkpoint; изменение sources либо уже отражено в
проверенной revision, либо коммитится после snapshot и помечает его устаревшим. Порядок lock для
многих stores един во всех writers; запрещён обратный захват блокировок. Нужны concurrency tests
с паузой writer именно между identity check и snapshot insert, а не только до чтения facts.

Реализован локальный фундамент P7 в V78: отложенные per-store/transaction события коалесцируют
массовые writes, commit увеличивает revision; охвачены sales/items, membership, classification,
decisions, sync status/coverage, timezone, смены и data-quality rows. Reader сохраняет revision
из того же MVCC-снимка, v3 writer проверяет её под lock и отклоняет устаревший input без insert.
PostgreSQL test блокирует writer уже после revision check и до insert, одновременно меняя
membership; commit источника происходит после snapshot, а следующая попытка со старым input
отклоняется. Внутренний candidate service делает не более трёх полных чтений facts после
source-conflict и вычисляет canonical source identity без имён и финансовых значений.
Публичный сервис ещё не вызывает writer. Clock-dependent freshness, persisted checkpoint и
внутренний stale read path реализованы. Для активации этапа A обязательны stable-source gate,
совместимые scheduler/public v3, AI/UI и нагрузочный gate. Исторический membership и его freshness
gate относятся к этапу B, как установлено в разделе границ выше, и не добавляются в очередь A.
SQL writers новых таблиц вне
V78 обязаны явно вызывать `mark_store_analytics_source_changed(store_id)` в своей транзакции.

Writer inventory включает sales/items, returns/relink, manual/reclassification и category rules,
cost corrections, shifts, membership, timezone и quality/coverage transitions. Изменение original
sale старше двух недель тоже увеличивает source revision, поскольку влияет на текущий return.
Глобальное правило классификации инвалидирует все затронутые stores. Runtime SQL вне этих paths
должен иметь явный invalidation step в runbook. `MAX(updated_at)` плюс count не является
достаточной заменой: timestamp совпадает по точности, commit order отличается от начала
транзакции, а изменение не максимальной строки может не поменять watermark.

Проверенный inventory текущих writer-путей перед реализацией fence:

| Вход seller facts | Runtime writers / зависимость | Требование к invalidation |
| --- | --- | --- |
| `sales_documents`, `sales_document_items`, включая original/relink, cost и classification | `SalesSyncPersistence`, `ReturnSyncPersistence`, `OrderSyncPersistence`, `ProductClassificationReconciliationService`, корректирующие SQL migrations | Ревизия старого и нового магазина при переносе/смене FK; original sale может быть вне двух недель. |
| `employee_store_assignments`, `employees.is_active` | `EmployeeSyncPersistence`, `EmployeeRatingSettingsService`, ручные изменения | Все затронутые stores; изменение только display name не должно менять финансовую identity. |
| `analytics_categories`, `product_category_assignments`, классификационные правила | `ProductCategoryImportService`, reconciliation, массовые category migrations | Не считать один глобальный updated-at достаточным: проверить фактически затронутые items/stores и category flags. |
| `warranty_attach_decisions`/allocations и `case_attach_decisions` | Ручные решения и derived attach-rate v4/case views | Инвалидировать оба периода, если изменилась атрибуция старой продажи или связанного возврата. |
| `sync_runs`, `sync_jobs`, `stores.timezone`, `employee_work_shifts` | sync lifecycle, настройка магазина, смены | Coverage/freshness и нормализацию business date считать отдельно от денежного content; смены не делать обязательными, пока полнота не доказана. |

Отдельная находка ревью: `SellerAttachRateRepository` считает numerator/denominator по cohort,
но при включённом attach v4 подмешивает store-wide pending warranty/unassigned-return quality из
`AttachRateRepository`. Это не финансовая сумма магазина, однако seller-only quality и
`preliminary` нельзя считать доказанными. Первый защитный шаг сделан: неизвестный возврат
помечает предварительным только затронутый код seller attach-rate; числитель/знаменатель остаются
seller-only, а store-wide счётчики трактуются как потенциальный риск, не как ошибка продавца.
Интеграционные тесты с чужим возвратом магазина и seller original-return case добавлены.
До v3 публикации нужно явное разделение этих счётчиков в дальнейшем контракте качества. Полная source revision
должна также учитывать изменения этих derived attach зависимостей.

Тяжёлое чтение facts остаётся без write locks. Гарантия snapshot — согласованное состояние
источников на сохранённую revision, а не вечная актуальность после будущих sync. Read path
показывает stale/preparing состояние отдельно от immutable content; новую проверенную revision
публикует planner. При исчерпании bounded retries сохраняется предыдущий совместимый отчёт с
признаком обновления, без tight retry loop и без блокирования приёма продаж.

Planner сравнивает persisted и current identity:

- store analytics semantic revision, включающую документы/items/originals, classification,
  cost, workload и quality/coverage;
- membership revision;
- discrete membership freshness state относительно publication deadline;
- current/previous cohort hashes;
- actionability roster hash;
- store timezone/business-date normalization version;
- contract/formula/snapshot/quality versions.

Successful sync ID/completed-at хранится в provenance, но сам по себе не меняет semantic identity,
если факты и состояния coverage/freshness не изменились. Переход STALE -> FRESH является значимым
изменением даже при тех же суммах; очередной FRESH -> FRESH no-op sync не создаёт новую revision.
Deadline transition проверяется и по времени, даже если не было ни одной DB-записи.
Внутренний v3 writer уже отклоняет facts, если к моменту записи наступили новые локальные сутки
или изменилась отчётная пара недель; ограниченный retry перечитывает весь bundle. Дополнительно
под store lock writer сверяет timezone facts с timezone магазина. Это закрывает гонку
«planner прочитал старую timezone → timezone commit → facts уже прочитали новую revision»:
совпадение revision в таком случае не доказывает корректную нормализацию периода. Отказ не
создаёт snapshot/checkpoint; bounded retries могут закончиться deferral, следующий scan читает
новую authoritative timezone. Regression test с реальным facts reader воспроизвёл ошибку до
добавления этой проверки. Внутренний
read path теперь отдельно проверяет свежесть checkpoint/snapshot после commit, поскольку время
может перейти границу позднее. Проверку вызывает внутренний unscheduled v3 planner;
подключение к расписанию и публичному контракту ещё предстоит выполнить.
Mutable planning state хранит последнюю проверенную identity и snapshot ID, включая
случай `content reused`, чтобы planner не пересчитывал одинаковый input на каждом scan.

Для этого P7 добавляет mutable `weekly_review_generation_state`, keyed по store, period и target
contract version. Она хранит `last_evaluated_source_identity_hash`, compatible snapshot ID,
evaluated-at и outcome `CREATED|REUSED|FAILED`. Обновление `CREATED/REUSED` происходит в той же
короткой persistence transaction; `FAILED` не продвигает last-evaluated identity. Это
операционный checkpoint, не пользовательский snapshot и не часть immutable revision chain.
В текущем внутреннем v3 writer это уже реализовано для `CREATED/REUSED`: checkpoint также хранит
source revision, а при ошибке вся транзакция откатывается. `FAILED` оставлен допустимым значением
схемы для будущего диагностического статуса, но текущий writer его не записывает. Внутренний
read-only assessor уже различает `PREPARING/CURRENT/STALE`: сначала дешёво проверяет локальную
дату и revision, затем подтверждает актуальность полным canonical identity в одной
`REPEATABLE_READ` транзакции. Он fail-closed при несовместимом checkpoint и помечает stale
после v2 rollback. Теперь его использует внутренний `SellerWeeklyV3PlanningService.evaluate`;
публичный v2 остаётся прежним, scheduler, API и AI jobs новый planner не вызывают.

Внутренняя оркестрация возвращает `UNCHANGED` при актуальной identity без записи checkpoint.
При `PREPARING/STALE` она читает timezone магазина и вызывает stable candidate path. Этот path
требует `STABLE` и complete coverage всех шести окон: `SALES/RETURNS/ORDERS` × current/previous.
После каждого source-conflict весь facts bundle и gate проверяются заново; максимум три попытки.
При неполном покрытии, active/failed/partial/cancelled sync или исчерпании конфликтов результат — `DEFERRED`;
identity не продвигается, совместимый предыдущий snapshot остаётся доступен как stale.
Внутренний manual candidate path по-прежнему умеет собирать masked `BLOCKED` для диагностики;
planner его не использует. Инфраструктурные ошибки пробрасываются, а не выдаются за ожидание sync.
После попытки генерации planner повторно оценивает freshness: `EVALUATED` означает проверку/запись
или content reuse, но не публикацию и не гарантию `CURRENT` при новой гонке или границе недели.
`Propagation.NEVER` запрещает общую outer transaction вокруг чтения, генерации и проверки.
PostgreSQL test проходит через preparing → current → active/failed/cancelled stale → reconciliation/reused
→ unchanged; deferred scans не меняют snapshot/checkpoint, reused identity не вызывает вечный
перерасчёт. Исторический membership и publication deadline policy этим этапом не завершены.

`REPEATABLE_READ` доказывает согласованность DB чтения, но не полноту внешнего sync. Sales/returns
также коммитятся частями: generation не объявляет READY только на основании старой successful
coverage, если более новый overlapping run всё ещё RUNNING или завершился с частичными записями
и FAILED. Нужен per-store source stability/dirty state; публикация ждёт успешного reconciliation,
а до него сохраняется последний проверенный snapshot с понятной свежестью. `CANCELLED` также
требует reconciliation: cancel в lifecycle job может наступить после commit очередного окна.
Старое successful coverage, неполная последующая неделя или успех другого магазина не снимают gate;
финансовая отмена за период вне отчётной пары не блокирует её. Это ограниченный gate
для Weekly Review, без переноса всего sales sync в одну транзакцию.
Reconciliation доказывается объединением **более поздних** successful intervals нужного scope/store
(для jobs — той же connection): reset/split окна не требуют одного огромного successor run.
PostgreSQL `range_agg` закрывает только непрерывное покрытие пересечения сбоя с отчётной парой;
gap остаётся `NEEDS_RECONCILIATION`. Run с неизвестными границами требует подтверждения всей пары,
а undated successor не является доказательством. Metadata-фазы jobs не привязаны к sales dates:
более поздний полный successful job обновляет их независимо от запрошенного financial interval.
Тесты покрывают соседние окна, gap, старый успех, другой store, cancelled financial interval вне
отчётной пары и unknown bounds. Активный relevant sync сохраняет приоритет `IN_PROGRESS`.

Изменение только имени не пересчитывает закрытый финансовый report и не создаёт revision. Старый
snapshot сохраняет display name на момент публикации; исправление presentation при необходимости
проектируется отдельным механизмом, не маскируется cohort change.
Изменение membership после закрытой недели не меняет её финансовые facts задним числом, но может
изменить `actionableNow` и удалить future action.

Автоматический planner обновляет текущую публикуемую пару закрытых недель. Старые отчёты остаются
историческими snapshots; их перерасчёт после поздних исправлений выполняется отдельной явной
операцией с новой revision и своим period context. Текущий roster не должен создавать бесконечный
веер revisions всех архивных недель и будущие actions в архивном отчёте.

Контрольная точка P7:

Локальная проверка внутреннего этапа 2026-09-28: 62 targeted unit/PostgreSQL tests, без failures,
errors и skips; Checkstyle main/test проходит. Проверки включают planner/read/candidate, timezone
regression, source stability и continuous reconciliation. Native/dependency cache обход выполнен
только параметрами локальных команд, без смены владельцев/прав, глобальных настроек или версий.
Полный `:backend:check` успешно повторён: 1571 tests / 371 suites, без failures/errors/skips;
OpenAPI compatibility, supply-chain integrity, operator security и Checkstyle проходят.
Первый общий прогон на том же коде показал три падения: session-expiration assertion в
`SecurityHardeningIntegrationTest`, startup ready-log timeout PostgreSQL в
`EmployeeRatingRosterMigrationIntegrationTest` и пустой claim в legacy
`WeeklySnapshotPipelineIntegrationTest`. Все три класса прошли отдельный повтор (11 tests),
затем полный повтор прошёл без изменений auth/pipeline кода или тестовых настроек. Причины
первых падений не установлены; перед релизом нужно отдельно подтвердить стабильность этих
временных/инфраструктурных сценариев, а не считать один зелёный повтор устранением flakiness.
Documentation checker unit tests: 25 passed; `git diff --check` чистый. Strict documentation
check пока выдаёт только шесть ранее зарегистрированных untracked документов (включая этот план);
индекс/commit для обхода этой проверки не менялись.
P7 temporal-этапа B в целом не закрыт: historical membership/freshness и scheduler/public v3
остаются незавершёнными. Это не разрешение включать этап A: у A остаются собственные A4–A6 gates.

- participation change без новой sales sync создаёт либо планирует revision;
- concurrent membership update не создаёт смешанный snapshot;
- повтор при неизменной identity переиспользует content;
- planner после `content reused` запоминает проверенную identity и не повторяет работу бесконечно;
- поздний return relink и classification/cost change создают revision.

### P8. Обновить optional AI и автоматическую публикацию

Не переписывать опубликованные файлы в `docs/prompts/` и `docs/schemas/`. Добавить новые версии:

- новый input schema;
- новый prompt;
- при необходимости новую selection/content schema;
- manifest/hash и evaluator fixtures.

`WeeklyReviewAiInputCompactor`:

- принимает только v3 READY/PARTIAL seller snapshot;
- требует `report.scope=SELLERS` и принимает только aggregate `TEAM` evidence без employeePublicId;
- не получает имена, employee IDs, roster history или unknown raw rows;
- не вычисляет KPI, contribution, cohort или materiality;
- передаёт только уже выбранные deterministic factors/actions.

AI job planner enqueue-ит только совместимые v3 snapshots. Старые v2 enrichments читаются только с
v2 и не могут примениться к v3. Автоматическая generation ждёт обязательную sales/returns coverage
и применяет membership freshness policy: при stale membership не создаются новые employee actions.
Автоматическая публикация остаётся выключенной до завершения canary из P11.

Контрольная точка P8:

- privacy allowlist test запрещает персональные поля;
- schema/structural/semantic/evaluator tests проходят;
- provider failure не скрывает deterministic v3 report;
- idempotency отделяет v2/v3 и разные prompt versions.

### P9. Обновить frontend без повторения информации

Frontend contract использует discriminated union v2/v3 либо эквивалентный versioned parser.
V2 остаётся читаемым во время перехода, v3 требует seller scope и новые блоки.
Новый frontend явно запрашивает поддерживаемую v3 версию; старые открытые вкладки сохраняют v2
ответ. Версия включена в query cache identity, переключение store/period/scope не использует
чужой cached report. Проверяется upgrade с уже открытой вкладкой, а не только fresh page load.

Изменить:

- `weeklyReviewContract.ts`;
- generated OpenAPI types;
- `weeklyReviewViewModel`;
- `WeeklyReviewContent`;
- detail panel;
- fixtures, component and accessibility tests;
- responsive CSS.

UX v3:

1. Явно подписывает, что результат рассчитан по продавцам рейтинга.
2. Оставляет четыре core cards.
3. Показывает общий процент допов и компактную структуру аксессуары/услуги в результатах.
4. Полную структуру и attach оставляет в раскрываемом detail.
5. Влияние состава показывает только при принятой materiality policy.
6. Ушедшему/выключенному сотруднику не показывает действие на будущую неделю.
7. Исторический return-only author не выглядит участником текущей команды.
8. Unknown membership показывает адресное ограничение без store fallback.

Контрольная точка P9:

- READY, PARTIAL, BLOCKED, empty cohort, joined/left и return-only fixtures;
- keyboard/focus/ARIA для details и side panel;
- `npm run visual:local` только против локальных frontend/backend;
- ручная оценка desktop/tablet/mobile изображений, а не только exit code.

### P10. Полная тестовая матрица

#### Membership и concurrency

- initial baseline, unknown before coverage;
- bootstrap одновременно с toggle; повторный seed не меняет history;
- manual off/on и no-op;
- sync deactivate/reactivate;
- employee active change во всех назначениях;
- transfer и rehire;
- несколько изменений внутри недели и одного дня;
- optimistic conflict и concurrent writers;
- ошибка в середине atomic batch откатывает current rows, raw normalization и history;
- более старый/потерявший lease run не может примениться после нового;
- manual toggle, racing с batch apply, в обоих commit orders;
- boundary timestamp после ожидания lock; неизменный hash периода при закрытии interval позже него;
- отсутствие overlap и divergence current/history.

#### Финансовые facts

- sale до/после effective boundary;
- full/partial return после выключения продавца;
- исходная продажа seller/non-seller/unknown;
- orphan и late link;
- deleted sale/return/item;
- EXCLUDE и UNMAPPED;
- missing cost и valid zero cost;
- all-EXCLUDE document count;
- разные document count/sample definitions и числитель weekly average против average receipt;
- изменение original seller/occurred-at, soft-deleted original и source revision вне отчётных дат;
- отрицательная/нулевая net revenue;
- category/additional hierarchy.

#### Attach и workload

- weighted numerator/denominator, clamp и denominator <= 0;
- raw numerators `-2,+5` дают `3` до clamp; full parity нового item-context с legacy v3;
- возврат из более раннего периода;
- смена без продаж, продажи без смены;
- membership boundary внутри shift date;
- отсутствие workload не ограничивает money metrics.

#### Comparison, snapshot и API

- stable roster, JOINED, LEFT, all-new, empty;
- cancelling employee deltas;
- historical return-only contributor;
- algebraic identity для stable/joined/left/current-return-only/previous-return-only одновременно;
- v2 exact read/hash;
- v3 write/read/hash;
- v2→v3 supersession;
- v2→v3→v2→v3 сохраняет общую revision chain и compatible read preference;
- membership-only invalidation;
- source writer commit между identity check и insert;
- два изменения с одинаковым timestamp, update/delete не максимальной строки;
- RUNNING/FAILED overlapping sync поверх старой successful coverage;
- повторный generate с другим `calculatedAt/actionabilityAsOf` не меняет content;
- fresh membership publication и stale-after-SLA без новых future actions;
- month/custom через baseline; return-only без period roster; больше 100 contributors;
- отрицательная mix-категория не отображается обычной диаграммой частей целого;
- old/new frontend parsing;
- old open browser tab после server cutover; negotiated version и query cache isolation;
- AI scope/privacy compatibility.

#### Performance и эксплуатация

- PostgreSQL integration tests на realistic row counts;
- query plans без full scan history на каждый item;
- `EXPLAIN (ANALYZE, BUFFERS)` для representative sanitized volume: range lookup выполняется на
  document cardinality до item join;
- bounded snapshot payload и AI input;
- planner не повторяет generation после no-op sync и `content reused`;
- метрики planner/job без business values;
- документационные strict checks.

### P11. Безопасный rollout

Использовать два ограниченных переключателя, а не набор флагов на каждую метрику:

- `seller-analytics.mode=LEGACY|SHADOW|CANONICAL` — источник seller projections;
- `weekly-review.contract-mode=V2|V3` — версия generation/read preference.

`SHADOW` вычисляет новый результат, но возвращает legacy response. В telemetry допускаются только
states, counts, duration и hashes; денежные значения, имена и строки evidence в logs/metrics не
попадают. `V3` разрешён только вместе с `CANONICAL`; недопустимая комбинация должна останавливать
startup validation, а не тихо падать в store fallback.

Последовательность:

1. Выпустить совместимый baseline приложения при `LEGACY/V2`: additive schema migrations,
   history/source writers и v2/v3 readers. Проверить его как минимальную версию rollback.
2. Загрузить и проверить approved baseline; сохранить только sanitized counts/hash evidence.
3. Если baseline не backdated доказуемым источником, выдержать warm-up до двух полных
   закрытых недель для weekly comparison. Monthly/custom coverage Overview проверяется отдельно;
   две недели не являются общим разрешением переключить все seller-запросы.
4. Запустить `SHADOW/V2` без изменения пользовательского ответа.
5. Сверить Overview SELLERS old/new на стабильных roster periods и объяснить ожидаемые temporal
   расхождения отдельно от дефектов формул.
6. Проверить `CANONICAL/V2` локально для Overview seller projection, включая old month/custom
   quality UX. Пользовательский переход согласовать с готовностью v3; временный v2 явно
   маркируется legacy/store-wide, если остаётся видимым.
7. Сгенерировать v3 snapshots локально на свежих данных двух магазинов.
8. Провести независимые code review и UI review.
9. Включить deterministic `CANONICAL/V3` read path canary.
10. Отдельно включить AI generation canary.
11. Доказать на scheduler fixture сценарии fresh membership и stale-after-SLA без future actions.
12. Только после успешного canary включить автоматическую публикацию.

Rollback не удаляет history или v3 snapshots. Он выключает новую generation/read preference и
возвращает последнюю совместимую v2 выдачу с явной legacy/store-wide маркировкой, пока причина
исследуется. V2 нельзя показывать как seller-first. После v3 публикации нельзя переписывать
существующую revision; исправление создаёт следующую.

Rollback флагов выполняется на совместимом baseline; history/source writers продолжают работать.
Возврат старого executable без этих writers создаёт пробел в истории, поэтому не является
безопасным rollback. Если такой возврат необходим аварийно, после него coverage помечается
неизвестной до контролируемого восстановления. Migration не требует удаления данных или
отключения immutable triggers на опубликованных snapshots.

## Definition of Done temporal seller-first этапа B

Этап завершён только если одновременно выполнено:

- Weekly Review, Overview SELLERS и plan SELLERS используют одну seller fact semantics;
- STORE scope не изменён;
- temporal membership и return eligibility доказаны integration tests;
- неизвестная история никогда не трактуется как current assignment;
- v2 snapshots продолжают проходить integrity check;
- v3 snapshot явно хранит seller scope/cohort/actionability provenance;
- все reconciliation identities выполняются;
- frontend прошёл локальный desktop/tablet/mobile visual review;
- AI payload не содержит employee PII и не считает бизнес-метрики;
- automatic publication включается отдельным контролируемым шагом;
- current/decision/API/frontend/AI docs обновлены по фактически реализованному поведению;
- backend, frontend, documentation и `git diff --check` gates зелёные.
