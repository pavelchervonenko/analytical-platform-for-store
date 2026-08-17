# ИИ-интерпретация v15: состояние, проверенные изменения и план выпуска

Дата фиксации: 2026-08-17
Статус: локальный release candidate; полный evaluation gate, canary и production-активация ещё не
выполнены.

Этот документ является короткой точкой продолжения работ по ИИ. Подробная хронология исследования,
реализации и всех промежуточных экспериментов находится в
[AI_INTERPRETATION_FULL_AUDIT_2026-08-14.md](AI_INTERPRETATION_FULL_AUDIT_2026-08-14.md).

## 1. Краткий итог

Текущий кандидат `weekly-interpretation-v15` достоин фиксации как release candidate. Известные
дефекты контрольного сценария устранены не только инструкциями prompt, но и структурой контракта,
backend-owned правилами и независимым evaluation gate.

На текущем этапе нельзя включать v15 как глобальный production default. Успешно проверен один из
26 сценариев матрицы. Остальные 25 сценариев, полный automatic gate, слепая ручная оценка и свежий
end-to-end canary ещё не выполнены.

Принятое решение:

1. Зафиксировать v15 и не создавать следующую prompt-версию без нового обнаруженного дефекта.
2. Отделить AI-релиз от посторонних незакоммиченных изменений.
3. Закончить evaluation и canary.
4. Только после успешных gate включать связку `weekly-interpretation-v15` / content schema `3`.

## 2. Где мы находимся сейчас

- Ветка: `codex/pilot-production-deployment`.
- Текущий локальный HEAD на момент фиксации: `3698d97`.
- AI-изменения ещё не собраны в отдельный коммит.
- Рабочее дерево содержит также изменения формул, классификации товаров, миграций и служебных
  аудитов. Их нельзя случайно включить в один AI-коммит без отдельной проверки зависимостей.
- Application default остаётся:
  - `LLM_PROMPT_VERSION=weekly-interpretation-v4`;
  - `LLM_CONTENT_SCHEMA_VERSION=2`.
- Те же безопасные значения по умолчанию остаются в production Compose и примере production env.
- Текущий AI-цикл не изменял production, не публиковал интерпретации и не создавал Telegram events.
- Фактически развёрнутый commit, release label, schema version и значения production env необходимо
  повторно проверить непосредственно перед релизом. Старую запись handoff нельзя считать live-
  проверкой на 2026-08-17.

Текущий этап процесса: **v15 прошёл одну платную контрольную shadow-пару; перед полным
evaluation-прогоном нужно исправить локальный gate подготовки blinded review**.

## 3. Что именно анализирует ИИ

ИИ не рассчитывает KPI и не обращается к базе. Все суммы, проценты, планы, рейтинги, категории,
attach-rate, достаточность данных и сравнения сначала рассчитывает backend. Модель получает только
агрегированный, псевдонимизированный и ограниченный provider input и формулирует интерпретацию.

Периоды weekly-интерпретации:

- анализируется последняя полностью завершённая неделя понедельник–воскресенье в timezone магазина;
- она сравнивается с непосредственно предшествующей полной неделей;
- магазин сравнивается сам с собой;
- сотрудник в динамике сравнивается прежде всего сам с собой;
- допустимые командные benchmarks, лидеры и relationships готовит backend, а не свободно вычисляет
  модель;
- выбранный пользователем месяц на weekly endpoint не влияет.

Изменение бизнес-формул должно выполняться в KPI/backend-слое. Новые snapshots затем получают уже
рассчитанные значения. Prompt не должен компенсировать ошибки формул.

## 4. Проверенные архитектурные изменения

### 4.1 Размещение и пользовательский интерфейс

- ИИ-разбор убран с главного экрана.
- Интерпретация остаётся в отдельном разделе `/insights`.
- Типы выводов визуально различаются: наблюдение, синтез, гипотеза, риск и возможность.
- Гипотеза явно помечается как предположение, а не установленная причина.
- Подтверждающие факты показываются рядом с выводом, действием или ограничением данных.
- Ограниченная и недостаточная выборка имеет отдельное отображение.
- Действия названы рекомендациями и показывают своё основание.

### 4.2 Exact evidence contract

- Миграция `V37__persist_llm_provider_input.sql` сохраняет exact compact provider input каждой
  попытки и его SHA-256.
- Ответ проверяется против фактов, реально отправленных модели в конкретной попытке, а не против
  более широкого snapshot.
- Для старых попыток до V37 сохранён legacy fallback.
- Provider schema разрешает только фактически доступные `evidenceRefs`.
- В compact input возвращены факты смен и workload, backend-owned limitations и безопасные
  отображаемые названия категорий.
