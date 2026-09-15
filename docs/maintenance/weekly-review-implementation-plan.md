---
doc_schema: 1
doc_type: working
status: draft
owner: project
audience:
  - developer
  - product
created_at: 2026-09-12
review_by: 2026-09-26
source_material:
  - docs/maintenance/weekly-review-manager-experience-plan.md
  - docs/current/ai/weekly-review.md
  - docs/current/product/employees-and-rating.md
  - docs/runbooks/frontend-acceptance.md
  - docs/runbooks/production-deployment.md
  - docs/runbooks/application-rollback.md
  - docs/runbooks/backup-restore-and-dr.md
  - docs/runbooks/migration-failure-and-forward-fix.md
  - docs/runbooks/weekly-review-ai.md
  - frontend/src/insights/WeeklyReviewView.tsx
  - frontend/src/insights/weekly-review-presentation.ts
  - frontend/src/insights/weekly-review/weekly-review.css
  - frontend/src/api/weeklyReviewContract.ts
  - frontend/e2e/visual-local.spec.ts
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewAssembler.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewResponse.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewTeamEmployeeProjector.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewPolicyV1.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiInputCompactor.java
  - backend/src/main/java/com/storeanalytics/interpretation/review/ai/WeeklyReviewAiRendererV25.java
required_reviewers:
  - product
  - frontend
  - backend
  - ai-semantic
exit_target: current
---

# «ИИ-разбор»: подробный план реализации согласованной переработки

## Статус и назначение

Это технический план реализации согласованного продуктового baseline из
[`weekly-review-manager-experience-plan.md`](weekly-review-manager-experience-plan.md). Он задаёт
порядок правок, границы пакетов, проверки и критерии завершения. Документ не утверждает, что
описанное поведение уже действует, и не заменяет текущие runtime-контракты.

План следует выполнять сверху вниз. Пакет считается завершённым только после его тестов и
контрольной точки; перенос незавершённых решений в следующий пакет не допускается.

### Ход реализации на 2026-09-15

- P0 завершён: исходные тесты, build и локальные desktop/tablet/mobile снимки зафиксированы.
- P1–P4 завершены локально. Семантика workload отделена от sales, summary принадлежит backend,
  основной экран и единая detail panel перестроены, контрактные и компонентные тесты проходят.
- P5 завершён локально: пять согласованных fixture-сценариев проверены в desktop/tablet/mobile
  (`15/15`), включая раскрытые действия, структуру, employee detail и limitation panels. Артефакты
  просмотрены вручную; найденные проблемы мобильных подписей и сценарной достоверности исправлены.
- P6 завершён в локальной части контракта и интерфейса: current-контракты синхронизированы,
  независимые code/UI review проведены повторно после исправлений. Полный backend и frontend gates,
  real-data `PARTIAL` read path и live desktop/tablet/mobile проверка зелёные. Runtime-прогон выявил
  и закрыл противоречие summary при материальном изменении средней продажи и слипшиеся действия
  секции команды.
- P6.1 завершён локально после пользовательского визуального review: primary factor отделён от
  secondary factors, действие сформулировано как операция менеджера, decision cards выровнены,
  командная сводка удалена, а плотные mobile-списки получили progressive disclosure. Targeted и
  полные code/UI проверки выполнены повторно после финальной текстовой калибровки.
- P7-A завершён локально: создан отдельный candidate worktree от подтверждённой production-базы,
  change set сокращён до Weekly Review и двух минимальных общих frontend-зависимостей, manifest
  зарегистрирован и проверен против настоящего Git index.
- P7-B завершён локально: чистая установка, полный frontend/backend/documentation набор,
  пятисценарная visual-матрица и ручной UI-review прошли; npm audit не обнаружил уязвимостей.
  Локальные backend/web images собраны из одного reviewed commit, OCI revision совпадает, а
  упакованный migration range не отличается от production-базы.
- P7-C повторён после уточнения бизнес-правил: v12 evidence сохранён, а штатный authenticated API
  создал для обоих разрешённых магазинов новые immutable revision с policy
  `weekly-snapshot-v13`. Нулевая себестоимость и недоступная исходная связь возврата больше не
  ограничивают отчёт; все четыре core KPI обоих snapshots имеют `READY`. Оба отчёта естественно
  остались `PARTIAL` из-за независимых содержательных ограничений — employee sales sufficiency и
  классификации. Реальный `READY` в выборке отсутствует, поэтому release-gate P7-C остаётся `STOP`,
  хотя согласованная реализация бизнес-правил подтверждена.
- P7-D завершён как `DEFERRED`: deterministic-first путь перепроверен с выключенными AI planner,
  worker и generation; paid provider request не выполнялся.
- P7-E имеет `PASS_WITH_LIMITS`: продуктовый владелец проверил и принял исправленный маршрут по
  локальным снимкам, а повторная fixture-матрица прошла `15/15`. Отдельного таймированного
  исследования с назначенным менеджером без подсказок не проводилось.
- Функциональная часть «ИИ-разбора» после коррекции v13 закрыта отдельно от release gates:
  targeted backend-набор прошёл `44/44`, включая `4/4` SQL integration с `skipped=0`; полный
  frontend check прошёл `199/199`, а актуальные desktop/tablet/mobile captures проверены вручную.
  Мобильный заголовок выровнен влево. Эти результаты подтверждают candidate-код, но не подменяют
  пользовательский преддеплойный прогон и не переводят P7-H/P7-I в завершённое состояние.
- P7-F выполнен до stop-условия на изолированном локальном контуре: чистая schema boundary,
  migration, encrypted dump/restore и запуск API/worker/web подтверждены. Exact previous runtime,
  staging deploy path и fresh production backup недоступны, поэтому gate остаётся `STOP`.
- P7-G остановлен до обращения к production: нет опубликованных immutable candidate coordinates,
  exact host/release-env доступа, свежего production backup/restore evidence и закрытых P7-C/P7-F.
  P7-H/P7-I, project-state и legacy cleanup не начаты.

## Целевой результат

Менеджер за один–три минуты проходит маршрут:

1. видит результат последней завершённой недели;
2. понимает один главный приоритет;
3. получает проверяемое действие с ориентиром и критерием проверки;
4. при необходимости открывает основание вывода без перестройки всей страницы;
5. переходит к сотруднику или качеству данных только тогда, когда такой переход действительно
   существует и доступен его роли.

Экран должен быть короче текущего, но не беднее по смыслу. Подробности сохраняются в контекстной
панели и свёрнутых вторичных секциях, а не удаляются и не уменьшаются до нечитаемого текста.

## Обязательные инварианты

Эти условия не являются предметом дальнейшей визуальной настройки и проверяются тестами:

1. Целевой экран — `WeeklyReviewView`; legacy-компонент получает только поддержку совместимости.
2. Итог недели формирует backend. Frontend выбирает и форматирует готовое представление, но не
   сочиняет второй бизнес-вывод по цифрам.
3. На первом содержательном экране находятся период, свежесть, итог, одно главное действие и четыре
   результата магазина.
4. Одно наблюдение не повторяется самостоятельными карточками summary, positive signal, factor и
   action. Короткая ссылка на главный фактор в summary допустима; повтор его полного объяснения — нет.
5. Действие содержит операцию, числовой ориентир и способ проверки. Оно называется «Что проверить
   на этой неделе», пока нет сохранённого task-state.
6. Evidence и формулы открываются в одной контекстной панели: справа на desktop и снизу на mobile.
7. На основном уровне показываются максимум три сотрудника с реальным `ATTENTION`. Полный список
   остаётся в разделе «Сотрудники».
8. Отсутствующие или неполные смены ограничивают только `SHIFT_COUNT`, `WORKED_HOURS` и
   `REVENUE_PER_HOUR`. Они не переводят сотрудника, команду или весь отчёт в `LIMITED`/`PARTIAL`, не
   повышают приоритет сотрудника и не порождают управленческое действие.
9. Чистая выручка сотрудника означает вклад. Сравнение эффективности с коллегами допустимо только
   по выручке в час и только при достаточных сменах у сравниваемой группы.
10. `PARTIAL` сохраняет все надёжные значения и адресно объясняет затронутые выводы; `BLOCKED` не
    показывает недостоверный длинный отчёт.
11. В первой реализации графиков нет: endpoint не содержит согласованного временного ряда.
12. Опубликованные prompt/schema не переписываются. Изменение AI-контракта возможно только после
    отдельного semantic review и новой версии runtime-артефакта.
13. Нулевая себестоимость является допустимым значением и не ограничивает прибыль, маржу или
    report state; действительно отсутствующая себестоимость сохраняет fail-closed поведение.
14. Отсутствующая исходная продажа/позиция у части возвратов не считается нарушением store-level
    согласованности. Возврат учитывается в результате магазина, а недоступная employee attribution
    объясняется нейтрально только внутри блока команды.

## Подтверждённое исходное техническое состояние

