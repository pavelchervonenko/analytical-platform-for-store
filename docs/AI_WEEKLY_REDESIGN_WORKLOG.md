# Редизайн страницы «ИИ-разбор»: план и рабочий журнал

Дата начала: 2026-08-26
Статус: этап 6 в работе; v24 targeted/offline gate пройдены, paid/full gate и production-canary ожидаются
Production baseline: release `v0.1.0-pilot.26`; v22/schema4 canary с v21/schema3 fallback

Этот документ является основной точкой продолжения редизайна страницы «ИИ-разбор». Он хранит
принятые продуктовые решения, последовательность этапов, критерии приёмки, найденные проблемы,
проверки и журнал изменений. Исторические документы по v15–v21 не переписываются.

## 1. Цель

Сделать страницу понятным еженедельным управленческим отчётом для руководителя магазина. За
3–5 минут пользователь должен получить ответы на четыре вопроса:

1. Как магазин отработал завершённую неделю?
2. Что повлияло на результат относительно предыдущей недели?
3. Где нужна управленческая реакция и кому требуется помощь?
4. Какие действия выполнить на следующей неделе?

Страница не должна требовать знания внутренних метрик, LLM-схем, технических статусов или правил
расчёта. Любой вывод должен быть понятен сам по себе и иметь проверяемое основание.

## 2. Зафиксированные продуктовые решения

### 2.1. Периоды

- основной период — последняя полностью завершённая локальная неделя, понедельник–воскресенье;
- период сравнения — непосредственно предшествующая полная неделя;
- обе недели считаются в timezone магазина;
- незавершённая текущая неделя не участвует в выводах;
- сравнения с месяцем, аналогичной неделей прошлого месяца или прошлым годом не выполняются без
  отдельного будущего решения и отдельной явной подписи;
- закрытая неделя не меняется незаметно: исправление источника создаёт новую immutable revision и
  должно отображаться пользователю как обновление данных.

### 2.2. Месячный план исключён

Месячный план полностью исключается из страницы «ИИ-разбор»:

- из интерфейса;
- из weekly provider input;
- из набора разрешённых candidate signals;
- из выбора primary signal;
- из fallback-текста;
- из evidence недельного разбора.

Факты `PLAN_*`, candidate theme `PLAN` и section `PLAN_OUTLOOK` не должны влиять на недельные выводы.
План остаётся на главной странице и в разделе «План и смены».

### 2.3. Ответственность backend и ИИ

Backend отвечает за:

- периоды, значения, сравнения и округление;
- достаточность базы и качество данных;
- пороги материальности;
- разрешённые сигналы и evidence;
- стабильные пользовательские названия показателей;
- запрет неподтверждённых выводов.

ИИ отвечает только за:

- краткое объяснение разрешённых подтверждённых сигналов;
- выбор ясной формулировки без изменения смысла метрик;
- формирование ограниченного числа управленческих действий из разрешённых фактов.

Страница должна оставаться полезной при временной недоступности провайдера: числа, сравнения,
основания и детерминированные состояния принадлежат приложению.

### 2.4. Версионирование и безопасность выпуска

- production v21/schema3 остаётся immutable baseline до принятия новой версии;
- новая реализация создаётся параллельно, с новой версией prompt/content contract и при
  необходимости snapshot/metrics policy;
- рабочее обозначение — `v22/schema4`; окончательные номера утверждаются после этапа 2;
- исторические interpretations продолжают читаться старым projector;
- переключение production выполняется только через feature flag/canary с возможностью возврата на
  v21/schema3;
- Telegram fanout не включается в рамках редизайна без отдельного решения.

## 3. Целевая структура страницы

Порядок блоков фиксируется как исходная продуктовая гипотеза и уточняется контрактом этапа 2.

1. **Период и состояние данных** — обе сравниваемые недели, data-through и конкретные ограничения.
2. **Краткий итог недели** — общий результат, главный положительный фактор и главный риск.
3. **Результаты недели в цифрах** — текущая неделя, предыдущая неделя и изменение.
4. **Что повлияло на результат** — не более трёх подтверждённых факторов.
5. **Структура продаж и допродажи** — категории, дополнительные продажи и attach-показатели без
   визуального двойного подсчёта.
6. **Команда** — только агрегированная командная картина и количество сотрудников по состояниям;
   без ФИО и персональных выводов.
7. **Карточки сотрудников** — факты, собственная динамика, допустимое сравнение с магазином,
   сильная сторона, зона роста и действие.
8. **Действия на следующую неделю** — не более трёх проверяемых действий.
9. **Ограничения данных** — только конкретные проблемы и только затронутые ими выводы.

## 4. Обязательные UX-инварианты

- Каждый сравнительный вывод явно называет текущий и базовый периоды.
- Каждый вывод содержит конкретный показатель или доступное пользователю evidence.
- Пользователь может открыть «На основании чего» и увидеть исходные значения.
- Слова «существенно», «заметно», «эффективно» допустимы только при утверждённом пороге; интерфейс
  всё равно показывает числовое изменение.
- Причина не подменяется корреляцией: backend/ИИ не заявляют причинность без достаточного факта.
- Один смысл не повторяется в нескольких блоках другими словами.
- У всех сотрудников не генерируется одинаковый нейтральный текст.
- При недостаточной базе показывается точная причина: количество смен, часов, продаж или coverage.
- Сотрудник без достаточной базы не сравнивается с коллегами и не получает искусственную оценку.
- Benchmark использует тот же явно определённый eligible-набор, что и отображаемый рейтинг.
- Ограничение возвратов, оплат или исходных документов не называется проблемой классификации.
- Технические коды и внутренние идентификаторы не показываются руководителю.
- В одной пользовательской секции не смешиваются разные временные горизонты.

