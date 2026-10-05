---
doc_schema: 1
doc_type: working
status: draft
owner: backend
audience:
  - developer
  - operations
created_at: 2026-10-04
review_by: 2026-10-11
source_material:
  - docs/decisions/ADR-0004-seller-weekly-historical-membership.md
  - docs/decisions/ADR-0005-weekly-ai-activation-and-retries.md
  - docs/current/ai/weekly-review.md
  - docs/runbooks/weekly-review-ai.md
required_reviewers:
  - backend
  - product
  - operations
exit_target: current
---

# Production automation недельного ИИ-разбора

## Границы и решения

Владелец подтвердил переход к надёжному автоматическому режиму. Это не разрешение включить
постоянные платные вызовы, изменить финансовые формулы или объявить текущий состав продавцов
исторически достоверным. Действует ADR-0004. Изменения выполняются в отдельной ветке от
проверенной базы, без включения незавершённых пользовательских правок.

Владелец согласовал forward-only baseline при включении и необходимые ограниченные повторы
без коммерческого денежного потолка; решение записано в ADR-0005. До production cutover
проверить конкретную baseline-операцию и runtime circuit breakers. Разрешения на старые exact
canary не переносятся на другую неделю или новый снимок.
Автоматическое восстановление до доказанного baseline запрещено; первая ручная публикация
старого периода должна явно сохранять текущую, не temporal, семантику состава.

## Порядок пакетов и контрольные точки

1. **Границы недель и право на выполнение.** Ввести явный закрытый календарный период в timezone
   магазина. Не считать конец рабочего дня закрытием недели. Worker с истёкшим lease не может
   начать платную попытку или оживить lease heartbeat. Проверить границу lease, takeover,
   одновременный start, сохранение receipts и отсутствие повторных расходов.
   До temporal cutover отдельно укрепить действующий current-roster path: bounded retries,
   изоляция ошибок магазинов и бесплатное обновление checkpoint без подмены exact snapshot.
2. **Temporal seller facts.** Подключить единую document-level eligibility к финансовым,
   структурным и attach-проекциям. По ADR-0006 аналитический возврат получает сотрудника записи
   LiveSklad и membership на дату возврата; исходная продажа остаётся отдельным reconciliation fact.
   Сначала проверить независимость от payroll и гарантийных правил, не меняя их неявно.
   UNKNOWN не превращать в false. Исторический автор остаётся в итогах, но не получает future
   action после ухода. Сохранить parity при неизменном составе и существующие формулы.
3. **Периодный read/planner и durable backlog.** Хранить store/week, состояние, попытки подготовки,
   next evaluation и lease в БД. Восстанавливать пропуски после baseline ограниченными пачками,
   включая задержку через две границы недель. Источники проверяются теми же coverage/stability
   predicates. Неполные данные откладывают подготовку, а не публикуют неполный итог.
4. **ИИ и гонки актуальности.** Планировать только точный CURRENT snapshot. Изменение источника
   до provider attempt позволяет повторную бесплатную подготовку; exact approved path может
   подтвердить тот же immutable snapshot, но никогда не подменяет snapshot/хеши. Один automatic job на store/week,
   в том числе после исправлений; ограниченные retries по ADR-0005. После оплаченного ответа
   сохранить receipt, расход и результат проверки даже при устаревании. Не повторять запрос
   автоматически при UNKNOWN outcome и не создавать новый job для обхода retry cap.
5. **Бюджет и наблюдаемость.** Проверяемые технические лимиты с учётом незавершённых и
   UNKNOWN расходов; состояние ожидания источников отдельно от технической ошибки. Диагностика
   показывает store/week, коды причин и timestamps, без секретов/персональных payload. Уведомлять
   об actionable задержке/ошибке, не отправлять повторяющийся шум на каждом scheduler tick.