| Область | На baseline | Следствие для реализации |
|---|---|---|
| Основной компонент | `WeeklyReviewView.tsx` содержит 1063 строки | Разделить ответственность до изменения визуального слоя |
| Стили | `weekly-review.css` содержит 1609 строк и накопленные media-overrides | Перестроить слой стилей, удалить неиспользуемые селекторы |
| Summary | Frontend содержит `deterministicSummaryLead` | Удалить повторную бизнес-интерпретацию |
| Действия | API уже передаёт `target` и `check`, карточка показывает только `title` | Использовать существующие данные до расширения API |
| Evidence | В нескольких карточках повторяется inline `<details>` | Ввести единый detail panel с контекстом вызова |
| Ограничения | `LimitationsSection` не включён в обычный `ReadyReview` | Показывать одну сводку и локальные маркеры для затронутых блоков |
| Сотрудники | Выбирается первый из списка до восьми человек | Перейти к exception-only без автоматического выбора |
| Смены | Workload limitation влияет на employee/team/report state | Развести sales sufficiency и workload availability на backend |
| Peer comparison | Backend считает медиану чистой выручки | Считать эффективность только по выручке в час при полной базе |
| AI | В AI input входят store summary/factors/actions, но не employee cards | Workload-исправление не требует само по себе новой AI-схемы |
| Visual fixtures | Текущий happy path слишком компактный | Добавить плотные READY, PARTIAL, BLOCKED и missing-shifts сценарии |

## Решение по совместимости контракта

Для первой переработки сохраняется текущая форма публичного ответа. Необходимые UI-данные уже есть:
`summary`, четыре `results`, `factors`, `actions.target`, `actions.check`, адресные `limitations` и
`evidenceRefs`.

Изменение peer benchmark выполняется совместимо:

1. Frontend-схема сначала начинает принимать как прежнюю `NET_REVENUE`, так и целевую
   `REVENUE_PER_HOUR` в `peerComparison.metricCode`.
2. Старое сравнение по `NET_REVENUE` не выводится как эффективность. Чистая выручка остаётся
   нейтральным показателем вклада в собственных метриках сотрудника.
3. После выпуска совместимого frontend backend начинает возвращать `REVENUE_PER_HOUR` только при
   достаточных workload-данных и минимум трёх подходящих сотрудниках. В остальных случаях
   `peerComparison` равен `null`.
4. Версии затронутых deterministic policy увеличиваются в коде и current-контракте, чтобы новая
   семантика создавала новую snapshot revision и была диагностируема.
5. Публичная версия ответа меняется только если в ходе реализации окажется, что существующей формы
   недостаточно. Такое решение оформляется отдельно до изменения DTO, OpenAPI и fixtures.

Связь factor/action не строится по позиции массива. В первой реализации она задаётся проверяемым
инвариантом: `action.metricCode` совпадает с `factor.comparison.code`, а evidence действия относится
к evidence этого фактора. Backend contract test обязан доказать однозначность. Если однозначность
не удаётся обеспечить для всех видов действия, работа останавливается на контрактном checkpoint и
в API добавляется явный идентификатор источника; frontend не получает скрытую эвристику.

## Целевая информационная архитектура

### Первый экран

Порядок фиксирован:

1. компактный header: период, «последняя завершённая неделя», время обновления, один нейтральный
   статус только для `PARTIAL`, `BLOCKED` или `PREPARING`;
2. decision block из главного итога и одной приоритетной проверки;
3. четыре KPI: чистая выручка, валовая прибыль, маржа, средний чек;
4. одна кнопка «Почему?» у вывода и одна «Как посчитано?» у нужной метрики открывают один и тот же
   detail panel с разным содержимым.

На desktop итог и действие размещаются рядом, ориентировочно в пропорции 3:2, растягиваются до
одинаковой высоты текущей строки и выравнивают evidence-действия по нижнему краю. Жёсткая высота в
пикселях не задаётся. На tablet/mobile они идут друг под другом — сначала вывод, затем действие —
и сохраняют естественную высоту.

### Вторичный уровень

После KPI идут независимые секции:

1. **Что изменилось** — до трёх материальных факторов; положительный сигнал появляется только здесь.
2. **Структура продаж** — свёрнутая таблица/иерархия без графика.
3. **Команда** — счётчик `N из M требуют проверки` в заголовке, без отдельной сводной плашки, и
   максимум три сотрудника `ATTENTION`.
4. **Ограничения данных** — одна сводка для `PARTIAL`; подробности открываются в detail panel.

Секции не вкладываются друг в друга. Внутри раскрытой секции нет второго accordion. READY без
материальных изменений не получает крупные пустые блоки: вместо них выводится одна спокойная строка.

### Контекстная панель

Один компонент detail panel обслуживает четыре вида контента:

- основание summary/factor;
- формулу метрики;
- ограничение данных;
- подробность сотрудника, если она нужна до перехода в его карточку.

Требования к поведению:

- триггер — обычная кнопка с `aria-expanded` и связью с заголовком панели;
- при открытии фокус переходит на заголовок/кнопку закрытия;
- `Escape`, кнопка закрытия и клик по backdrop закрывают панель;
- после закрытия фокус возвращается на исходный триггер;
- фокус не уходит за пределы модальной панели, фон недоступен для screen reader;
- на mobile панель становится bottom sheet, но критичные данные доступны без hover;
- одновременно открыта только одна панель и хранится один `DetailContext`, а не набор boolean-state.

## Пакеты реализации

### P0. Baseline и защита от случайного расширения scope

Цель: получить воспроизводимую точку сравнения до правок.

Правки кода: отсутствуют.

Действия:

- [x] Зафиксировать только релевантный diff; не включать параллельные изменения пользователя.
- [x] Запустить текущие targeted backend/frontend тесты Weekly Review.
- [x] Запустить текущий local visual route `/insights` и просмотреть все три viewport.
- [x] Зафиксировать в рабочем комментарии, какой экран реально открылся: новый review или legacy
  fallback.
- [x] Зафиксировать исходные проблемы: высота страницы, первый экран, overflow, число карточек и
  повторяющиеся формулировки. Скриншоты с бизнес-данными не коммитить.

Контрольная точка P0:

- известны исходные падения тестов, если они есть;
- причина любого legacy fallback отделена от UI-переработки;
- подтверждено, что дальнейшая работа затрагивает `WeeklyReviewView`, а не переписывает legacy.

### P1. Исправить backend-семантику смен и сотрудников

Цель: исключить ложные ограничения и оценочные сравнения до переработки карточек.

Основные файлы:

- `WeeklyReviewPolicyV1.java`;
- `WeeklyReviewTeamEmployeeProjector.java`;
- `WeeklyReviewAssembler.java`;
- `WeeklyReviewResponse.java` — только если нужны дополнительные проверки существующей формы;
- соответствующие `WeeklyReview*Test.java`.

Изменения:

1. Развести две независимые оси достаточности:
   - sales sufficiency управляет выводами по продажам, допродажам и собственной динамике;
   - workload sufficiency управляет только сменами, часами и выручкой в час.
2. Пересчитать employee limitations:
   - нехватка продаж может ограничить конкретный sales-вывод;
   - нехватка смен не добавляет общий текст в `employee.limitations`;
   - workload-метрики получают собственное `UNAVAILABLE`/`LIMITED` и evidence.
3. Пересчитать `sortGroup`:
   - `ATTENTION` только при материальном отрицательном наблюдении на достаточной базе;
   - отсутствие смен само по себе не создаёт `LIMITED`;
   - положительная/стабильная группа определяется доступными sales-данными.
4. Пересчитать team state и roster:
   - workload-only gaps не увеличивают `limitedOrInsufficient`;
   - team block не становится `LIMITED` из-за смен;
   - report state вследствие этого не становится `PARTIAL`;
   - реальная неполнота attribution или sales-данных остаётся ограничением.
5. Пересчитать peer benchmark:
   - eligible employee имеет достаточные sales и workload-данные;
   - сравнивается `REVENUE_PER_HOUR`;
   - минимум группы остаётся равен действующей политике;
   - без подходящей группы peer comparison отсутствует;
   - чистая выручка не получает positive/negative peer effect.
6. Проверить генерацию employee action: ни одна workload-зависимая рекомендация не создаётся при
   неполных сменах.
7. Увеличить версии затронутых policy и подтвердить, что изменение содержимого создаёт новую
   snapshot revision без перезаписи прежней.

Обязательные backend-тесты:

- [x] продажи достаточны, смен нет: employee не `LIMITED`, team `READY`, report не `PARTIAL`;
- [x] в том же сценарии `workedHours` и `revenuePerHour` недоступны, остальные employee metrics
  остаются доступны;
- [x] missing shifts не создаёт attention/action и не меняет порядок сотрудников;
- [x] при достаточных сменах минимум у трёх сотрудников benchmark — медиана выручки в час;
- [x] при двух eligible сотрудниках peer comparison отсутствует;
- [x] старая чистая выручка не интерпретируется как эффективность;
- [x] реальная нехватка sales/attribution по-прежнему даёт адресное ограничение и корректный
  `PARTIAL`;
- [x] snapshot content hash/revision меняется, предыдущая snapshot остаётся неизменной.

Контрольная точка P1: матрица `sales sufficient/insufficient × workload sufficient/insufficient`
покрыта тестами, и workload-only случай не влияет на общий статус.

### P2. Упростить deterministic presentation contract

Цель: backend передаёт один непротиворечивый управленческий рассказ.

Изменения backend:

1. Вынести построение summary из перегруженного assembler в небольшой renderer/presenter либо
   чистый private collaborator с отдельными тестами.
2. Summary формулирует категориальный итог: лучше, слабее, разнонаправленно или без существенных
   изменений. Точные KPI остаются в четырёх result cards и не повторяются полным предложением.
3. Summary может назвать один primary factor короткой фразой, но его значения и explanation
   находятся только в «Что изменилось»/detail panel.
