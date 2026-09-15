---
doc_schema: 1
doc_type: evidence
status: historical
owner: project
audience:
  - developer
  - product
snapshot_date: 2026-09-14
verdict: PASS_WITH_LIMITS
verdict_scope: "Local weekly-review prerelease: read-only LiveSklad sync for 2026-08-31..2026-09-13, deterministic snapshots, authenticated read path and responsive visual review."
source_of_truth:
  - docs/current/ai/weekly-review.md
  - docs/current/integrations/livesklad/synchronization.md
  - docs/maintenance/weekly-review-implementation-plan.md
verification_sources:
  - sanitized local PostgreSQL coverage queries
  - authenticated local backend API on loopback
  - local Playwright desktop, tablet and mobile run
  - full backend, frontend and documentation verification commands
required_reviewers:
  - product
  - frontend
  - backend
---

# Local prerelease verification: weekly review

## Verdict

`PASS_WITH_LIMITS`. The exact fourteen-day source interval was synchronized into an isolated local
database without gaps for sales, returns and issued-order positions. Two deterministic snapshots
for the completed week were generated and read through the normal authenticated local API. Both
real-data reports are `PARTIAL`; no fresh real-data `READY` report was available in the authorized
scope, so `READY` remains verified by deterministic fixtures rather than this runtime observation.

Production and staging were not contacted or changed. LiveSklad access was read-only and limited to
the two already configured stores and the approved date interval. No credentials, personal names,
financial values or screenshots are preserved in this record.

## Source coverage

The durable backfill job completed with `SUCCESS`. Successful or partially successful child runs
formed one continuous union from the local start of 31 August through the exclusive end of
14 September for each required scope:

| Scope | Boundary coverage | Internal gaps | Partial child runs |
|---|---|---:|---:|
| Sales | complete | 0 | 0 |
| Returns | complete | 0 | 3 |
| Issued-order positions | complete | 0 | 0 |

The partial return runs represent unresolved source records rather than a missing time interval.
They correctly survived as addressable quality limitations in both reports instead of being hidden
by the terminal job status.

## Sanitized snapshot observations

Both snapshots cover `2026-09-07..2026-09-13` and compare it with
`2026-08-31..2026-09-06`.

| Observation | Store A | Store B |
|---|---:|---:|
| Report state | `PARTIAL` | `PARTIAL` |
| Blocking issues | 0 | 0 |
| Warnings | 3 | 4 |
| Ready / limited core metrics | 1 / 3 | 1 / 3 |
| Complete / partial coverage sources | 2 / 1 | 2 / 1 |
| Factors / root actions | 3 / 1 | 0 / 0 |
| Employees participating in workload benchmark | 0 | 0 |

Observed limitation codes were limited to unattributed returns, sales/returns consistency,
unexpected zero cost and, for one store, unclassified products. Missing or incomplete shifts did
not create a page-level workload warning, employee priority or benchmark comparison.

## Defects found and corrected

The first live visual review exposed two release-relevant inconsistencies:

1. The team limitation trigger and the link to all employees had no shared layout container and
   visually touched each other. They now use one wrapping action row with an explicit gap.
2. The deterministic outcome considered only net revenue and gross profit. A material, reliable
   average-sale change could therefore coexist with a neutral «substantially unchanged» outcome.
   The outcome now considers every material `READY` core KPI, includes their evidence references,
   and uses snapshot policy `weekly-snapshot-v11`.

In addition, `PARTIAL` without a root action no longer claims that no check is required. It tells
the manager that a priority was not formed from the available data and points them to the report
limitations. New backend and frontend regression tests cover both cases.

New immutable revision-2 snapshots were generated after the fixes. The affected real-data outcome
changed from neutral to negative while remaining `LIMITED`, proving that the new policy corrected
the contradiction without promoting limited metrics into the direction calculation.

## Verification

- Full `:backend:check` passed in the local Java 21 verifier with no test failures, including
  Checkstyle, OpenAPI compatibility, Gradle supply-chain integrity and operator-script security.
- Frontend contract generation check, lint, `53` test files / `245` tests and production build
  passed.
- The final live visual run passed `6/6`: two stores across desktop, tablet and mobile. Full pages
  and the detail panel were inspected locally; no overflow, runtime error, HTTP `5xx`, clipped
  action row or contradictory outcome remained. Screenshots stay ignored and are not evidence
  attachments.
- Documentation unit tests passed `25/25`; strict integrity passed with `406` inventory rows and
  zero baseline warnings against a temporary index containing the pending documentation set.
  `git diff --check` passed. The ordinary strict command still reports the pre-existing untracked
  documentation set until those files are added to the real Git index.

