---
doc_schema: 1
doc_type: working
status: draft
owner: project
audience:
  - developer
  - operator
  - product
created_at: 2026-09-15
review_by: 2026-09-29
source_material:
  - docs/current/project-state.md
  - docs/runbooks/production-deployment.md
  - docs/runbooks/livesklad-return-recovery.md
  - docs/maintenance/weekly-review-release-manifest.md
  - docs/history/releases/2026/09/v0.1.0-pilot.32-production-verification.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-01_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-02_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-03_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-04_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-05_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-06_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-07_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-07_MOBISFERA.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-08_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-08_MOBISFERA.md
required_reviewers:
  - backend
  - integrations
  - operations
  - product
  - security-privacy
exit_target: evidence
---

# Единый manifest релиза и исправлений сверки LiveSklad

## Цель и границы

Этот manifest объединяет накопленные изменения приложения и оставшиеся точечные исправления
данных после помесячной сверки января–августа 2026 года. Он отвечает на четыре вопроса:

1. какие исправления ещё не выполнены;
2. какой код должен войти в общий релиз;
3. что проверить до production deployment;
4. в каком порядке выполнять изменения данных после deployment.

Manifest не разрешает production write, migration, backfill, recovery или изменение категорий.
Фактическое состояние production, release identity и schema берутся только из
[`docs/current/project-state.md`](../current/project-state.md) и подтверждаются заново перед
релизом. Секреты, cookies, персональные данные и полные provider payload здесь не хранятся.

## Короткий вывод

Несколько проверенных продуктовых изменений можно развернуть одним общим immutable-релизом. При
этом каждый логический пакет остаётся отдельным reviewable commit/PR, а финальный release candidate
собирается в чистом worktree и проходит интегральную проверку.

Исправления исторических данных не входят в Flyway и не запускаются автоматически при deployment.
После успешного релиза они выполняются отдельной очередью с новым preflight:

- один return document — один API request и один независимый post-check;
- классификации — одна атомарная bounded transaction на магазин и месяц;
- после каждого месяца — полная повторная сверка, а не только проверка изменённых строк.

Исходный dirty scope зафиксирован логическими коммитами и собирается в отдельной
`codex/store-release-rc`. Эту ветку нельзя деплоить до закрытия интегральных gate, публикации exact
paired images, staging/release-equivalent rehearsal и fresh production preflight.

## Состояние месячных сверок

| Период и магазин | Текущий статус | Что уже совпадает | Что осталось |
|---|---|---|---|
| Январь, `МАГАЗИН` | `FAIL_ACTION_REQUIRED` | STORE и aggregate SELLERS, analytics, структура, attach | 4 parent sales prerequisite, 4 return relink, 4 payroll assignments |
| Февраль, `МАГАЗИН` | `FAIL_ACTION_REQUIRED` | Все существующие продажи/orders и payment ledger | 5 missing returns, 6 return relink, 2 payroll assignments |
| Март, `МАГАЗИН` | `FAIL_ACTION_REQUIRED` | STORE revenue, direct sales, orders, payment ledger; `F000148` уже исправлен | 1 zero-net missing return, 4 return relink, 3 analytics и 8 payroll assignments |
| Апрель, `МАГАЗИН` | `PASS_WITH_LIMIT` | Полная повторная сверка | Только принятые source zero-cost limits; действий нет |
| Май, `МАГАЗИН` | `PARTIAL` | STORE, aggregate SELLERS, документы, позиции и категории | 1 relink `F000244`; неверны только два личных employee KPI/attach |
| Июнь, `МАГАЗИН` | `PASS_WITH_LIMITS` | Полная повторная сверка | Только принятые source/model limits; действий нет |
| Июль, `МАГАЗИН` | `PASS_WITH_LIMITS` | Финансы, документы, позиции, категории и KPI | Некритичная referential integrity `F000349`; числовой дельты нет |
| Июль, `МобиСфера` | `PASS_WITH_LIMITS` | Полная повторная сверка | Только объяснённые model limits; действий нет |
| Август, оба магазина | `PASS_WITH_LIMITS` | Полная сверка STORE/SELLERS, документов, позиций и категорий | Только принятые source/model limits; действий нет |

Всего в открытом correction ledger:

