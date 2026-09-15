---
doc_schema: 1
doc_type: runbook
status: draft
owner: operations
audience:
  - operator
  - developer
last_verified: 2026-09-15
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - staging
  - production-read-only
operation_type: destructive
environments:
  - staging
  - production
risk_level: high
requirement_sources:
  - docs/maintenance/production-release-and-reconciliation-corrections-manifest.md
implementation_sources:
  - scripts/reconciliation/run-bounded-classification-correction.sh
  - scripts/reconciliation/sql/bounded-classification-correction.sql
verification_sources:
  - scripts/tests/bounded-classification-correction-test.sh
  - scripts/tests/test_bounded_classification_manifests.py
runtime_evidence:
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-01_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-02_MAGAZIN.md
  - docs/history/audits/2026/09/LIVESKLAD_PRODUCTION_RECONCILIATION_2026-03_MAGAZIN.md
source_of_truth:
  - scripts/reconciliation/run-bounded-classification-correction.sh
  - scripts/reconciliation/sql/bounded-classification-correction.sql
  - scripts/reconciliation/manifests/2026-01-magazin.json
  - scripts/reconciliation/manifests/2026-02-magazin.json
  - scripts/reconciliation/manifests/2026-03-magazin.json
verification_evidence:
  - level: static
    scope: exact manifests, SQL guards, synthetic V51 preflight/apply/verify and repeat-apply stop
    verified_at: 2026-09-15
    evidence: scripts/tests/bounded-classification-correction-test.sh
required_reviewers:
  - operations
  - backend
  - security-privacy
  - product
review_triggers:
  - classification-manifest-change
  - database-schema-change
  - correction-runner-change
supersedes: []
superseded_by: null
---

# Bounded historical classification corrections

## Назначение и статус

Процедура исправляет только доказанные исторические категории января, февраля и марта 2026 года
для магазина `МАГАЗИН`. Она не является Flyway migration, backfill, startup hook или правилом
будущей payroll-классификации. До завершения release-equivalent rehearsal, production preflight и
отдельного согласования exact data mutation статус остаётся `draft`.

Deployment приложения сам по себе не запускает correction. `install-host.sh` только устанавливает
reviewed runner, SQL и manifests в root-owned каталог
`/opt/store-analytics/deploy/corrections/`.

## Зафиксированный scope

| Manifest | Позиции | Analytics transitions | Payroll transitions | Product assignments |
|---|---:|---:|---:|---:|
| `2026-01-magazin.json` | 4 / 27,000 ₽ / cost 11,200 ₽ | 0 | 4 | 3 payroll |
| `2026-02-magazin.json` | 2 / 6,500 ₽ / cost 3,500 ₽ | 0 | 2 | 2 payroll |
| `2026-03-magazin.json` | 9 / 41,790 ₽ / cost 24,300 ₽ | 3 | 9 effective payroll transitions | 2 analytics + 4 payroll |

Мартовские девять payroll transitions включают восемь explicit `PAID_REPAIR` assignments и
переход чистки `A000261` из `ACCESSORY` в `SERVICE`, который следует из исправленной аналитической
категории без отдельного payroll override. Количество product assignments меньше количества
позиций, когда один source product повторяется в нескольких документах.

Manifests не содержат ФИО или source employee IDs. Employee guard хранится как односторонняя
операционно-солёная ссылка и пересчитывается внутри БД из exact employee исходного документа.
Raw production audits остаются только локальными временными файлами и не коммитятся.

## Обязательные условия

До любого `--apply` одновременно должны выполняться условия:

1. exact backend/web release уже развернут и Flyway имеет версию `51`;
2. новый production read-only audit совпал с manifest;
3. immutable manifest SHA-256 и `RELEASE_COMMIT` внесены в change record;
4. нет `APPROVED`/`PAID` payroll run и `APPROVED`/`ARCHIVED`/`FINALIZED` report на период;
5. worker остановлен, API не принимает административные writes, очереди sync/recovery/report
   пусты, новый producer не может поставить работу во время операции;
6. backup checkpoint и isolated restore evidence уже подтверждены;
7. назначены operator, observer и владелец повторной полной сверки;
8. владелец отдельно разрешил exact месяц, manifest hash и release commit.