## Follow-up: P6.1 UI calibration

После согласованного пользовательского review интерфейс был повторно откалиброван без нового
обращения к LiveSklad и без изменения production/staging. Связанный с главным действием фактор
больше не повторяется отдельной карточкой, действие по возвратам описывает проверку чеков и причин,
desktop decision cards выровнены без фиксированной высоты, отдельная серая командная сводка удалена,
а mobile показывает дополнительные факторы и сотрудников по запросу в том же DOM.

Неполные смены остаются локальным ограничением time-оценки. У сотрудника, уже выбранного по
независимому sales-сигналу, интерфейс показывает нейтральную подпись; она не создаёт новый attention,
action или page-level warning.

Повторная верификация после калибровки:

- frontend contract, lint, `53` test files / `251` tests и production build прошли;
- полный `:backend:check` прошёл на Java 21: `1121` tests, без failures, errors и skipped;
- пять fixture-состояний прошли `15/15` на desktop/tablet/mobile, включая раскрытые состояния;
- два существующих `PARTIAL` snapshots прошли authenticated local live visual `6/6`; новые snapshots
  и новые source sync jobs не создавались;
- ручной UI-review выполнен по локальным снимкам; персональные имена, финансовые значения и сами
  screenshots в evidence не сохранены;
- documentation unit suite прошёл `25/25`, strict integrity — `408` inventory rows с нулём
  baseline warnings через временный индекс, `git diff --check` прошёл. Реальный Git index не
  изменялся; обычный strict продолжает видеть общий набор зарегистрированных untracked-документов.

Новая deterministic policy `weekly-snapshot-v12` применяется к будущим snapshots из-за изменения
persisted wording. Уже сохранённые revisions не переписывались, поэтому этот follow-up подтверждает
новый frontend read path на реальных `PARTIAL` payloads и будущую policy — contract/backend tests,
но не выдаёт старый snapshot за заново сгенерированный v12.

## Follow-up: P7-C real-data v12 gate, 2026-09-15

После отдельного разрешения проверено сохранённое локальное покрытие двух магазинов за
`2026-08-31..2026-09-13`. Оно уже было непрерывным для всех четырнадцати дней, поэтому новый
внешний запрос LiveSklad не выполнялся. Через штатный local authenticated admin API для обоих
магазинов созданы revision 3 с `snapshotPolicy=weekly-snapshot-v12`.

Обезличенная проверка БД и API показала:

- оба новых отчёта — `PARTIAL`, blocking issues отсутствуют;
- у каждого отчёта `2` complete / `1` partial coverage source и `1` ready / `3` limited core
  metrics;
- прежние v10/v11 revisions сохранены; у каждого магазина три разных content hash, две корректные
  supersedes-связи и ни одной недействительной связи;
- неполные смены не стали самостоятельным page-level warning, employee priority или workload
  benchmark;
- ограничения остались адресными: unattributed returns, sales/returns consistency и unexpected
  zero cost; один отчёт дополнительно ограничен unclassified products.

P7-C остановлен по предусмотренному условию: естественного реального `READY` в разрешённой выборке
нет. Fixture `READY` не считается заменой. Live visual v12 тоже не объявляется выполненным:
Windows-local web/API были доступны на loopback, но WSL browser не мог достичь этого адреса, а
Docker Desktop завершал TLS-ошибкой загрузку browser runtime из MCR и двух Alpine mirrors. Ранее
пройденные `15/15` fixture captures и `6/6` live v11 сохраняют регрессионную ценность, но не являются
v12 live evidence.

Readiness временного стенда возвращал `DOWN` по ожидаемой защите schema boundary: reused local DB
уже находилась на `V49`, а RC-образ содержит migration range до `V48`. Функциональный API работал,
но стенд не признаётся release-equivalent. Схема не откатывалась, readiness не ослаблялся,
production/staging не затрагивались. Verdict P7-C: `STOP`; production rollout не разрешён.

## Follow-up: P7-C v13 business-rule correction, 2026-09-15

Владелец продукта уточнил два правила: отсутствие доступной исходной продажи/позиции у части
возвратов и нулевая себестоимость являются нормальными состояниями. Кандидат был исправлен
адресно: возвраты сохраняются в результате магазина, ноль участвует в расчёте прибыли, а
действительно отсутствующая себестоимость и остальные проблемы согласованности сохраняют прежнее
fail-closed поведение.