- Добавлены fail-closed проверки связности snapshot, facts, evidence, candidates и attempt input.

### 4.3 Детерминированные аналитические candidates

- `WeeklySnapshotPolicyV3` формирует material movements, plan gaps, category growth/decline,
  store/employee attach gaps и employee self-dynamics до вызова модели.
- Backend рассчитывает командные квартильные benchmarks при достаточной выборке.
- Backend выбирает допустимых unique leaders, most improved и mentor/learner relationships.
- Каждый candidate содержит scope, theme, kind, sufficiency и точные evidence refs.
- Модель не может создать свободный candidate, произвольную связь сотрудников или подменить
  kind/theme/scope/evidence существующего candidate.
- Актуальные snapshot-версии нового контура: `weekly-metrics-v3`, `weekly-snapshot-v6`,
  `weekly-quality-v3`.

### 4.4 Безопасный evidence bundle для API и UI

- Consumer API возвращает только факты, использованные опубликованной интерпретацией.
- Внутренние evidence codes заменяются непрозрачными `EV001`, `EV002` и далее.
- Внутренние employee refs `E01`, `E02` не попадают в публичный JSON; API использует публичный UUID
  и имя из immutable membership snapshot.
- Форматирование денег, процентов, рейтинга, статусов и сравнений выполняется backend.
- Frontend не пересчитывает KPI и не пытается трактовать сырые значения.
- При несвязанном или отсутствующем evidence consumer projection завершается fail-closed.
- Сохранённый canonical content и Telegram fanout от добавления evidence bundle не изменяются.

### 4.5 Content schema v3 и устранение повторов

Промты v10–v12 показали, что повтор главного вывода нельзя надёжно убрать одной формулировкой.
Старый content schema одновременно требовал STORE headline и отдельный insight для одного
обязательного STORE candidate.

В content schema v3 реализовано структурное исправление:

- один backend-selected `primarySignal` является главным выводом hero-блока;
- его `candidateRef` запрещено повторять во вторичных insights;
- STORE `HEADLINE` в v3 запрещён;
- при отсутствии material STORE candidate `primarySignal=null`, а нейтральный текст формирует
  backend;
- исторические content schema v1/v2 продолжают читаться прежними projectors;
- dashboard и Telegram получают стабильную presentation model и показывают главный сигнал один раз.

### 4.6 Structured provider transport

Начиная с v14 модель больше не формирует один общий массив обязательных summary blocks:

- `employees` и analysis status формирует backend;
- `teamOverview` является отдельным обязательным объектом;
- `employeeHeadlines` имеет обязательный ключ для каждого сотрудника manifest;
- `supportingSummaries` содержит только необязательные дополнительные блоки;
- backend детерминированно собирает из provider transport канонический content schema v3 перед
  schema и semantic validation.

Это исключает пропуск обязательного team overview, лишний STORE headline и неправильный состав
summary blocks уже на уровне формы ответа.

### 4.7 Ограничения v15

V15 сохраняет schema v3 и structured transport v14 и закрывает два последних дефекта v14:

- `teamOverview` может ссылаться только на exact TEAM evidence;
- командный вывод должен описывать сопоставимость команды, benchmark или подтверждённую relationship,
  а не повторять STORE-категорию или индивидуальный результат;
- при недостаточной базе допустим нейтральный вывод об ограниченности сравнения;
- `primarySignal` и `teamOverview` независимо сравниваются на смысловую близость;
- действие должно содержать одну основную управленческую операцию и один конкретный наблюдаемый
  результат;
- запрещены присоединённые общие цели: «проанализировать спрос», «понять причины», «принять меры»,
  «устранить проблемы» и аналогичный boilerplate;
- для category mix разрешена конкретная проверка наличия/выкладки с формированием списка позиций,
  но запрещено заранее объявлять проблемы или причины без evidence.

## 5. Как развивался кандидат

Каждая использованная prompt-версия осталась immutable. Новый дефект исправлялся новой версией, а
сохранённый ответ повторно прогонялся через усиленный независимый gate.