- 6 отсутствующих return documents;
- 15 relink, влияющих на employee/SELLERS KPI;
- 1 дополнительный link-only integrity target `F000349`, не меняющий текущие числа;
- 17 полей classification assignment: 3 analytics и 14 payroll.

Это 39 exact-target изменений. Они не равны 39 shell/API запускам: 22 возврата обрабатываются по
одному, а 17 classification fields безопаснее применить тремя месячными транзакциями. Загрузка
январских parent sales не включена в это число, потому что её точный scope ещё не выбран.

## Реестр оставшихся возвратов

### Missing return — после deployment выполнять по одному

| Месяц | Документы | Количество | Особое условие |
|---|---|---:|---|
| Февраль | `F000106`, `F000117`, `F000118`, `F000121`, `F000127` | 5 | `F000117` и `F000118` имеют доказанный net `0.00`; нельзя подменять положительной суммой |
| Март | `F000175` | 1 | доказанный net `0.00`, quantity/cost ненулевые |

Для всех шести используется только `MISSING_RETURN` с exact external ID, document number, store,
position count, amount, quantity, cost и уникальным idempotency key. Wide backfill не является
заменой: cashless returns не обнаруживаются через cash-only path.

### Existing return relink — влияет на KPI

| Месяц | Документы | Количество | Текущий тип дефекта |
|---|---|---:|---|
| Январь | `F000061`, `F000066`, `F000086`, `F000096` | 4 | `UNASSIGNED`; parent sales отсутствуют в production |
| Февраль | `F000102`, `F000103`, `F000104`, `F000108`, `F000109`, `F000122` | 6 | `UNASSIGNED`; parents/items уже доказаны |
| Март | `F000144`, `F000146`, `F000156`, `F000168` | 4 | `UNASSIGNED`; parents/items уже доказаны |
| Май | `F000244` → `B002456` | 1 | original links пусты, но текущий employee заполнен неверным processing employee |

Первые 14 `UNASSIGNED` targets используют `expectedCurrentEmployeeExternalId = null`. Для майского
target source-контракт расширен exact-ожиданием текущего employee: `F000244` можно переносить с
доказанного processing employee на employee исходной продажи только при полном совпадении этого
guard и остальных ожиданий. Обнулять employee отдельным SQL-шагом запрещено. До release это
расширение всё ещё должно пройти полный test/migration/RC gate.

### Некритичная referential integrity

`F000349` → `B002204` в июле уже имеет правильную business-атрибуцию и не создаёт финансовой,
employee или attach-rate дельты, но у него отсутствуют `original_document_id` и
`original_item_id`. Source-контракт теперь допускает этот link-only repair, только когда
`expectedCurrentEmployeeExternalId` точно равен сотруднику исходной продажи. Он устраняет известную
неполную связь без изменения сумм и атрибуции, но до постановки `F000349` в очередь по-прежнему
требуются полный test/migration/RC gate и fresh production preflight.

## Реестр оставшейся классификации

Analytics и payroll — независимые проекции. Исправление одного поля не должно автоматически менять
второе.

| Месяц | Документы | Analytics change | Payroll change |
|---|---|---:|---:|
| Январь | `A000134`, `A000158`, `A000166`, `A000169` | 0; уже `SETUP_SERVICE` | 4 × `SERVICE → PAID_REPAIR` |
| Февраль | `A000201`, `A000210` | 0; уже `SETUP_SERVICE` | 2 × `SERVICE → PAID_REPAIR` |
| Март | `A000221`, `A000240`, `A000261` | 3 × accessory/device category → `SETUP_SERVICE` | — |
| Март | `A000221`, `A000240`, `A000256`, `A000276`, `A000282`, `A000290`, `A000302` и вторая позиция `A000221` | — | 8 × current category → `PAID_REPAIR` |

`A000261` остаётся payroll `SERVICE`: это платная чистка, а не подтверждённый платный ремонт.
Мартовский manifest содержит девять позиций; одна позиция меняет обе проекции, поэтому в нём
11 field changes. Exact item/product IDs, суммы и cost берутся из месячного evidence и fresh
read-only preflight, а не только из document number.

Безопасная форма исполнения:

- отдельный script на январь, февраль и март;
- режимы `--preflight`, `--apply`, `--verify`;
- одна транзакция на месяц с exact expected row count и totals;
- guard на store, inclusive business period, document/item/product IDs, source kind, current и
  target category, quantity, revenue, cost;
- stop при approved/paid payroll run, approved/archived report, overlapping assignment или active
  sync/recovery того же scope;
- запись audit log и независимый read-only post-commit verifier.

Текущий rule v8 решает будущую analytics-ошибку: `sourceKind=SERVICE` получает приоритет над
лексическими accessory/device rules, сохраняя более специфичные warranty/Care rules. Он не решает
payroll `PAID_REPAIR`: существующая подсказка распознаёт в основном слово «ремонт», но не все
варианты «замена ...». Пока зарплатный блок планируется переделывать, безопасный выбор — применить
только перечисленные исторические assignments и оставить prospective payroll automation отдельным
решением.

## Январский блокер: отсутствующие исходные продажи

Для `F000061`, `F000066`, `F000086`, `F000096` parent numbers уже подтверждены:
`B000975`, `B001238`, `B001181`, `B000304`. Эти продажи и их позиции отсутствуют в production.
Технические timestamps указывают на предшествующий период, но точные source business dates перед
mutation нужно получить заново.

Обычный backfill API принимает только диапазон дат и не умеет загрузить один exact sale. Для
четырёх relink выбран следующий безопасный путь:

1. Получить три декабрьских отчёта и провести полную сверку декабря.
2. По source business dates четырёх parent sales определить минимальный доказанный bounded период.
3. После отдельного разрешения синхронизировать этот период штатным backfill и повторить декабрьскую
   сверку.
4. Только после появления exact parent documents/items выполнить четыре январских relink.

Exact parent-sale/reference-only import отклонён для текущего release train: он добавил бы новый
recovery contract и создал частично заполненный месяц без полного evidence. Январский backfill не
поможет, а ручная вставка parent rows SQL запрещена. До получения декабрьских отчётов и отдельного
разрешения январские четыре relink остаются `BLOCKED`; январские payroll assignments можно выполнить
отдельно, но итоговый январский verdict останется открытым.

## Что должно войти в общий релиз

### Пакет R1 — LiveSklad return recovery

- explicit `MISSING_RETURN` и guarded existing-return relink modes;
- durable exact expectations и idempotency uniqueness per external ID + mode;
- поддержка доказанного zero-net item return;
- atomic validation before/after mutation и rollback transaction при mismatch;
- расширение current-employee guard для `F000244` и link-only `F000349` либо явное исключение
  второго из scope;
- OpenAPI/current docs/runbook и migration tests;
- последовательная цепочка новых Flyway migrations в порядке V49 → V50 → V51.

R1 не решает автоматическое обнаружение всех будущих cashless returns. Это recovery capability,
а не доказательство полного provider coverage.

### Пакет C1 — analytics classifier

- rule v8: source `SERVICE` раньше generic accessory/device lexical rules;
- regression cases для заднего стекла, дисплея, камеры и чистки устройства;
- сохранение специфичных warranty/Care classifications;
- bounded reconciliation включается только с exact allowlist и expected item count либо полностью
  выключена в release environment.

### Пакет P1 — manager feature permissions

Локальный commit `70a2d88` содержит migration V50, backend/API и frontend permission gates.
Migration сначала сохраняет прежний доступ существующим руководителям. Ограничивать конкретных
пользователей разрешено только после успешного запуска новых API и web и отдельной проверки доступа.
После такой настройки откат на старый runtime policy-несовместим; нужен compatible forward-fix или
временная деактивация затронутых учётных записей с security approval.

### Другие накопленные продуктовые пакеты

В integration candidate включены следующие reviewable commits и пакеты:

| Commit | Пакет | Статус для общего релиза |
|---|---|---|
| `4fbb6a4` | refresh current contracts | проверить вместе с фактическим runtime diff |
| `d99e3d9` | manager-facing warnings | повторить backend/frontend/OpenAPI/visual gates |
| `a1ce1fe` | Weekly Review classification-quality isolation | интегрировать с чистым Weekly Review candidate |
| `bf0e32e` | plan workspace redesign | повторить полный frontend и visual acceptance |
| `70a2d88` | manager feature permissions + V50 | security, migration и rollback review обязательны |
| `ed0c157`, `63303c8` | monthly plan и overview UI | полный frontend/visual gate |
| `86192a8` | guarded return recovery + V49/V51 | backend, migration, OpenAPI и staging recovery rehearsal |
| `db70f15` | analytics classifier v8 | category/STORE/SELLERS regression |
| `1ba04b8` | auth/payroll/quality/admin UI | frontend, role/error-state и visual gate |
| `6eff078` | исходный Weekly Review snapshot | заменяется более поздним `41e5dc8` при merge |
| `31a7a01` | сверки, runbook и release ledger | strict documentation/security review |
| `a1cd7b9` | production delayed-cash-return hotfix | обязательная база; не дропать при merge |
| `41e5dc8` | проверенный Weekly Review RC tip | интегрировать с общими recovery/auth/frontend пакетами |

После merge новый commit добавляется в release ledger только с owner, зависимостями, migration/API/UI
impact и verification evidence. Неизвестный или `misc` пакет снова блокирует freeze кандидата.

Weekly Review собирается по отдельному
[`weekly-review-release-manifest.md`](weekly-review-release-manifest.md): локальная граница P7-A
проверена, но полные P7-B/P7-C gates и решение по AI-path не считаются закрытыми автоматически.

### Рекомендуемая граница ближайшего релиза

Не следует бесконечно откладывать correction-enabler, ожидая все будущие commits. Для ближайшего
release train обязательны R1 и C1. Пакет P1 и уже накопленные warnings/plan/Weekly Review можно
добавить, только если каждый из них закрывает собственные gates до freeze; пакет, который не готов,
переходит в следующий release train и не блокирует исправление сверенных месяцев.

Текущая migration chain содержит V50 между recovery migrations, поэтому исключение P1 нельзя
делать простым удалением commit/file: нужна отдельная пересборка и review последовательности
migrations. Если P1 остаётся в candidate, его auth/security и rollback gates обязательны.

В момент freeze фиксируется один exact commit. Любой новый commit после freeze либо переносится в
следующий релиз, либо создаёт новый candidate и обнуляет результаты интегральных tests, images,
staging rehearsal и preflight прежнего candidate. Это позволяет продолжать разработку других
функций, не превращая уже проверенный релиз в движущуюся цель.

## Что не включать в release artifacts

- `.codex-prod-recovery/`, временные runners, sudoers fragments и SSH proxy helpers;
- исходные XLSX, raw API payload, cookies, tokens и secret files;
- production screenshots или иной материал с бизнес-/персональными данными;
- `frontend/visual-artifacts/`, build outputs и локальные caches;
- уже исполненные mutation scripts как автозапускаемые migration/startup hooks.

Санитизированные reusable scripts можно отдельно code-review, но exact production manifests и
idempotency keys создаются только перед операцией и не становятся частью образа.

## Что уже выполнено и запрещено повторять

Перед созданием любой recovery проверяется denylist historical evidence. Уже завершены:

- март: `F000148`;
- апрель: `F000180`, `F000217`;
- май: `F000229`, `F000230`, `F000247`, `F000250`;
- июльский пакет: `F000321`, `F000340`, `F000342`, `F000344`, `F000352`, `F000371`, `F000378`,
  `F000380`.

Также не повторяются уже применённые bounded classification corrections апреля, мая, июня и июля.
Повторное упоминание документа в source report не является разрешением на mutation. Preflight
должен обнаруживать уже существующий completed recovery и останавливаться.

## До deployment: обязательный checklist

### 1. Собрать чистый интегральный candidate

1. Взять подтверждённую production-базу из `project-state.md` в отдельном clean worktree.
2. Перенести только reviewable commits/patches из release ledger; сохранить логические границы.
3. Не копировать текущий dirty worktree целиком и не делать один «всё сразу» commit.
4. Зафиксировать freeze commit; всё добавленное после него уходит в следующий train или требует
   полного повторения всех candidate gates.
5. Проверить `git status --short`, untracked inventory, `git diff --check` и отсутствие secrets,
   data files, screenshots, build outputs и временных operations artifacts.
6. Убедиться, что migration numbers уникальны и packaged expected schema соответствует последней
   включённой migration.

