# Неизменяемые отчёты

Статус: реализовано в Flyway V11–V30 и backend/frontend, проверено 2026-08-06.

## Назначение и граница

Главный экран продолжает динамически рассчитывать показатели за текущий день, готовую неделю,
готовый месяц или произвольный календарный период до 366 дней. Эти запросы не создают снимков.

Раздел «Отчёты» хранит только фактические, закрытые и неизменяемые документы:

- месячный отчёт создаётся после перевода конкретной ревизии payroll в `PAID`;
- годовой отчёт создаётся только за закрытый календарный год из точных месячных ревизий;
- отдельного ручного утверждения отчёта нет: финансовым основанием является выплаченная payroll
  revision;
- сравнение отчётов не выполняется автоматически; пользователь самостоятельно открывает нужные
  документы и ревизии.

Финансовые snapshots, payroll events, finalized rating и finalized reports не входят в
автоматическую очистку технических данных.

## Жизненный цикл

### Месяц

Операция `mark paid` и создание месячного отчёта выполняются в одной транзакции. Если снимок
создать нельзя, выплата не фиксируется частично. Повторная обработка той же payroll revision
идемпотентна благодаря уникальному `payroll_run_id`.

Если уже выплаченный месяц исправлен по принятому payroll-процессу, создаётся новая выплаченная
payroll revision и новая неизменяемая report revision. Предыдущая остаётся доступной, новая
содержит `supersedes_snapshot_id` и обязательную причину ревизии.

### Год

Планировщик ежедневно проверяет только прошедшие календарные годы. Годовая ревизия появляется,
когда существуют все ожидаемые месячные отчёты:

- для обычного года — январь–декабрь;
- для первого года — от месяца `stores.reporting_started_on` до декабря;
- текущий незакрытый год не фиксируется.

Годовой отчёт хранит ссылки на точные месячные snapshot ID, revision и payload hash в
`annual_report_months` и в payload. Если позднее появилась новая ревизия одного из месяцев,
создаётся новая годовая ревизия; старая не переписывается. Повторный запуск с тем же набором
источников возвращает существующую ревизию.

Годовые суммы пересчитываются из сохранённых числителей и знаменателей месячных отчётов.
Проценты и средние значения не усредняются «средним от средних».

## Содержимое

Месячный payload фиксирует:

- идентификацию и реквизиты магазина, период, покрытие и версии контракта;
- KPI магазина и категорий;
- средние показатели без блока сравнений;
- attach-rate с числителями, знаменателями и quality counters;
- план и его фактическое выполнение;
- finalized rating;
- выплаченную payroll revision, начисления, удержания и суммы к выплате;
- безопасный срез качества данных;
- автора и момент выплаты.

Годовой payload содержит:

- годовые итоги магазина;
- агрегаты по сотрудникам, категориям и attach-rate;
- полный набор использованных месячных payload;
- идентификаторы, ревизии и хеши каждого источника;
- признак `COMPLETE` или `PARTIAL_FIRST_YEAR`.

Имена сотрудников и магазина сохраняются как исторические snapshots для понятного просмотра.
Секреты, технические exception messages, raw payload и credentials в отчёт не попадают.

## Целостность

Каждый finalized report имеет:

- `schema_version`, `template_version` и `data_contract_version`;
- `source_hash` набора исходных snapshots;
- `payload_hash` точных UTF-8 байтов сохранённого JSON-документа;
- номер ревизии и provenance;
- фактическое время создания снимка.

Payload хранится как проверяемый JSON `text`, а не `jsonb`: архив должен сохранять точное
байтовое представление, покрытое SHA-256, тогда как `jsonb` нормализует порядок ключей и
whitespace. PostgreSQL при записи проверяет, что текст содержит JSON object.

Backend перепроверяет payload hash перед выдачей документа. PostgreSQL дополнительно проверяет
соответствие месячного отчёта выплаченной payroll revision, последовательность report revisions,
границы календарного периода и принадлежность годовых источников одному магазину. Триггеры
запрещают обновление и удаление finalized reports и связей `annual_report_months`.

### Закрытие JSONB integrity regression — 2026-08-06

Причиной найденного `500 report snapshot integrity check failed` была несовместимость byte-level
SHA-256 с `jsonb`: приложение хешировало исходную JSON-строку, а PostgreSQL нормализовал порядок
ключей и whitespace. V30 переводит payload в проверяемый JSON `text`, один раз пересчитывает hash
существующих строк по сохранённому тексту и в той же транзакции возвращает immutable trigger.

Regression evidence:

- PostgreSQL integration test сохраняет реальный typed annual payload, очищает persistence context,
  повторно читает строку и проверяет точное равенство payload/hash и typed decode;