4. Factors остаются максимум тремя и сортируются по управленческому приоритету, а не по удобству
   рендера.
5. Root actions:
   - возникают только из отрицательного материального фактора;
   - сохраняют `target` и `check`;
   - первый элемент является primary action;
   - `metricCode` и evidence однозначно связывают действие с фактором;
   - при только положительной динамике искусственное действие не создаётся.
6. `PARTIAL` не добавляет одинаковую оговорку в каждый narrative item. Общая оговорка хранится в
   report/limitations, а локальная маркировка определяется `affectedBlockIds`/`affectedMetricCodes`.

AI compatibility gate:

- employee/workload поля не добавляются в AI input;
- compactor продолжает принимать только store-level summary, factors, actions и их evidence;
- renderer использует те же action IDs и проверяемый `check`;
- опубликованные prompt/schema остаются неизменными, если structural и semantic tests проходят;
- любое изменение selector vocabulary, обязательных полей или смысловых обещаний AI оформляется
  отдельной задачей и версией, а не включается скрыто в UI-коммит.

Обязательные тесты P2:

- [x] summary не дублирует точные значения четырёх KPI;
- [x] balanced/positive/negative/neutral состояния имеют один итог;
- [x] action соответствует ровно одному factor;
- [x] action target/check переживают deterministic и AI-enhanced путь;
- [x] положительный фактор не создаёт действие;
- [x] PARTIAL сохраняет доступное содержание и адресные ограничения;
- [x] AI compactor, renderer, structural/semantic validator и completion integration проходят без
  изменения опубликованных артефактов.

Контрольная точка P2: один и тот же fixture даёт один backend-owned summary и проверяемый primary
action; frontend больше не должен вычислять смысл по KPI.

### P3. Ввести frontend presentation model

Цель: вынести выбор и дедупликацию данных из React-разметки.

Планируемая структура:

```text
frontend/src/insights/weekly-review/
  WeeklyReviewContent.tsx
  ReviewHeader.tsx
  DecisionSummary.tsx
  ResultsGrid.tsx
  ChangesSection.tsx
  SalesStructureSection.tsx
  TeamExceptionsSection.tsx
  ReviewDetailPanel.tsx
  weeklyReviewViewModel.ts
  weekly-review-layout.css
  weekly-review-sections.css
  weekly-review-detail-panel.css
```

Текущий публичный `WeeklyReviewView.tsx` остаётся тонкой точкой загрузки/query-state и может
реэкспортировать внутренние компоненты, чтобы не менять route placement одним большим diff.

`weeklyReviewViewModel.ts` как набор чистых функций формирует:

- `summaryText` только из backend summary/AI enhancement;
- `primaryAction` и `secondaryActions` по backend priority/order;
- связь action → factor по контрактному инварианту, без index-based fallback;
- `visibleFactors` без отдельного дублирующего positive signal;
- `attentionEmployees` только из `ATTENTION`, максимум три;
- team empty/calm state без автоматического выбранного сотрудника;
- `limitationsByBlock` и `limitationsByMetric`;
- semantic evidence contexts для summary, factor, metric, employee и limitation;
- допустимость peer efficiency только для `REVENUE_PER_HOUR`;
- neutral contribution для `NET_REVENUE` без сравнительной оценки.

Из `WeeklyReviewView.tsx` удаляются:

- `deterministicSummaryLead`;
- локальный синтез summary из core metrics;
- отдельная крупная positive signal card;
- master–detail выбор первого сотрудника;
- повторяющиеся inline evidence `<details>`;
- UI-ветвления, дублирующие правила достаточности backend.

Обязательные unit-тесты presentation model:

- [x] primary action выбирается стабильно;
- [x] факторы и summary не дублируются самостоятельными карточками;
- [x] 0/1/3/4 attention employees превращаются в 0/1/3/3 карточки;
- [x] `LIMITED`, `POSITIVE` и `STABLE` не попадают в exception list;
- [x] workload-only limitation не создаёт page warning;
- [x] limitation правильно адресуется к блоку/метрике;
- [x] неизвестный evidence ref даёт безопасное отсутствие detail, а не падение;
- [x] старый `NET_REVENUE` peer payload не показывается как эффективность;
- [x] новый `REVENUE_PER_HOUR` peer payload показывается только при валидной базе.

Контрольная точка P3: React-компоненты получают готовую view model и не принимают бизнес-решения.

### P4. Перестроить интерфейс и progressive disclosure

Цель: реализовать согласованную иерархию без визуального шума.

#### Header и decision block

- [x] Сократить header до периода, свежести и только значимого состояния.
- [x] Удалить технические revision/provider/version поля из обычного UI.
- [x] Убрать фиксированную высоту summary.
- [x] Разместить primary action рядом с итогом на desktop и сразу после него на mobile.
- [x] Показывать в action: заголовок, «Ориентир: …», «Проверка: …», период следующей полной недели.
- [x] Secondary actions раскрывать одной кнопкой; не показывать пустой контейнер.

#### Результаты и изменения

- [x] Сохранить ровно четыре KPI и одинаковую визуальную структуру карточек.
- [x] Текущее значение — главный акцент, динамика — второй, предыдущее значение — detail.
- [x] Формулу чистой выручки перенести в detail panel.
- [x] Переименовать секцию в «Что изменилось».
- [x] Ограничить факторы тремя и не добавлять отдельный positive hero.
- [x] Для спокойного READY показывать одну компактную строку без большой пустой секции.

#### Структура и команда

- [x] Оставить структуру продаж свёрнутой по умолчанию.
- [x] Сохранить табличное сравнение текущей/предыдущей недели; не добавлять график.
- [x] Убрать горизонтальный employee selector.
- [x] Показать счётчик attention и до трёх exception cards.
- [x] В каждой exception card: имя, одна причина, собственная динамика, при наличии — проверяемое
  действие, ссылка `/employees/:employeeId` с сохранением текущего query scope.
- [x] Добавить ссылку «Все сотрудники» на `/employees`.
- [x] Если exception нет, показать одну командную сводку; не выбирать случайного сотрудника.
- [x] Не показывать незаполненные смены как недостаток сотрудника.

#### Ограничения и переходы

- [x] В `PARTIAL` показать одну верхнюю сводку: что доступно и сколько выводов ограничено.
- [x] У затронутого блока/метрики показать короткий локальный маркер, ведущий в detail panel.
- [x] Не повторять один limitation текст в нескольких карточках.
- [x] `resolution` превращать в ссылку только при существующем маршруте и достаточных правах.
- [x] Менеджеру для admin-only исправления показывать ответственного текстом без недоступной кнопки.
- [x] До появления фильтрованного drill-down для возвратов/структуры оставлять честную рекомендацию
  без декоративного перехода.

#### Detail panel и accessibility

- [x] Реализовать один `ReviewDetailPanel` с typed context.
- [x] Использовать существующие доступные modal/sheet-паттерны проекта как ориентир, не проводить
  несвязанный глобальный refactor диалогов.
- [x] Проверить focus trap, возврат фокуса, `Escape`, backdrop, scroll lock и screen-reader name.
- [x] Обеспечить touch target не менее 44 px, перенос длинных русских строк и reduced motion.
- [x] Не прятать важные данные только в tooltip.

Контрольная точка P4: менеджер отвечает на вопросы «что произошло?» и «что проверить?» до первой
длинной прокрутки, а detail panel не меняет высоту страницы.

### P5. Пересобрать тестовые сценарии и визуальную проверку

Цель: проверять реальную плотность, а не только удобный happy path.

Fixture matrix:

| Сценарий | Обязательное содержание |
|---|---|
| `ready-dense` | 3 фактора, 3 действия, 10 сотрудников, 3 `ATTENTION`, длинные названия |
| `ready-calm` | нет материальных факторов и действий, нет employee exceptions |
| `ready-missing-shifts` | sales-анализ доступен, time metrics скрыты, общий статус READY |
| `partial` | доступны KPI, ограничения затрагивают разные блоки/метрики |
| `blocked` | одно понятное объяснение и корректный следующий шаг |

Изменения тестов:

- `frontend/src/test/weeklyReviewFixture.ts` — базовые builders вместо одного монолитного объекта;
- `frontend/src/api/weeklyReviewContract.test.ts` — старый и новый peer metric, state invariants;
- `frontend/src/insights/WeeklyReviewView.test.tsx` — новая иерархия, panel, ссылки и отсутствие
  дублей;
- `frontend/e2e/visual-local.spec.ts` — сценарий передаётся fixture API явно, а не определяется
  production endpoint;
- визуальные маршруты остаются локальными и не содержат production credentials.

DOM/interaction assertions:

- [x] на первом экране есть итог, primary action и четыре KPI;
- [x] action показывает target/check;
- [x] отдельной positive summary card нет;
- [x] secondary actions и structure раскрываются с клавиатуры;
- [x] detail panel открывается из разных контекстов и возвращает фокус;
- [x] employee cards не больше трёх и ведут на существующие маршруты;
- [x] `PARTIAL` показывает доступные KPI и одну сводку ограничений;
- [x] missing shifts не показывает глобальное предупреждение;
- [x] `BLOCKED` не рендерит недостоверные READY-секции;
- [x] технические версии не видны пользователю.

Visual acceptance для каждого сценария:

- desktop, tablet и mobile;
- нет горизонтального overflow;
- первый экран не содержит пустого hero-пространства;
- основной action виден без поиска по странице;
- строки не обрезаются и не превращаются в мелкий шрифт;
- состояние drawer/bottom sheet проверено отдельным снимком;
- изображения просмотрены вручную, а не только успешно созданы.

Обязательная команда после каждой материальной frontend-правки запускается из `frontend/` только
локально с `VISUAL_ROUTES=/insights`. Артефакты из `frontend/visual-artifacts/` не коммитятся.

Контрольная точка P5: component/contract tests зелёные, каждый fixture просмотрен во всех viewport,
а обнаруженные визуальные проблемы исправлены и проверены повторно.

### P6. Синхронизировать документацию и выполнить полный verification

Документы обновляются в том же change set, что и поведение:

- `docs/current/ai/weekly-review.md` — новая presentation-семантика, factor/action invariant,
  состояния и роль AI enhancement;
- `docs/current/product/employees-and-rating.md` — разница между вкладом и эффективностью,
  optional shifts и eligibility benchmark;
- `docs/current/frontend/README.md` либо существующий подходящий frontend contract — порядок
  блоков, detail panel, маршруты и visual scenarios;
- OpenAPI/current contract artifacts — только если публичная форма реально изменилась;
- `docs/current/project-state.md` — только после отдельной sanitized runtime verification;
- новый production evidence под `docs/history/` — только после фактического выпуска.

Проверки реализации:

```text
./gradlew :backend:test --tests 'com.storeanalytics.interpretation.review.*WeeklyReview*'
./gradlew :backend:check

cd frontend
npm run check
VISUAL_ROUTES=/insights npm run visual:local

cd ..
python3 -m unittest scripts/tests/test_documentation_check.py
python3 scripts/check-documentation.py --strict
git diff --check
```

Если wildcard Gradle filter не поддержит выбранные классы, targeted suite запускается явным
перечнем классов, после чего всё равно выполняется `:backend:check`.

Контрольная точка P6: код, OpenAPI при необходимости, current-документы и тестовые ожидания
описывают одно и то же поведение; working-документ не выдаётся за runtime truth.

### P6.1. Калибровка интерфейса после пользовательского review

Цель: убрать обнаруженные после полного визуального прогона композиционные и смысловые дубли до
production rollout. Это не косметический polish: пакет меняет порядок чтения, видимую плотность и
формулировку управленческого действия. P7 заблокирован до контрольной точки P6.1.

#### P6.1-A. Зафиксировать владельца каждого сообщения

Основные файлы:

- `frontend/src/insights/weekly-review/weeklyReviewViewModel.ts`;
- `frontend/src/insights/weekly-review/WeeklyReviewContent.tsx`;
- `backend/src/main/java/com/storeanalytics/interpretation/review/WeeklyReviewAssembler.java`;
- AI renderer/validator и относящиеся к ним тесты — только для согласования формулировок действий.

Изменения:

1. Сохранить backend-owned summary и запретить frontend создавать альтернативный итог.
2. Использовать уже вычисленный `primaryActionFactor` для разделения факторов:
   - связанный с primary action фактор не рендерится второй равноправной карточкой в
     `Что изменилось`;
   - его числа и evidence остаются доступны из действия и detail panel;
   - остальные материальные факторы сохраняются в `Что изменилось`;
   - если исходные факторы были, но все перенесены в decision block, секция скрывается, а не пишет
     ложное `Существенных изменений нет`;
   - если исходных факторов действительно нет, остаётся компактное спокойное состояние.
3. Заменить evidence-подписи, повторяющие заголовок, на назначения: `Показать расчёт` или
   `Почему это важно`. Полный заголовок остаётся в доступном имени detail panel.
4. Перепроверить deterministic action templates. Заголовок описывает операцию менеджера, а не
   пересказывает фактор; для возвратов он указывает на проверку чеков/причин, без неясной
   формулировки `исходные продажи`.
5. Синхронизировать deterministic и AI-enhanced пути. Если меняется persisted wording или policy,
   увеличить соответствующую policy revision и проверить создание новой immutable snapshot
   revision; старые snapshots не переписывать.

Тесты:

- один связанный factor/action даёт один видимый смысловой блок и доступное evidence;
- несвязанные secondary factors не исчезают;
- неоднозначная связь не приводит к молчаливому скрытию факторов;
- отсутствие исходных факторов и отсутствие только secondary factors имеют разные presentation
  состояния;
- deterministic и AI-enhanced action сохраняют `metricCode`, target, check и evidence refs;
- в видимом тексте нет повторяющейся связки `Почему: <полный заголовок>`.

#### P6.1-B. Выровнять decision block

Основные файлы:

- `frontend/src/insights/weekly-review/weekly-review.css`;
- `frontend/src/insights/weekly-review/WeeklyReviewContent.tsx`;
- `frontend/e2e/visual-local.spec.ts`.

Изменения:

1. Удалить desktop-правило `align-items: start`, из-за которого summary и action имеют независимую
   высоту.
2. Растягивать обе карточки до высоты текущей grid-строки без `height`/`min-height` в пикселях.
3. Внутреннюю структуру карточек сделать grid/flex-колонкой и выровнять нижние evidence-действия,
   не добавляя заполняющих показателей или декоративного текста.
4. На breakpoint `<= 980px` сохранить последовательность `итог → действие` и естественную высоту;
   одинаковая высота для сложенных карточек не требуется.
5. Проверить длинный итог, длинное действие, primary action без secondary actions, спокойный READY и
   PARTIAL без primary action. Контент не обрезается, а более высокая карточка определяет высоту
   desktop-строки.

Автоматизированная visual assertion на desktop сравнивает высоту двух карточек с допуском не более
одного CSS-пикселя. На tablet/mobile тест проверяет порядок и отсутствие искусственной пустой
высоты, а не равенство карточек.

#### P6.1-C. Упростить секцию команды

Основные файлы:

- `frontend/src/insights/weekly-review/WeeklyReviewContent.tsx`;
- `frontend/src/insights/weekly-review/weekly-review.css`;
- `frontend/src/insights/weekly-review/weeklyReviewViewModel.ts` при необходимости отдельного
  presentation-поля.

Изменения:

1. Удалить серую `.weekly-review-team-summary` при наличии employee exceptions.
2. Заголовок показывает одно законченное сообщение: `N из M требуют проверки`, где `M` —
   `activeAssignedWithActivity`, а `N` — backend-owned `attentionEmployeeCount`.
3. Сразу после заголовка показывать до трёх карточек. При нулевом `N` выводить одну спокойную строку,
   не оставляя пустую сетку и не создавая большую success-плашку.
4. Убрать повторяющийся общий статус `Требует проверки` из каждой карточки. Причина карточки уже
   задаётся конкретным attention title.
5. Переименовать действия карточки:
   - `Подробнее: <ФИО>` → `Почему сотрудник в списке`;
   - `Карточка сотрудника` → `Открыть сотрудника`.
   Доступные имена содержат ФИО и назначение действия, хотя видимый текст остаётся коротким.
6. Если сотрудник показан по надёжному sales-сигналу, а `REVENUE_PER_HOUR` недоступна из-за
   workload, вывести одну нейтральную подпись: `Часть смен не заполнена — оценка по часам
   недоступна`. Подпись не меняет `sortGroup`, attention count, team/report state и не создаёт
   действие заполнить смены.
7. На mobile по умолчанию показать первую карточку и кнопку `Ещё N сотрудников`. Оставшиеся
   карточки раскрываются в том же DOM, без копий и без горизонтального carousel. На desktop все три
   карточки остаются видимыми.

Тесты:

- `0/1/3/>3 ATTENTION` дают корректный заголовок и максимум три карточки;
- отдельной серой summary-плашки и повторяющегося статуса нет;
- обе кнопки имеют короткий видимый текст, различимые accessible names и сохраняют query scope;
- missing shifts не создают employee exception, но адресно объясняют отсутствие time-метрики у
  сотрудника, уже показанного по независимому sales-сигналу;
- mobile reveal управляется кнопкой с корректным `aria-expanded`, работает с клавиатуры и не
  дублирует карточки для screen reader.

#### P6.1-D. Сократить плотный mobile-сценарий

1. В `Что изменилось` на mobile показывать первый secondary factor и кнопку
   `Ещё N изменений`; на desktop сохранять до трёх карточек.
2. Не скрывать primary action, KPI, PARTIAL limitations или единственный employee exception.
3. Не вкладывать новый accordion внутрь `Структуры продаж` или detail panel.
4. После раскрытия сохранять обычный документный порядок и не перемещать фокус без действия
   пользователя.
5. Текст кнопки и число пересчитывать по фактически скрытым элементам, а не по исходной длине
   backend-массива.

#### P6.1-E. Полная матрица проверки

Порядок проверки после реализации:

1. Targeted frontend unit/component tests для view model, content, accessibility и responsive
   disclosure.
2. Targeted backend tests для action wording, AI semantic validation и policy revision, если
   backend изменён.
3. `npm run check` и полный `./gradlew :backend:check`, если изменён persisted backend output.
4. Обязательный локальный `npm run visual:local` для всех пяти fixtures:
   `ready-dense`, `ready-calm`, `ready-missing-shifts`, `partial`, `blocked` — desktop, tablet и
   mobile.