## 5. Поэтапный план

### Этап 1. Полный аудит текущей end-to-end реализации

Статус: **COMPLETED 2026-08-26**.

Проверить цепочку:

```text
источники и расчёты
→ WeeklyAnalyticsFacts
→ immutable weekly snapshot
→ факты, sufficiency, limitations и candidates
→ provider compactor
→ prompt и provider response schema
→ semantic validation и backend enrichment
→ publication и read projection
→ API contract
→ frontend rendering и пользовательские состояния
```

Для каждого текущего блока зафиксировать:

- пользовательское назначение;
- источник и формулу показателей;
- текущий и базовый периоды;
- пороги materiality/sufficiency;
- правила выбора primary/supporting signals;
- кто формирует текст;
- допустимые evidence refs;
- поведение при отсутствии данных и ошибке провайдера;
- влияние data-quality issues;
- фактическое отображение на desktop/mobile;
- тестовое покрытие и отсутствующие проверки.

Обязательные сценарии аудита:

- production snapshots обоих магазинов за 2026-08-17—2026-08-23;
- рост, падение и отсутствие материального изменения;
- нулевые продажи и нулевой denominator;
- одна смена/мало часов/мало продаж у сотрудника;
- равные результаты сотрудников;
- сотрудник вне рейтинга;
- отсутствующая и неожиданно нулевая себестоимость;
- неклассифицированная позиция;
- возврат без исходной продажи/позиции и mismatch оплат;
- FAILED, PARTIAL и READY generation/read states;
- новая snapshot revision после исправления источника.

Результаты этапа:

1. Матрица `текущая реализация → проблема → риск → целевое решение`.
2. Карта всех периодов и зависимостей.
3. Каталог пользовательских текстов и владельца каждого текста.
4. Реестр найденных дефектов с severity и тестом воспроизведения.
5. Решение о готовности перейти к этапу 2.

Полный результат: [AI_WEEKLY_REDESIGN_STAGE1_AUDIT.md](AI_WEEKLY_REDESIGN_STAGE1_AUDIT.md).

Критерий завершения: ни один отображаемый блок, текст или fallback не остаётся без объяснённого
источника, периода и владельца.

### Этап 2. Точный контракт каждого блока

Статус: **COMPLETED 2026-08-26 — PRODUCT APPROVED**.

Для каждого блока утвердить:

- назначение и управленческий вопрос;
- обязательные и необязательные поля;
- точные формулы и единицы;
- сравниваемые периоды;
- минимальную достаточную базу;
- пороги и правила округления;
- разрешённые типы выводов;
- evidence contract;
- empty/loading/error/partial states;
- правила раскрытия подробностей;
- desktop/mobile presentation;
- accessibility и понятные подписи.

Результаты этапа:

1. Версионированный backend/API contract.
2. Версионированный content schema.
3. Wireframe и frontend state matrix.
4. Acceptance examples для обоих магазинов и граничных сценариев.
5. Явный список удаляемых полей и backward-compatibility plan.

Утверждённый контракт: [AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md](AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md);
нормативная typed-модель:
[AI_WEEKLY_REDESIGN_STAGE2_API_CONTRACT.md](AI_WEEKLY_REDESIGN_STAGE2_API_CONTRACT.md).

Критерий завершения: по контракту можно независимо реализовать backend, prompt и frontend без
дополнительного толкования продуктового смысла.

### Этап 3. Новая backend-модель

Статус: **COMPLETED**.

- реализовать утверждённые периоды, метрики, sufficiency и materiality;
- исключить месячный план из weekly-контура;
- исправить маршрутизацию data-quality limitations;
- формировать ограниченные backend-owned candidates;
- обеспечить immutable revision и backward-compatible read path;
- добавить unit, repository, integration и contract tests;
- обновить OpenAPI и техническую документацию.

Критерий завершения: backend детерминированно выдаёт все необходимые значения и не разрешает ИИ
сформировать неподтверждённый вывод.

### Этап 4. Новый prompt и content schema

Статус: **COMPLETED — CANDIDATE_ELIGIBLE_FOR_CANARY**.

- создать новую, а не изменять существующую production-версию;
- передавать только минимальный privacy-reduced набор фактов;
- ограничить output точными candidates/evidence;
- исключить общие, повторяющиеся и причинно неподтверждённые формулировки;
- запретить персональный вывод при недостаточной базе;
- синхронизировать online validator и offline evaluator;
- выполнить deterministic corpus и платный semantic-прогон только после автоматических gate.

Критерий завершения: все обязательные сценарии проходят автоматическую и слепую семантическую
проверку, critical/forbidden findings отсутствуют.

### Этап 5. Новый интерфейс

Статус: **COMPLETED / VERIFIED LOCALLY**.

- реализовать утверждённую иерархию страницы;
- показывать периоды и сравнение непосредственно рядом с показателями;
- сделать evidence доступным без технического шума;
- разделить факты, объяснения и действия;
- реализовать точные partial/insufficient/error states;
- исключить дублирующие блоки «Данные» и неопределённые подписи;
- добавить component, interaction, responsive и accessibility tests.

Критерий завершения: руководитель понимает смысл каждого блока и источник каждого вывода без
обращения к документации.

### Этап 6. Полная проверка и production rollout