Скрипт повторяет DB guards в `SERIALIZABLE` transaction, берёт глобальный и operation-specific
advisory locks, а при apply кратковременно блокирует конфликтующие таблицы в
`SHARE ROW EXCLUSIVE`. `lock_timeout=5s`, поэтому рабочая нагрузка приводит к stop, а не к долгому
ожиданию. Любое несовпадение документа, позиции, продукта, сотрудника, даты, source kind/status,
количества, суммы, себестоимости или текущих категорий откатывает всю месячную транзакцию.

## Режимы

- `--preflight` использует только `DB_BACKUP_USER`; permanent DML у этой роли отсутствует.
- `--apply` использует `DB_MIGRATOR_USER` и требует четыре независимых подтверждения через
  environment: operation ID, manifest SHA-256, deployed commit и approval reference.
- `--verify` снова использует `DB_BACKUP_USER` и независимо проверяет exact target state,
  effective-dated assignments и immutable audit rows.

Пароль читается из root-owned secret file, не передаётся аргументом и не выводится. Output содержит
только operation ID, hash, release commit и агрегатные totals без строк сотрудников.

## Preflight

Для выбранного месяца вычислить hash reviewed manifest:

```bash
sha256sum /opt/store-analytics/deploy/corrections/manifests/2026-02-magazin.json
```

Затем выполнить read-only preflight:

```bash
sudo /opt/store-analytics/deploy/corrections/apply-classification-2026-02-magazin.sh \
  --preflight /etc/store-analytics/release.env
```

Единственный допустимый успешный итог — JSON со статусом `PREFLIGHT_PASS` и точными aggregate
totals выбранного manifest. Ошибка означает stop и диагностику; менять manifest под текущее
состояние без повторной доказательной сверки запрещено.

## Apply

Ниже показана форма команды, а не разрешение на её запуск. Значения берутся из подписанного change
record и не должны содержать секреты:

```bash
sudo env \
  CONFIRM_BOUNDED_CLASSIFICATION_APPLY=classification-correction-2026-02-magazin-v1 \
  CORRECTION_EXPECTED_MANIFEST_SHA256=<reviewed-64-hex> \
  CORRECTION_EXPECTED_RELEASE_COMMIT=<deployed-40-hex> \
  CORRECTION_APPROVAL_REF=<approved-change-ref> \
  /opt/store-analytics/deploy/corrections/apply-classification-2026-02-magazin.sh \
  --apply /etc/store-analytics/release.env
```

Успешный `APPLY_PASS` означает только commit bounded transaction. Не повторять apply: повторный
запуск обязан остановиться на несовпадении current state. При обрыве сначала выполнить
`--verify`; blind retry запрещён.

## Независимый verify и месячная сверка

```bash
sudo /opt/store-analytics/deploy/corrections/apply-classification-2026-02-magazin.sh \
  --verify /etc/store-analytics/release.env
```

После `VERIFY_PASS` запустить полный read-only аудит месяца и повторить сверку STORE, SELLERS,
каждого сотрудника, категории, документа и позиции. Correction не считается завершённым только по
целевым строкам. Для февраля и марта сначала учитывается очередь recovery/relink из общего release
manifest; порядок волн изменять без нового review нельзя.

## Критерии остановки и восстановление

- Любой guard, lock timeout, serialization failure или unexpected count — `STOP`.
- SQL transaction откатывается целиком до `APPLY_PASS`; ручной частичный repair запрещён.
- После неизвестного исхода только `--verify` и read-only audit определяют состояние.
- Эти assignments не удаляются обратным SQL. Ошибка исправляется reviewed forward correction;
  restore всего production допустим только по DR runbook и отдельному incident authority.
- Финализированные отчёты и утверждённая/выплаченная зарплата не переписываются.

## Локальная проверка 2026-09-15

На изолированном PostgreSQL 16 с migration boundary `V51` и синтетическими данными выполнены:

- `PREFLIGHT_PASS`;
- отказ при несовпадении monetary fact с полным rollback;
- один `APPLY_PASS` для analytics + payroll;
- независимый `VERIFY_PASS`, включая audit rows;
- отказ повторного apply и неизменность counts.

Все три manifests отдельно сопоставлены с fresh production read-only audits по exact
document/item/product, employee reference, дате, source type/status, quantity, revenue, cost и
current categories. Production write не выполнялся. Эта локальная проверка не заменяет staging на
fresh production backup и отдельное production-разрешение.