6. **Release gate.** После каждого пакета code review + тесты его инвариантов + сверка с планом.
   Перед выпуском полные backend/frontend/docs gates, миграционная rehearsal, проверка локального
   UI при изменении интерфейса. Затем отдельные production approval, verification и ограниченный
   paid canary. Только после них разрешается постоянная автоматическая публикация.

## Критерии готовности

- На новой неделе после успешной синхронизации появляется обзор последней полной недели.
- Restart, повторный scheduler и задержка источников не теряют пропущенные недели.
- Неизвестная история, открытая неделя и неполный coverage не выдаются за точные показатели.
- Параллельные worker/planner, истечение lease и source churn не создают дополнительный расход.
- Опубликованный отчёт остаётся immutable; исправления создают revision без платного автоповтора.
- Есть проверяемый runbook включения/остановки и понятный статус для нового разработчика.

## Прогресс

- Пакет 3, exact period read/free planner: additive historical GET под store authorization и
  прежними feature gates не пишет данные, не подставляет current-ranking/STORE snapshot и не
  вызывает provider. Cheap metadata и snapshot/checkpoint читаются в одной RR-транзакции;
  interval/actionability/baseline/source identity совпадает с heavy preparation без финансовых
  агрегатов. Semantic reuse оценивается по compatible checkpoint, без изменения embedded payload.
  Проверяются latest revision, policy versions, activity/timezone, coverage/stability и закрытие
  периода; future actions устаревшей недели требуют free revision, включая boundary во время GET.
  Exact free planner делает одну подготовку запрошенной недели и не меняет дату на latest week.
  Historical AI guard использует этот path, но новая revision не заменяет одобренный snapshot.
  Current-ranking reader/planner/AI path сохранены. Scheduler/backlog paid planning и atomic
  publication fence ещё не подключены. Frontend, production, baseline и платные вызовы не менялись.
  Code review и сверка с планом завершены. Review добавил expiry future-action горизонта и
  повторную calendar проверку после metadata read; exact refresh новой revision не считается
  разрешением старого snapshot. Финальный targeted gate: 149 tests в 21 классе, 0 failures/errors/skips,
  Checkstyle main/test PASS. Включены real historical queue → facts → writer → read/free planner,
  authenticated period API (401/403/400, CURRENT/PREPARING), checkpoint reuse без payload rewrite,
  старый seller HTTP path и current-ranking AI planning/freshness regressions. Тестовый менеджер
  проходит обычную password-change policy; security не ослаблена ради HTTP test.
  Documentation: 25 tests, strict integrity 457 inventory rows и 0 warnings; release/operator
  safety и Gradle supply-chain integrity (449 components, 840 artifacts), diff check PASS.
  Frontend не изменён: нового visual gate в этом этапе нет, общий frontend/UI release gate остаётся.
  Это не полный backend release gate и не доказательство atomic paid publication. Далее source-lock
  paid-attempt/completion fence, automatic backlog AI planning/scheduler и общий выпускной прогон.

