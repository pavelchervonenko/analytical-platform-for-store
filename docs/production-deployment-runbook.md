# Production deployment runbook

Практический журнал подготовки и развёртывания production-среды Store Analytics. Документ
дополняется по мере настройки инфраструктуры и используется вместе с
[`deployment-and-operations.md`](deployment-and-operations.md), где зафиксирована целевая
архитектура и эксплуатационная политика.

Последнее обновление: 2026-08-07.

## 1. Правила ведения

- Не записывать сюда пароли, SSH private key, S3 Secret Key, токены, session secrets и ключ
  шифрования backup.
- Допустимо фиксировать имена ресурсов, IP-адреса, fingerprint сертификатов и имена технических
  пользователей.
- Каждый этап считается завершённым только после проверки командой или restore/acceptance test.
- Значение `Подтвердить` означает, что настройка обсуждалась, но ещё не была доказана выводом
  команды или скриншотом панели.
- Фактические секреты должны находиться в root-owned файлах с режимом `0600` или в согласованном
  secret manager, но не в Git, Docker image, Compose-файле или shell history.

## 2. Текущее состояние

| Область | Статус | Результат |
| --- | --- | --- |
| Аккаунт и владение | Выполнено | Timeweb Cloud оформлен на заказчика |
| VPC | Выполнено | Приватная сеть `10.20.0.0/24` |
| Application VM | Выполнено | Ubuntu 24.04 LTS, SSH hardening, UFW, Docker |
| Application release candidate | Проверено локально | 674 backend tests, contract/security checks и live API/SPA acceptance прошли; release images ещё не зафиксированы |
| Managed PostgreSQL | Частично | Private endpoint и TLS `verify-full` проверены; runtime/backup roles ещё нужны |
| S3 backup bucket | Частично | Запись, versioning и Object Lock проверены; backup pipeline ещё не реализован |
| Домен и DNS | Домен зарегистрирован, DNS настроен | `store-analytics.net`; `A` → `92.53.127.24`, `AAAA` отсутствует |
| Caddy и HTTPS | Не начато | Требуются публикация single origin, headers, limits и trusted proxy verification |
| Production Compose | Заготовка | Текущий файл собирает backend из source и не содержит web/migrate/backup topology; для production не принят |
| Backup/restore | Не начато | Требуются encrypted dump, retention, alert и restore drill |
| Мониторинг приложения | Не начато | Сейчас доступна только инфраструктурная статистика Timeweb/Zabbix |

### 2.1 Application acceptance evidence на 2026-08-06

Подтверждённое состояние release candidate на локальном тестовом контуре:

- полный `:backend:check` на Java 21/PostgreSQL 16: 674 теста в 239 suites, без failures,
  errors и skipped;
- Checkstyle, OpenAPI compatibility, Gradle supply-chain integrity и operator-script security
  прошли;
- рабочая тестовая БД штатно мигрирована V29→V30 при startup;
- оба существующих report snapshots восстановили воспроизводимый SHA-256 (`2` hashed,
  `0` mismatched), `payload` имеет тип `text`, immutable trigger включён;
- login, список отчётов и обе report revisions возвращают `200` через авторизованный API;
- desktop/tablet/mobile Playwright acceptance основных пользовательских разделов и переключение ревизий
  «Актуальная ↔ История» прошли без HTTP 500, query/runtime errors.

Это закрывает найденный application-level regression, но не является production go-live evidence.
Проверка не доказывает Caddy/TLS/trusted proxy, production secrets и роли БД, immutable image
promotion, backup/restore, external alerts, production-sized migration duration или rollback.
Соответствующие launch gates ниже остаются незакрытыми до проверки на staging и production.

## 3. Целевая схема публикации

Используется один public origin:

```text
https://<production-domain>/       -> Caddy -> frontend
https://<production-domain>/api/*  -> Caddy -> backend API
```

Это исключает production CORS между frontend и backend и упрощает cookies, CSRF, TLS и Telegram
webhook. PostgreSQL, внутренние backend-порты и S3 credentials не публикуются наружу.

## 4. Инвентаризация Timeweb Cloud

### 4.1 Сеть

| Параметр | Значение |
| --- | --- |
| Регион | Санкт-Петербург |
| VPC CIDR | `10.20.0.0/24` |
| VM private IPv4 | `10.20.0.10` |
| PostgreSQL private IPv4 | `10.20.0.20` |
| VM public IPv4 | `92.53.127.24` |
| Public IPv6 | Отключён в ОС; global IPv6 отсутствует |