Результат на интегральном candidate: выполнено. Истории production-base и Weekly Review RC
сохранены, изменения разделены на логические commits, unknown/untracked и conflict markers не
обнаружены, migration numbers уникальны, candidate release metadata выровнены с packaged target.

### 2. Закрыть code gaps R1/C1

1. Добавить exact current-employee expectation или эквивалентный fail-closed guard для
   `F000244`/`F000349`.
2. Тестировать три состояния отдельно: employee null; employee заполнен неверным processing
   employee; employee уже равен original employee, но links null.
3. Проверить zero-net constraint и сервисный guard на amount `0.00`, positive positions и exact
   cost.
4. Проверить idempotency, duplicate mode, mismatch rollback, deleted/wrong-store/wrong-parent и
   concurrent recovery paths.
5. Проверить rule v8 и отсутствие unintended reclassification warranty/Care и product rows.

Результат на интегральном candidate: выполнено. Guard принимает отдельный immutable expectation,
fail-closed проверяет source и target facts и покрыт integration/webhook tests для null,
wrong-current и already-correct employee вариантов. Полный backend gate включая classification,
OpenAPI, security и concurrency regression проходит.

### 3. Подготовить исторические scripts/manifests

1. Создать три bounded classification scripts: январь, февраль, март.
2. Создать sanitised per-document execution manifests для шести missing returns, 15 KPI relinks и,
   если approved, `F000349`.
3. Для каждого target сохранить ссылку на месячное evidence, но получать fresh provider/DB facts
   перед apply.
4. Выбрать январскую parent-sale strategy; без неё январский relink package не готов.
5. Проверить отсутствие approved/paid payroll, finalized reports и conflicting jobs в каждом
   месяце непосредственно перед mutation.

Результат: `PARTIAL`.

- три bounded classification scripts и sanitised manifests созданы; режимы
  `--preflight/--apply/--verify`, exact source guards, global/operation locks, locked
  payroll/report и queue guards, одна транзакция на месяц и immutable audit реализованы;
- manifests повторно сопоставлены с fresh production read-only audits 2026-09-15: январь `4`
  позиции / 27,000 ₽ / cost 11,200 ₽, февраль `2` / 6,500 ₽ / 3,500 ₽, март `9` / 41,790 ₽ /
  24,300 ₽; product-level scope не содержит дополнительных строк;
- изолированный PostgreSQL 16/V51 rehearsal прошёл preflight, mismatch rollback, apply, independent
  verify и repeat-apply fail-closed на синтетических данных;
- выбран полный декабрьский reconciliation + bounded sync, а не новый reference-only import;
- sanitised execution manifests для 22 return operations ещё не созданы, декабрьские отчёты не
  получены, а classification tooling ещё должен пройти новый CI и production-like rehearsal на
  fresh backup.

Ни один production write не выполнялся. Fresh apply-preflight допустим только после успешного
deployment и нового отдельного разрешения exact targets.

### 4. Выполнить локальные и CI gates на exact candidate

Минимальный набор:

```bash
python3 -m unittest scripts/tests/test_documentation_check.py
python3 scripts/check-documentation.py --strict
./gradlew :backend:check --no-daemon
cd frontend
npm ci
npm run check
```

Для каждого material frontend change дополнительно выполнить `npm run visual:local` только против
локальных frontend/backend и вручную просмотреть desktop/tablet/mobile artifacts затронутых routes.
OpenAPI baseline/current/generated types проверяются на чистом candidate, а не переносятся вручную
из mixed worktree.

После локальных проверок обязательны green CI, reproducible build и immutable backend/web images,
чьи revisions соответствуют exact reviewed commit.

Локальный результат на интегральном candidate:

- backend Java 21: полный `check`, `1138` tests, `0` failures, `0` errors, Checkstyle/OpenAPI/
  supply-chain/operator/security gates проходят;
- frontend Node 22: contracts check, lint без warnings, `262` tests и production build проходят;
- local-only visual gate: `15` route/scenario-наборов × `3` viewport, representative artifacts
  затронутых областей просмотрены вручную; найденные fixture/payroll defects исправлены и
  перепроверены;
