---
doc_schema: 1
doc_type: current
status: current
owner: backend
audience:
  - developer
  - operator
last_verified: 2026-10-01
requirement_sources:
  - docs/archive/legacy-contracts/database-design.md
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - backend/src/main/java/com/storeanalytics/common/database/CatalogActivationState.java
  - backend/src/main/resources/db/migration/V89__store_catalog_activation_boundary.sql
  - scripts/catalog-audit/export_migration_state.sh
  - backend/src/main/java/com/storeanalytics/MigrationApplication.java
  - backend/src/main/java/com/storeanalytics/common/database/CatalogMigrationPreflight.java
  - backend/src/main/resources/db/migration
  - backend/src/main/java/com/storeanalytics/common/database/ExpectedSchemaVersion.java
  - deploy/bin/deploy.sh
  - deploy/bin/rollback.sh
  - deploy/bin/forward-fix.sh
verification_sources:
  - backend/src/test/java/com/storeanalytics/common/database/CatalogActivationStateIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/HistoricalCatalogRows.java
  - scripts/tests/test_catalog_migration_state_export.py
  - backend/src/test/java/com/storeanalytics/common/database/CatalogMigrationPreflightIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/CatalogMigrationPreflightTest.java
  - backend/src/test/java/com/storeanalytics/common/database/ExpectedSchemaVersionTest.java
  - backend/src/test/java/com/storeanalytics/MigrationApplicationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/UserFeatureAccessMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/WorkShiftStoreScopeMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/SaleTypedReturnIssueMigrationIntegrationTest.java
  - backend/src/test/java/com/storeanalytics/common/database/ZeroCostSeverityMigrationIntegrationTest.java
runtime_evidence: []
required_reviewers:
  - backend-data
  - operations
review_triggers:
  - migration
  - schema-compatibility-change
  - rollback-script-change
supersedes: []
superseded_by: null
---

# Flyway и совместимость схемы

## Действующий контракт

Backend вычисляет максимальную ожидаемую schema version из packaged migration resources;
источник — `ExpectedSchemaVersion`, а не число в документации. Цепочка содержит дробные версии,
которые учитываются при сортировке. Runtime API/WORKER не мигрирует БД и должен только read-only
проверить фактическую историю.

Production deploy сначала проверяет immutable images и packaged schema, затем останавливает worker
и API и только после подтверждения отсутствия работающих writers запускает Flyway. При ошибке
migration writers остаются остановленными до диагностики; blind retry запрещён.

Существенные поздние изменения:

| Версия | Смысл |
|---|---|
| V39.1 | Первое создание LiveSklad webhook inbox для совместимости веток |
| V42 | Повторяет inbox DDL через `IF NOT EXISTS`; не является единственным источником таблицы |
| V43 | Lease/retry/terminal state и orphan return до появления исходной продажи |
| V44 | Audited, idempotent validated return recovery |
| V45–V48 | Weekly-review snapshots, AI enrichment/jobs/attempts и contract hardening |
| V49 | Durable ожидания для повторного связывания orphan return |
| V50 | Глобальные функции руководителей с backfill `PLAN`, `SHIFTS`, `PAYROLL` |
| V51 | Non-negative expectation для доказанных zero-net item returns |
| V52 | Уникальность смены ограничена магазином: `(store_id, employee_id, work_date)` |
| V53 | Закрывает только доказуемые stale missing-original issues у sale-typed return-feed записей |
| V54 | Переводит открытые unexpected zero-cost issues в информационную severity |
| V91 | Пустое temporal-хранилище участия продавцов с append-only защитой; baseline не создаётся автоматически |
| V92 | Согласует CHECK справочника категорий с Java enum: разрешает базы AIRPODS и APPLE_WATCH без изменения строк |
| V93 | Ограничивает старт проверки каталожной роли индексным поиском snapshot; сохраняет правила проверки устаревших ролей и возвратов, не меняет данные |

## Защита перспективного перехода каталога

Владелец 2026-10-01 подтвердил: 19 каталожных миграций с backfill были только локальными
черновиками и нигде не применялись. На этом основании в этих файлах удалены 56 команд записи
в `sales_document_items`, `product_category_assignments` и `product_payroll_category_assignments`.
DDL, добавление категорий и определения проекций сохранены. Следующая локальная
миграция активирует 17 новых листьев для датированного назначения и дополняет зарплатный
fallback: PS5 в `GAME_CONSOLES` остаётся `TECH_TIER_1`, остальные консоли — `TECH_TIER_2`.
Ставки, формула и уже записанные продажи не меняются. Решения по товарам не отменены:
они должны применяться отдельными датированными назначениями, не историческим backfill.
Уже применённые SQL не переписывались; repair/checksum bypass не используется.