- Пакет 3, historical snapshot и free runner: interval/baseline identity читается в той же RR
  транзакции, что combined facts, и проверяет исторический union cohort. Canonical selection
  содержит обрезанные обеими неделями интервалы; текущая actionability имеет отдельный hash.
  UNKNOWN не превращается в current-roster fallback. Historical assembler имеет отдельные
  policy versions и сохраняет вклад/карточки ушедших без future action. Давно пропущенная неделя
  также не назначает новые team/employee actions на следующую неделю. Backend codec и frontend
  parser поддерживают additive historical basis, сохраняя legacy current-ranking contract.
  Отдельный period writer под store → source locks проверяет активность/timezone, source и
  membership revisions, baseline и закрытие периода; snapshot/checkpoint атомарны. Равный
  semantic content переиспользует immutable snapshot. Free runner делает одну подготовку за
  вызов, проверяет lease перед writer и exact binding; waits имеют backoff, technical failure
  terminal с безопасным кодом. Scheduler/period read/AI planner не подключены; production,
  baseline и paid calls не затронуты. Code review и сверка с планом завершены: temporal selection
  не подменяется текущим составом, UNKNOWN не скрывается, immutable reports не переписываются.
  При review усилен regression старой недели: сначала подтверждены реально создаваемые team и
  employee actions, затем их отсутствие в запоздалом отчёте. Canonical codec roundtrip проверяет
  bytes/content hash, а не Java-типы чисел в polymorphic evidence. Targeted backend gate:
  137 tests в 14 классах, 0 failures/errors/skips; Checkstyle main/test PASS. Полный frontend check:
  61 файл, 312 tests, contract check, lint, TypeScript/Vite build PASS. Documentation: 25 tests,
  strict integrity 457 inventory rows, 0 warnings; operator/release security и Gradle supply-chain
  integrity (449 components, 840 artifacts) PASS; diff check PASS. Frontend изменён только в parser,
  не в UI; visual verification не выполнялась и остаётся gate при публичном historical cutover.
  Это не полный backend release gate и не закрытие автоматического режима: далее period read/AI
  planner, atomic AI publication fence, scheduler wiring и общий выпускной прогон.

- Пакет 3, durable free preparation: добавлены per-store baseline/timezone cursor и уникальные
  store/week jobs. Discovery ограничена 1–52 неделями и начинается лишь после двух полных
  authoritative недель; restart и задержки через новые границы не удаляют старые задачи.
  Claim использует SKIP LOCKED и независимый token, expired heartbeat/defer/complete запрещены.
  Lease пересматривается по свежему Clock после ожидания блокировок; операции имеют отдельные
  короткие транзакции, чтобы не унаследовать locks/isolation внешней подготовки.
  Source/history waits имеют backoff, технический FAILED terminal. Очередь приоритизирует
  время готовности/lease: due retry старой недели не обгоняет ещё не проверенную неделю
  лишь из-за даты периода. Привязка snapshot проверяет
  exact store/week/timezone, historical basis, latest revision и текущий checkpoint; короткий
  store/source lock защищает только этот переход, не заменяет будущий publication fence.
  Bounded refresh устаревшего результата возвращает ту же бесплатную задачу в PENDING,
  не создавая другой store/week/paid job и не перематывая cursor.
  Периодный внутренний facts source читает любую закрытую неделю в одной read-only RR-транзакции,
  проверяя coverage/stability до combined temporal facts, без current-roster fallback.
  Scheduler, public historical assembly/schema/identity, paid planning и publication ещё не
  подключены. Production и frontend не изменены.
  Общий migration/targeted gate: 108 tests в 48 классах, 0 failures/errors/skips, Checkstyle PASS.
  После lease review: 70 tests в 12 классах, 0 failures/errors/skips, Checkstyle PASS; включены
  historical financial/attach, coverage/stability, заполненный upgrade, restricted migrator,
  migration executable, schema info/security guards. Последний отдельный fairness regression
  финального кода: 24 tests в 2 классах, 0 failures/errors/skips, Checkstyle main/test PASS.
  Новая queue integration проверяет restart и bounded cursor через две границы недель,
  missing/midweek baseline, DST/local closure, timezone change, SKIP LOCKED, takeover того же
  owner, expired/stale-token updates, lease после ожидания locks, free backoff/fairness,
  exact/latest historical snapshot и повторную подготовку без нового paid job.
  Filled-schema upgrade сохраняет прежний snapshot/checkpoint/baseline; validate и повтор
  migration no-op проходят. Source unit suite отдельно проверяет точную старую неделю, scoped
  coverage/stability, UNKNOWN без fallback, изменение timezone и read-only RR contract.
  Self-review выявил два edge cases: проверку lease по времени до locks и обгон новых недель
  due retry старого периода. Исправлены fresh-clock fence и порядок по времени готовности/lease;
  регрессионные сценарии проходят. Первые прогоны нашли ошибки synthetic snapshot headers,
  неправильный тестовый join paid jobs и Mockito/generic assertions; schema constraints не
  ослаблялись. Documentation unit tests (25), strict integrity (457 rows, 0 warnings), operator
  security, supply-chain (449 components / 840 artifacts) и diff check проходят. Это не полный
  backend/frontend выпускной прогон. Public temporal identity/schema, historical assembly/read,
  подключение queue runner и atomic AI publication fence остаются обязательными следующими gates.