### 4.2 Application VM

| Параметр | Значение |
| --- | --- |
| Hostname | `store-analytics-prod-app-01` |
| ОС | Ubuntu 24.04.4 LTS, Noble |
| Архитектура | `x86_64` |
| Профиль | 4 vCPU / 8 GiB RAM / 80 GiB disk |
| Timezone | UTC |
| Административный пользователь | `pavel`, группа `sudo` |
| Прямой SSH для root | Запрещён |
| SSH password authentication | Запрещён |
| Deployment root | `/opt/store-analytics`, `root:root`, `0755` |
| Secret/config root | `/etc/store-analytics`, `root:root`, `0700` |
| Runtime state root | `/var/lib/store-analytics`, `root:root`, `0750` |

Локальный приватный SSH-ключ хранится в WSL:

```text
~/.ssh/store-analytics-prod
```

Подключение:

```bash
ssh \
  -o IdentitiesOnly=yes \
  -o PasswordAuthentication=no \
  -i ~/.ssh/store-analytics-prod \
  pavel@92.53.127.24
```

Для административных команд используется `sudo`; прямой вход `root@92.53.127.24` намеренно
запрещён.

### 4.3 Managed PostgreSQL

| Параметр | Значение |
| --- | --- |
| Cluster name | `store-analytics-prod-pg` |
| PostgreSQL | 16 |
| Профиль | 2 vCPU / 4 GiB RAM / 40 GiB disk |
| Public endpoint | Не используется |
| Private endpoint | `10.20.0.20:5432` |
| Application database | `store_analytics` |
| Migration role | `store_migrator` |
| Runtime role | `store_runtime` |
| Logical backup role | `store_backup_reader` |
| Application schema | `app`, owner `store_migrator` |
| Provider physical backups | Ежедневно с `00:15 МСК`, хранить 7 копий |
| Provider-generated database | `default_db`, приложением не используется |
| Provider-generated user | `gen_user`, приложением не используется |
| TLS certificate DNS identity | `managed-631415-8744455` |
| TLS certificate issuer | Self-signed `managed-631415-8744455` |
| Certificate validity | 2026-08-05 — 2036-08-02 |
| SHA-256 fingerprint | `B5:08:F5:A0:D6:7D:DB:2F:D0:24:20:2A:51:04:3D:1D:79:3A:11:D5:0B:BF:0C:A6:37:1C:65:85:4D:1D:1C:DF` |
| Runtime CA path | `/etc/store-analytics/pki/postgresql-ca.crt`, `root:root`, `0644` |

Внутреннее DNS-имя сертификата не разрешается через `getent`. Для `libpq` используются отдельно
certificate hostname и network address:

```bash
sudo /usr/bin/psql \
  "host=managed-631415-8744455 \
hostaddr=10.20.0.20 \
port=5432 \
dbname=store_analytics \
user=store_migrator \
sslmode=verify-full \
sslrootcert=/etc/store-analytics/pki/postgresql-ca.crt" \
  -W
```

Проверено:

- TCP-доступ к `10.20.0.20:5432` по VPC;
- TLS session;
- hostname verification через `sslmode=verify-full`;
- fingerprint root-owned CA совпадает с сертификатом DBaaS;
- подключение через root-owned CA использует TLS 1.3 и `TLS_AES_256_GCM_SHA384`;
- runtime/backup роли не имеют `SUPERUSER`, `CREATEDB`, `CREATEROLE` или `BYPASSRLS`;
- default privileges схемы `app` дают runtime только DML, backup только чтение и никому не дают `CREATE`;
- глобальные defaults мигратора не дают `PUBLIC` права `EXECUTE` на функции или `USAGE` на типы;
- TLS-login ролей `store_runtime` и `store_backup_reader` проверен с `current_schema=app`;
- DB credentials доставлены в три root-owned файла `0600` в `/etc/store-analytics/secrets`;
- физические backup DBaaS включены ежедневно, retention 7 копий;
- создание, запись и rollback тестовой транзакции пользователем миграций.

До production необходимо:

- запускать Flyway в схеме `app` и после миграции проверить фактические ACL всех объектов;
- подключать root-owned CA read-only только в нужные контейнеры;
- настроить storage/connection alerts.

### 4.4 S3 backup storage

