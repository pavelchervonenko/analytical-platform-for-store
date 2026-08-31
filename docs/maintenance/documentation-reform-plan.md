---
doc_schema: 1
doc_type: working
status: closed
owner: project
audience:
  - developer
  - operator
created_at: 2026-08-31
review_by: 2026-12-31
source_material:
  - docs/maintenance/documentation-inventory.tsv
required_reviewers:
  - information-architecture
exit_target: archive
---

# Реформа документации Store Analytics

Статус: все 11 этапов завершены; итоговая приёмка зафиксирована immutable evidence.

Дата начала: 2026-08-31.
Дата завершения: 2026-08-31.

## Цель

Сделать документацию однозначным и поддерживаемым источником знаний о продукте, коде и
production-эксплуатации. Действующие контракты должны быть отделены от инструкций оператора,
архитектурных решений, исторических доказательств и устаревших рабочих материалов.

## Неподвижные правила миграции

- Существующие документы не удаляются и не перемещаются до завершения полного реестра.
- Для каждого удаления или объединения заранее указывается проверенная актуальная замена.
- Production-факт фиксируется только после проверки фактического runtime.
- Исторические release records, аудиты, canary и reconciliation evidence не переписываются как
  действующие инструкции.
- Versioned prompts, JSON Schema и примеры считаются runtime-контрактами. Их перенос возможен
  только вместе с обновлением путей сборки и полным contract-прогоном.
- Секреты, полные production environment-файлы и персональные данные не переносятся в
  документацию.
