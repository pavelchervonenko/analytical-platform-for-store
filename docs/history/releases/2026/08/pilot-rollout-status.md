---
doc_schema: 1
doc_type: evidence
status: historical
owner: operations
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved legacy evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/current/project-state.md"
original_content_sha256: abc4264372bc15c7df6282250e57416d1606d9cce3298930316b1054f8a74f4b
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/project-state.md`.

# Pilot rollout status

> **Historical status — do not use as a runbook or current production source.** The release/schema
> values below are obsolete. Current verified state: [current/project-state.md](../../../../current/project-state.md).
> The listed recovery of eight July returns is complete and reconciled; **do not rerun it**.

Последнее обновление: 2026-08-24.

Это исторический status-снимок, а не текущий production source. Повторяемая процедура на момент
снимка: [production-deployment-runbook.md](../../../../archive/legacy-contracts/production-deployment-runbook.md).

## Контур на дату исторического снимка

- public origin: `https://store-analytics.net`;
- release: `v0.1.0-pilot.22`, commit `2e8f9c2`;
- Flyway schema: `V44`;
- healthy topology: `web`, `backend-api`, `backend-worker`;
- managed PostgreSQL 16 по private network и TLS `verify-full`;
- Caddy/HTTPS, HSTS и same-origin SPA/API;
- nightly encrypted logical backup timer и независимый health monitor;
- SSH key-only, root login/password auth disabled, UFW ограничивает ingress.

Фактические IP, fingerprints и инфраструктурные параметры остаются в
[deployment-and-operations.md](../../../../archive/legacy-contracts/deployment-and-operations.md).

## Данные и синхронизация

- плановая синхронизация включена;
- incremental overlap: три дня;
- июль и август были загружены для сверки, но полнота каждого периода определяется runtime
  `dataThroughDate`/quality, а не этой записью;
- августовская сверка выполняется по завершенным дням;
- июльский аудит обнаружил восемь подтвержденных возвратов, отсутствующих в приложении;
- разница июльской выручки: `716 750 ₽`;
- **errata:** восстановление восьми документов завершено и сверено с CRM; **do not rerun**.

Аудит: [REVENUE_RECONCILIATION_AUDIT_2026-08-23_JULY.md](../../../audits/2026/08/REVENUE_RECONCILIATION_AUDIT_2026-08-23_JULY.md).

## Возвратные webhook

| Компонент | Production |
| --- | --- |
| Receiver | enabled |
| Sale-return worker | enabled |
| Order-return worker | disabled до canary |
| Sale/order secrets | provisioned отдельно |
| Durable inbox/retry | schema V42–V43 |
| Validated manual recovery | schema V44 |

Webhook позволяют ловить будущие события и диагностировать расхождения, но не заменяют сверку
исторических периодов. Order worker включается только после настоящего события и проверки
`data.id`.

## ИИ

- production default: prompt `v4`, content schema `2`;
- `v21/schema3`: `26/26` semantic cases, `4.8/5`;
- новая схема не активирована автоматически и требует отдельного rollout.

## Текущий релиз-кандидат

Ветка `codex/livesklad-daily-webhook-protection` содержит пять проверенных продуктовых commit и один документационный commit поверх production:

- semantic evaluation `v21/schema3`;
- уточнение планов/смен;
- иерархия структуры продаж и прозрачный attach benchmark;
- исправление evidence/limited UX ИИ;
- июльский reconciliation audit.

Кандидат не отправлен и не развернут. Backend runtime и migrations не меняются.
[Полная сводка](RELEASE_CANDIDATE_2026-08-24.md).

## Открытые gates

- [ ] отправить/развернуть текущий UI-кандидат после final fetch/diff;
- [x] восемь июльских возвратов восстановлены validated API — **do not rerun**;
- [x] июльская CRM-сверка завершена с точным совпадением;
- [ ] получить настоящий `ORDER_RETURN` и провести canary order worker;
- [ ] продолжить помесячный backfill с независимой сверкой обоих магазинов;
- [ ] закрыть data-quality по сменам/классификации только подтвержденными данными;
- [ ] провести отдельный production rollout `v21/schema3`;
- [ ] поддерживать актуальное restore evidence и проверяемые RPO/RTO.

Временный `.codex-prod-recovery/` не является release artifact и остается вне Git.