Статус: **IN PROGRESS — LOCAL GATES COMPLETE, PRODUCTION CANARY PENDING**.

Последовательность gate:

1. Backend check, миграционные и rollback/forward-fix проверки.
2. Frontend tests, typecheck и production build.
3. Cross-layer contract и end-to-end tests.
4. Offline semantic corpus на обоих магазинах и граничных сценариях.
5. Shadow/canary без замены текущей опубликованной версии.
6. Ручная проверка руководительского UX и evidence.
7. Явное решение о включении feature flag.
8. Post-deploy API/UI/observability проверка и подтверждённый rollback path.

Критерий завершения: новая версия стабильна для обоих магазинов, все gate зелёные, а возврат на
v21/schema3 проверен и документирован.

## 6. Исходные известные проблемы для аудита

Это предварительный список, а не окончательные выводы этапа 1.

| ID | Наблюдение | Предварительный риск | Статус |
| --- | --- | --- | --- |
| AIW-001 | Месячный `PLAN` может стать главным сигналом недельного разбора | Смешение периодов и неверное пользовательское толкование | Подтверждено, целевое решение принято |
| AIW-002 | Формулировка «существенно ниже целевого уровня» не называет показатель и период | Непонятный и непроверяемый вывод | Подтверждено |
| AIW-003 | Любая open quality issue может превращаться в `CLASSIFICATION_QUALITY_LIMITED` | Возвраты/оплаты ошибочно называются классификацией | Подтверждено, требуется аудит влияния |
| AIW-004 | При недостаточной базе появляются общие тексты сотрудника | Формальный анализ без управленческой ценности | Подтверждено и трассировано |
| AIW-005 | Пользователь не всегда видит основание вывода рядом с текстом | Низкое доверие к ИИ | Подтверждено |
| AIW-006 | На странице встречались два неочевидных блока «Данные» | Дублирование и непонятная иерархия | Подтверждено |
| AIW-007 | Production v21/schema3 технически работает, но продуктовая семантика не принята | Риск принять успешную генерацию за качественный разбор | Подтверждено |

Полный реестр `AIW-001…AIW-023` с severity, риском и целевым направлением находится в отчёте этапа 1.

## 7. Правила выполнения работ

- Проходим этапы строго последовательно.
- Новый этап не начинается, пока критерий предыдущего не проверен и не зафиксирован здесь.
- Все найденные проблемы записываются до исправления; проблема не исчезает из журнала после фикса.
- Любое изменение контракта содержит причину, затронутые слои и migration/compatibility impact.
- Код делится на логические коммиты: документация, backend contract, prompt/schema, frontend,
  rollout/operations не смешиваются без необходимости.
- Перед коммитом проверяется `git diff`; пользовательские и параллельные изменения не включаются.
- Paid provider calls, production publication и внешние уведомления требуют отдельного явного
  разрешения.
- Результаты тестов записываются с точной командой, количеством тестов и статусом.
- Производственные данные не копируются в репозиторий; фиксируются только агрегированные выводы и
  безопасные идентификаторы сценариев.

## 8. Рабочий журнал

Каждая существенная сессия добавляет запись следующего формата:

```text
### YYYY-MM-DD — этап N — краткое название

Статус этапа до/после:
Проверено:
Найдено:
Принятые решения:
Изменённые файлы/коммиты:
Запущенные проверки и результат:
Открытые вопросы и риски:
Следующая точка продолжения:
```

### 2026-08-26 — этап 0 → этап 1 — фиксация направления редизайна

Статус этапа до/после: продуктовая проблема сформулирована; этап 1 открыт.
Проверено: production v21/schema3 успешно генерирует и публикует READY-ответы для обоих магазинов,
но успешный lifecycle не гарантирует понятную продуктовую семантику.
Найдено: недельный разбор смешивает недельную динамику с месячным планом; плановый сигнал может
стать главным выводом; ограничения качества могут называться неточно.
Принятые решения: план полностью исключить из «ИИ-разбора»; сравнивать только две полные недели;
сначала провести end-to-end аудит, затем утвердить контракт, и только после этого менять backend,
prompt/schema и frontend.
Изменённые файлы/коммиты: создан этот living-документ; коммит ещё не создавался.
Запущенные проверки и результат: проверено чистое состояние tracked worktree; пользовательский
`.codex-prod-recovery/` не затронут.
Открытые вопросы и риски: полный реестр появится по результатам этапа 1.
Следующая точка продолжения: построить карту backend-to-UI текущей production-цепочки и матрицу
всех отображаемых блоков.

### 2026-08-26 — этап 1 — полный end-to-end аудит v21/schema3

Статус этапа до/после: `IN PROGRESS → COMPLETED`; gate разрешает проектирование этапа 2 после
подтверждения пользователя.
Проверено: расчётные источники, периоды, snapshot facts, quality/sufficiency/materiality,
candidates, provider compactor, v21 prompt и specialized schema3, semantic validation, backend
enrichment, publication/read states, public API/evidence и desktop/mobile frontend path.
Найдено: месячный plan доминирует над недельными сигналами; comparison dates отсутствуют в API;
v21 не даёт содержательного employee analysis/actions; global open issues ошибочно
маршрутизируются как classification; team eligibility thresholds противоречат друг другу; provider
остаётся обязательным single point of failure для почти полностью backend-owned отчёта.
Принятые решения: production v21/schema3 не править точечно; на этапе 2 проектировать direct
`v22/schema4` без plan/legacy slots, с deterministic report и необязательным AI enrichment.
Изменённые файлы/коммиты: создан `docs/AI_WEEKLY_REDESIGN_STAGE1_AUDIT.md`, обновлён этот
журнал; коммит не создавался.
Запущенные проверки и результат: backend targeted suite — 38/38; frontend targeted Vitest —
23/23; сохранённая v21 semantic matrix повторно проанализирована — 26/26 technical pass, но
0/26 actions и только 1 secondary insight на 26 сценариев.
Открытые вопросы и риски: на этапе 2 утвердить точный block contract, roster/benchmark semantics,
quality routing, deterministic fallback и критерии manager usefulness.
Следующая точка продолжения: начать этап 2 с контракта блока «Период и состояние данных», затем
зафиксировать KPI comparison block и только после этого store/team/employee narratives/actions.