| Версия | Основное изменение | Результат контрольного сценария |
|---|---|---|
| v4 | Текущий baseline, schema v2 | Повторы, общий action, WORKLOAD без прямого evidence |
| v5 | Сокращён обязательный текст, улучшена presentation semantics | Перенос числового значения в narrative |
| v6 | Жёстче WORKLOAD и team relationships | Несовпадение candidate kind и утечка `E01` в текст |
| v7 | Strict candidate-only insights и backend normalization | Структурно прошёл, но действия остались общими и похожими |
| v8 | Action-quality и duplicate-action gate | Общий второй action и вывод о прибыли из evidence выручки |
| v9 | Narrative dimension guard и лимит actions | Boilerplate action и повтор headline/insight |
| v10 | Усилены dimension/action guards | Повтор главного сигнала и директива внутри insight |
| v11 | Narrative-quality gate | Неподтверждённая причина и повтор через расширенный headline |
| v12 | Containment и causal gate | Остался структурно вынужденный повтор главного сигнала |
| v13 | `primarySignal`, content schema v3 | Повтор устранён, но модель неверно собрала mandatory summaries |
| v14 | Structured provider transport | Остались общий action и STORE-смысл в team overview |
| v15 | TEAM-only evidence, primary/team duplicate gate, строгий action | Контрольная пара прошла automatic и ручную проверку |

Суммарная фактическая стоимость контрольных shadow-пар v4/v5–v4/v15 составила `59.612 RUB`.

## 6. Независимые проверки качества

Проверки не полагаются только на то, что модель выполнила prompt.

Production validator и offline evaluator контролируют, среди прочего:

- JSON schema и согласованную пару prompt/content schema;
- точный состав сотрудников и backend-owned analysis status;
- существование, доступность и scope каждого evidence;
- запрет cross-employee evidence;
- exact candidate kind/theme/scope/category/employee/evidence;
- запрет повторного candidateRef;
- достаточность данных и ограничения для `LIMITED`/`INSUFFICIENT`;
- WORKLOAD только при прямом workload evidence;
- отсутствие цифр, внутренних refs и технических идентификаторов в narrative;
- соответствие упомянутого измерения evidence: revenue не доказывает profit/margin;
- причинные утверждения только для разрешённого HYPOTHESIS candidate;
- близость primary, team, secondary narratives и действий;
- запрет управленческих директив внутри аналитических insights;
- конкретность действия, его cardinality и отсутствие boilerplate;
- только backend-разрешённые team relationships.

Evaluation infrastructure:

- содержит 26 обезличенных сценариев;
- строит пары v4/v15 на одном и том же compacted provider input;
- использует production compactor, request factory и Yandex client;
- dry-run не требует ключа и не вызывает сеть;
- платный режим требует отдельной confirmation-фразы, versioned model URI, secret file, лимит числа
  вызовов и верхнюю границу стоимости;
- не повторяет ошибочные вызовы автоматически и не перезаписывает готовые ответы;
- сохраняет response, receipt, request/evaluation hashes и безопасные failure metadata;
- поддерживает blinded A/B review с SHA-256 binding ответов, assignment и score sheet;
- положительный итог review может разрешить только canary, но не автоматическую смену default.

## 7. Последняя подтверждённая проверка

Контрольная shadow-пара v4/v15 выполнена 2026-08-17 для сценария `accessory-gap`:

- выполнено ровно два вызова без retry;
- оба ответа получили HTTP 200;
- использован один и тот же compacted provider input;
- v4: `2.484800 RUB`, 3106 токенов;
- v15: `3.536800 RUB`, 4421 токен;
- вся пара: `6.021600 RUB`;
- v4 получил четыре нарушения;
- v15 получил ноль automatic violations;
- v15 прошёл ручной просмотр этого сценария.

В подтверждённом ответе v15:

- `primarySignal` точно описывает category decline;
- `teamOverview` отдельно сообщает о недостаточной базе командного сравнения и использует только
  `TEAM.RATING.ELIGIBLE_COUNT`;
- employee headline нейтрально описывает собственную динамику сотрудника;
- вторичные insights, ложный WORKLOAD и неподтверждённые relationships отсутствуют;
- единственное действие предлагает конкретную проверку наличия и выкладки и конкретный список
  отсутствующих или неправильно выложенных позиций;
- narrative не содержит чисел, технических refs, выдуманных причин или общих «принять меры».

Partial `automatic-report.json` имеет общий `passed=false` намеренно: baseline v4 не прошёл, а 50
ответов остальных сценариев отсутствуют. Успех одной пары не является полным matrix acceptance.

## 8. Последние зелёные локальные проверки

- Полный backend suite: **831 тест, 0 failures, 0 ignored**.
- Checkstyle main/test: пройден.
- Evaluation/review Python suite: **42 теста, все пройдены**.
- Сохранённый реальный ответ v14 под v15 gate воспроизводит все три ожидаемых нарушения:
  non-TEAM evidence, близкий primary/team повтор и неконкретный action.