- Пакет 2, temporal attach и historical presentation: добавлена отдельная opt-in projection
  с provenance каждой позиции. Обычные операции используют собственный timestamp и source
  processor возврата; специальные warranty allocations/base сохраняют контракт целевой продажи.
  Historical attach требует v4 policy и RR-транзакцию; UNKNOWN автора/history и eligible автор
  вне financial cohort блокируют подготовку. Combined reader объединяет финансовые, document,
  attach и return-quality факты обеих недель без payroll/source/snapshot DML. Opt-in presenter
  сохраняет карточку ушедшего продавца с `actionableNow=false`, явной пометкой и без future action;
  действующая current-roster presentation сохраняет parity. Публичный membership contract,
  historical snapshot assembly/period read, durable backlog и publication fence ещё не подключены.
  Итоговый targeted run: 67 tests в 14 классах, 0 failures/errors/skips, Checkstyle main/test PASS.
  Включены historical attach (5), combined financial preparation (9), cards (8), warranty (19),
  catalog/attach parity, migration application и security. Filled-schema upgrade проверяет
  прежние view definitions, raw quantities/arrays, allocations и financial fields; checksum
  validation и no-op повтор migration проходят. Restricted migrator также проходит.
  Отдельный общий migration gate: 43 tests в 40 классах, 0 failures/errors/skips,
  Checkstyle main/test PASS; все обновлённые ожидания packaged schema проверены.
  Self-review подтвердил half-open boundaries, same RR transaction, сохранность warranty
  attribution, отсутствие current-roster fallback и закрытый статус новых public consumers.
  Первый прогон выявил style нарушения, а новые migration fixtures — отсутствующий finish time
  успешного run и сравнение JDBC arrays по object identity. Исправлены fixtures/сравнение
  canonical JSON, не ослаблены schema constraints или расчетные инварианты.
  Documentation unit tests (25), strict integrity (0 warnings), operator security, supply-chain
  integrity и diff check проходят. Полный выпускной прогон ещё не выполнен; frontend source и
  production не менялись. Historical UI ещё не подключён, визуальная проверка перед cutover обязательна.
- Пакет 2, предыдущая контрольная точка temporal financial preparation: внутренний reader применяет selection
  к employee/category и document aggregates обеих недель в одной RR-транзакции. Baseline до начала
  сравнения обязателен; открытая неделя и UNKNOWN автор/history не удаляются молча. Исторический
  roster сохраняет ушедших и нулевые строки, текущие action IDs отделены. Публичные readers,
  payroll, warranty и scheduler не переключены. Attach views теряют document provenance:
  их безопасная temporal проекция и presentation остаются обязательными до публикации.
  Финальный targeted run: 36 tests в 5 классах, 0 failures/errors/skips, Checkstyle main/test PASS.
  Новый historical integration suite (8 tests) проверяет midweek toggle, уход с удалённым
  assignment, own-return processor без payroll DML, UNKNOWN author/history, отсутствующий/поздний
  baseline, пустые периоды/cohort, точную локальную границу closure и parity прежних финансовых
  формул при неизменном составе. Также проходят document selection (1), employee KPI (12),
  employee category KPI (2) и current analytics service (13). Self-review подтвердил одну RR
  транзакцию, одинаковый document filter для трёх проекций, half-open membership boundaries,
  сохранение нулевых строк ушедших и отсутствие подключения financial-only результата к AI.
  Первые проверки нашли missing source_system в synthetic fixture, длинные строки и неоднозначное
  generic AssertJ assertion в новом boundary test; исправлены без ослабления проверок.
  Documentation unit tests (25), strict integrity (0 warnings), host operator security и diff check
  проходят. Полный release gate не запускался для этого промежуточного этапа; temporal attach,
  presentation, durable backlog и atomic source-publication fence ещё остаются открытыми.
