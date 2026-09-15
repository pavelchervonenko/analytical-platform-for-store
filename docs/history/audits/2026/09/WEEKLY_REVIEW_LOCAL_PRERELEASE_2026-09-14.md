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