- После введения content schema v3 прошли 18 релевантных frontend tests и production frontend build.
- На этапе полного evidence UI проходили 30 frontend test files / 113 тестов, typecheck, lint и
  production build.
- `git diff --check`: пройден.
- Offline plan построил 52 запроса v4/v15 без сети и подтвердил совпадение provider input внутри
  каждой пары.

## 9. Что пока не проверено

- Нет ответов для остальных 25 сценариев, то есть отсутствуют 50 вызовов v4/v15.
- Не пройден полный automatic gate всей матрицы.
- Не подготовлен и не завершён настоящий blinded review всей матрицы.
- Не выполнен свежий end-to-end canary v15/content schema 3.
- Не проверена точная production-конфигурация и наличие production-owned credentials на момент
  будущего релиза.
- Не выполнена production-публикация v15, проверка dashboard на реальном опубликованном документе
  и Telegram delivery/fanout acceptance для v15.
- Application и production defaults не переключены с v4/schema 2.

### 9.1 Обнаруженный блокер blinded review

При подготовке этого handoff повторно проверен фактический код `review.py`. Сейчас команда
`review.py prepare` требует, чтобы общий automatic gate не содержал ни одного нарушения у обеих
конфигураций. Это противоречит сравнительному назначению матрицы:

- v4 является baseline, а не новым кандидатом;
- сохранённый ответ v4 в уже выполненной паре имеет четыре известных нарушения;
- эти нарушения должны измеряться и участвовать в сравнении с v15;
- при текущем условии любой известный дефект v4 блокирует создание A/B packet, даже если все ответы
  v15 корректны.

До платного полного прогона нужно локально разделить два понятия:

1. completeness/integrity gate всей матрицы — все ответы и hashes присутствуют и проверяемы;
2. candidate eligibility gate — v15 не имеет блокирующих automatic errors и не регрессирует к v4,
   а нарушения v4 сохраняются как baseline metrics и не запрещают blinded review сами по себе.

Исправление должно быть покрыто тестами минимум для трёх случаев: baseline fails / candidate
passes; candidate fails; неполная или подменённая матрица. До этого полный платный запуск не имеет
смысла: результаты будут получены, но штатный workflow не позволит подготовить blinded packet.

## 10. Бюджет и время оставшейся матрицы

Осталось 25 сценариев × 2 конфигурации = 50 платных запросов.

- Консервативный preflight maximum: `689.018400 RUB`.
- Это предохранительный максимум при полном использовании output budget, а не ожидаемый счёт.
- По фактическому соотношению контрольной пары базовая экстраполяция составляет около `147 RUB`.
- Разумный ожидаемый диапазон: `150–250 RUB`; фактическая сумма зависит от длины ответов.
- Последняя пара имела суммарную provider latency около 9.6 секунды.
- Ожидаемое время 50 последовательных вызовов: примерно 5–10 минут без rate limit/failure.
- Automatic report занимает ещё несколько минут.
- Полный внимательный blinded review и оформление решения: ориентировочно 1–2 часа.

Для контроля риска матрицу можно получать партиями, но итоговый gate всё равно требует все 52
ответа. Runner resumable и не должен повторно оплачивать уже сохранённые успешные ответы.

## 11. Следующие шаги

### Шаг 1. Заморозить и аккуратно зафиксировать release candidate

1. Не менять prompt v15 и schema v3 без нового воспроизводимого дефекта.
2. Исправить gate `review.py prepare`: известные нарушения baseline v4 должны измеряться, но не
   блокировать A/B packet при корректной v15; добавить негативные и положительные regression tests.
3. Разделить текущее рабочее дерево на осмысленные коммиты и исключить временные audit-файлы.
4. Проверить зависимости AI-кода от незакоммиченных изменений KPI и классификации.
5. Обновить устаревшие упоминания v4/v5 в production operations до фактической матрицы v4/v15.
6. Повторить changed-scope проверки после формирования чистого commit candidate.

### Шаг 2. Получить полную v4/v15 shadow-матрицу

1. Отдельно утвердить бюджетный предел.
2. Выполнить оставшиеся 50 вызовов без publication, Telegram и production mutations.
3. Сохранить receipts, responses и evaluation hashes в отдельном закрытом каталоге `build/`.
4. При ошибке не выполнять автоматический retry; сначала классифицировать provider failure.

### Шаг 3. Выполнить automatic gate

1. Проверить наличие и hash binding всех 52 ответов.
2. Запустить полный evaluator с `--require-responses`.
3. Убедиться, что v15 проходит schema, evidence, candidate, narrative, action и relationship gates.
4. Сохранить нарушения v4 как baseline metrics, не смешивая их с eligibility кандидата.
5. Сравнить v15 с baseline v4 и не допустить регрессии по versioned metrics.
6. При провале не менять prompt наугад: сохранить ответ, воспроизвести дефект regression-тестом и
   только затем принимать решение о новой версии.

