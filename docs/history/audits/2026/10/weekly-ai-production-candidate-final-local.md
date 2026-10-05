---
doc_schema: 1
doc_type: evidence
status: historical
owner: backend
audience:
  - developer
  - operations
snapshot_date: 2026-10-06
verdict: PASS
verdict_scope: Local Java frontend API documentation security and monitoring checks passed; not deployment approval
verification_levels:
  - local
source_of_truth:
  - docs/maintenance/weekly-ai-production-automation-plan.md
  - docs/runbooks/weekly-review-ai.md
required_reviewers:
  - backend
  - operations
---

# Финальная локальная проверка candidate автоматического недельного разбора

Изолированная ветка сохраняет пользовательский dirty worktree без включения посторонних правок.
Проверки используют только synthetic PostgreSQL fixtures и localhost visual fixtures.
Production, baseline, синхронизация и provider calls не изменены. Состояние production остаётся
в каноническом project-state, а не в этом локальном evidence.

## Проверки

- Первый общий backend run: 2 079 tests в 437 классах, один failure, 0 errors/skips.
  Существующий catalog parity test не нашёл review row перед первым решением. Причина не
  установлена. Повтор всех 16 catalog tests и временные 30 повторов спорного сценария прошли.
  Постоянные assertions теперь отдельно проверяют CURRENT/STALE snapshot и наличие review row;
  никакие production predicates, migrations или checks не ослаблены. Временный stress test удалён.
- Полный frozen Java повтор: 2 079 tests в 437 классах, 0 failures/errors/skips; Checkstyle main/test
  и bootJar PASS. Завершающий OpenAPI gate обнаружил незаписанный additive historical GET.
  Committed artifact и generated frontend transport синхронизированы, Java runtime/tests не
  изменены. Прямое generated-vs-committed/baseline сравнение PASS: breaking changes нет.
  Финальная совместная команда PASS: Java suite/style/package/export переиспользованы как
  UP-TO-DATE с неизменными inputs, исправленный API artifact проверен заново. Сохранён прежний
  bootBuildInfo, чтобы timestamp-only rebuild не подменял область повторной проверки;
  Java tests не исключались. Это не второй новый запуск 2 079 tests после API regeneration.
- Frontend после transport regeneration: 61 test file / 314 tests PASS, contract check, ESLint,
  TypeScript/Vite production build PASS на Node 22.
  Ранее выполнены visual checks material seller-current/historical UI на localhost для desktop,
  tablet и mobile; изображения просмотрены, не закомичены. Это fixture-проверка, не
  authenticated acceptance на живом backend с реальными данными.
- Documentation unit tests: 25 PASS. Strict inventory gate после регистрации этого evidence:
  462 rows, 0 warnings; финальный unit/strict gate повторён после обновления evidence.
- Host operator security tests: PASS. Gradle dependency/wrapper integrity: 449 components,
  840 artifacts PASS. Container backend gate отдельно исключает host security task, поскольку
  его runtime image не содержит jq; host gate не пропущен.
- Все семь Prometheus rules и startup/fire/recovery fixtures: PASS официальным promtool 3.2.1.
  Архив проверен опубликованным checksum. Дополнительно PASS в локальном scratch container
  с read-only mount, без network/capabilities/secrets. Pull официального validator image из
  этой среды недоступен; CI wrapper и фактическая Alertmanager delivery этим не подтверждены.

## Review и границы выпуска

Проверены независимость payroll/warranty, return processor attribution, неизвестная история,
forward-only baseline, полнота двух недель, durable free backlog, bounded scheduler, точные
historical bindings, неподменяемый exact approval, один automatic store/week job, неоплаченный
rebind, lease/deadline/source publication fences, immutable receipts и учёт UNKNOWN расходов.
Public current reader не заменяет unavailable historical revision legacy fallback. Ушедший
продавец остаётся в historical итогах без будущего action. Preparation flag сам не разрешает AI.
Метрики имеют fixed labels, failure sanitization и exporter health вместо stale healthy cache.

До выпуска нужны CI неизменного commit и registry coordinates, filled database backup/restore
rehearsal, grants/schema/runtime/API acceptance, exact forward-only baseline operator и его
отдельное approval, защищённый scrape и deduplicated alert fire/recovery, deployment approval,
postverify и свежий ограниченный paid canary. Старые разрешения не переносятся на новые hashes
или период. Успешная локальная проверка не означает активированную production автоматику.
Если каталоговый сбой повторится, выпуск остаётся STOP до установленной причины.