- План зафиксирован; production изменения и платные вызовы не выполнялись.
- Пакет 1: локально проверены календарная идентичность, lease/deadline heartbeat,
  attempt-count fence и сериализация runner. Code review не выявил изменения формул/transport.
  Финальный targeted набор: 38 tests, 0 failures, 0 skipped; включает PostgreSQL integration
  job store и completion. Checkstyle, operator security и 25 documentation tests проходят;
  strict documentation: 0 warnings. Это не полный backend/frontend release gate.
- Пакет 1, продолжение: ограниченные retries внутри одной weekly job, UNKNOWN outcome без
  автоматического повторного расхода, изоляция ошибок магазинов и бесплатное обновление
  checkpoint только для прежнего exact snapshot. Отличающаяся revision не подменяет job.
  Финальный targeted набор: 52 tests, 0 failures, 0 skipped; Checkstyle main/test проходит.
  Code review подтверждает: истёкший STARTED attempt остаётся UNKNOWN/FAILED, повторный
  scheduler не создаёт другую weekly job, exact guard не подменяет revision. Operator security,
  25 documentation tests и strict documentation (0 warnings) проходят. Полный release gate
  этим targeted набором не заменяется.
- На контрольной точке пакета 1 atomic completion ещё не доказывала сохранение receipt при
  потере владельца. Этот случай реализован отдельным этапом ниже; смена snapshot при source churn
  и durable backlog по-прежнему остаются в следующих пакетах.
- Правило аналитического сотрудника возврата подтверждено владельцем и записано в ADR-0006.
  Независимая historical eligibility projection читает сотрудника записи LiveSklad на дату
  возврата. Не меняет сохранённый финансовый автор, не использует fallback на оригинал;
  собственный UNKNOWN не превращается в отсутствие участия. Подключение к всем агрегатам,
  новая policy/revision и исторический cutover на этой контрольной точке ещё не реализованы;
  payroll-контракт сохраняется.
  Локальный PostgreSQL integration test проверяет 16 synthetic документов: разных авторов,
  orphan/late link, отсутствие source ID, unresolved ID с совпадающим manual employee,
  границу baseline и выключения участия, удаление документа и оригинала. Общий `employee_id`
  остаётся прежним; Checkstyle main/test и documentation gates проходят. Это проверка
  независимой projection, не всего нового финансового контура.
- Пакет 2, денежный этап: общий read-only resolver подключён к employee KPI, категориям,
  документным totals, финансовым составляющим рейтинга и unknown-return диагностике weekly.
  Store totals, сохранённый `employee_id`, payroll и warranty allocation не переписываются.
  Новые formula/policy versions отличают прежнюю семантику без изменения опубликованных
  prompt/schema artifacts. Исторический membership, полный temporal attach, ежедневный
  SELLERS-факт планов и historical backlog остаются отдельными незавершёнными этапами.
  Финальный targeted backend-набор: 113 tests в 17 классах, 0 failures/errors/skips;
  Checkstyle main/test проходит. Включены KPI/category/rating, seller weekly, source identity,
  sync и warranty integration; из load suite проверен один точный сценарий, не весь suite.
  Code review подтверждает точный connection/source resolver, независимость от original link,
  сохранность signed amounts и warranty allocations, новую identity без переписывания snapshots.
  Ежедневный SELLERS-факт плана остаётся старым потребителем и блокером согласованного cutover.
  Targeted результат не заменяет полный backend release gate окончательного кода.
  Scope, self-review и результаты повторов сохранены в
  [локальном evidence денежного этапа](../history/audits/2026/10/return-processor-financial-projections-local.md).