| Параметр | Значение |
| --- | --- |
| Endpoint | `https://s3.twcstorage.ru` |
| Region | `ru-1` |
| Bucket | `5e8de462-4a0c-42a7-9a3b-e4d432c18eaf` |
| Access | Private |
| Service user | `store-backup-writer` |
| Service user access | Read and write только для backup bucket |
| Root credentials file | `/etc/store-analytics/secrets/s3-backup-credentials`, `root:root`, `0600` |
| Root AWS config file | `/etc/store-analytics/secrets/s3-backup-config`, `root:root`, `0600` |
| Versioning | Включён и проверен |
| Object Lock | `GOVERNANCE`, default retention 48 часов |
| Storage class | Cold (Холодный) |
| Maximum bucket size | 100 GB; при достижении лимита бакет становится read-only |

Проверочная загрузка с VPS успешно создала:

```text
s3://5e8de462-4a0c-42a7-9a3b-e4d432c18eaf/connectivity-test/vps.txt
```

Ответ `head-object` содержал `VersionId`, `ObjectLockMode=GOVERNANCE` и дату удержания через 48
часов. AWS CLI установлен через `pipx`:

```text
aws-cli/1.34.30
```

S3 credentials ротированы 2026-08-07 после переноса в root-owned secret delivery. Новый ключ
проверен командой `head-object`; provisioning-профиль `/home/pavel/.aws` удалён.

S3-инфраструктура не означает готовый backup. Ещё требуются:

- `pg_dump --format=custom`;
- шифрование до upload;
- checksum и manifest;
- временный object key и проверка загруженного объекта;
- отдельные retention classes: hourly 48h, daily 14d, weekly 8w, monthly 12m;
- alert о failed/stale backup;
- изолированный restore test;
- еженедельная encrypted copy за пределы Timeweb.

### 4.5 Домен, владение и требования ЕСИА

Домен `store-analytics.net` зарегистрирован 2026-08-05 через Timeweb Cloud и оформлен на
заказчика. Административный email должен быть подтверждён по письму регистратора.

Требования с 2026-09-01 относятся к регистрации и обслуживанию национальных зон `.RU`, `.РФ` и `.SU`.

Зафиксированные последствия:

- регистрация до 2026-09-01 выполняется по действующему порядку, но не освобождает администратора
  от ЕСИА при будущем продлении, смене администратора/регистратора, делегировании и других значимых
  операциях после этой даты;
- домен должен оформляться непосредственно на заказчика, а не на разработчика или отдельного
  сотрудника как физическое лицо;
- для юридического лица потребуется учётная запись организации в ЕСИА, связанная с подтверждённой
  учётной записью руководителя или уполномоченного сотрудника;
- данные заказчика у регистратора должны совпадать с данными ЕСИА;
- отсутствие домена не блокирует подготовку VM, DB, S3 и deployment artifacts, но блокирует
  финальную настройку public HTTPS, production cookies и Telegram webhook.

Для дальнейшего обслуживания должны быть зафиксированы:

1. юридическое или физическое лицо, которое будет администратором домена;
2. recovery email и телефон администратора;
3. основной production-домен `store-analytics.net`;
4. регистратор и DNS provider;
5. минимум два ответственных лица за продление.

Правовые и отраслевые источники:

- Федеральный закон от 29.12.2025 № 569-ФЗ;
- FAQ Координационного центра доменов `.RU`/`.РФ` о порядке с 2026-09-01;
- инструкция Timeweb Cloud по идентификации администратора домена через ЕСИА.

Принятое решение: использовать отдельный `store-analytics.net` как основной production-домен.
Дополнительный `.ru` на первом этапе не приобретается. Требование ЕСИА для `.net` не применяется,
но домен всё равно оформляется на заказчика с достоверными регистрационными данными и
подтверждённым recovery email. После активации здесь фиксируются registrar, expiration date,
состояние auto-renewal, DNS provider и ответственные за продление.

## 5. Firewall

Защита реализована двумя слоями: Timeweb Cloud Firewall и UFW на VM.

### 5.1 Разрешённый ingress на VM

| Источник | Протокол/порт | Назначение |
| --- | --- | --- |
| Any IPv4 | TCP/22 | SSH с key-only authentication и rate limit в UFW |
| Any IPv4 | TCP/80 | Caddy HTTP/ACME/redirect, пока сервис не запущен |
| Any IPv4 | TCP/443 | Caddy HTTPS, пока сервис не запущен |
| `92.53.116.12/32` | TCP/10050 | Timeweb Zabbix |
| `92.53.116.111/32` | TCP/10050 | Timeweb Zabbix |
| `92.53.116.119/32` | TCP/10050 | Timeweb Zabbix |
| DHCP infrastructure | UDP/68 | Получение адреса VM |