- deploy release-safety, security hardening и Weekly Review AI release-safety scripts проходят;
- documentation gate после обновления manifests: `25` unit tests и strict inventory validation
  для `413` rows без warnings.

CI, reproducible immutable image publication и проверка image revisions остаются внешними
stop-условиями и локальным результатом не закрываются.

PR [#1](https://github.com/pavelchervonenko/analytical-platform-for-store/pull/1) создан для
`codex/store-release-rc`. Пользователь сообщил о зелёном CI на commit `0db4484`; доступ к GitHub API
из локальной execution-среды отсутствовал, поэтому это external user-reported evidence. После
добавления correction tooling commit кандидата изменится, и полный CI обязан пройти повторно до
review/merge.

### 5. Выполнить integration/conflict gate

Успешные тесты отдельных commits не доказывают, что они безопасны вместе. На frozen candidate
обязательно строится conflict matrix со следующими пересечениями:

| Контуры | Что может конфликтовать | Обязательное доказательство |
|---|---|---|
| LiveSklad recovery ↔ webhook/sync/backfill | двойная обработка, потеря links, удаление return, гонка одного external ID | concurrent/integration tests, очередь пуста перед mutation, один writer на target |
| V49 ↔ V50 ↔ V51 | порядок Flyway, constraint/ACL несовместимость, старый runtime после migration | полный upgrade rehearsal, schema/ACL tests, rollback compatibility verdict |
| Classification ↔ reports/Weekly Review/payroll | analytics и payroll смешаны, persisted snapshots меняют смысл | regression на STORE/SELLERS, category/payroll/attach и snapshot quality |
| Manager permissions ↔ plan/shifts/payroll/admin UI | backend разрешает то, что frontend скрывает, или наоборот | role/feature matrix API + UI, direct-URL denial и admin update tests |
| Weekly Review ↔ warnings/quality | новая ошибка маскируется fallback или неверно блокирует READY | persisted fixture, contract and failure-state tests |
| Plan UI ↔ shared date/workspace/query state | смена магазина/периода переносит чужие данные или ломает URL state | cross-store/period integration tests и visual checks |
| OpenAPI ↔ generated frontend client | несовместимая DTO/enum/required field | compatibility check и generation только из candidate contract |
| API ↔ worker ↔ web versions | разные образы ожидают разные schema/contracts | exact paired images, startup order и smoke после каждого service boundary |

Отдельно выполняется server-safety rehearsal на production-like staging:

- измерить время migration и startup, максимальные DB connections, CPU, memory и свободное место;
- проверить, что migration не держит неожиданно долгие locks на рабочих таблицах;
- поднять API и worker с production-like limits и дождаться устойчивого health, а не одного
  успешного probe;
- проверить backlog/throughput очередей и отсутствие restart loop, OOM, disk pressure и новых 5xx;
- подтвердить одну API replica, пока действует process-local session limitation из
  `project-state.md`;
- выполнить smoke критических read paths и прав доступа до запуска data corrections;
- проверить controlled failure: migration mismatch, недоступная БД, worker failure и безопасную
  остановку без blind retry.

Любой необъяснённый конфликт, flaky integration test, нехватка server headroom, restart/OOM,
длительный lock, рост 5xx или несовместимость rollback переводит релиз в `NO-GO`. Такой пакет
исправляется или переносится в следующий release train; guard не обходится ручным запуском.

Локальная часть conflict gate выполнена: объединённый backend/frontend candidate и contract gates
проходят, миграционная цепочка и schema/ACL safety покрыты тестами, UI role/feature и failure-state
сценарии входят в общий regression. Production-like server-safety rehearsal, измерение headroom,
locks, очередей и exact previous-runtime rollback остаются незакрытыми.

### 6. Rehearsal и production readiness

- staging upgrade через всю включённую migration chain;
- staging rehearsal `MISSING_RETURN` positive-net и zero-net;
- staging rehearsal relink для null, wrong-current и already-correct employee variants;
- проверка mismatch rollback и повторного idempotency key;
- application rollback compatibility review после V50;
- fresh backup checkpoint и доказанный isolated restore;
- fresh production-read-only check exact host/DB, runtime identity, Flyway history, health, queues,
  conflicting jobs, secret-file ownership/mode и image provenance;
- назначенные operator, observer, backup owner и forward-fix owner.

Пока хотя бы один пункт не закрыт, общий статус — `NO-GO`.

## Как выполнять deployment

Deployment выполняется один раз штатным путём из
[`production-deployment.md`](../runbooks/production-deployment.md), после отдельного подтверждения
показанного exact release plan. Перед ним оператор запускает штатный production preflight и
сохраняет только sanitised evidence.

Единственная штатная команда mutation:

```bash
sudo /opt/store-analytics/deploy/bin/deploy.sh /etc/store-analytics/release.env
```

Скрипт сам должен выполнить порядок:

1. проверить immutable image provenance и packaged migration target;
2. остановить worker, затем API;
3. применить Flyway migrations;
4. восстановить least-privilege database ACL;
5. поднять API, затем worker, затем web;
6. выполнить smoke и зафиксировать release state.

Blind retry запрещён. Если migration завершилась неясно, сначала read-only исследуются Flyway,
state marker и containers. Application rollback не откатывает БД; при несовместимости нужен
reviewed forward-fix или isolated restore по runbook.

## После deployment: очередь данных

Сначала подтверждаются exact runtime/images, health, migration history, API contract и отсутствие
failed/active conflicting jobs. Затем данные меняются в следующем порядке.

### Волна 1 — февраль

1. Пять `MISSING_RETURN` по одному; после каждого terminal `PROCESSED` — document/item/employee и
   aggregate post-check.
2. Шесть relink по одному с exact original document/item/employee guards.
3. Одна февральская payroll transaction для `A000201`, `A000210`.
4. Полная сверка февраля; переход дальше только при нулевой либо доказанно объяснённой разнице.

### Волна 2 — март

1. Zero-net `F000175` как один `MISSING_RETURN`; `F000148` не повторять.
2. Четыре relink по одному.
3. Одна мартовская transaction: 3 analytics и 8 payroll fields на девяти positions.
4. Полная сверка марта.

### Волна 3 — май и июльская целостность

1. `F000244` relink только новым current-employee-aware guard.
2. Полная сверка мая, включая отдельные KPI/attach двух затронутых сотрудников.
3. Если approved, link-only `F000349`; проверить неизменность всех сумм, quantities, employee и
   attach-rate и появление exact original links.

### Волна 4 — январь

1. Fresh source audit exact parent business dates.
2. Выполнить отдельно approved parent-sale strategy и проверить загруженные sales/items.
3. Четыре relink по одному.
4. Одна январская payroll transaction.
5. Полная сверка января; если выбран полный декабрьский sync — отдельная сверка декабря.

Каждая волна имеет собственное разрешение на exact targets. Ошибка одного документа останавливает
волну; следующий idempotency key до диагностики не создаётся. Между волнами можно выпускать другой
код только новым reviewed deployment, после чего fresh preflight повторяется.

## Что остаётся отдельным product/engineering backlog

Следующие ограничения не должны потеряться, но не маскируются под уже готовые исправления:

- автоматическое discovery cashless sale returns: recovery API исправляет известные документы, но
  не гарантирует обнаружение будущих;
- prospective payroll classifier для «замена ...» и других ремонтов: отложен до переделки
  зарплатного блока; до этого нужен monthly classification review;
- order payments в `sales_payments`: сейчас order merchandise и движение денег сравниваются
  раздельно;
- source `ZERO_UNEXPECTED`: пользователь разрешил нулевую себестоимость; это quality limit, а не
  межсистемная дельта;
- shared session registry: пока он не реализован, production topology следует ограничению из
  `project-state.md`.

## Superseded pre-deploy NO-GO evidence

Следующий срез описывает состояние до публикации images, rehearsal и production authorization.
Он сохранён как история принятия решения и заменён фактическим результатом deployment ниже.

На 2026-09-15 общий локальный candidate собран и проверен:

- исходный dirty scope разделён на логические commits; Weekly Review RC влит в
  `codex/store-release-rc`, обе исходные истории сохранены, unknown/untracked и conflict markers
  отсутствуют;
- guarded relink покрывает nullable, wrong-current и already-correct current employee варианты,
  включая техническую форму для `F000244`/`F000349`;
- OpenAPI v12/current/backend-generated и frontend transport types согласованы; новые recovery поля
  совместимы и не required;
- полный backend `check` на Java 21 проходит: `1138` tests, `0` failures, `0` errors, включая
  Checkstyle, OpenAPI, supply-chain, operator, security и deploy safety;
- frontend на Node 22 проходит contracts, lint без warnings, `262` tests и production build;
- local-only visual gate покрывает `15` route/scenario-наборов на desktop/tablet/mobile; найденные
  при интеграции duplicate fixture и payroll blocked-readiness skeleton исправлены и повторно
  проверены;
- deploy release-safety, security hardening и Weekly Review AI release-safety scripts проходят.
- documentation unit/strict gate после обновления manifests проходит: `25` tests, `413` inventory
  rows, `0` warnings.

Общий verdict остаётся `NO-GO`, потому что не закрыты обязательные внешние и data-correction gates:

- CI для предыдущего PR head `0db4484` подтверждён пользователем, но новый correction commit ещё не
  опубликован и не прошёл повторный CI; exact paired immutable images отсутствуют;
- production-like staging upgrade/recovery rehearsal, server headroom/locks/queues и exact
  previous-runtime rollback не доказаны;
- отсутствуют fresh production read-only preflight, backup checkpoint/isolated restore evidence и
  operations/security sign-off;
- return execution manifests ещё не подготовлены; classification scripts готовы локально, но не
  прошли новый PR CI и production-like fresh-backup rehearsal;
- для выбранной январской стратегии ещё нужны три декабрьских отчёта, сверка и отдельное разрешение
  bounded sync.

Это означает, что локальный candidate готов к внешнему release pipeline и review, но production
deployment пока не разрешён.

## Deployment result and remaining correction gate

Code release `v0.1.0-pilot.32` фактически развернут 2026-09-15. Paired immutable images, fresh
backup/restore, V48-to-V51 production-like rehearsal, final production preflight, migration,
service health, public smoke и два независимых post-deploy observation прошли. Транзиентный
внешний web-health false negative не привёл к blind retry и закрыт отдельным read-only verifier.
Полное sanitised evidence находится в
[`v0.1.0-pilot.32 production verification`](../history/releases/2026/09/v0.1.0-pilot.32-production-verification.md).

Deployment не выполнял 22 return operations, три classification transactions, январскую загрузку
parent sales, backfill или resynchronization. Поэтому этот общий manifest остаётся `draft` до
выполнения или явного отказа от каждого correction target и повторной полной сверки затронутых
месяцев. Следующий разрешённый этап — подготовка sanitised exact-target return manifests и новый
read-only preflight для первой отдельно согласованной волны, а не повторный deployment.

## Критерий готовности к показу exact production plan

Release candidate готов только когда одновременно выполнены все условия:

- release ledger закрыт, unknown/misc files нет;
- clean worktree и reviewed commit/tag;
- migrations/OpenAPI/docs согласованы;
- full backend/frontend/visual/documentation checks и CI зелёные;
- conflict matrix закрыта на общем candidate, server-safety rehearsal подтверждает достаточный
  запас ресурсов, отсутствие опасных locks/restarts и совместимость API/worker/web;
- R1 покрывает каждый approved return target либо target явно исключён;
- classification scripts/manifests проходят dry-run на fresh facts;
- январская parent strategy выбрана либо январь явно вынесен в последующую волну;
- staging recovery/migration rehearsal пройден;
- backup/restore и production-read-only preflight fresh;
- владельцу показаны exact target, impact, downtime, rollback boundary и post-deploy queue.

Только после этого запрашивается отдельное согласование deployment. После него отдельно
согласуются точечные data mutations по этому manifest.

## Критерий закрытия

Manifest закрывается после общего deployment, выполнения или явного отказа от каждого correction
target, повторной полной сверки затронутых месяцев и создания immutable release/post-correction
evidence. Фактические runtime identities после deployment записываются только в новый historical
release record и затем обновляются в `project-state.md`.

## Результат извлечения

Code-release часть извлечена в
[`v0.1.0-pilot.32 production verification`](../history/releases/2026/09/v0.1.0-pilot.32-production-verification.md).
Окончательное закрытие manifest ожидает отдельные data-correction approvals, выполнение либо
явный отказ от targets и post-correction monthly reconciliations.