- Production не менялся; постоянная автоматическая публикация ещё не готова к включению.
- Пакет 2, ежедневный аналитический факт плана: SELLERS-фильтр теперь использует тот же
  read-only resolver автора возврата, что месячный Overview; собственная business date сохранена.
  Версия чтения обновлена без изменения целей, прогнозных формул или payroll. Финальный targeted
  набор: 44 tests в 7 классах, 0 failures/errors/skips; Checkstyle main/test проходит.
  Проверена parity дневных revenue/accessory/service totals с Overview в обоих scopes,
  разные авторы, граница месяца, orphan, удаление оригинала/возврата, missing/unresolved
  автор и неактивный roster. Включены неизменённые payroll repository/engine/calculation tests.
  Self-review подтверждает отсутствие fallback/DML и совпадение SELLERS roster predicate.
  Documentation unit/strict и operator security проходят. Этот прежний потребитель исправлен;
  historical membership, temporal attach, backlog и late receipts остаются открытыми.
  [Локальное evidence](../history/audits/2026/10/return-processor-daily-plan-local.md) сохраняет
  область проверки; полный release gate и production cutover этим набором не заменяются.
- Владелец подтвердил read-only аудитом полное покрытие и стабильность источника за новую
  закрытую неделю: [наблюдение 5 октября](../history/audits/2026/10/weekly-source-readiness-october5.md).
  Это снимает прежний source blocker, но не заменяет snapshot/quality/paid approval gates.
- Пакет 4, независимое сохранение provider response: additive append-only receipt хранится
  отдельной транзакцией до публикации/retry. Поздний ответ не изменяет завершённую UNKNOWN attempt
  и не оживляет terminal job. Известный RUB-расход учитывается один раз; неизвестный сохраняет
  резерв оценки. Publication/retry fence дополнен lease/deadline/attempt count. Ошибка валидатора
  сохраняет ответ и завершает job без автоматического платного повторения.
  Финальный targeted набор: 45 tests в 6 классах, 0 failures/errors/skips, Checkstyle main/test PASS.
  Отдельно 44 migration/security tests в 34 классах, 0 failures/errors/skips и Checkstyle PASS:
  пустая/заполненная схема, промежуточный предыдущий target с AI attempts и restricted migrator.
  Self-review проверил независимую транзакцию, immutable попытки, hash/claim binding, idempotency,
  отсутствие повторного расхода и запрет переопределения уже известного legacy response/price.
  Первая проверка стиля выявила восьмой параметр helper; сигнатура исправлена без ослабления
  правила, финальный прогон зелёный. Документация/операторская безопасность/supply-chain проходят.
  [Локальное evidence](../history/audits/2026/10/weekly-ai-response-receipts-local.md) сохраняет
  scope и оставшиеся gates. Crash до durable записи остаётся UNKNOWN. Нужны privacy/retention
  review, runtime grants и migration rehearsal; сохранённые production facts не изменены.
  Это не закрывает гонку source change непосредственно перед publication, durable backlog,
  historical cutover или весь автоматический режим.
- Общая локальная контрольная точка сохранена в
  [sanitized evidence](../history/audits/2026/10/weekly-ai-local-regression-checkpoint.md).
  Полный backend run: 1 896 tests, один `ContainerLaunchException` при инициализации retention
  integration test, без skips. Отдельный повтор всех 4 retention-тестов прошёл без изменения
  retention-кода. Frontend check (61 test file), generated OpenAPI, security и supply-chain
  проверки прошли. Это не зелёный полный release gate окончательного объединённого кода.
- Решения о forward-only baseline и необходимых retries зафиксированы в ADR-0005; конкретный
  timestamp baseline и production-конфигурация ещё не активированы.