### 2026-08-26 — этап 2 — точный контракт v22/schema4

Статус этапа до/после: `NOT STARTED → IN PROGRESS → COMPLETED`; продуктовый gate подтверждён пользователем.
Проверено: все девять целевых блоков, формулы core KPI, source/quality routing,
sufficiency/materiality, roster/benchmark, employee cards, actions, deterministic fallback, frontend
states, accessibility, versioning и rollback compatibility.
Найдено: composite employee score зависит от plan context; старый average receipt смешивает net
revenue после возвратов с числом продаж; один общий employee sufficiency скрывает независимые
доступные метрики.
Принятые решения: новый direct weekly-review endpoint; gross sales и returns раскрываются отдельно;
employee sales/efficiency/attach имеют независимую достаточность; team benchmark — медиана минимум
трёх eligible сотрудников; AI — необязательный store-level enrichment; team block содержит только
агрегаты без ФИО, весь персональный контент принадлежит employee cards.
Изменённые файлы/коммиты: созданы `docs/AI_WEEKLY_REDESIGN_STAGE2_CONTRACT.md` и
`docs/AI_WEEKLY_REDESIGN_STAGE2_API_CONTRACT.md`, обновлён журнал; production-код и коммиты не
изменялись.
Проверки: Markdown whitespace check — PASS; вручную проверены periods, plan exclusions, null/zero и provider-failure states.
Открыто: продуктовых блокеров этапа 2 нет.
Следующая точка: начать этап 3 с immutable snapshot v7, typed public report DTO и contract tests.

### 2026-08-26 — этап 3 — deterministic backend и immutable snapshot v2

Статус этапа до/после: `NOT STARTED → IN PROGRESS → COMPLETED`; backend gate закрыт.
Проверено и реализовано: две завершённые недели, sales/returns decomposition, четыре core KPI,
иерархическая структура продаж, attach, независимые sufficiency/materiality, period-scoped quality,
агрегированный team block, персональные employee cards, exact employee-attribution limitation,
deterministic factors/actions/evidence и direct public DTO. Месячный plan, forecast, current week и
`EmployeeRatingService` из нового source graph исключены.
Граница team/employee: `TeamBlock` не содержит ФИО, employee ID, персональных метрик или actions;
весь персональный контент принадлежит `EmployeeCard`. Возврат без продавца исходной продажи
остаётся в store totals, но ограничивает только team/employees с точным count и evidence.
Хранение и совместимость: добавлена additive V45 с immutable `weekly_review_snapshots`, revision
chain, SHA-256 integrity и отдельным read path; v21/schema3 endpoint и historical storage не
изменены. Старый V44 image после V45 не является допустимым rollback; безопасный путь — старый
endpoint внутри V45-compatible build и forward-fix.
Версии реализации: `weekly-review-contract-v2`, facts schema `2`, `weekly-metrics-v4`,
`weekly-snapshot-v7`, `weekly-quality-v4`.
Изменённые файлы/коммиты: новый пакет `interpretation.review`, controller, V45, contract/repository/
integration tests, OpenAPI и технические документы; коммит не создавался.
Проверки: targeted weekly-review suite — PASS; ранее упавшие migration/schema/OpenAPI области —
PASS; полный backend — `965 tests, 0 failures, 0 errors, 0 skipped`; checkstyle main/test — PASS;
`git diff --check` — PASS.
Открыто: prompt v22/schema4, AI enrichment, frontend, scheduler/operator generation и production
feature flag намеренно относятся к следующим этапам.
Следующая точка: этап 4 начать с failing schema/validator tests, privacy-reduced provider input и
запрета AI изменять числа, периоды, factors, actions или employee content.

### 2026-08-27 — этап 4 — foundation v22/schema4

Статус этапа до/после: `NOT STARTED → IN PROGRESS`; production activation запрещена.
Проверено и реализовано: отдельные immutable identifiers `weekly-interpretation-v22`/schema4,
строгая output schema и canonical example, typed DTO без repair-нормализации, store-only
privacy-reduced input/schema, compactor и semantic ownership validator.
Privacy boundary: provider input не содержит employee/team/plan/period/provenance/store/snapshot IDs,
ФИО, UUID, shifts или raw payload; допускаются только выбранные store factors/actions и их exact
evidence.
Semantic gate: exact ordered factor/action sets, object evidence allowlist, numeric literal allowlist,
causality permission, forbidden monthly/current horizon, personnel judgment, generic fallback, UUID и
duplicate narrative. Невалидный enrichment отбрасывается целиком.
Совместимость: legacy registry, jobs, v21/schema3 resources, endpoint и historical projector не
изменены; provider call, persistence, scheduler и publication для v22 отсутствуют.
Изменённые файлы/коммиты: новый пакет `interpretation.review.ai`, input/output schemas, prompt v22,
examples, tests и `docs/weekly-review-v22-ai-contract.md`; коммит не создавался.
Проверки: schema/typed/privacy/semantic suite — `15 tests, 0 failures, 0 errors`; checkstyle main/test —
PASS. Принудительный full backend run — `972 tests, 1 infrastructure failure` на старте
PostgreSQL Testcontainer в `SecurityHardeningIntegrationTest`; isolated retry класса — PASS.
Открыто: validated enrichment applier/storage, новый provider lifecycle, общий deterministic corpus,
offline evaluator, paid semantic shadow и blind review.
Следующая точка: закрепить неизменность backend numbers/targets/team/employees при применении
validated enrichment, затем спроектировать отдельное immutable storage без изменения V45 snapshot.

