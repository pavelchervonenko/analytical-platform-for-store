---
doc_schema: 1
doc_type: evidence
status: historical
owner: backend
audience:
  - developer
  - operations
snapshot_date: 2026-10-05
verdict: FAIL
verdict_scope: Full backend run has one container initialization failure; isolated rerun passed; not release approval
verification_levels:
  - local
source_of_truth:
  - docs/maintenance/weekly-ai-production-automation-plan.md
  - docs/decisions/ADR-0006-livesklad-return-employee-analytics.md
required_reviewers:
  - backend
  - operations
---

# Локальная контрольная точка недельного ИИ и автора возврата

Изолированная ветка не включает незавершённые правки основного рабочего каталога.
Production, синхронизация и платные provider calls в этих проверках не затронуты.
Дата соответствует рабочей среде; сама по себе не подтверждает закрытие недели магазина.

## Результаты с точной областью проверки

- AI guards, календарь, retries и повторная подготовка прежнего snapshot: targeted набор
  52 backend tests, 0 failures, 0 skipped. Checkstyle main/test проходит.
- Полный backend test run на коммите `d168cf0`: 1 896 tests, 1 failure, 0 errors, 0 skipped;
  длительность 26 минут 32 секунды. Единственный failure — `initializationError` класса
  `DataRetentionRepositoryIntegrationTest`, тип `ContainerLaunchException`, до исполнения
  тестов приложения. Первопричина недоступности тестового контейнера не установлена.
- Отдельный повтор на неизменённом retention-коде: все 4 теста этого класса прошли.
  Это дополнительное свидетельство, а не замена неуспешного полного прогона.
- Проекция нового автора возврата проверена отдельно на коммите `57ee9aa`:
  PostgreSQL integration test с 16 synthetic документами прошёл, Checkstyle main/test прошёл.
  Проверены разные авторы продажи/возврата, собственная дата membership, orphan/late link,
  отсутствующий и неразрешённый source employee, baseline, границы участия и удаление.
  Общий сохранённый `employee_id` не менялся. Тест повторно прошёл вместе с 4 retention-тестами.
- `generateOpenApi` и проверка совместимости generated OpenAPI прошли; один тест генерации
  стабильных frontend schemas прошёл. Transport/UI не изменены.
- Frontend `npm run check` прошёл: contracts, lint, Vitest, type checking и build.
  В Vitest result cache — 61 test file без failures. Новые UI-изменения отсутствуют;
  визуальная проверка этим результатом не заявляется.
- Operator security test на host и Gradle supply-chain verifier прошли.
  Supply-chain verifier проверил 449 components и 840 artifacts.
- Documentation checker: 25 unit tests проходят; strict integrity — без warnings
  на момент фиксации контрольной точки.

Набор полного прогона предшествует добавлению проекции автора возврата; объединённый код
не имеет доказанного зелёного полного backend run. Следующий release gate должен проверять
окончательный неизменный набор исходников, а не складывать эти результаты в заявление
«все тесты прошли».

## Остаток работ

Независимая проекция пока не подключена ко всем финансовым/структурным/attach агрегатам.
Durable backlog, сохранение позднего provider receipt при потере lease, baseline cutover,
production rehearsal, exact canary и включение постоянной автоматической публикации остаются
незавершёнными этапами [плана](../../../../maintenance/weekly-ai-production-automation-plan.md).