5. Отдельные снимки раскрытых `Ещё проверки`, `Ещё изменения`, `Ещё сотрудники` и detail panel.
6. Ручное сравнение не только overflow, но и маршрута первых 10 секунд: период → итог → одно действие
   → четыре KPI.
7. Повторная local live visual-проверка существующих обезличенных `PARTIAL` snapshots без нового
   обращения к LiveSklad. Реальные данные и screenshots не сохраняются в документации.
8. Независимый code review после зелёных тестов и отдельный UI review после просмотра всех viewport;
   замечания исправляются до обновления статуса пакета.
9. Обновление current-документов только по фактически реализованному поведению, затем documentation
   unit/strict checks и `git diff --check`.

Контрольная точка P6.1:

- decision cards равны по высоте на desktop и естественны на tablet/mobile;
- итог, primary action и `Что изменилось` не показывают один фактор тремя самостоятельными блоками;
- действие сформулировано как понятная операция менеджера;
- в `Команде` нет серой сводной плашки, повторяющихся имён и общего статуса на каждой карточке;
- неполные смены адресно ограничивают только time-оценку и не выглядят как слабая работа сотрудника;
- плотный mobile-сценарий сокращён progressive disclosure без скрытия критичного;
- полная fixture-матрица, local live `PARTIAL`, code review, UI review и documentation gates зелёные.

### P7. Совместимый выпуск и удаление legacy

Цель: превратить локально проверенную P6.1-реализацию в обозримый release candidate, подтвердить
реальные состояния и выполнить совместимый выпуск без смешивания UI-релиза, включения платного AI и
удаления legacy.

#### Текущий verdict до начала P7

**Production write: NO-GO.** Известные незакрытые гейты:

- свежий реальный `READY` и реально сохранённый snapshot policy `weekly-snapshot-v12` ещё не
  подтверждены;
- release candidate не выделен из общего изменённого worktree;
- обычный documentation strict видит зарегистрированные, но ещё не добавленные в Git документы;
- [`production-deployment.md`](../runbooks/production-deployment.md) остаётся `draft` до staging и
  production-read-only evidence конкретного релиза;
- восстановление свежего backup в изолированный target с измеренными RPO/RTO не подтверждено;
- платный AI-canary имеет отдельный `NO-GO` в
  [`weekly-review-ai.md`](../runbooks/weekly-review-ai.md): до enqueue отсутствует read-only preview
  compacted input/hash, privacy verdict, cost boundary и проверка конфликтующего job.

AI не является условием работоспособности детерминированного Weekly Review. Поэтому P7 имеет два
явных пути:

1. **Deterministic-first — рекомендуемый:** выпустить интерфейс и deterministic backend с AI
   generation/planner выключенными; AI включать отдельным решением после P7-C.
2. **AI-enabled:** не переходить к production, пока полностью не закрыты P7-C, staging paid canary
   и отдельное подтверждение стоимости.

Выбранный путь фиксируется до сборки release candidate. Нельзя молча считать выключенный AI
проверенным AI-сценарием или задерживать безопасный deterministic fallback из-за необязательного
provider-layer.

#### P7-A. Выделить точную границу релиза

1. Выбрать exact base commit/tag и создать отдельный release branch/worktree. Текущий грязный
   worktree не использовать как источник production build.
2. Составить manifest файлов Change A–G: frontend compatibility/UI, backend weekly-review policy,
   тесты, OpenAPI при фактическом contract change и относящиеся к ним документы.
3. Для каждого изменённого файла указать `include`, `exclude` или `dependency`. Не включать
   параллельные auth, payroll, LiveSklad recovery и migration changes только потому, что они уже
   находятся в общем worktree.
4. Отдельно определить migration range кандидата. P6.1 сама не требует переписывания persisted
   snapshots или новой таблицы; случайное включение несвязанной migration меняет риск релиза и
   возвращает пакет на backend/operations review.
5. Проверить отсутствие credentials, business screenshots, provider payloads, временных evidence,
   build outputs и local database artifacts.
6. Добавить зарегистрированные документы в release branch и запустить ordinary strict против
   реального index, без временной имитации tracked state.

Контрольная точка P7-A:

- exact base и manifest утверждены;
- `git status` release worktree содержит только ожидаемый пакет;
- каждый diff относится к Weekly Review или явно названной обязательной зависимости;
- обычный documentation strict проходит без оговорки про untracked files.

Статус: **PASS локально**. Фактическая граница описана в
[`weekly-review-release-manifest.md`](weekly-review-release-manifest.md). Полная копия общего visual
harness была отклонена из-за смешанных plan/auth hunks и заменена узким `/insights`-патчем;
`QueryState` сокращён до используемого Weekly Review stale-state. В index находятся 41 ожидаемый
файл, migration и OpenAPI diff отсутствуют. Documentation suite прошёл `25/25`, ordinary strict —
398 inventory rows с нулём предупреждений, targeted frontend — `50/50`, targeted backend на Java 21
— `74/74`, local fixture visual — `3/3` для desktop/tablet/mobile. P7-B остаётся отдельной
воспроизводимой полной сборкой кандидата.

После P7-A проводится независимый code review границы change set. Review проверяет не только код,
но и отсутствие посторонних изменений; найденное смешение исправляется до тестов кандидата.

#### P7-B. Собрать воспроизводимый release candidate

1. На чистом release worktree выполнить установку по lock-файлам и полный набор проверок:

   ```text
   cd frontend
   npm ci
   npm run check
   VISUAL_USE_FIXTURES=true \
   VISUAL_ROUTES='/insights?reviewScenario=ready-dense,/insights?reviewScenario=ready-calm,/insights?reviewScenario=ready-missing-shifts,/insights?reviewScenario=partial,/insights?reviewScenario=blocked' \
   npm run visual:local

   cd ..
   ./gradlew :backend:check
   python3 -m unittest scripts/tests/test_documentation_check.py
   python3 scripts/check-documentation.py --strict
   bash scripts/tests/deploy-release-safety-test.sh
   bash scripts/tests/weekly-review-ai-release-safety-test.sh
   git diff --check
   ```

2. Просмотреть desktop/tablet/mobile артефакты, включая раскрытые secondary actions, factors,
   employees, structure и detail panel. Успешный Playwright exit без ручного просмотра недостаточен.
3. Зафиксировать sanitized totals, Node/Java versions и lock hashes; raw logs с business data не
   сохранять.
4. CI собирает immutable backend/web images только из reviewed commit. До preflight фиксируются
   candidate commit, image digests и OCI revision; mutable tag не является release target.
5. Сверить packaged schema range и фактическую migration boundary с manifest P7-A.

Контрольная точка P7-B: один и тот же reviewed commit воспроизводимо проходит frontend, backend,
documentation, supply-chain и release-safety gates; его immutable images однозначно связаны с
commit.

Статус: **PASS локально**. На чистом candidate worktree получены следующие результаты:

- `npm ci`, generated contract check, lint, `42` test files / `198` tests и production build —
  успешно; полный и production-only npm audit показывают `0` известных уязвимостей;
- полный `:backend:check` на Java 21 — `1098` tests без failures/errors/skips; OpenAPI generation и
  compatibility, Checkstyle, operator/release-safety и Gradle supply-chain verification прошли;
- fixture-матрица пяти состояний прошла `15/15` на desktop/tablet/mobile. Все основные и раскрытые
  состояния просмотрены вручную; переполнения, обрезки, нарушения композиции и повторения смыслов
  не обнаружены, desktop decision cards отличаются по высоте не более чем на один CSS-пиксель;
- documentation unit suite прошёл `25/25`, ordinary strict проверил `398` inventory rows без
  предупреждений; оба release-safety набора и `git diff --check` прошли;
- локальные backend/web images собраны с immutable candidate-тегами; обе OCI revision labels
  совпадают с reviewed commit. Images не публиковались и не запускались против внешних сред;
- migration diff относительно production-базы пуст. В backend JAR присутствуют все `49` исходных
  migration-файлов, без дополнительных migration; OpenAPI diff отсутствует;
- отдельный scope/security review не нашёл secrets, business screenshots, local data, build
  outputs или изменений auth, payroll, plan/shifts и LiveSklad integration в candidate diff.

После P7-B выполняются отдельные code review backend/frontend и security/privacy review артефактов.
При изменении кода после review весь затронутый gate запускается повторно.

#### P7-C. Подтвердить реальные `READY`, `PARTIAL` и актуальную snapshot policy

Проверка выполняется сначала только в local/test. Новый read-only scope LiveSklad требует отдельного
явного разрешения по магазинам и датам; прежнее разрешение на `2026-08-31..2026-09-13` не
расширяется автоматически.

1. Получить непрерывное покрытие обеих сравниваемых полных недель и прочитать sanitized coverage до
   генерации snapshot.
2. Через штатный локальный authenticated admin API создать immutable snapshot для каждого
   разрешённого магазина. Не писать в production/staging и не изменять source LiveSklad.
3. Подтвердить в сохранённом payload:
   - `snapshotPolicy=weekly-snapshot-v13`;
   - ожидаемый `reportState`, coverage и metric states;
   - ровно одну связь primary action с factor либо сохранение всех факторов при неоднозначности;
   - действие `Проверить чеки и причины возвратов` при соответствующем реальном сигнале;
   - отсутствие workload-only влияния на report/team/attention state;
   - корректную immutable revision: старый payload не перезаписан.
4. Получить минимум один естественный реальный `READY` и один реальный `PARTIAL`. Не изменять
   business facts ради искусственного достижения состояния.