### 2026-08-27 — этап 4 — safe application и immutable V46

Статус подэтапа: `COMPLETED`; provider integration и production activation по-прежнему запрещены.
Исторический контракт v22 разрешал AI менять `summary.outcome.text`, `Factor.detail` и `Action.title`; в v23 заголовок действия переведён в backend ownership.
Отдельные `summary.positive/risk`, числа, periods, factor comparison/contribution, action
target/check/horizon, team, employees, limitations, evidence и provenance остаются неизменными.
Numeric allowlist теперь объектный и объединяет только литералы исходного текста с exact values его
evidence. `action.check` обязан дословно совпадать с backend.
Validation gate разделяет structural-valid и semantic-valid состояния. Enricher и persistence
принимают только semantic marker; invalid, structural-only или mismatched content возвращает
исходный deterministic report либо отклоняется до записи.
Хранение: additive V46 `weekly_review_ai_enrichments` связано с exact V45 snapshot, хранит
prompt/schema, input/content SHA-256, canonical content и timestamps; unique key и trigger
обеспечивают one-version/immutability. `WeeklyReviewService.current` применяет optional published
enrichment только при `published_at <= asOf`, а при отсутствии строки отдаёт V45 report без
изменений.
Изменённые файлы/коммиты: Enricher, codec/store/persisted model, V46, read composition, schema/prompt,
unit/integration tests и документация; коммит не создавался.
Проверки: targeted weekly-review/V46 suite — `60 tests, 0 failures, 0 errors, 0 skipped`;
checkstyle main/test — PASS; `git diff --check` — PASS. Первый full backend run выявил пропущенный
V46 non-JPA table в schema-contract allowlist; после исправления класс — PASS. Повторный full run —
`987 tests, 2 unrelated flakes`: concurrent-session eviction и системные часы, сдвинувшиеся назад
между `started_at/finished_at` sync job. Изолированный повтор обоих классов — PASS.
Открыто: provider request/worker/budget/readiness, deterministic evaluator corpus, offline eval,
paid shadow-run, blind review, frontend switch, scheduler/operator generation и production flag.

### 2026-08-27 — этап 4 — durable provider lifecycle и operational safety

Статус подэтапов: `IMPLEMENTATION COMPLETED`; production activation не выполнялась.
Реализовано: 17-case deterministic corpus, единая online/offline validation path, provider request
factory, durable V47 jobs/attempts, lease/retry/max-two-calls, readiness и budget gates, атомарная
публикация V46, planner только для latest exact V45 revision, операторские endpoints и state model.
Безопасность: provider input privacy-reduced; AI не может менять числа, периоды, team/employees,
action target/check или evidence. Дневная стоимость резервируется атомарно до provider call. Parent
flag является настоящим read-rollback и исключает применение уже сохранённого enrichment.
Operations: production compose передаёт все flags/caps/timeouts; release preflight запрещает дочерние
flags без parent, unversioned model URI и более двух calls. Добавлены job-state metrics, manual
deterministic snapshot, one-job canary, blind packet с проверкой input/response/receipt integrity и
runbook `docs/weekly-review-v22-rollout.md`.
Проверки подэтапов: targeted lifecycle/storage/atomicity/budget/operator/metrics tests — PASS; offline
review tests — PASS; network-free shadow plan — 4 cases, максимум 12.720800 RUB; compose config и
release-safety tests — PASS. Полный итоговый backend-прогон фиксируется следующей записью.
Открытый внешний gate: реальные provider credentials отсутствуют; платный четырёх-case shadow и
независимая слепая семантическая оценка не запускались. До них `CANDIDATE_ELIGIBLE_FOR_CANARY` не
выдан и production flags должны оставаться false.

### 2026-08-27 — этап 4 — итоговая автоматическая верификация

Статус реализации: `VERIFIED`; внешний semantic acceptance остаётся `PENDING`.
Полный backend был запущен с `--rerun-tasks`: `1017 tests, 0 failures, 0 errors, 0 skipped`;
checkstyle main/test — PASS; все V1–V47 migration/schema checks — PASS. Blind-review tooling — `3/3`;
network-free shadow plan — `4/4`, maximum `12.720800 RUB`; security/release-safety scripts и
production compose config — PASS. Платных вызовов, deploy, production writes и Telegram fanout не
было.

### 2026-08-27 — этап 4 — paid semantic calibration v2