- migration test выполняет V29→V30, проверяет тип `text`, восстановленный SHA-256 и запрет update
  finalized snapshot;
- на рабочей тестовой БД до V30 было `2` hashed / `2` mismatched, после V30 — `2` hashed /
  `0` mismatched; trigger имеет enabled state `O`;
- список отчётов и обе месячные ревизии прочитаны авторизованным API с `200`; SPA переключил
  «Актуальная ↔ История» без HTTP 500, `query-error` и runtime failures.

Для production V30 сначала репетируется на восстановленной production-sized копии: миграция меняет
тип колонки и обновляет существующие report rows, поэтому необходимо измерить длительность lock,
иметь свежий backup/restore evidence и выполнять migration one-shot до запуска API/worker.

## API и доступ

Store-scoped чтение доступно ADMIN и назначенному MANAGER:

- `GET /api/stores/{storeId}/reports?year={year}&type=MONTHLY|ANNUAL&page=0&size=20`;
- `GET /api/stores/{storeId}/reports/years`;
- `GET /api/stores/{storeId}/reports/{reportId}`.

Список возвращает bounded page envelope
`items/page/size/totalElements/totalPages/hasNext/hasPrevious`, все ревизии выбранной страницы и
отмечает текущую через `currentRevision`. Максимальный `size` — 100; фильтры и latest revision
вычисляются в SQL, JSON payload не выбирается. Детальный ответ содержит ровно один из payload:
`monthly` или `annual`. Отсутствующий документ возвращает стабильный `REPORT_NOT_FOUND`.

Административное восстановление исторических снимков — durable async API:

- `POST /api/admin/reports/backfill?storeId={storeId}&year={year}` с обязательным
  `Idempotency-Key` создаёт задачу и возвращает `202`;
- `GET /api/admin/reports/backfill?limit={1..100}` возвращает последние задачи;
- `GET /api/admin/reports/backfill/{jobId}` возвращает persisted status и progress;
- `POST /api/admin/reports/backfill/{jobId}/cancel` идемпотентно запрашивает остановку.

Статусы: `PENDING`, `RUNNING`, `WAITING_RETRY`, `SUCCESS`, `FAILED`, `CANCELLED`.
Ответ содержит phase/cursor, 13-step progress, число созданных и уже существовавших месячных
снимков, retry state, безопасный bounded error summary и timestamps. Idempotency key никогда не
возвращается клиенту и не попадает в метрики.

Worker выбирает последнюю `PAID` payroll revision каждого месяца, не дублирует существующие
снимки и пытается создать годовой отчёт только если год закрыт и набор месяцев полон. Каждый месяц
и annual finalization — отдельный атомарный database step; snapshot и cursor фиксируются в одной
транзакции. Создание и запрос отмены аудируются.

## Планировщик и наблюдаемость

Настройки backend:

| Переменная | Значение по умолчанию |
| --- | --- |
| `REPORTS_ANNUAL_SCHEDULING_ENABLED` | `true` |
| `REPORTS_ANNUAL_CRON` | `0 30 4 * * *` |
| `REPORTS_SCHEDULING_ZONE` | `Europe/Kaliningrad` |
| `REPORT_BACKFILL_WORKER_ENABLED` | `true` |
| `REPORT_BACKFILL_WORKER_DELAY` | `5s` |
| `REPORT_BACKFILL_MAX_ATTEMPTS` | `3` |
| `REPORT_BACKFILL_LEASE_DURATION` | `30m` |
| `REPORT_BACKFILL_RETRY_INITIAL_DELAY` | `30s` |
| `REPORT_BACKFILL_RETRY_MAX_DELAY` | `15m` |
| `REPORT_BACKFILL_MAX_ACTIVE_JOBS` | `20` |

Ошибки одного магазина не останавливают обработку остальных и записываются в журнал с полной
stack trace. HTTP latency отчётных endpoint входит в
`storeanalytics.backend.request.duration{area="report"}`; в метриках нет store ID, report ID,
имён или иных высококардинальных тегов.

`storeanalytics.report.backfill.step.duration{phase,outcome}` измеряет отдельный атомарный шаг;
`storeanalytics.report.backfill.jobs{status=failed|retrying|expired_lease}` показывает сохранённое
операционное состояние без job/store/user IDs.

Audit actions: `MONTHLY_REPORT_FINALIZED`, `ANNUAL_REPORT_FINALIZED`,
`REPORT_BACKFILL_REQUESTED` и `REPORT_BACKFILL_CANCELLATION_REQUESTED`. Автоматический годовой
процесс записывается как системное действие, месячный — с автором выплаты.