`CatalogMigrationPreflight.prepareReviewedRollout` допускает заполненную базу с pending
каталожной цепочкой только при явно заданной бизнес-полуночи более чем через час и совпадении
SHA-256 всех 19 reviewed SQL-файлов. Перед migrate он сохраняет отпечатки всех строк
`sales_document_items`, `product_category_assignments`, `product_payroll_category_assignments`;
после migrate `verifyUnchanged` сравнивает их до регистрации даты активации.
Отклонение даты или SQL происходит до migrate; изменение исторических строк обнаруживается
после миграции и не означает автоматического rollback. Writers должны оставаться остановленными.
Пустая база и база без этих pending-версий допускаются к обычной Flyway validation/migration.
Read-only `verify` сохраняет строгий отказ для заполненной базы без reviewed rollout.
Этот технический путь не заменяет release-specific rehearsal проекций и restore.

Статический тест теперь запрещает прямую запись в три защищённые таблицы в новой цепочке.
Это не универсальный SQL-парсер. Populated fixtures сравнивают fingerprints всех полей товаров,
документов, позиций и двух видов назначений после отдельной миграции и после всей оставшейся
цепочки. Это не доказывает неизменность вычисляемых views или результата attach-rate.
Прямые вызовы Flyway вне режима `MIGRATION` стратегией не защищены.

## Неизменяемая дата включения

Структурная миграция создаёт `catalog_classification_activation`, не заполняя дату автоматически.
Единственная строка содержит event-time границу, время записи и версию политики.
UPDATE, DELETE и TRUNCATE запрещены триггерами; история товаров и зарплат не затрагивается.

Migration runner разбирает явный `app.catalog-classification.activate-from` до запуска Flyway.
После успешной migration он однократно регистрирует эту дату в configured schema.
Первичная запись допускается лишь для даты не раньше текущего времени БД; часы запуска
не подставляются вместо решения оператора. Повтор с той же датой идемпотентен, другая дата
или потеря конфигурации при существующей записи приводят к отказу без изменения строки.
Точность — микросекунды PostgreSQL; timezone/offset обязателен.
Граница должна совпадать с 00:00 бизнес-зоны `Europe/Kaliningrad`, поскольку зарплатные
назначения датируются днями. Неверное время отклоняется до Flyway в migration runner,
повторно перед записью marker, при запуске API/worker и выпускным preflight.
При обновлении посреди дня выбирается ближайшее подходящее будущее бизнес-полуночь;
настройка «прямо сейчас» небезопасна для зарплатной истории.

API/WORKER через `CatalogClassificationCutover` только читают и сверяют запись при создании bean,
после инициализации БД. При включённом capture дата `app.catalog-compatibility.snapshots-from`
должна совпадать с датой классификации. Дата, настроенная без записи в БД, также не разрешает старт.
Отсутствие и записи, и настройки сохраняет unactivated development/fresh-schema режим;
оно **не является разрешением production rollout**.

Отказ регистрации даты возможен уже после завершения структурной migration (например,
запланированное время прошло во время её выполнения). Не объявлять такую попытку «без изменений»:
нужна диагностика Flyway history/marker по recovery runbook. Автоматическое изменение уже
записанной даты, её удаление или отключение guard для продолжения запрещены.

При запуске через `deploy.sh` отказ происходит уже после остановки writers и установки
`MIGRATION_IN_PROGRESS`. Сообщение «миграции не применялись этим вызовом» не разрешает
самостоятельно менять marker или перезапускать старый runtime. См.
[процедуру диагностики](../../runbooks/migration-failure-and-forward-fix.md).
Не запускать production deploy только ради проверки этого предохранителя.

## Read-only исходное состояние перед перспективным rollout

`scripts/catalog-audit/export_migration_state.sh` — отдельный операторский экспорт,
не migration entrypoint и не deploy. Скопировать проверенный файл на сервер, например
как `/tmp/export_catalog_migration_state.sh`, затем выполнить
`sudo bash /tmp/export_catalog_migration_state.sh` без аргументов.
Скрипт читает только необходимые literal-ключи release env, не исполняет его содержимое,
подключается закреплённой backup-reader ролью через TLS verify-full.
Приложение не останавливается, ACL и release marker не меняются.

В одной repeatable-read/read-only транзакции выгружаются ordered Flyway history
(version/type/script/checksum/time/success, без installed_by) и три признака наличия строк:
позиции продаж, аналитические назначения, зарплатные назначения. Завершение — ROLLBACK.
Имена товаров, сотрудники, суммы, provider payload и пароли в результат не включаются.
Отсутствующая таблица или право SELECT приводят к отказу; скрипт не выдаёт себе права.

Результат — новая приватная директория в `/home/pavel/`, `snapshot.jsonl` и
`snapshot.sha256`. Передать оба файла для проверки; не коммитить runtime-выгрузку
и не копировать наблюдаемую schema version в этот документ.
Незавершённый `snapshot.jsonl.partial` нельзя считать валидным результатом.
SQL проверен локально; последующий owner-run read-only экспорт сверён с исходниками.
Санитарное наблюдение сохранено в history и связано с project-state.