Повторная проверка выполнена без нового LiveSklad read на изолированной V48-копии уже разрешённых
локальных данных. Штатный authenticated admin API создал revision 4 обоих магазинов с policy
`weekly-metrics-v7` / `weekly-snapshot-v13` / `weekly-quality-v7`; revisions 1–3 не
переписывались. Обезличенный результат:

- required coverage продаж и возвратов у обоих отчётов — `COMPLETE`;
- все четыре core KPI каждого отчёта имеют состояние `READY`;
- согласованные нормальные случаи отсутствуют в root limitations;
- optional employee attribution coverage остаётся `PARTIAL` и объясняется нейтрально внутри
  команды, не создавая самостоятельный page-level warning;
- оба report state остаются естественными `PARTIAL`: один из-за employee sales sufficiency, другой
  из-за `PRODUCTS_UNCLASSIFIED`.

Business facts не исправлялись ради искусственного статуса. Поэтому семантика v13 подтверждена,
но отсутствие natural real-data `READY` сохраняет P7-C release verdict `STOP`. Production и
staging не использовались; финансовые значения, персональные имена и business-data screenshots в
evidence не сохранялись.

Feature-closeout после этой коррекции подтвердил `44/44` targeted backend checks, включая `4/4`
`StoreKpiIntegrationTest` с реальным локальным PostgreSQL Testcontainer и `skipped=0`. Полный
frontend check прошёл contracts, lint, `199/199` tests и production build. `visual:local` прошёл на
desktop/tablet/mobile, а READY/PARTIAL captures просмотрены вручную; исправлено мобильное
выравнивание заголовка. Скриншоты не добавлялись в evidence. Supply-chain, checkstyle,
shell-security и OpenAPI compatibility также прошли; общий production-equivalent rerun оставлен
отдельным преддеплойным gate по решению владельца продукта.

## Remaining limits

- Fresh real data did not yield a `READY` report. A production decision must not reinterpret the
  fixture-only `READY` result as runtime evidence.
- AI generation was disabled; this run proves the deterministic path and the optional-AI fallback,
  not a provider call or published AI enrichment.
- LiveSklad rate limits made the backfill repeatedly revisit already persisted phases. Idempotency
  held, but checkpointing after each completed phase should be improved separately before larger
  historical imports.
- This local evidence does not authorize or prove a production rollout. P7 remains a separate,
  explicitly approved operation.

## Follow-up: P7-D–P7-G release-gate assessment, 2026-09-15

После запроса довести план до production-ready решения продолжены только локальные и read-only
действия. Production и staging не запрашивались и не изменялись; новых LiveSklad reads, snapshots
или business-data screenshots не создавалось.

- P7-D зафиксирован как `DEFERRED`. AI release-safety, targeted backend AI tests, offline shadow
  plan и локальный eval прошли; paid provider request и staging canary не выполнялись. Выпуск может
  рассматриваться только с выключенными AI planner/generation/worker.
- P7-E получил `PASS_WITH_LIMITS`: владелец продукта принял исправленный интерфейс, а пять fixture-
  сценариев повторно прошли desktop/tablet/mobile (`15/15`) и ручной просмотр. Отдельного
  таймированного manager study без подсказок не проводилось.
- Для P7-F создан отдельный локальный network/DB target. Candidate migration дошла до ожидаемой
  boundary, API и worker получили `UP`, web отдал приложение и успешно проксировал readiness.
  Backend/web OCI revision совпали с reviewed runtime commit.
- Локальный custom dump был зашифрован и расшифрован с неизменным checksum, восстановлен в новую
  PostgreSQL DB; Flyway history, schema inventory и выбранные технические агрегаты совпали, API и
  worker поднялись поверх restore. Источник был пустым rehearsal target, не production backup;
  поэтому это evidence механики, а не доказательство production RPO/RTO.
- Exact previous production image отсутствовал локально, registry pull завершился timeout. Staging,
  production deploy path, двухшаговый rollout, application rollback на exact compatible pair,
  ACL/HTTPS/Prometheus и реальные queues не подтверждены. Deploy release-safety test прошёл и
  сохранил fail-closed boundary, но P7-F verdict остаётся `STOP`.
- P7-G остановлен до production обращения: immutable published coordinates, exact host/release-env,
  fresh live preflight, production backup checkpoint и required sign-off отсутствуют. P7-C также не
  содержит natural real-data `READY`.

P7-H и P7-I не начаты. Перед production write требуется закрыть stop-условия и получить новое
точное подтверждение показанного release plan; post-release observation нельзя выполнить или
задокументировать заранее.