### Шаг 4. Провести blinded ручную оценку

1. Подготовить A/B packet только после completeness/integrity gate всей матрицы и отсутствия
   блокирующих automatic violations у v15; нарушения v4 остаются baseline для сравнения.
2. Оценить каждый сценарий по пяти dimensions rubric.
3. Проверить required/forbidden findings и все critical errors.
4. Финализировать decision report с раскрытием v4/v15 только после заполнения score sheet.
5. Допустить v15 максимум к статусу `CANDIDATE_ELIGIBLE_FOR_CANARY`.

### Шаг 5. Выполнить свежий end-to-end canary

Canary должен использовать exact release candidate и связку:

- `LLM_PROMPT_VERSION=weekly-interpretation-v15`;
- `LLM_CONTENT_SCHEMA_VERSION=3`;
- `weekly-snapshot-v6`;
- зафиксированный versioned Yandex model URI;
- production-equivalent token, call и cost limits.

Проверить весь путь snapshot → job → provider → validation → immutable publication → consumer API
→ отдельный AI-раздел. Telegram fanout на canary не включать без отдельной приёмки. Должны быть
проверены токены, стоимость, модель, отсутствие повторной публикации, evidence projection и
fallback/rollback.

### Шаг 6. Подготовить и выполнить production release

1. Проверить фактически развёрнутые commit/release/schema labels.
2. Убедиться, что release candidate является потомком production commit.
3. Повторить полный backend/frontend release gate для точного commit.
4. Проверить миграции и совместимость отката; V37 является additive, старые v1/v2 readers сохранены.
5. Проверить отсутствие активного synchronization job.
6. Сделать и проверить backup.
7. Собрать immutable images с commit/release labels и проверить checksums.
8. Развернуть по production runbook, выполнить health, HTTPS, authorization и changed-user-journey
   smoke tests.
9. Только после успешного canary активировать v15/schema 3 в release env.
10. Проверить один реальный опубликованный weekly result в API и UI; Telegram включать/проверять
    отдельно в соответствии с текущей политикой fanout.
11. Обновить production handoff фактическими, а не ожидаемыми результатами.

### Шаг 7. Наблюдение и rollback

- Контролировать provider errors, validation retries, token/cost budget, stuck jobs и publication.
- Не считать успешную сборку production acceptance без пользовательской проверки результата.
- При проблемах остановить planners/workers/publication в установленном порядке.
- Для отката prompt вернуть согласованную пару v4/schema 2; нельзя смешивать prompt v15 со schema 2
  или prompt v4 со schema 3.
- Использовать предыдущие immutable images для application rollback.
- Не откатывать Flyway вручную: совместимость должна обеспечиваться приложением и additive migration.

## 12. Условия допуска v15 в production default

Все пункты обязательны:

- [ ] AI-изменения зафиксированы в чистом проверенном commit.
- [ ] Исправлен и протестирован baseline/candidate gate подготовки blinded review.
- [ ] Production operations documentation соответствует v4/v15 и schema v2/v3.
- [ ] Получены все 52 shadow-ответа.
- [ ] Матрица прошла completeness/integrity gate, а v15 — свой blocking automatic gate без
  нарушений.
- [ ] Blinded review завершён без critical errors и без значимой регрессии к v4.
- [ ] Свежий end-to-end canary v15/schema 3 пройден.
- [ ] Проверены production release, secrets, budget limits, backup и отсутствие активного sync job.
- [ ] Подготовлен и проверен rollback на v4/schema 2.
- [ ] После развёртывания пройдены health, API, UI и необходимые notification checks.
- [ ] Production handoff обновлён по фактическим результатам.

## 13. Связанные документы

- [Полный аудит ИИ](AI_INTERPRETATION_FULL_AUDIT_2026-08-14.md)
- [Контракт LLM output](llm-output-contract-v2.md)
- [Production-эксплуатация LLM](llm-production-operations.md)
- [YandexGPT staging acceptance](yandexgpt-staging-acceptance.md)
- [Weekly snapshot builder](weekly-snapshot-builder.md)
- [Дизайн LLM notifications](llm-notifications-design.md)
- [Evaluation и blinded review](../scripts/llm-eval/README.md)
- [Production deployment runbook](production-deployment-runbook.md)
- [Правила production pilot](PRODUCTION_PILOT_WORKING_PRACTICES.md)
