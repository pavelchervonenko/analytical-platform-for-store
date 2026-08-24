# Production deployment runbook

Последнее обновление: 2026-08-24.

Практический порядок релиза Store Analytics. Архитектурная политика:
[deployment-and-operations.md](deployment-and-operations.md). Текущий кандидат:
[RELEASE_CANDIDATE_2026-08-24.md](RELEASE_CANDIDATE_2026-08-24.md).

## 1. Подтвержденная база

- origin: `https://store-analytics.net`;
- production release: `v0.1.0-pilot.22`, commit `2e8f9c2`;
- schema: `V44`;
- Compose: `/opt/store-analytics/deploy/compose.production.yml`;
- release env: `/etc/store-analytics/release.env`;
- services: `backend-api`, `backend-worker`, `web`;
- DB: managed PostgreSQL 16, TLS `verify-full`;
- API/management ports наружу не публикуются.

Текущий UI-кандидат не содержит миграций или backend runtime delta. Восстановление июльских
возвратов, order-webhook canary и активация `v21/schema3` — отдельные change records.

## 2. Неподлежащие обсуждению правила

- деплоится только точный reviewed commit и immutable image digest;
- secrets не печатаются, не передаются аргументами и не записываются в Git/shell history;
- перед миграцией обязательно проходит `preflight-release.sh`;
- backup/health должны быть исправны до начала;
- миграция, приложение и post-deploy smoke завершаются до бизнес-операций/backfill;
- rollback разрешен только при подтвержденной совместимости текущей schema;
- если rollback запрещен, используется проверенный forward fix или restore;
- временные recovery-скрипты не входят в релиз автоматически.

## 3. Локальный release gate

```bash
git status --short
git fetch origin
git rev-list --left-right --count origin/codex/livesklad-daily-webhook-protection...HEAD
git diff --check
./gradlew :backend:check
cd frontend
npm ci
npm run check
```

Для материального UI-изменения:

```bash
VISUAL_ROUTES='/overview,/plan,/insights' npm run visual:local
```

Команда только локальная. После нее вручную просмотреть desktop/tablet/mobile artifacts. До push
убедиться, что `.codex-prod-recovery/`, `visual-artifacts/`, credentials и customer exports не
попали в index.

## 4. Подготовка release metadata

Release env создается из проверенного шаблона и должен содержать:

- точные `BACKEND_IMAGE`, `WEB_IMAGE` и digest metadata;
- `RELEASE_ID`, `SCHEMA_VERSION`;
- допустимые schema source/runtime ranges;
- non-secret runtime flags;
- пути к root-owned secret files.

Для возвратных webhook до preflight существуют оба secret file:

- `LIVESKLAD_SALE_RETURN_WEBHOOK_SECRET_FILE`;
- `LIVESKLAD_ORDER_RETURN_WEBHOOK_SECRET_FILE`.

Проверка Compose выполняется **до миграции**:

```bash
cd /opt/store-analytics
sudo /opt/store-analytics/deploy/bin/preflight-release.sh \
  /etc/store-analytics/release.env
```

Отдельно записать без секретов current release и image references:

```bash
sudo sed -n -E '/^(RELEASE_ID|SCHEMA_VERSION|BACKEND_IMAGE|WEB_IMAGE)=/p' \
  /etc/store-analytics/release.env
sudo cat /var/lib/store-analytics/release-state/current-release
sudo cat /var/lib/store-analytics/release-state/database-schema-version
```

## 5. Перед deploy

```bash
sudo systemctl status store-analytics-backup.timer --no-pager
sudo systemctl status store-analytics-health.timer --no-pager
cd /opt/store-analytics
sudo docker compose \
  --env-file /etc/store-analytics/release.env \
  -f deploy/compose.production.yml ps
```

Убедиться:

- нет активной миграции;
- нет sync/backfill, который нельзя безопасно прервать;
- последняя backup-задача успешна и объект проверяем;
- API/worker/web healthy;
- известен previous release и его schema compatibility;
- есть владелец наблюдения за релизом и окно rollback.

## 6. Deploy

Штатный скрипт сам:

1. повторяет preflight;
2. проверяет schema metadata;
3. сохраняет previous/candidate env;
4. получает immutable images;
5. сверяет ожидаемую schema image;
6. запускает one-shot migration;
7. восстанавливает least-privilege ACL;
8. поднимает API, затем worker, затем web;
9. выполняет public smoke;
10. фиксирует current release только после успеха.

Команда:

```bash
sudo /opt/store-analytics/deploy/bin/deploy.sh \
  /etc/store-analytics/release.env
```

Не запускать `docker compose up` вручную вместо скрипта для обычного релиза.

## 7. Post-deploy acceptance

```bash
cd /opt/store-analytics
sudo docker compose \
  --env-file /etc/store-analytics/release.env \
  -f deploy/compose.production.yml ps

sudo env APP_DOMAIN=store-analytics.net \
  /opt/store-analytics/deploy/bin/smoke.sh
```

Проверить:

- release ID и schema;
- `backend-api`, `backend-worker`, `web` healthy;
- HTTPS, HSTS, `nosniff`, `/livez`, `/readyz`;
- public actuator недоступен;
- login и один read-only период;
- для UI-релиза: `/overview`, `/plan`, `/insights`;
- отсутствие новых 5xx/browser errors;
- scheduler не создал неожиданный overlapping job;
- webhook receiver/worker flags совпадают с approved plan.

Не включать order worker или новую ИИ-схему «заодно».

## 8. Rollback и forward fix

Штатный rollback:

```bash
sudo /opt/store-analytics/deploy/bin/rollback.sh
```

Скрипт откажется запускать previous image, если он несовместим с фактической schema. Этот отказ
нельзя обходить ручным изменением state file.

Если миграция уже изменила schema и previous runtime несовместим:

```bash
sudo /opt/store-analytics/deploy/bin/forward-fix.sh \
  /etc/store-analytics/forward-fix.env
```

Forward-fix env должен быть заранее review и preflight. Restore используется только по отдельному
incident/DR runbook с подтвержденным backup.

Для текущего UI-кандидата schema остается `V44`; ожидаемый rollback — возврат previous web digest.

## 9. Webhook canary

Текущий production режим:

- receiver: enabled;
- sale-return worker: enabled;
- order-return worker: disabled.

Order canary выполняется отдельно:

1. сохранить настоящий `ORDER_RETURN` в inbox;
2. проверить `eventId`, `webhook_kind`, `data.id`, delivery count и payload mismatch;
3. вручную подтвердить, что `data.id` — ID нужного заказа;
4. включить order worker для ограниченного наблюдаемого окна;
5. проверить повторную загрузку заказа/позиций и отсутствие двойного эффекта;
6. при mismatch выключить только order flag и сохранить событие для диагностики.

Полный протокол: [livesklad-webhook-receiver.md](livesklad-webhook-receiver.md).

## 10. Историческое восстановление и backfill

После успешного deploy, не одновременно с ним:

- подтвержденные известные возвраты — по
  [validated-return-recovery-runbook.md](validated-return-recovery-runbook.md);
- исторические месяцы — отдельными backfill jobs;
- после каждого магазина/месяца — CRM reconciliation;
- незавершенный текущий день исключается из контрольного периода;
- mismatch не исправляется прямым SQL.

## 11. Завершение change

Зафиксировать:

- release ID, commit и image digests;
- schema до/после;
- preflight/deploy/smoke result;
- время и владельца наблюдения;
- runtime flags без secret values;
- отклонения и решение rollback/continue.

Production считается измененным только после фактического успешного deploy. Локальная проверка,
push или сборка image не меняют статус production.
