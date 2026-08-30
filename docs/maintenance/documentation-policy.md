---
doc_schema: 1
doc_type: current
status: current
owner: project
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-reform-plan.md
implementation_sources:
  - docs/maintenance/documentation-inventory.tsv
  - docs/maintenance/documentation-ownership.md
  - docs/maintenance/templates/README.md
verification_sources:
  - docs/maintenance/documentation-inventory.md
runtime_evidence: []
required_reviewers:
  - information-architecture
  - operations
review_triggers:
  - documentation-structure-change
  - release-process-change
supersedes: []
superseded_by: null
---

# Политика документации Store Analytics

## Назначение

Эта политика определяет, где хранится документация, какой материал считается действующим, кто
подтверждает его содержание и как предотвращается повторное накопление противоречащих документов.

Политика не объявляет существующие документы актуальными автоматически. Их статус и дальнейшая
судьба определяются реестром
[`documentation-inventory.tsv`](documentation-inventory.tsv) и проверяются по соответствующему
источнику истины во время доменных этапов реформы.

## Классы материалов

### Действующий документ (`current`)

Описывает поведение системы, продуктовые правила, архитектуру или контракт, действующие сейчас.
Он изменяется вместе с кодом и обязан ссылаться на проверяемые источники истины.

Действующий документ:

- не содержит датированное утверждение о конкретном production-релизе, кроме единственного
  `current/project-state.md`;
- не смешивает желаемое поведение с реализованным;
- явно фиксирует известные расхождения между требованиями и реализацией;
- описывает инварианты и границы, а не дублирует каждое поле генерируемого OpenAPI.

### Runbook (`runbook`)

Исполняемая инструкция для оператора. Runbook описывает процедуру без привязки к устаревшему номеру
релиза и содержит preflight, влияние, критерии остановки, проверку результата и безопасное
восстановление.

Наличие команд в историческом аудите или release record не делает такой материал runbook.

### Архитектурное или продуктовое решение (`decision`)

ADR фиксирует контекст, принятое решение, альтернативы и последствия. После принятия его основное
содержание не переписывается. Изменение решения оформляется новым ADR, а старый получает ссылку
`superseded_by`.

### Историческое доказательство (`evidence`)

Release record, audit, canary, incident report, reconciliation result и handoff фиксируют реально
наблюдавшееся состояние в конкретный момент. После завершения материал считается immutable.

Допустимые изменения evidence:

- исправление ссылки;
- добавление явно помеченной errata;
- ссылка на итог инцидента или актуальный контракт;
- metadata, не меняющая исходный вывод.

Evidence не используется как действующая операторская инструкция.

### Архив (`archive`)

Superseded design, discovery и рабочий журнал, которые полезны для происхождения решения, но не
описывают текущее поведение. Архив исключается из основной навигации и обязан указывать актуальную
замену либо причину отсутствия замены.

### Runtime-контракт (`runtime-artifact`)

Versioned prompt, JSON Schema, пример, OpenAPI baseline и другой файл, используемый сборкой или
тестами. Опубликованная версия не переписывается. Изменение поведения создаёт новую версию и
проходит contract/evaluation gate.

Runtime-контракт нельзя архивировать или удалить только потому, что он выглядит как старая
документация.

### Рабочий материал (`working`)

Временный discovery, plan или worklog. Он не публикуется в разделе действующих документов. После
принятия решения полезное содержание переносится в current/ADR/evidence, а исходник отправляется в
archive или удаляется по подтвержденной карте замены.

`draft` не является отдельным типом документа: это status незавершенного `current`, `runbook` или
`working`. Рабочий материал использует `doc_type: working`, чтобы его нельзя было ошибочно
проиндексировать как действующий контракт.

## Целевая структура

```text
docs/
├── README.md
├── current/
│   ├── project-state.md
│   ├── product/
│   ├── architecture/
│   ├── api/
│   ├── integrations/
│   ├── frontend/
│   └── ai/
├── runbooks/
├── security/
├── decisions/
├── history/
│   ├── releases/YYYY/MM/
│   ├── audits/YYYY/MM/
│   ├── canaries/YYYY/MM/
│   ├── incidents/YYYY/MM/
│   └── handoffs/YYYY/MM/
├── archive/
└── maintenance/
```

Co-located README, license, runtime provenance и tool instructions могут оставаться рядом с кодом,
если перенос ухудшает воспроизводимость или ломает относительные пути. Они учитываются в общем
реестре и получают ссылку из тематического индекса.

`docs/prompts/` и `docs/schemas/` остаются на текущих путях до отдельного технического решения.

## Источники истины