Paid shadow разрешён пользователем и выполнен без production publication. Первый пакет выявил не
галлюцинацию модели, а несовпадение evaluator corpus с production compactor и строковое сравнение
числового формата: `120000` ошибочно отклонялось как `120 000`. Numeric validator переведён на
каноническое числовое сравнение с сохранением запрета нового значения; corpus/manifest обновлены до
`weekly-review-ai-eval-v2`, prompt разрешает только безопасное русское типографическое оформление.
Последующие пакеты откалибровали action title от общего «Проверить возвраты» через перегруженный
вариант к короткой команде «Проанализировать рост возвратов». Финальный acceptance run:
`4/4 semanticValidated=true`, actual `3.380800 RUB`, forbidden/critical automation findings — 0.
Всего за калибровку: 18 calls, `14.716800 RUB`. Временная копия credentials удалена с сервера.
Integrity-checked blind packet создан; последующая независимая оценка зафиксирована отдельной записью ниже.

### 2026-08-27 — этап 4 — регрессия после paid calibration

После исправлений evaluator, numeric validator и prompt выполнен полный backend-прогон с
`--rerun-tasks`: `1017 tests, 0 failures, 0 errors, 0 skipped`; checkstyle main/test — PASS;
blind-review tooling — `3/3`; финальный пакет — `4/4 semantic outputs ready`; production compose,
security/release-safety scripts и `git diff --check` — PASS. Новых платных вызовов, deploy и
production writes не выполнялось. Единственный незакрытый gate этапа 4 — независимая слепая
продуктовая оценка финального пакета.

### 2026-08-27 — этап 4 — blind rejection v2 и исправленный corpus v3

Независимый reviewer оценил пакет вслепую: общая средняя `4.55/5`, три кейса прошли, но gate
корректно выдал `REJECTED` из-за critical error в `exact-numeric-literals`. Synthetic input сочетал
`110000 -> 120000` с ростом `5,9%`, хотя арифметически это около `9,1%`. Модель не придумала число,
а дословно использовала разрешённый backend outcome; дефект находился в acceptance-corpus.
Corpus исправлен на `113315 -> 120000` (`5,899484%`, округление до `5,9%`) и версионирован как
`weekly-review-ai-eval-v3`; manifest v1/v2 сохранены как исторические. Targeted Java/Checkstyle,
Python integrity tests и network-free v3 plan — PASS: 4 cases, maximum `13.648000 RUB`.
Незакрыто: новый четырёх-case paid acceptance v3 и повторный независимый blind review.

### 2026-08-27 — этап 4 — final acceptance v3

Paid acceptance v3 выполнен в новом изолированном каталоге: `4/4 semanticValidated=true`,
actual `3.382400 RUB`, maximum `13.648000 RUB`. Независимый blind reviewer поставил среднюю
оценку `4.75/5`: clarity `5.00`, specificity `4.75`, actionability `4.50`, evidence fidelity `5.00`,
non-duplication `4.50`; required findings покрыты во всех кейсах, forbidden/critical findings — 0.
Integrity-finalize выдал `CANDIDATE_ELIGIBLE_FOR_CANARY`; все четыре case averages — `4.6–5.0`.
Итоговая стоимость всей калибровки v2+v3: 22 calls, `18.099200 RUB`. Временные credentials удалены.
После v3 выполнен полный `--rerun-tasks` backend gate: `1017 tests, 0 failures, 0 errors, 0 skipped`;
checkstyle main/test, production compose, security/release-safety, Python blind tooling и
`git diff --check` — PASS. Deploy, production publication и включение feature flags не выполнялись.

## 9. Текущая точка продолжения

Активный этап: **этап 6 — V24 CALIBRATION AND FULL RELEASE GATE IN PROGRESS**.

Безопасная точка: v22/schema4 реализован end-to-end от immutable V45 facts до bounded provider
lifecycle и optional immutable V46 enrichment. Runtime default-off; новый frontend зафиксирован в release-ветке, production activation не
выполнялась.

Этап 4 закрыт статусом CANDIDATE_ELIGIBLE_FOR_CANARY. В этапе 5 маршрут `/insights` локально
переключён с v21/schema3 на direct weekly-review v2. Реализованы строгий runtime contract,
manager-first иерархия без месячного плана, отдельные контуры «Команда» и «Сотрудники», локальные
состояния блоков, явные empty/error states и адаптивный интерфейс без горизонтального overflow.
Golden fixture получена сериализацией production `WeeklyReviewAssembler`; полный evidence graph и
report invariants проверяются тем же Zod-контрактом, что и сетевой ответ. Контракт экрана
зафиксирован в `docs/AI_WEEKLY_REDESIGN_STAGE5_UI_CONTRACT.md`.

Финальная локальная проверка этапа 5: generated OpenAPI contract — PASS; ESLint — PASS; Vitest —
`41 files, 173 tests`; production build — PASS; backend-to-frontend golden serialization test и
Java checkstyleTest — PASS; visual `/insights` — desktop 1440, tablet 768 и Pixel 7, `3/3 PASS`, без
console errors, HTTP 5xx и горизонтального overflow. Два независимых UX/code review выполнены:
все первичные замечания устранены, повторный review подтвердил отсутствие P0/P1 и блокеров;
финальные P2 по периодам, revenue identity и factor semantics также закрыты runtime-инвариантами и
негативными тестами.

Логические release-коммиты подготовлены. Следующие отдельные операции — default-off deploy, затем
проверка real v2 snapshot каждого магазина в ручном canary и только после этого включение
автоматических planner с rollback-путём на v21/schema3.

### 2026-08-27 — этап 5 — повторная пятиэтапная проверка

1. Ручной UX/style audit подтвердил manager-first иерархию, визуальное соответствие приложению,
   отсутствие смешения блоков «Команда» и «Сотрудники» и лишнего технического текста.
