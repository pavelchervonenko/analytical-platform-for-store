---
doc_schema: 1
doc_type: current
status: current
owner: operations
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/deployment-and-operations.md
  - docs/data-retention.md
implementation_sources:
  - deploy/bin/backup-postgres.sh
  - deploy/backup.env.example
  - deploy/systemd/store-analytics-backup.service
  - deploy/systemd/store-analytics-backup.timer
  - deploy/compose.production.yml
verification_sources:
  - scripts/tests/security-hardening-test.sh
runtime_evidence: []
required_reviewers:
  - operations
  - security-privacy
review_triggers:
  - backup-change
  - database-change
  - retention-change
  - infrastructure-change
supersedes: []
superseded_by: null
---

# Устойчивость и резервное копирование

## Назначение и границы

Документ фиксирует реализованный в репозитории backup-контур и границы его доказательности. Он не
утверждает, что timer включён в production, объект доступен, restore выполнен или целевые RPO/RTO
достигнуты. Эти факты допустимы только в датированном evidence.

## Действующий контракт

- Контейнеры приложения не хранят business state: PostgreSQL является внешним stateful-компонентом,
  а Caddy использует отдельные volumes только для TLS/runtime-данных.
- `backup-postgres.sh` подключается отдельной read-only ролью по TLS `verify-full`, делает custom
  dump схемы `app` с `--serializable-deferrable`, проверяет каталог через `pg_restore --list`,
  шифрует dump симметричным AES-256 и загружает encrypted object с manifest.
- Manifest содержит время, имя БД, схему, SHA-256 зашифрованного файла, размер и версию `pg_dump`.
  После upload скрипт сравнивает только удалённый размер с локальным.
- Репозиторный systemd timer задаёт ежедневный запуск. `install-host.sh` копирует unit-файлы, но
  намеренно не включает timer автоматически.
- Пароль backup-роли, passphrase и S3 credentials читаются из root-owned файлов. Временный каталог
  удаляется trap-обработчиком.

## Инварианты

- Application rollback не заменяет восстановление БД и не откатывает Flyway.
- Успешный upload и `pg_restore --list` не доказывают возможность restore.
- Backup не считается пригодным для destructive retention или DR без скачивания, проверки
  checksum, расшифровки и восстановления в изолированный PostgreSQL.
- Ключ расшифровки не должен храниться только рядом с единственной копией backup.

## Реализовано, но не подтверждено в runtime

| Возможность | Репозиторная реализация | Что не доказано |
|---|---|---|
| Encrypted logical backup | Скрипт и systemd unit | Что timer включён и последний объект пригоден |
| Upload verification | Remote size и локальный SHA-256 в manifest | Повторное скачивание и checksum удалённого объекта |
| Расписание | Ежедневный timer | Фактический RPO и обработка пропущенных/failed запусков |
| DR | Архитектурная возможность restore | Restore run, business reconciliation и измеренный RTO |
| Object storage | S3-compatible upload | Versioning, lifecycle, Object Lock и off-provider copy |

## Расхождения и открытые решения

- Репозиторный timer ежедневный; заявлять часовой RPO нельзя.
- У backup service нет репозиторного `OnFailure`, а stale-backup alert не реализован.
- Нет исполняемого restore-скрипта и автоматической проверки восстановленной схемы/данных.
- Не определена и не доказана независимая копия у другого провайдера.

## Проверка

Статически проверяются shell safety и наличие защищённых secret paths. Полная проверка требует
процедуры `docs/runbooks/backup-restore-and-dr.md` и отдельного production-drill evidence.

## Триггеры пересмотра

Изменение backup format, schedule, storage provider, encryption/key custody, PostgreSQL major
version, retention authorization или DR topology требует обновить документ.