5. Выполнить local live visual desktop/tablet/mobile для обоих состояний и отдельно проверить
   сотрудника с незаполненными сменами, если такой случай существует в разрешённой выборке.
6. Evidence сохраняет только versions, states, counts, hashes и limitation codes. Финансовые
   значения, имена, credentials и screenshots с business data не сохраняются.

Stop-условия P7-C:

- нет полного coverage обеих недель;
- `READY` удаётся получить только fixture-ом;
- v13 создаёт противоречивый summary/action/factor или меняет старую revision;
- неполные смены создают глобальный warning, employee attention или негативную оценку сами по себе;
- frontend скрывает schema/transport error legacy fallback-ом.

Контрольная точка P7-C: реальные `READY` и `PARTIAL` подтверждают тот же контракт, что fixtures и
tests; новый v13 snapshot прочитан актуальным frontend, а ограничения evidence явно зафиксированы.

Статус: **STOP после разрешённого локального прогона**. Непрерывное покрытие обеих недель уже
находилось в изолированной локальной БД, поэтому дополнительного обращения к LiveSklad не
потребовалось. Для обоих разрешённых магазинов штатный authenticated admin API создал revision 3 с
policy `weekly-snapshot-v12`; предыдущие v10/v11 revisions и их content hashes сохранены, цепочки
supersedes корректны, недействительных связей нет. Оба новых отчёта — естественный `PARTIAL`: у
каждого `2` complete и `1` partial coverage source, `1` ready и `3` limited core metrics. Неполные
смены не стали самостоятельным page-level warning или workload benchmark.

Естественного реального `READY` в разрешённой выборке нет, поэтому fixture evidence не подменяет
runtime evidence и контрольная точка не пройдена. Live visual нового v12 также не объявляется
пройденным: локальный Windows web/API доступны только через loopback, WSL browser не достигает этот
loopback, а загрузка отдельного browser runtime была заблокирована TLS-сбоями Docker Desktop для
MCR и двух Alpine mirrors. Ранее пройденные `15/15` fixture captures и `6/6` live v11 остаются
полезной регрессией, но не заменяют v12 live-проверку.

Отдельное ограничение стенда: candidate backend ожидает schema `V48`, а повторно используемая
локальная БД уже имеет `V49`, поэтому защитный readiness endpoint возвращает `DOWN`. Функциональный
authenticated API и транзакционная генерация доступны, однако такой стенд не считается
release-equivalent. Схема не откатывалась, readiness не ослаблялся, production/staging не
затрагивались.

Повторный прогон 2026-09-15 после продуктового уточнения выполнен на изолированной V48-копии той же
локальной выборки. Штатный authenticated API создал revision 4 обоих магазинов с версиями
`weekly-metrics-v7` / `weekly-snapshot-v13` / `weekly-quality-v7`; прежние revisions не
переписывались. В обоих payload все четыре core KPI имеют `READY`, required coverage продаж и
возвратов — `COMPLETE`, а нулевая себестоимость и отсутствие исходной связи возврата отсутствуют в
limitations. Оба отчёта остаются естественными `PARTIAL`: один из-за локальной недостаточности
employee sales, другой из-за `PRODUCTS_UNCLASSIFIED`. Эти факты не изменялись ради получения
искусственного `READY`, поэтому release-gate сохраняет честный `STOP`, а реализация согласованных
правил считается проверенной.

После P7-C проводится новый независимый UI review по обезличенному live-наблюдению. При любом
изменении presentation поведение возвращается в P6.1 и проходит полную fixture-матрицу повторно.

#### P7-D. Закрыть или явно отложить AI-enabled путь

Для включения AI нужно сначала устранить NO-GO из AI runbook:

1. Добавить authenticated read-only preflight exact snapshot без enqueue. Ответ должен содержать
   canonical input hash, privacy verdict, active version pair, provider/model identity, верхнюю
   границу token/cost, existing enrichment и conflicting job state, но не raw compacted input,
   employee scope, PII или credentials.
2. Покрыть preflight authorization, tenant isolation, отсутствие записи, hash stability, privacy
   rejection, budget boundary и concurrency тестами.
3. Обновить OpenAPI/current contract и [`weekly-review-ai.md`](../runbooks/weekly-review-ai.md),
   затем повторить полный backend/documentation/security gate.
4. Выполнить staging paid canary только после отдельного approval exact snapshot/hash, max calls и
   cost cap.
5. Проверить `SUCCEEDED`, пустые validation violations, стабильный enrichment hash, неизменность
   deterministic facts/actions/evidence и отсутствие нового weekly Telegram event.
6. Проверить fallback при timeout, provider error и invalid selection: deterministic экран остаётся
   доступным, а AI label не обещает несуществующее обогащение.

Контрольная точка P7-D имеет два допустимых результата:

- `DEFERRED`: deterministic-first release продолжается с AI planner/generation выключенными и
  documented flag verdict;
- `VERIFIED`: preflight, staging paid canary, privacy/cost approval и fallback прошли, после чего
  AI может получить отдельное production approval.

`DEFERRED` не разрешает вручную вызвать production AI endpoint. Immutable enrichment не удаляется
как способ отката; исправление требует новой версии либо отключения AI-layer.

Результат 2026-09-15: `DEFERRED`. AI release-safety, targeted backend AI tests, offline shadow-plan
и локальный eval прошли; production defaults для planner/generation/worker остаются выключенными.
Платный provider-вызов не выполнялся, privacy/cost approval и staging canary не заявляются.

Продолжение 2026-09-15: в отдельном candidate change set реализованы admin-only network-free
preflight exact snapshot и approval-bound enqueue. Preflight не раскрывает compacted input,
employee scope, полный model URI или credentials; API runtime не получает provider key. Локально
проверены стабильность hashes и отсутствие enqueue, budget/privacy rejection, авторизация,
PostgreSQL concurrency и OpenAPI compatibility. Результат P7-D остаётся `DEFERRED`: candidate ещё
не выпущен, staging paid canary и production read-only audit не завершены, отдельного exact
cost/privacy approval на provider-вызов нет.

#### P7-E. Провести менеджерскую приёмку

На release candidate назначенный менеджер без подсказок выполняет пять задач:

1. За первые 10 секунд формулирует, как завершилась неделя и что проверить первым.
2. Открывает основание главного вывода и объясняет связь с действием.
3. Находит причину появления сотрудника и переходит в его карточку.
4. Верно трактует незаполненные смены как отсутствие time-оценки, а не как плохую работу.
5. Повторяет маршрут на mobile, раскрывая дополнительные изменения/сотрудников только при
   необходимости.

Acceptance фиксирует только результат задачи, затруднение и viewport — без имён и business values.
Провал понимания итога, действия, ограничения данных или статуса сотрудника блокирует rollout.
Косметические пожелания, не влияющие на маршрут, выносятся отдельно и не возвращают визуальный шум
перед релизом.

Контрольная точка P7-E: менеджер проходит маршрут «итог → действие → основание → сотрудник» за
одну–три минуты и не делает ни одной критической ошибочной интерпретации.

Результат 2026-09-15: `PASS_WITH_LIMITS`. Продуктовый владелец оценил интерфейс с позиции менеджера,
согласовал исправления decision cards и секции команды и принял повторный визуальный результат.
Пять fixture-состояний повторно прошли desktop/tablet/mobile (`15/15`) и были просмотрены вручную:
маршрут, progressive disclosure и нейтральная трактовка незаполненных смен не противоречат плану.
Отдельная таймированная сессия без подсказок не проводилась, поэтому она остаётся post-pilot
исследованием, а не выдуманным evidence этого gate.

#### P7-F. Выполнить staging/release-equivalent rehearsal

1. Развернуть exact candidate images в staging по production deploy path. Если staging отсутствует,
   создать изолированный release-equivalent контур; локальный visual test не заменяет deployment
   rehearsal.
2. Проверить exact schema source/target, Flyway, ACL, quiesce API/worker, health, HTTPS headers,
   закрытый Prometheus, queues и read path Weekly Review.
3. Репетировать совместимый двухшаговый порядок:
   - этап 1: новый web с текущим совместимым backend/worker;
   - этап 2: новый backend/worker с уже выпущенным web.
   Если штатный deploy bundle не может безопасно выполнить такой порядок, сначала изменить и
   проверить release procedure; не заменять её ручными Compose-командами.
4. Проверить старый и новый snapshot, `READY`, `PARTIAL`, `BLOCKED`, AI-disabled fallback и legacy
   fallback instrumentation.
5. Выполнить application rollback rehearsal на совместимой schema pair. Migration не откатывать;
   при несовместимости предыдущего runtime проверить reviewed forward-fix path.
6. Восстановить свежий encrypted backup в новый изолированный target, проверить checksum, Flyway,
   constraints и согласованные агрегаты, измерить RPO/RTO. Production target не использовать.

Контрольная точка P7-F: deployment, rollback/forward-fix и isolated restore воспроизводимо прошли
на release-equivalent контуре, а runbooks получили staging evidence. Без restore evidence и exact
schema compatibility production write остаётся `NO-GO`.