2. Новый visual pass desktop/tablet/mobile — `3/3 PASS`; горизонтального overflow, console errors,
   HTTP 5xx, наложений и нечитаемых мобильных блоков нет.
3. Interaction/state matrix — `30/30 PASS`: READY/PARTIAL/BLOCKED/PREPARING/404/error, локальные
   block states, formula/evidence/structure disclosures, retry, employee details и roster > 8.
   Native keyboard toggle Enter дополнительно проверен в Chromium на всех трёх viewport.
4. Сквозной weekly-review backend contract — `48/48 PASS`: расчёты, quality, structure,
   team/employees, AI enrichment, API и backend-to-frontend golden JSON; checkstyle — PASS.
5. Полный regression gate: frontend `41 files, 173 tests`, OpenAPI types, ESLint и production build
   — PASS; backend `1018 tests, 0 failures, 0 errors, 0 skipped`, OpenAPI compatibility,
   supply-chain integrity, release-safety и checkstyle — PASS (`--rerun-tasks`, 11m20s).

По итогам проверки продуктовых или функциональных дефектов не обнаружено. Добавлены только
постоянные interaction-регрессии для клавиатурных раскрытий и длинного списка сотрудников;
production, commit и feature flags не затрагивались.

### 2026-08-28 — этап 5 — visual polish и anti-AI review

Выполнена отдельная визуальная переработка без изменения бизнес-логики, данных, текстового
контракта и назначения контролов. Убраны card-heavy композиция, вложенные поверхности,
декоративные иконки заголовков, крупные скругления и ложное selected-состояние KPI. Результаты,
факторы, действия, команда и сотрудники переведены в единую рабочую сетку с умеренной плотностью;
tablet KPI сотрудника адаптированы, mobile hero и строки сотрудников уплотнены. Повторные KPI в
раскрытии сотрудника визуально исключены, но остаются доступными в его основной строке.

Проведены pixel-level, anti-AI и visual-subtraction проходы на desktop 1440, tablet 768 и Pixel 7.
Независимая первая оценка `7.1/10` использована как список дефектов; после трёх итераций и финального
subtraction-pass повторный reviewer подтвердил отсутствие P1 и итог `8.7/10`: typography `8.5`,
spacing `8.6`, alignment `8.7`, hierarchy `8.6`, density `8.4`, color/surface discipline `8.9`,
responsive `8.6`, consistency `8.8`, professional polish и отсутствие AI-look `8.6`.

Проверки: ESLint — PASS; frontend `41 files, 173 tests` — PASS; production build — PASS; visual
desktop/tablet/mobile — `3/3 PASS`; `git diff --check` — PASS; запрещённые gradient/shadow/blur,
pill и oversized-radius паттерны в polish-слое отсутствуют. Commit, deploy и production flags не
выполнялись.

### 2026-08-28 — этап 5 — точечная переработка ключевых блоков

По пользовательскому ревью уточнены четыре ключевые зоны без изменения данных и бизнес-логики.
Период собран в две выровненные строки: дата завершённой недели выделена, период сравнения оставлен
обычным. В заголовок «Результаты недели» добавлен анализируемый период. «Главное» переведено в
компактный рамочный модуль: отдельные предложения отображаются самостоятельными строками с
разделителями, суммы и знак рубля не разрываются на мобильном, исходный текст сохранён как
accessibility label. «Основные изменения» и «Следующие шаги» получили последовательную вертикальную
структуру без пустой desktop-колонки; мобильные ориентир и проверка складываются в читаемые строки.

Декоративный зелёный убран с рамок, наблюдений и аватаров; цвет оставлен только для успешного
статуса и положительной числовой динамики. Независимое визуальное ревью после трёх проверок выдало
PASS для desktop, tablet и mobile по всем четырём требованиям. Финальные проверки: ESLint — PASS;
frontend `41 files, 173 tests` — PASS; production build — PASS; visual — `3/3 PASS`;
`git diff --check` — PASS. Commit, deploy и production flags не выполнялись.

### 2026-08-28 — этап 5 — возврат аналитики в верхний уровень

После пользовательского ревью исправлен перекос в сторону сухих чисел. Верхний блок теперь сначала
объясняет, как завершилась неделя относительно предыдущей: направление чистой выручки, валовой
прибыли и маржи. Если backend доказал арифметический вклад возвратов, он добавляется к итогу
отдельным понятным предложением. Рядом снова постоянно видны положительная динамика, риск и
приоритетное действие; их числовые основания раскрываются отдельно и не подменяют вывод.

Для факторов без рассчитанного вклада интерфейс прямо отделяет наблюдение от причины общего
результата. Backend-заголовки переведены с механического `<label> вырос` на устойчивые формулировки:
«Возвраты выросли», «Выручка направления … выросла», «Допродажи … выросли». Golden fixture
актуализирована и сверена с production `WeeklyReviewAssembler`.

Проверки на этой точке: frontend `41 files, 173 tests`, OpenAPI contract, ESLint и production build
— PASS; backend assembler golden test и checkstyle main/test в Java 21 — PASS; visual desktop,
tablet и mobile — `3/3 PASS`, без HTTP 5xx, console errors и горизонтального overflow.
Повторный независимый продуктовый review — PASS, P0/P1 замечаний нет; формулировка неподтверждённой
связи сокращена по итоговому P2. Причины возвратов намеренно не придумываются до проверки данных.
Commit, deploy и production flags не выполнялись.

### 2026-08-29 — этап 6 — release hardening и автоматизация