UFW:

```text
Status: active
Logging: low
Default incoming: deny
Default outgoing: allow
Default routed: deny
```

Проверено, что после установки и перезапуска Docker публично слушают только `22/tcp` и
ограниченный cloud agent `10050/tcp`. DNS resolver слушает только loopback. Docker может обходить
UFW для опубликованных container ports, поэтому production Compose не должен публиковать API,
worker, migration, backup или debug ports на `0.0.0.0`.

## 6. Docker host

| Компонент | Версия/состояние |
| --- | --- |
| Docker Engine | 29.7.1 |
| Docker API | 1.55 |
| containerd | 2.2.6 |
| runc | 1.3.6 |
| Docker Compose | 5.4.0 |
| Startup | `docker.service` enabled |
| Default logging driver | `local` |
| Log rotation | 10 MiB × 5 files per container, compression enabled |
| Live restore | Enabled |
| Default no-new-privileges | Enabled |

Конфигурация: `/etc/docker/daemon.json`. Она была проверена через `dockerd --validate`, после чего
Docker успешно перезапущен и повторно запустил `hello-world`.

Пользователь `pavel` намеренно не добавлен в группу `docker`, поскольку доступ к Docker socket
практически эквивалентен root. Административные операции выполняются через `sudo docker`.

## 7. Зафиксированные архитектурные решения

| Решение | Обоснование |
| --- | --- |
| Один application VM на первом этапе | Соответствует бюджету; RTO зависит от restore/recreate процедуры |
| Managed PostgreSQL, один DB node | Уменьшает операционную нагрузку; принимает downtime при отказе node |
| DB только по VPC | Исключает публичную поверхность PostgreSQL |
| TLS `verify-full` к DB | Проверяет шифрование и идентичность сервера |
| Single-origin frontend/API | Не нужен production CORS; проще cookies, CSRF и TLS |
| S3 только private | Backup не является публичным контентом |
| Object Lock `GOVERNANCE` | Защита от случайного удаления с возможностью контролируемого break-glass |
| Отдельный S3 service user | Primary S3 credentials не попадают в приложение |
| MFA пока не внедряется | Явно принятое временное ограничение |
| VPN/IP allowlist пока не внедряется | SSH остаётся key-only, rate-limited и защищён cloud firewall + UFW |

## 8. Следующие шаги

1. Подтвердить административный email и проверить auto-renewal для `store-analytics.net`.
2. Проверить распространение `A` record `store-analytics.net` → `92.53.127.24`; `AAAA` не создавать,
   пока IPv6 отключён.
3. Подготовить root-owned deployment directories и secret delivery.
4. Создать PostgreSQL runtime и backup roles с least privilege.
5. Завершить production Dockerfiles и Compose для ролей web/API/worker/migrate/backup.
6. Настроить Caddy, HTTPS, security headers, request limits и trusted proxy boundary.
7. Применить Flyway migration отдельным one-shot deployment step.
8. Реализовать и проверить encrypted backup pipeline и restore drill.
9. Подключить independent HTTP/HTTPS, certificate, readiness, backup-age и stale-sync monitoring.
10. После готовности функций добавить Telegram webhook/outbox и Yandex LLM worker с timeout,
    concurrency, retry, quota и cost limits.

## 9. Production launch gates

- [ ] Домен принадлежит заказчику, DNS и recovery access задокументированы.
- [ ] HTTPS автоматически выпускается и продлевается.
- [ ] Внешне доступны только `22`, `80`, `443` и адресно ограниченный `10050`.
- [ ] Backend runtime role не имеет DDL и administrative privileges.
- [ ] Migration role не используется API/worker.
- [ ] Backup role не имеет DML/DDL.
- [ ] Secrets отсутствуют в Git, image layers, Compose environment и shell history.
- [ ] Images зафиксированы digest, проверены и имеют SBOM/scan evidence.
- [ ] Healthchecks, resource limits и restart policies определены для каждого контейнера.
- [ ] Backup создаётся, шифруется, загружается и проверяется автоматически.
- [ ] Restore drill подтверждает фактические RPO/RTO.
- [ ] Внешние alerts доставляются разработчику и ответственному представителю заказчика.
- [ ] Rollback и break-glass процедуры проверены.
- [ ] Финальная production acceptance выполнена для frontend, API, worker, Telegram и LLM.