Результат 2026-09-15: `STOP` после ограниченной локальной репетиции. На отдельной Docker-сети
candidate migration успешно подготовила чистую ожидаемую schema boundary; API и worker получили
`UP`, web отдал HTML и проксировал `readyz`. Локальные backend/web OCI revision совпали с reviewed
runtime commit. Техническая backup/restore цепочка также прошла: custom dump был зашифрован и
расшифрован с неизменным SHA-256, восстановлен в новый PostgreSQL target, а Flyway history, число
таблиц и выбранные нулевые агрегаты совпали с источником; API и worker поднялись поверх restore.

Это не полный P7-F: источник был пустым локальным rehearsal target, а не свежим production backup;
RPO и полный RTO не подтверждены. Staging отсутствует, production deploy bundle не запускался,
ACL/HTTPS/закрытый Prometheus и реальные queues не проверены. Точный предыдущий production image
не был доступен локально, а registry pull завершился сетевым timeout, поэтому совместимый
двухшаговый rollout и application rollback на exact previous runtime не репетировались. Статический
deploy release-safety test прошёл и подтвердил fail-closed compatibility boundary, но не заменяет
runtime rehearsal.

#### P7-G. Провести production read-only preflight

Этот пакет не изменяет production. Reviewer фиксирует sanitized:

- exact host/database/release target и отсутствие расхождения с change record;
- reviewed commit, backend/web digests, OCI revisions и release-env hash;
- live Flyway version, отсутствие failed migration и совместимость source/target range;
- свежий backup checkpoint и ссылку на успешный isolated restore P7-F;
- health API/worker/web, critical queues и отсутствие конфликтующей migration/recovery/backfill;
- текущие Weekly Review flags, planner/worker/AI state и legacy fallback baseline;
- назначенных observer, rollback/forward-fix owner и согласованное окно наблюдения.

Любое неизвестное значение, `MIGRATION_IN_PROGRESS`, mutable image reference, недоступный backup,
конфликтующий job или несовпадающий target означает stop. Read-only preflight не является
разрешением deploy.

Контрольная точка P7-G: operations и security reviewers подписали exact release plan; пользователю
показаны target, commit/digests, schema range, влияние и rollback boundary без секретов.

Результат 2026-09-15: `STOP`, production не запрашивался и не изменялся. Кандидат существует только
как локальные images и не опубликован по immutable registry coordinates; exact production host,
release-env hash, live Flyway/health/queue state и свежий backup checkpoint в доступном контексте
отсутствуют. Дополнительно P7-C не содержит естественного real-data `READY`, а P7-F не подтвердил
exact previous-runtime rollback. Эти неизвестные прямо входят в stop-критерии, поэтому старое
описание project-state не переиспользуется как свежий read-only preflight.

#### P7-H. Выполнить отдельно авторизованный совместимый production rollout

Каждый mutating этап требует нового явного подтверждения exact release plan. Общая фраза
«приступай к P7» не является разрешением production write.

1. По [`production-deployment.md`](../runbooks/production-deployment.md) выполнить этап совместимого
   frontend. Старый backend продолжает обслуживать новый parser; сразу проверить health, schema/
   transport errors и основные routes.
2. Выдержать согласованное окно наблюдения. При UI-дефекте откатить только web на previous immutable
   digest; snapshots не удалять.
3. Отдельным approved release выполнить backend/worker policy changes с тем же web. Flyway и ACL
   выполняются только штатным deploy script.
4. Проверить `/livez`, `/readyz`, exact digests, live schema, queues и отсутствие HTTP `5xx`.
5. Поскольку production snapshot planner сейчас может быть выключен, не предполагать автоматическое
   создание v13. Для одного exact store/week выполнить либо подтверждённый planner path, либо
   отдельно разрешённый authenticated admin generation endpoint; затем read-only проверить snapshot
   и UI. Только после успешного canary переходить ко второму магазину.
6. AI оставить выключенным при результате P7-D `DEFERRED`. При `VERIFIED` его production canary всё
   равно получает отдельное approval exact snapshot/hash/cost.
7. Legacy fallback не удалять и не использовать для сокрытия contract error.

Контрольная точка P7-H: новый `WeeklyReviewView` читает старые и новые revisions, v13 canary
непротиворечив, deterministic путь работает без AI, а rollback остаётся доступным в рамках live
schema compatibility.

Статус 2026-09-15: `NOT STARTED`. Перед первым production write нужен закрытый P7-G и новое точное
подтверждение показанного release plan с target, immutable coordinates, schema/backup boundary,
окном влияния и ответственными. Предыдущее общее разрешение продолжить P7 не подменяет эту
контрольную точку.

#### P7-I. Наблюдение, evidence и решение о legacy

1. В согласованное окно наблюдать schema/transport error rate, frontend fallback reason, API
   latency/error, snapshot/AI job states, queue health и report-state distribution без business
   values/PII.
2. Если fallback сейчас не измеряется, добавить безопасный counter или structured reason code до
   решения об удалении legacy; raw payload не логировать.
3. Создать immutable sanitized release evidence под `docs/history/releases/YYYY/MM/` с фактически
   наблюдёнными commit/digests/schema/flags/health/verdict. Динамические значения не копировать в
   другие документы.
4. Только после evidence обновить [`project-state.md`](../current/project-state.md) и сохранить его
   предыдущую версию в history по documentation policy.
5. Frontend legacy fallback удалять отдельным Change H после подтверждённого периода без fallback.
6. Backend legacy schema/Telegram path удалять только после инвентаризации всех потребителей,
   отдельного migration/compatibility review и собственной rollback strategy.

Контрольная точка P7-I: release evidence обезличено и подтверждает реальное состояние; legacy
cleanup имеет отдельную задачу и не смешан с выпуском.

Статус 2026-09-15: `NOT STARTED`. Наблюдение и обновление project-state возможны только после
фактического P7-H; локальная репетиция не выдаётся за production runtime evidence.

#### Stop/go и rollback matrix

| Ситуация | Решение |
|---|---|
| Нет реального `READY` или актуального v13 evidence | `STOP`; fixture не заменяет runtime |
| AI preflight/cost/privacy не закрыты | `GO` только deterministic-first с AI disabled |
| Frontend contract/UI defect до backend rollout | Откатить web на previous digest |
| Backend defect, live schema совместима с previous runtime | Штатный application rollback |
| Migration failed/partial или schema несовместима | `STOP`; reviewed forward-fix или isolated restore, без blind retry |
| Ошибка только AI-layer | Отключить AI usage; deterministic report остаётся |
| Ошибка текста immutable snapshot | Не редактировать payload; новая policy/revision |
| Legacy fallback скрывает schema/transport error | `STOP`; диагностировать контракт до продолжения |
| Target/digest/schema/backup расходятся с change record | `STOP`; production write запрещён |

Финальная контрольная точка P7: production показывает согласованный `WeeklyReviewView`, реальный
v13 snapshot и deterministic fallback подтверждены, optional AI имеет честный `DEFERRED` или
`VERIFIED` verdict, release evidence обезличено, а legacy остаётся до отдельного доказанного
cleanup.

## Рекомендуемая последовательность change sets

Чтобы review оставался управляемым, реализацию не объединять в один большой diff:

1. **Change A — compatibility and tests:** frontend parser двух peer-метрик, builders/scenarios и
   регрессионные тесты missing shifts без изменения внешнего вида.
2. **Change B — backend semantics:** разделение sales/workload, новый peer benchmark, state/action
   invariants и policy revision.
3. **Change C — presentation model:** backend-owned summary, relation invariants и чистый frontend
   view model.
4. **Change D — information architecture:** новые секции, primary action, exception-only employees
   и ограничения.
5. **Change E — detail panel and responsive UI:** drawer/bottom sheet, accessibility, CSS cleanup и
   visual matrix.
6. **Change F — documentation and rollout preparation:** current contracts, полный verification и
   операционный checklist.
7. **Change G — post-review UI calibration:** дедупликация primary factor, operational wording,
   равная desktop-композиция, упрощённая команда и mobile progressive disclosure; повторный полный
   verification и обновление current-контрактов.
8. **Change H — legacy cleanup:** только после отдельного production evidence.

Каждый change set должен быть самодостаточным, проходить относящиеся к нему тесты и не оставлять
frontend в состоянии, которое не понимает возможный backend payload.

## Риски и меры контроля

| Риск | Контроль |
|---|---|
| Backend начнёт отдавать новую peer-метрику раньше frontend | Compatibility change выпускается первым |
| Missing shifts продолжит проникать в report state через косвенный team state | Матрица sufficiency и отдельный assembler regression test |
| Summary снова начнёт дублироваться в frontend | Удаление синтеза и unit-тест view model на источник текста |
| Связь factor/action окажется неоднозначной | Contract test; при провале — явное поле API, без эвристики |
| Drawer снизит доступность | Focus management, keyboard tests и ручная screen-reader проверка |
| Упрощение скроет важное ограничение | Одна global summary плюс адресные markers и detail contexts |
| Employee exceptions превратятся в скрытый рейтинг | Только достаточная база, собственная динамика и осторожный peer benchmark |
| Visual test пройдёт на нереалистичном fixture | Обязательная пятисценарная матрица и длинные данные |
| Простое растягивание decision cards создаст большое пустое поле | Естественная grid-высота, нижнее выравнивание действий и сценарии с длинным/коротким контентом |
| Дедупликация primary factor ошибочно сообщит, что изменений нет | Различать отсутствие исходных факторов и отсутствие только secondary factors в view model |
| Старые immutable snapshots сохранят неясный action wording | Совместимая policy revision и новые snapshots без перезаписи истории |
| Mobile-сворачивание продублирует карточки для assistive technology | Один DOM, `aria-expanded`, keyboard test и ручная accessibility-проверка |
| Подпись о сменах превратится в оценку сотрудника или глобальный warning | Показывать её только как локальную недоступность time-метрики без изменения attention/state/action |
| Legacy будет удалён до готовности других потребителей | Удаление вынесено в отдельный change после runtime evidence |
| Параллельные изменения worktree попадут в реализацию | Релевантный diff перед каждым checkpoint, без destructive cleanup |

