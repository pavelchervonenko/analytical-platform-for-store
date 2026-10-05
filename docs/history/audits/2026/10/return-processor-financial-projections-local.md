---
doc_schema: 1
doc_type: evidence
status: historical
owner: backend
audience:
  - developer
  - operations
snapshot_date: 2026-10-05
verdict: PASS
verdict_scope: Targeted financial return-author regression, sequential frontend check and generated API compatibility; not complete automation or production approval
verification_levels:
  - local
source_of_truth:
  - docs/decisions/ADR-0006-livesklad-return-employee-analytics.md
  - docs/maintenance/weekly-ai-production-automation-plan.md
required_reviewers:
  - backend
  - operations
---

# Денежные проекции сотрудника возврата: локальная проверка

Работа изолирована от незавершённых изменений основного рабочего каталога.
Production не менялся; синхронизация и платные вызовы не запускались.

## Проверки

- Итоговый targeted backend run: 113 tests в 17 классах, 0 failures/errors/skips;
  Checkstyle main/test прошёл. Включены финансовые KPI, категории, рейтинг, seller weekly,
  identity/read service, sync и warranty integration. Из seller load suite выполнен один
  сценарий late link; полный load suite этим результатом не подтверждается.
- Предыдущий targeted run: 87 tests, 2 failures. Оба теста ожидали старого денежного автора
  возврата. После проверки подтверждённого ADR обновлены именно денежные ожидания;
  проверки сохранённого автора и гарантийных allocations не убраны. Для отсутствующего
  source employee добавлено отдельное assertion неопределённости.
- Первый frontend check одновременно с backend run: 306 tests прошли, 4 не прошли в
  3 файлах, включая timeout. Контракты и lint прошли; build не был достигнут.
  Окончательная причина не установлена; нагрузка является возможным объяснением, не доказанным фактом.
- Последовательный повтор неизменённого `npm run check`: 61 файл, все 310 tests прошли;
  transport types, lint, TypeScript и production build прошли. Тесты и timeout settings не менялись.
- `checkOpenApiCompatibility` прошёл после генерации API из финального backend-кода:
  drift относительно committed artifact и breaking changes не обнаружены.
- Host security/operator safety tests и Gradle supply-chain check прошли.
  Documentation unit tests: 25 прошли; strict inventory проверяется перед коммитом.
- Frontend source не менялся; визуальная проверка интерфейса в этом пакете не выполнялась.

## Self-review и соответствие плану

Resolver ограничен той же connection и source system LiveSklad, не использует оригинал как
fallback и не выполняет DML. Интеграционные проверки сохраняют store signed totals и общий
`employee_id`, различают missing/unresolved source author, проверяют orphan/late link и EXCLUDE.
Изменённые formula/policy versions отделяют новую семантику; published prompt/schema artifacts,
сохранённые payroll facts и immutable snapshots не переписываются.

Это денежный этап пакета 2, не весь temporal cutover. Ежедневный SELLERS-факт плана ещё читает
старого автора; historical membership в агрегатах, полный temporal attach, durable backlog
и сохранение позднего provider receipt при потере lease остаются незавершёнными.
Полный backend gate окончательного кода, CI, rehearsal и production approvals ещё необходимы.
Ранее неуспешный полный backend run сохранён отдельно и не объявляется прошедшим этим набором.