Перед production rollout проведён отдельный code/release review. Закрыты три риска: frontend больше
не теряет v21 при отсутствии/ошибке v22; snapshot/enrichment JSON связан с immutable DB headers
миграцией V48; неопределённый provider outcome сохраняет оценочный суточный резерв. Суточный запрос
ограничен точным UTC-интервалом.

Добавлен default-off deterministic `WeeklyReviewSnapshotPlanner`: после появления совокупного
непрерывного покрытия обеих сравниваемых недель он создаёт первую revision, переиспользует
неизменившийся content и создаёт новую revision только после более свежего source. Все активные магазины
обрабатываются keyset-страницами без постоянного лимита первой пачкой. AI planner не допускается
release preflight без deterministic planner.

Добавлен независимый `WEEKLY_REVIEW_ENABLED`. В выключенном состоянии новый endpoint отдаёт 404,
и frontend показывает v21/schema3 даже при уже сохранённых v22 snapshots. Это даёт обратимый
per-store canary и мгновенный функциональный rollback без удаления immutable history.

Повторное независимое review выявило и закрыло ещё три риска: V48 backfill на заполненной V47
обходит immutable-trigger только внутри транзакции и затем восстанавливает его; planner не оставляет
магазины после первой страницы; достаточное sync coverage проверяется от начала предыдущей недели
до конца текущей. Добавлен populated-V47 migration test и регрессии keyset/coverage.

Целевые проверки: frontend fallback `17/17 PASS`; полный frontend `41 files, 175 tests`,
ESLint, OpenAPI types и production build — PASS. Финальный backend gate — `1030 tests, 0 failures,
0 errors, 0 skipped`; V1–V48 migrations, populated-V47 upgrade, cumulative coverage, OpenAPI
compatibility, supply-chain, security, release-safety и checkstyle — PASS. Повторный независимый
release review — PASS, блокеров нет. Новых платных вызовов, deploy и production activation не выполнялось. Реализация разделена на
логические backend, frontend и documentation commits.


### 2026-08-29 — этап 6 — v23 управленческая калибровка и полный regression

Статус этапа до/после: локальный v23 candidate прошёл автоматические и semantic gate; production-canary не выполнялся.

Provider input версионирован как `weekly-review-ai-input-v2`: backend передаёт `summary.outcomeEffect` и предметный `factor.managementMeaning`, а модель больше не определяет направление недели и смысл показателя самостоятельно. Prompt `weekly-interpretation-v23` отделяет единый итог от факторов и действий; validator запрещает неподтверждённое уточнение «предыдущая полная неделя». V22 enrichment остаётся fallback.

Offline corpus расширен до 41 сценарий. Последовательная paid-калибровка израсходовала 14,104 ₽ из разрешённых 20 ₽. Финальный ответ semantic-valid; независимый blind reviewer — PASS, 4,8/5, hard gates — 0. Дополнительные платные вызовы остановлены.

Полный backend gate после исправлений: `1045 tests, 0 failures, 0 errors`; Checkstyle main/test, generated OpenAPI compatibility, security/release-safety и supply-chain (`449 components`, `840 artifacts`) — PASS. Frontend gate: `41 files, 175 tests`, generated contracts, ESLint и production build — PASS.

Full regression выявил редкий откат системных часов между claim и завершением snapshot-job. `WeeklySnapshotJobStore` и runner теперь не допускают `finished_at < started_at` и строят retry deadline от монотонного времени; добавлены unit/integration regression tests. Повторный targeted и полный прогон — PASS.

Открыто: финальное независимое code/release review, разделение на логические коммиты, default-off deploy и ручной canary «МобиСферы».


### 2026-08-29 — этап 6 — v24 fail-closed management contract

Независимое review v23 выявило принципиальный риск: корректный префикс summary мог сопровождаться свободным противоречащим продолжением. Paid prompt v23 оставлен immutable. Активный candidate переведён на `weekly-interpretation-v24`, provider input `weekly-review-ai-input-v3` и corpus `weekly-review-ai-eval-v5`.

Backend формирует полный allowlist `summary.allowedNarratives`; validator принимает только дословный элемент списка. Factor строится из exact `managementMeaning` и фиксированной оценки effect; title/check действия остаются backend-owned. Read-path использует fallback `v24 → v23 → v22`, старые jobs и enrichments не переписываются.

Targeted contract/semantic/compactor tests, 41-case offline corpus и Checkstyle — PASS. Network-free maximum для четырёх paid cases — 14,9472 ₽; v23 уже использовал 14,104 ₽ из общего лимита 20 ₽, поэтому v24 разрешён только последовательными вызовами с отдельным hard cap и case offset.

Полный локальный gate: backend `1052 tests, 0 failures, 0 errors, 0 skipped`, Checkstyle и OpenAPI compatibility — PASS; frontend `41 files, 175 tests`, ESLint и production build — PASS; security, release-safety и supply-chain gates — PASS. Независимый code review не выявил P0/P1 в реализации.

Один paid `positive-growth` case прошёл semantic validation и стоил 1,036 ₽. Independent blind review не нашёл hard-gate нарушений и дал среднюю оценку 4,4/5, но `manager usefulness` — только 2/5: summary и factor почти дословно повторяли backend input. V24 закрыт со статусом REJECT, дополнительные paid calls и production-canary остановлены. Следующая итерация должна быть отдельной immutable prompt/input/corpus version и добавлять управленческий синтез без ослабления factual boundary.

Clock-rollback защита расширена на terminal transitions `ReportBackfillJob`; отдельные regression tests покрывают SUCCESS, FAILED и CANCELLED.