- Каждая итерация оформляется отдельными логическими коммитами и проходит независимый review.

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
│   ├── releases/
│   ├── audits/
│   ├── canaries/
│   ├── incidents/
│   └── handoffs/
├── archive/
└── maintenance/
```

`docs/prompts/` и `docs/schemas/` временно остаются на текущих путях. Решение об их переносе
принимается и реализуется отдельным техническим этапом.

## Этапы

1. **Инвентаризация.** Полный реестр, назначение, владелец, источник истины, статус и
   предварительное решение по каждому артефакту. Без переносов и удалений.
2. **Политика, ownership и шаблоны.** Метаданные, lifecycle, review gates и только schema/правила
   будущего единственного production-state. Фактическое runtime-состояние на этом этапе не
   публикуется.
3. **Production-state и критические противоречия.** Создание проверенного по runtime
   `docs/current/project-state.md`, актуализация точек входа и устранение опасных расхождений до
   массовой реорганизации.
4. **Operations и security.** Deploy, migration, rollback, forward-fix, backup/restore,
   observability, access и incident response.
5. **Архитектура, API, данные и LiveSklad.** Канонические технические контракты и runbook
   интеграции.
6. **Продукт, метрики и frontend.** Формулы, классификация, reconciliation, payroll и UI-контракты.
7. **ИИ и Telegram.** Действующий weekly-review, legacy-контур, validation, evaluation и
   эксплуатация.
8. **История, архив и удаление.** Перенос evidence, разбор backup-файлов и удаление только
   подтвержденных дублей.
9. **Runtime prompts и schemas.** Отдельная миграция машинных контрактов при подтвержденной
   необходимости.
10. **Постоянная защита.** Docs impact, PR/release checklist, link/metadata/orphan checks и
    блокирующие CI-правила после очистки baseline.
11. **Финальная приемка.** Междоменный аудит и проверка процесса на реальных изменениях и релизе.

## Контрольные точки

Каждый этап считается завершенным только после:

1. проверки полноты изменений;
2. проверки локальных ссылок и релевантных тестов;
3. независимого review соответствующей области;
4. фиксации известных ограничений и следующей точки продолжения;
5. отдельного логического коммита.

## Текущий прогресс

### Этап 1

- [x] Проведен предварительный read-only аудит структуры.
- [x] Выполнены независимые reviews информационной архитектуры, operations/security,
  product/metrics/AI и процесса актуализации.
- [x] Зафиксирован полный реестр всех документационных артефактов.
- [x] Реестр проверен на полноту и корректность классификации двумя независимыми reviewer-ами.
- [x] Этап 1 подготовлен к фиксации отдельным логическим коммитом.

### Этап 2

- [x] Утверждены классы документов, жизненный цикл и правила источников истины.
- [x] Утверждены логические владельцы и уровни review.
- [x] Подготовлены шаблоны current, runbook, ADR, evidence, archive и working.
- [x] Реестр дополнен новыми maintenance-файлами и повторно проверен.
- [x] Этап 2 прошел два независимых review и подготовлен к фиксации отдельным коммитом.

### Этап 3

- [x] Runtime-факты отделены от стабильных архитектурных контрактов.
- [x] Создан датированный sanitized production evidence для `v0.1.0-pilot.27`.
- [x] Создан единственный канонический `docs/current/project-state.md`.
- [x] Опасные июльские recovery-инструкции помечены `completed — do not rerun`.
- [x] Корневые точки входа переведены на новый project-state.
- [x] Выполнен независимый review этапа; P0/P1 закрыты, format check повторён.

### Этап 4

- [x] Созданы канонические контракты resilience, observability, resources и audit/telemetry.
- [x] Создан security baseline, threat model и реестр остаточных рисков.
- [x] Deploy, rollback, migration recovery, backup/DR, incidents, access и secrets отделены в
  runbooks с явными stop-условиями.
- [x] Неотрепетированные production-write/recovery процедуры оставлены в статусе `draft`.

### Этап 5

- [x] Архитектура приложения, БД, миграций и ошибок сверена с кодом и тестами.
- [x] OpenAPI отделён от семантических API-контрактов; gaps v10 зафиксированы.
- [x] Описаны LiveSklad API observations, sync, webhook, recovery и provider uncertainty.
- [x] Июльское восстановление обозначено завершённым и запрещённым к повторному запуску.

### Этап 6

- [x] Формулы, периоды, классификация, attach-rate, payroll, отчёты и data quality сведены в
  продуктовые контракты.
- [x] Frontend-контракты связывают endpoint, период, cohort, `null` и пользовательскую подпись.
- [x] Расхождение employee return attribution и mixed-period Overview оформлены proposed ADR,
  а не скрыты как реализованное поведение.

### Этап 7

- [x] Weekly Review v25/schema4 отделён от legacy schema1–3 и deterministic fallback.
- [x] Описаны immutable prompts/schemas, YandexGPT boundary, validation и paid evaluation gates.
- [x] Telegram weekly/daily контуры, отсутствие schema4 bridge и poison-event риск зафиксированы.
- [x] Privacy/retention gaps не выданы за закрытые controls.

### Этап 8

- [x] Для 105 корневых документов зафиксирована полная source/destination/replacement-карта.
- [x] 36 evidence-документов перенесены в history, 69 superseded-материалов — в archive.
- [x] Пять ignored `.orig` удалены только после fragment map и независимого PASS.
- [x] Старые пути сохранены tombstone-записями; перенос прошёл SHA/link/metadata review без P1/P2.

### Этап 9

- [x] Подтверждено, что `docs/prompts/` и `docs/schemas/` являются runtime-артефактами.
- [x] Физический перенос отклонён как не дающий пользы и создающий риск поломки runtime paths.
- [x] Опубликованные версии защищены inventory action `runtime-keep` и CI-проверкой.

### Этап 10

- [x] Documentation impact закреплён в `AGENTS.md` и PR checklist.
- [x] Inventory, metadata, links, anchors, orphan, backup и tombstone gates проверяются offline.
- [x] Strict documentation check включён в обычный CI и release workflow.
- [x] Transitional warning baseline очищен; допустимых предупреждений — 0.

### Этап 11

- [x] Приёмка воспроизведена в отдельном detached checkout без зависимости от рабочей копии.
- [x] Два независимых reviewer-а проверили информационную архитектуру и
  operations/security/product/AI границы.
- [x] Все найденные P2 закрыты: сводка inventory синхронизирована, migration map закрыта, прямой
  paid AI execute обозначен NO-GO, Prometheus rules обозначены repository examples.
- [x] Unit suite документационного guard содержит 25 тестов; strict current и полный диапазон
  реформы проходят с 0 предупреждений.
- [x] Итог и границы проверки сохранены в
  [финальном audit-evidence](../history/audits/2026/08/documentation-reform-final-audit.md).

Полные backend/frontend suites в финальной документационной приёмке не повторялись: локальная
среда содержит Java 11 и Node 20 вместо требуемых проектом версий. В реформе не менялись application
source, versioned prompts или schemas; это ограничение явно сохранено в итоговом evidence.