| Утверждение | Канонический источник |
|---|---|
| Реализованное поведение | код и автоматические тесты |
| API transport shape | versioned OpenAPI и generated contract checks |
| Схема данных | Flyway migrations, schema oracle и migration tests |
| Product/metric semantics | действующий продуктовый контракт, код формулы и тестовые примеры |
| Требование заказчика | подтвержденное решение/evidence; не считается реализованным без code/test proof |
| Production release/schema/flags | проверенный runtime и единственный `current/project-state.md` |
| Действия оператора | актуальный проверенный runbook |
| Факт прошлого релиза/инцидента | immutable evidence |

Если источники расходятся, документ не выбирает удобную версию молча. Он фиксирует:

1. фактически реализованное поведение;
2. ожидаемое поведение;
3. подтвержденное расхождение;
4. ответственное решение или открытую задачу.

## Единственный production-state

Этап 2 определяет только schema и правила будущего production-state. Сам файл создается на этапе 3
после отдельной проверки runtime. После этого только `docs/current/project-state.md` может
содержать текущие:

- release ID и commit;
- schema version;
- immutable image digests;
- подтвержденные runtime roles;
- несекретные feature flags;
- дату и способ проверки.

Архитектура и runbook ссылаются на него, но не копируют значения. Release candidate до deploy
описывает только цель. Production-факт появляется только после post-deploy проверки фактического
runtime и никогда не формируется из старого handoff.

## Metadata

Новые и уже мигрированные действующие Markdown-документы, runbook, ADR, evidence и archive
используют YAML front matter. Старые документы получают metadata во время своего доменного этапа;
отсутствие metadata до переноса не делает их автоматически current.

Общие поля:

- `doc_schema` — версия правил metadata, сейчас `1`;
- `doc_type` — `current`, `runbook`, `decision`, `evidence`, `archive` или `working`;
- `status` — допустимое состояние соответствующего типа;
- `owner` — логический владелец из ownership matrix;
- `audience` — целевые читатели;
- `required_reviewers` — логические reviewer-роли для междоменных и критических утверждений;
- `supersedes`/`superseded_by` — явная цепочка замены, когда она применима к типу документа.

Источники указываются по смыслу, а не одним неоднозначным списком:

- `current`: `requirement_sources`, `implementation_sources`, `verification_sources` и
  `runtime_evidence`;
- `runbook`: `source_of_truth` и `verification_evidence`;
- `decision`: `decision_sources`, `implementation_sources`, `verification_sources` и
  `implementation_status`;
- `evidence`: `source_of_truth`, `verdict` и ограниченный `verdict_scope`;
- `archive`: `source_of_truth` и актуальная замена;
- `working`: `source_material`, `review_by` и `exit_target`.

Для `current` и `runbook` обязательны `last_verified` и `review_triggers`. `status: current` у
действующего контракта запрещен без `implementation_sources` и `verification_sources`. Требование
заказчика само по себе не доказывает реализацию. `runtime_evidence` обязательно, только если
документ утверждает состояние production. Для evidence обязательна `snapshot_date`. Для runbook
дополнительно используются `last_rehearsed`, `verification_levels`,
`required_verification_levels`, `operation_type`, `environments`, `risk_level` и структурированный
`verification_evidence`.

Дата `last_verified` не означает автоматическую истинность и не продлевается косметической
правкой. Она меняется только после фактической проверки источников истины.

Допустимые статусы:

| `doc_type` | `status` |
|---|---|
| `current` | `draft`, `current`, `superseded` |
| `runbook` | `draft`, `current`, `superseded` |
| `decision` | `proposed`, `accepted`, `rejected`, `superseded` |
| `evidence` | `historical` |
| `archive` | `archived` |
| `working` | `draft`, `closed` |

Элементы `verification_levels` и `required_verification_levels` принимают только `static`, `local`,
`staging`, `production-read-only` или `production-drill`. Это наборы независимых проверок, а не
линейная шкала: наличие `staging` не заменяет обязательный production read-only preflight. Каждому
достигнутому уровню соответствует запись `verification_evidence` с level, scope, датой и путем к
sanitized evidence. Даты используют ISO `YYYY-MM-DD`; неизвестная дата задается YAML `null`, а не
вымышленным значением.

Пути в source-полях указываются от корня репозитория. Для runtime-наблюдения используется
описательный идентификатор вида `production-runtime:<sanitized-check>` и ссылка на evidence;
секретные значения в metadata не включаются.

Поле `migration_status` в inventory описывает состояние материала во время реформы и имеет
открытый словарь. Оно не равно нормативному front matter `status` и не меняет жизненный цикл
документа.

Versioned prompt/schema/example, third-party license, dependency manifest и сами template-файлы не
получают этот front matter автоматически: их формат является частью runtime/tooling-контракта или
шаблона. Они остаются учтены в inventory и проверяются отдельными правилами.

## Жизненный цикл