## Definition of Done

Переработка считается завершённой, когда одновременно выполнено следующее:

- [x] пользовательский маршрут «итог → приоритет → проверка → основание → переход» работает;
- [x] на первом экране видны итог, четыре KPI и одно главное действие;
- [x] одинаковый вывод не размножен по summary, primary action и самостоятельной factor card;
- [x] действие содержит title, target, check и корректный горизонт;
- [x] title primary action описывает понятную операцию менеджера, а не повторяет аналитический факт;
- [x] decision cards равны по высоте на desktop без жёсткой высоты и естественны на tablet/mobile;
- [x] смены не влияют на независимый sales-анализ и общий статус;
- [x] локальная подпись объясняет недоступность time-оценки из-за смен без негативной оценки
  сотрудника;
- [x] peer efficiency использует только выручку в час на достаточной базе;
- [x] по умолчанию показано не более трёх `ATTENTION` сотрудников;
- [x] в `Команде` нет отдельной серой summary-плашки, повторяющихся ФИО и общего статуса в каждой
  карточке;
- [x] плотные mobile-списки изменений и сотрудников используют доступное progressive disclosure;
- [x] PARTIAL и BLOCKED соответствуют согласованным сценариям;
- [x] evidence/detail panel доступен с клавиатуры и на touch;
- [x] desktop/tablet/mobile проверены на всех обязательных fixtures;
- [x] полный backend Gradle suite повторно запущен после финальных edge-case исправлений:
  `:backend:check` прошёл с `1121` обнаруженным test case, без failures/errors, вместе с
  Checkstyle, OpenAPI compatibility, operator-script security и supply-chain проверками в
  изолированном Java 21 runner на проверенном локальном Gradle cache;
- [x] frontend, AI compatibility и documentation suites проходят;
- [x] current-документы обновлены вместе с поведением;
- [ ] production rollout подтверждён отдельным sanitized evidence;
- [x] legacy cleanup не смешан с основной переработкой.

## Критерий закрытия документа

План закрывается после выполнения P0–P7, переноса реализованного поведения в current-контракты и
ссылки на отдельное runtime evidence. Если часть scope сознательно отложена, она получает отдельную
задачу/decision, а не остаётся скрытым незавершённым пунктом этого документа.

## Локальный предрелизный прогон 2026-09-12

Проведён локальный read-path с реальными обезличенными данными проектной базы: миграции применены до
актуальной версии, вход, выбор магазина, создание immutable snapshot и повторное чтение ответа
прошли через штатные HTTP endpoints. Production и внешние среды не затрагивались.

Наблюдаемый сценарий оказался `BLOCKED`: продажи и возвраты в локальной базе не покрывают последнюю
завершённую неделю. Это подтвердило корректность fail-closed поведения, но не является фактической
runtime-проверкой `READY` или `PARTIAL` на свежих данных. Эти состояния проверены детерминированными
fixture-сценариями.

Прогон выявил и устранил до выпуска следующие дефекты:

- при `BLOCKED` часть вычисленных нулей могла выглядеть как доступный результат; теперь основные
  KPI, декомпозиция выручки, структура, команда, сотрудники и evidence адресно маскируются;
- при том же состоянии derived warnings по классификации, себестоимости, консистентности и
  атрибуции больше не строятся поверх неполного обязательного источника; limitation refs остаются
  разрешимыми, а team roster обнуляется;
- смена deterministic policy могла ошибочно классифицироваться как отсутствие изменений источника;
  теперь сравнение policy versions создаёт новую immutable revision;
- строгое правило новой семантики сначала мешало читать прежнюю immutable revision; frontend
  применяет его только начиная с `weekly-metrics-v6`, поэтому legacy v5 остаётся читаемой;
- одинаковые ограничения продаж и возвратов получили разные понятные формулировки и зависимости;
- администратор получил явный переход в качество данных выбранного магазина, а менеджер видит
  ответственного и инструкцию без недоступной ссылки.

Локальная визуальная проверка выполнена на desktop, tablet и mobile как для фактического `BLOCKED`,
так и для детерминированного `BLOCKED`; drawer/bottom sheet просмотрены вручную. Полная fixture-матрица
`READY`, спокойного `READY`, `READY` без смен, `PARTIAL` и `BLOCKED` остаётся обязательным evidence
для состояний, которых нет в текущем наборе локальных данных.

Предрелизный вывод: локальный `BLOCKED` read-path и совместимость старой/новой revision готовы;
production rollout остаётся отдельным P7 и требует свежих данных, sanitized runtime evidence и
штатной последовательности выпуска frontend → backend.

Финальная локальная верификация на этом этапе:

- frontend contract, lint, `53` test files / `245` tests и production build — успешно;
- затронутый backend-контур — `37/37` JUnit tests, ручная компиляция Java 21 и Checkstyle — успешно;
- documentation unit suite — `25/25`, strict inventory — `399` строк без предупреждений,
  `git diff --check` — успешно;
- независимые финальные code-review и UI-review — замечаний P0/P1/P2 нет;
- полный `./gradlew :backend:check --offline` завершён успешно: `1121` обнаруженный test case,
  без failures и errors; Windows-host получил `HTTP 200` от Maven Central, но прямой clean-cache прогон из
  Linux-runner остаётся недоступен и не подменяется этим offline evidence.

## Локальный предрелизный прогон 2026-09-14

Разрешённый read-only backfill LiveSklad завершился `SUCCESS` и дал непрерывное покрытие sales,
returns и issued-order positions за `2026-08-31..2026-09-13`. Через штатный authenticated API
созданы и прочитаны snapshots двух магазинов за `2026-09-07..2026-09-13`; оба состояния —
`PARTIAL`, без blocking issues. Свежий реальный `READY` в этом scope отсутствует.

Live visual review двух отчётов на desktop/tablet/mobile выявил и закрыл два дефекта: слипшиеся
действия внизу секции команды и нейтральный summary при материальном `READY`-изменении средней
продажи. Итог теперь учитывает все material `READY` KPI, snapshot policy увеличена до
`weekly-snapshot-v11`, а `PARTIAL` без действия не обещает, что дополнительная проверка не нужна.
После исправлений созданы новые immutable revisions; полный backend/frontend verification и
финальный live visual `6/6` прошли.

После согласованной P6.1-калибровки выполнен отдельный повторный прогон без нового обращения к
LiveSklad. Два уже сохранённых `PARTIAL` snapshots прочитаны через актуальную локальную сборку и
штатную авторизацию: live visual снова прошёл `6/6` на desktop/tablet/mobile. Fixture-матрица
`ready-dense`, `ready-calm`, `ready-missing-shifts`, `partial`, `blocked` прошла `15/15`, включая
раскрытые mobile-списки и detail panels. Frontend прошёл `53` test files / `251` tests, полный
backend `:backend:check` — `1121` tests без failures/errors. Policy будущих deterministic snapshots
увеличена до `weekly-snapshot-v12` из-за новой формулировки действия; существующие immutable
snapshots не переписывались. Свежий реальный `READY` по-прежнему отсутствует и не подменяется
fixture evidence.

Финальный documentation gate после P6.1: unit suite `25/25`, strict integrity — `408` inventory
rows и `0` baseline warnings через временный индекс, `git diff --check` — успешно. Обычный strict
по-прежнему видит зарегистрированные, но ещё не добавленные в реальный Git index документы общего
worktree; staging area во время проверки не изменялся.

Обезличенные доказательства и ограничения сохранены в
[`WEEKLY_REVIEW_LOCAL_PRERELEASE_2026-09-14.md`](../history/audits/2026/09/WEEKLY_REVIEW_LOCAL_PRERELEASE_2026-09-14.md).
Rate-limit backfill показал отдельную P2-неэффективность: поздний retry повторно читает уже
сохранённые фазы текущего окна. Корректность и идемпотентность не нарушены, но checkpointing фаз
нужно улучшать отдельной задачей перед крупными историческими импортами.

## Результат извлечения

Локальная часть P0–P6 извлечена в current-контракты и подтверждена
[`WEEKLY_REVIEW_LOCAL_PRERELEASE_2026-09-14.md`](../history/audits/2026/09/WEEKLY_REVIEW_LOCAL_PRERELEASE_2026-09-14.md).
P7-A и P7-B подтверждают границу и воспроизводимость исходного кандидата. P7-C сохранил реальные
immutable v12 snapshots, затем после согласованной коррекции бизнес-семантики — новые immutable
v13 revisions. Во всех v13 snapshots четыре core KPI готовы; оба отчёта остались `PARTIAL` только
из-за независимых локальных ограничений. Отсутствие естественного `READY` и отдельного live v13
visual сохраняет release-контрольную точку в `STOP`, но не блокирует завершение согласованной
реализации блока. Production rollout, post-release observation и legacy cleanup не объявляются
завершёнными и остаются для отдельного преддеплойного/релизного прогона.
