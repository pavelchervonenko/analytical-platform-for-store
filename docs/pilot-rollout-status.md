# Pilot rollout status

Последнее обновление: 2026-08-07. Этот файл фиксирует фактическое состояние первого production
rollout. Архитектурный стандарт и повторяемая процедура находятся в
`pilot-production-deployment.md` и `production-deployment-runbook.md`.

## Развёрнутая инфраструктура

- приложение: `https://store-analytics.net`;
- VPS: Ubuntu 24.04 LTS, public IPv4 `92.53.127.24`, private IPv4 `10.20.0.10`;
- managed PostgreSQL 16: private IPv4 `10.20.0.20`, база `store_analytics`, schema `app`;
- private S3: bucket `5e8de462-4a0c-42a7-9a3b-e4d432c18eaf`, versioning и Object Lock включены,
  максимальный объём 100 GB;
- release: `v0.1.0-pilot.2`;
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
- restore drill и lifecycle policy остаются отдельными launch gates.

## Pilot data bootstrap

- initial developer administrator активирован; постоянный пароль хранится только в root-owned
  secret на VPS;
- customer-approved classification `customer-approved-2026-08-07-v2` импортирована:
  2514 products и 2514 assignments;
- classification readiness для `2026-07-01`: `true`;
- initial durable backfill job:
  `4325e996-92eb-4c09-889f-9ae6607c9730`;
- период: `2026-07-01` — `2026-08-06` включительно;
- nightly schedule включён на 03:15 `Europe/Kaliningrad`, overlap 3 дня;
- `LIVESKLAD_RATE_LIMIT` переводит job в `WAITING_RETRY`; worker продолжает автоматически после
  `next_attempt_at`, завершённые шаги повторно не теряются.

## Открытые pilot gates

- [ ] initial backfill перешёл в `SUCCESS`;
- [ ] две торговые точки распознаны как «Магазин» и «Мобисфера» и сверены с заказчиком;
- [ ] продажи, возвраты, сотрудники и payroll сверены на контрольных датах;
- [ ] созданы три именные customer accounts либо письменно принят риск shared account;
- [ ] разработчик связал Telegram и получил тестовый technical alert;
- [ ] Telegram webhook/linking/delivery включены и проверены;
- [ ] указан Yandex Cloud folder ID, включены snapshot/generation/publication и provider budget;
- [ ] проведён isolated restore drill и зафиксированы RPO/RTO;
- [ ] временное правило `NOPASSWD` удалено после завершения настройки.

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