Экспорт не заменяет backup/restore и не разрешает применение новых категорий.
Отдельный диагностический `scripts/catalog-audit/CatalogSnapshotMigrationRehearsal.java`
проверяет checksum приватного снимка, восстанавливает выбранные исходные таблицы в
одноразовом локальном PostgreSQL и сравнивает сохранённые строки и опубликованные
attach-rate агрегаты до/после цепочки. Это не полное восстановление backup.
Результат и пределы проверки зафиксированы в
[историческом свидетельстве](../../history/audits/2026/10/catalog-snapshot-migration-rehearsal.md).
Предохранитель остаётся включённым до закрытия остальных gate.

Следующий gate: сверить журнал с packaged SQL, определить уже опубликованные/применённые
файлы и выбрать reviewed путь без изменения их checksum. Отдельно проверить изменения
views, повторный sync и поздние возвраты: датированное назначение само по себе не замораживает
fallback классификатора, который вызывается при отсутствии эффективного назначения.
Reviewed entrypoint реализован в общем кандидате; его наличие не подтверждает production
rehearsal этого кандидата. Запрещено обходить SQL fingerprints или проверку исторических строк.

### Повторное обновление после активации

Release preflight проверяет будущую дату при первом переходе. Если дата уже прошла или
до неё осталось менее часа, допускается только точное совпадение со значением в защищённом
`STATE_DIR/current.env` установленного каталожного релиза: regular non-symlink file,
владелец root, mode 0600. Отсутствие записи, старый докаталожный release или другая дата
означают отказ. Capture остаётся включённым с той же границей.
Это проверка сохранённой конфигурации, не доказательство состояния БД: migration role
и API/worker независимо сверяют фактический immutable marker. Дату никогда не передвигать
вперёд ради прохождения повторного preflight.

## Политика изменения

- Миграции forward-only; опубликованный migration file не переписывается.
- Application rollback не откатывает Flyway.
- Предыдущий runtime можно запускать только на явно разрешённой ему schema.
- При несовместимости требуется reviewed forward-fix или восстановление из проверенного backup,
  а не ручное редактирование `flyway_schema_history`.
- Любая новая migration обновляет schema oracle, migration tests и release compatibility metadata.
- ACL repair после Flyway получает тот же exact release env и не использует defaults для DB target,
  schema или ролей.

## Что доказано

Fresh-schema tests применяют всю packaged-цепочку к PostgreSQL 16. Есть representative populated
upgrade tests, включая поздние weekly-review migrations. `ExpectedSchemaVersionTest` проверяет
oracle packaged version.

Локально 2026-10-01 выполнены 5 целевых тестов стратегии и preflight на PostgreSQL 16:
реальный migration entrypoint сохраняет прежнюю версию и назначения при отказе;
проверены пустая база, отдельная schema, удалённый факт и уже применённая цепочка.
Запуск — отдельный javac/JUnit с закреплёнными зависимостями; это не полный Gradle release gate.

### Проверка переработанной черновой цепочки — 2026-10-01

На 18 прежних товарных фикстурах проверена точная сохранность всех полей товаров,
документов, позиций и аналитических/зарплатных назначений после целевой миграции и всей
оставшейся цепочки. Все 18 сценариев PASS. Проверки migration entrypoint, запрета прямого
DML и schema oracle также PASS.

После исправления точности синтетических timestamps отдельный повтор resolver/cutover,
реального sync/возврата и immutable даты: 17 тестов PASS, без пропусков.
Исправление фикстуры не меняет production-логику: наносекундное Instant.now приводится
к представимой в PostgreSQL точности, и тест дополнительно проверяет принятие нового имени.
Checkstyle PASS. Контрольные суммы всех 52 ранее применённых SQL повторно совпали с read-only
выгрузкой; пересмотр касался только подтверждённых черновиков.

Прогоны выполнены отдельным javac/JUnit с закреплёнными зависимостями. Устаревшие SQL
из build/resources исключены из classpath; необходимые неизменяемые contracts/prompts
подключены отдельно. Это не полный Gradle/release gate, не restored production-copy rehearsal
и не подтверждение неизменности вычисляемого attach-rate.

## Что не доказано

- Не существует полного populated upgrade matrix из каждой прежней версии в текущую packaged schema.
- Downgrade текущей packaged schema в предыдущую schema не реализован и не репетировался.
- Release scripts используют локальный state-файл для compatibility decision. После failed
  migration marker `MIGRATION_IN_PROGRESS` нельзя автоматически примирить с реальным
  `flyway_schema_history`; штатный recovery runbook для этого ещё не подтверждён.
- Нельзя считать локальный state-файл доказательством фактической версии БД.

Поэтому документ описывает только forward compatibility и не разрешает production recovery.
Операторская процедура migration failure должна оставаться draft, пока не появятся staging и
production-read-only evidence по правилам documentation policy.