| `doc_type` | Допустимый переход | Условие |
|---|---|---|
| `current` | `draft → current → superseded` | Проверены implementation и verification sources; замена создана до снятия старого контракта |
| `runbook` | `draft → current → superseded` | Выполнен минимальный gate для окружения и риска |
| `decision` | `proposed → accepted|rejected → superseded` | Решение принято владельцем; `accepted` не означает, что код уже реализован |
| `evidence` | `historical` | Создается только из наблюдаемого события и после фиксации не становится current |
| `archive` | `archived` | Не возвращается в current без новой полной верификации |
| `working` | `draft → closed` | Полезный результат извлечен в current/decision/evidence либо подтверждено удаление |

Рабочий материал обязан иметь срок пересмотра `review_by` и `exit_target`. Он не включается в
навигацию действующих контрактов. Закрытие фиксирует, куда перенесены решения и evidence; после
этого исходник архивируется или удаляется по правилам безопасного удаления.

## Gate для production-runbook

`operation_type` принимает `read-only`, `reversible-write`, `migration`, `recovery` или
`destructive`; `environments` — список из `local`, `test`, `staging`, `production`; `risk_level` —
`low`, `medium`, `high` или `critical`.

| Операция в production | Минимальный `risk_level` | Обязательные `required_verification_levels` до `status: current` |
|---|---|---|
| `read-only` | `low` | `production-read-only`, точный target и sanitized evidence |
| `reversible-write` | `medium` | `staging` + `production-read-only`, rollback и идемпотентность |
| `migration` | `high` | `staging` + `production-read-only`, backup/compatibility/forward-fix |
| `recovery` | `high` | `staging` + `production-read-only`, до/после инварианты и reconciliation |
| `destructive` | `critical` | `staging` + `production-read-only`, critical review и разовая авторизация точного target |

У операции может быть более высокий риск, но не ниже указанного минимума. Для runbook без
production минимальный набор выбирается по фактическому окружению: `static` допустим только для
неисполняемого reference, `local` — для local/test, `staging` — для staging.

| `risk_level` | Дополнительная защита |
|---|---|
| `low` | review владельца и точный scope |
| `medium` | доменный reviewer, явные stop criteria и rollback/retry |
| `high` | независимый `operations` reviewer, evidence для каждого обязательного уровня и явная роль авторизации |
| `critical` | независимый critical review, разовая авторизация точного target; `security-privacy` обязателен при secrets, access или персональных данных |

Статическая проверка не позволяет присвоить `status: current` runbook, который выполняет запись,
миграцию, recovery или destructive-операцию в production. Для операции, которую невозможно
безопасно репетировать полностью, документ остается draft либо содержит явно более узкий scope,
подтвержденный доступным evidence.

## Правила именования и ссылок

- Действующие файлы используют нейтральные kebab-case имена без даты и номера релиза.
- Evidence хранит дату и/или версию в пути или имени.
- ADR использует формат `ADR-NNNN-short-title.md` до появления автоматической нумерации.
- Внутренние Markdown-ссылки относительные и проверяются автоматически.
- Ссылка ведет на канонический документ, а не на исторический worklog, если нужен текущий ответ.
- Текст не содержит секретов, полных environment-файлов, cookie, session ID или персональных
  provider payload.

## Матрица обязательных обновлений

| Изменение | Документационный результат |
|---|---|
| Flyway migration | database contract, compatibility/forward-fix, release evidence |
| API/DTO | OpenAPI, generated types и тематический API-контракт |
| Метрика/классификация | business rule, формула, примеры и тесты; ADR при изменении решения |
| LiveSklad/sync/webhook | integration contract, retry/idempotency и operations runbook |
| Feature flag/env | конфигурационный контракт и preflight; project-state только после deploy |
| UI-поведение | frontend contract, состояния и локальная visual acceptance |
| AI prompt/schema | новая immutable версия, eval evidence и rollout/canary record |
| Deployment | candidate record до deploy; project-state и release evidence после deploy |
| Incident/recovery | incident/recovery evidence; runbook меняется только для долговечного урока |
| Внутренний refactoring | допустимо `Docs impact: none` с проверяемой причиной |

## Review и удаление

Владельцы и обязательные reviewers определены в
[`documentation-ownership.md`](documentation-ownership.md).

Удаление возможно только когда:

1. указан канонический replacement или доказано отсутствие самостоятельной ценности;
2. обновлены все входящие ссылки;
3. runtime/build references отсутствуют;
4. для backup-файла составлен fragment map;
5. независимый reviewer подтвердил отсутствие потери evidence;
6. изменение выполнено отдельным логическим коммитом.

Git является механизмом истории. `.orig`, `.bak`, `.rej` и ручные копии не являются допустимым
долгосрочным форматом документации.

## Применение проверок

CI-защита вводится поэтапно:

1. проверки запускаются в warning-режиме на существующем baseline;
2. baseline очищается и документируется;
3. детерминированные проверки ссылок, metadata, orphan current и запрещенных backup-файлов
   становятся блокирующими;
4. внешние URL и review age остаются предупреждениями, чтобы сетевой сбой или календарная дата не
   блокировали безопасный релиз.
