# Pilot rollout status

Последнее обновление: 2026-08-08. Этот файл фиксирует фактическое состояние первого production
rollout. Архитектурный стандарт и повторяемая процедура находятся в
`pilot-production-deployment.md` и `production-deployment-runbook.md`.

## Развёрнутая инфраструктура

- приложение: `https://store-analytics.net`;
- VPS: Ubuntu 24.04 LTS, public IPv4 `92.53.127.24`, private IPv4 `10.20.0.10`;
- managed PostgreSQL 16: private IPv4 `10.20.0.20`, база `store_analytics`, schema `app`;
- private S3: bucket `5e8de462-4a0c-42a7-9a3b-e4d432c18eaf`, versioning и Object Lock включены,
  максимальный объём 100 GB;
- worker release: `v0.1.0-pilot.5`, commit `c561b63`;
- API временно оставлен на `v0.1.0-pilot.4`, web — на `v0.1.0-pilot.2`, так как исправление
  касается только фоновой синхронизации;
- контейнеры `web`, `backend-api`, `backend-worker` находятся в состоянии `healthy`;
- HTTP перенаправляется на HTTPS; сертификат Caddy и обязательные security headers проверены;
- API и management ports на host не опубликованы.

## База данных и безопасность

- Flyway schema version: 33;
- migration role: `store_migrator`;
- runtime role: `store_runtime`, CRUD без schema CREATE, TRUNCATE и DDL;
- backup role: `store_backup_reader`, read-only;
- `search_path=app, pg_catalog` закреплён отдельно для каждой роли;
- deployment после каждой миграции идемпотентно восстанавливает минимальные schema/object/default
  privileges;
- соединения используют TLS 1.3 с `sslmode=verify-full` и закреплённым CA managed PostgreSQL.

## Backup

- первый encrypted logical backup создан и проверен 2026-08-07;
- object:
  `postgres/daily/2026/08/07/store-analytics-20260807T153755Z.dump.gpg`;
- pipeline: `pg_dump` custom format, `pg_restore --list`, AES-256, SHA-256 manifest, S3 upload и
  `head-object` verification;
- `store-analytics-backup.timer` включён, ближайшие запуски выполняются ежедневно;
- перед загрузкой 7 августа внеплановый encrypted logical backup завершён с `Result=success`;
- restore drill и lifecycle policy остаются отдельными launch gates.

## Pilot data bootstrap

- initial developer administrator активирован; постоянный пароль хранится только в root-owned
  secret на VPS;
- customer-approved classification `customer-approved-2026-08-07-v2` и последующие reviewed
  supplements импортированы; на конец загрузки 7 августа классифицированы все 2625 products;
- classification readiness для `2026-07-01`: `true`;
- initial durable backfill job:
  `4325e996-92eb-4c09-889f-9ae6607c9730`;
- initial period `2026-07-01` — `2026-08-06` завершён со статусом `SUCCESS`;
- точечная задача за 7 августа `819c218d-2bcd-4863-b2c9-ec9c2f8339ed` завершена
  `SUCCESS` 2026-08-08 в 14:00:11 `Europe/Kaliningrad`;
- за 7 августа сохранено 50 продаж / 127 позиций для «МАГАЗИН» и 31 продажа / 52 позиции для
  «МобиСфера»;
- scheduler проверяет один и тот же overlap 3 дня в 03:15, 04:15 и 05:15
  `Europe/Kaliningrad`;
- `LIVESKLAD_RATE_LIMIT` переводит job в `WAITING_RETRY`; worker продолжает автоматически после
  `next_attempt_at`, завершённые шаги повторно не теряются.

## Инцидент синхронизации 2026-08-08

- 7 августа отсутствовало в аналитике, потому что одноразовый cron на 03:15 был пропущен во время
  замены worker-контейнера; запись `INCREMENTAL` в `sync_jobs` не создавалась;
- исправление добавляет recovery-проверки в 04:15 и 05:15, не создаёт дубли после успеха, не
  конкурирует с активной задачей и разрешает повтор нового job только после terminal recoverable
  failure;
- профильный `SyncJobIntegrationTest` прошёл; полный backend suite обнаружил пять существовавших
  до изменения несвязанных тестовых расхождений (четыре ожидания schema 32 вместо 33 и одну
  устаревшую LLM fixture);
- при загрузке LiveSklad создал 10 новых product identities; они рассмотрены и назначены через
  ADMIN API, 12 уже сохранённых snapshots точечно reconciled без повторной загрузки дня;
- итоговые инварианты: products без assignment — 0, активные `UNMAPPED` items — 0, открытые
  `UNMAPPED_PRODUCT` issues — 0, расхождения assignment/category snapshot — 0;
- причина повторного появления новых products: отдельные карточки/коды для единиц б/у техники.
  Для следующего изменения нужен отдельный high-confidence automatic rule layer с тестами ложных
  срабатываний; случайный fallback в общую категорию запрещён.

## Открытые pilot gates

- [x] initial backfill перешёл в `SUCCESS`;
- [x] две торговые точки распознаны как «МАГАЗИН» и «МобиСфера»;
- [ ] продажи, возвраты, сотрудники и payroll сверены на контрольных датах;
- [ ] созданы три именные customer accounts либо письменно принят риск shared account;
- [ ] разработчик связал Telegram и получил тестовый technical alert;
- [ ] Telegram webhook/linking/delivery включены и проверены;
- [ ] указан Yandex Cloud folder ID, включены snapshot/generation/publication и provider budget;
- [ ] проведён isolated restore drill и зафиксированы RPO/RTO;
- [x] временное правило `NOPASSWD` удалено после завершения настройки.

## Операторские команды

Статус контейнеров:

```bash
sudo docker ps
```

Статус initial backfill:

```bash
sudo /opt/store-analytics/deploy/bin/check-initial-backfill.sh
```

Public smoke:

```bash
sudo env APP_DOMAIN=store-analytics.net \
  /opt/store-analytics/deploy/bin/smoke.sh
```

Проверка backup timer:

```bash
sudo systemctl list-timers store-analytics-backup.timer --no-pager
```
