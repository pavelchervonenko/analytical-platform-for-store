---
doc_schema: 1
doc_type: evidence
status: historical
owner: security
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved July 2026 audit evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/security/baseline.md"
  - "docs/security/threat-model-and-risk-register.md"
  - "docs/runbooks/production-deployment.md"
original_content_sha256: 40f8fba7bda71d682f2f630fd0443629327b644f3d4bc84b2bc422191d12dfd6
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during documentation reform. Current replacements: `docs/security/baseline.md`, `docs/security/threat-model-and-risk-register.md`, `docs/runbooks/production-deployment.md`.

# Временный аудит production-ready и безопасности

Дата ревизии: 2026-07-25
Статус: **рабочий документ, не является разрешением на production-запуск**
Область: backend, frontend, API-контракт, Docker Compose, хранение секретов и артефактов,
эксплуатация, отказоустойчивость и сценарии злоупотребления.

Нормативный источник решений по инфраструктуре и эксплуатации —
`docs/archive/legacy-contracts/deployment-and-operations.md`, прежде всего разделы 21–29. Настоящий аудит не предлагает
альтернативную схему деплоя: он показывает, какие части выбранной схемы уже реализованы, какие ещё
нужно реализовать и каким доказательством закрывается каждый риск. При расхождении приоритет имеет
поддерживаемый документ в `docs/`.

Этот файл специально сделан временным. После устранения замечаний его лучше превратить в три
поддерживаемых документа: threat model, production runbook и security checklist релиза.

## 1. Короткий вывод

Основа приложения спроектирована здраво. Для браузерного закрытого кабинета правильно выбраны
server-side session, HttpOnly cookie, CSRF-защита и same-origin API. На backend уже есть fail-closed
маршрутизация, централизованная проверка доступа к магазину, безопасные DTO, generic auth errors,
ограничение входов, raw-аудит и защита важных финансовых состояний. Frontend не хранит пароль,
session id или bearer token в Web Storage, использует единый API client, runtime-валидацию Zod и
сбрасывает cache при завершении сессии.

Целевая эксплуатационная архитектура уже выбрана: публичный Internet, customer-owned domain и
Timeweb Cloud, одна application VM с Docker Compose, Caddy на edge, один API replica, отдельный
worker, managed PostgreSQL 16 по private VPC/TLS, immutable images из GHCR, RPO 1 час и RTO 4 часа.
Это правильная для текущего масштаба схема. Однако **она пока описана как target state, а не
реализована и доказана**, поэтому запуск с реальными зарплатами и credentials LiveSklad ещё нельзя
считать уверенным production. Главные незакрытые пункты:

1. отсутствует `.dockerignore`, поэтому корневой Docker build context способен передать builder-у
   `.env`, `.git`, `outputs/` и другие ненужные файлы;
2. нет production web/Caddy image и Compose с ролями web/API/worker/migrate/backup, private
   networks, TLS и проверенной обработкой trusted proxy headers;
3. не реализованы hourly encrypted backup, immutable S3/off-provider copy и restore drill,
   доказывающий RPO 1 час/RTO 4 часа;
4. runtime-приложение и Flyway используют одну роль БД, то есть компрометация приложения может
   дать права владельца схемы и ослабить ценность DB-аудита;
5. для initial launch выбрано временное исключение SEC-01 без application MFA; оно допустимо
   только после подписи, с полным набором compensating controls и сроком не более 90 дней;
6. нет CI security gates, E2E security regression suite и автоматизированной проверки зависимостей;
7. `npm audit --omit=dev` сейчас возвращает две high-записи для одной advisory React Router;
8. backend roles и one-shot migration реализованы, но production topology, отдельные DB users,
   точные grants и credential delivery пока не подключены deployment-конфигурацией.

Это исправимый объём. Модульный монолит и единый React SPA здесь уместны; Kubernetes,
microfrontend, JWT и отдельный API Gateway на данном масштабе не нужны.

## 2. Что именно было проверено

Проверены в том числе незакоммиченные файлы и актуальная документация в `docs/`:

- Spring Security configuration, auth/session/CSRF/CORS, login throttling и store authorization;
- controller/DTO/service/repository boundaries и документация API;
- LiveSklad client, allowlist, timeouts, raw payload и sync lifecycle;
- frontend API client, cookies, cache, ошибки, Zod-контракты и опасные DOM API;
- `Dockerfile`, dev/prod Compose, `.gitignore`, env-примеры, скрипты и права файлов;
- retention/audit/report changes, включая текущие незакоммиченные файлы;
- frontend dependency audit и сборка обоих приложений.

Я намеренно **не открывал содержимое `.env`** и не выводил секреты. Проверялись только наличие,
git-status, ignore-правило и Unix permissions. Не выполнялись внешнее сканирование VPS, DAST по
реальному домену, TLS-аудит, нагрузочный тест и восстановление настоящей БД: инфраструктуры и
production URL в репозитории нет. Поэтому этот отчёт — code/config threat review, а не pentest.

Результаты локальной проверки:

- backend: `:backend:check` — успешно под JDK 21 в контейнере, включая Testcontainers;
- frontend: lint — успешно; Vitest — 25/25; production build — успешно;
- frontend production dependencies: `npm audit --omit=dev` — неуспешно, 2 high-записи,
  обе восходят к `GHSA-qwww-vcr4-c8h2`;
- локальный WSL по умолчанию использует Java 11, поэтому `./gradlew` без правильного toolchain/JDK
  завершается до сборки. CI должен фиксировать Java 21 явно.

## 3. Целевая архитектура связи frontend и backend

Зафиксированный production-путь:

```text
Public Internet
    |
    | HTTPS :443
    v
Caddy web (единственная публичная точка на application VM)
    |-- /, /assets/*  -> immutable React static files
    |-- /api/*        -> backend-api (один replica, private Compose network)
    `-- /healthz      -> минимальная публичная readiness без деталей
                              |
backend-worker (без ingress) -+-- TLS verify-full -> managed PostgreSQL 16/private VPC
    `-- HTTPS -> exact LiveSklad host allowlist

one-shot migrate -- migration_owner --> managed PostgreSQL
hourly backup -- backup_reader --> encrypted private S3 + weekly off-provider copy
```

Ключевое решение — **один origin** для HTML и `/api`. Тогда:

- браузер никогда не получает LiveSklad credentials;
- `JSESSIONID` остаётся Secure/HttpOnly cookie;
- CSRF token остаётся отдельной readable cookie и отправляется header-ом;
- CORS в production фактически не требуется для UI, но строгий allowlist можно сохранить;
- нет сложностей с third-party cookies;
- Caddy единообразно задаёт TLS, CSP, cache policy, routing и request limits.

API и worker строятся из одного immutable backend image, но запускаются в явных ролях. В API
отключены scheduler/worker/probes, в worker отключён или недоступен HTTP ingress и ровно один worker
владеет singleton schedules. Production не собирает исходники: CI публикует принятые в ephemeral
staging images в GHCR, а production получает те же digests.

Не следует добавлять JWT «потому что production». Для одного web-клиента server-side session
уменьшает риск кражи долгоживущего токена и уже хорошо встроена в текущую модель мгновенного
отзыва прав. BFF также не нужен: Spring backend уже является application-specific API.

## 4. Классы данных и правила хранения

| Класс | Примеры | Требование |
| --- | --- | --- |
| Secret | PostgreSQL/LiveSklad/bootstrap passwords, session secrets, TLS private key, backup key | Только secret files/manager, `0600`, rotation, никогда в Git/образ/frontend/log/CLI |
| Строго конфиденциальные | зарплата, начисления, audit trail, права пользователей | Least privilege, TLS, encrypted backup, no-store, журнал доступа |
| Конфиденциальные бизнес-данные | продажи, маржа, сотрудники, raw LiveSklad JSON, отчёты, product IDs/names | Retention, доступ по магазину, запрет публичных raw endpoint, ограничение экспорта |
| Внутренние | обезличенные health/metrics/build version | Только минимально нужное раскрытие |
| Публичные | hashed JS/CSS, favicon, login shell | Без секретов и runtime-конфигурации |

Найдено:

- `.env` корректно исключён из Git и не tracked, но имеет permission `0644`. На общем хосте это
  позволяет другим локальным пользователям читать файл. Требуется `chmod 600 .env` и такой же
  режим для Docker secret files и backup keys;
- `.env.example` содержит placeholders, но сейчас сам файл untracked. Его нужно проверить и
  закоммитить как безопасный шаблон без рабочих значений;
- явных tracked private keys, keystore, PEM или `.env` при выборочной проверке не найдено;
- `outputs/` untracked, но **не ignored**. Там есть product classification JSON/XLSX и изображения.
  Такие артефакты нельзя случайно отправлять в Docker context, Git, публичную CI-artifact storage
  или нешифрованный backup;
- `frontend/dist/` ignored, sourcemap production build отключён — это хорошо;
- переменные `VITE_*` всегда вшиваются в публичный JS bundle. В них допустимы только несекретные
  build metadata и относительный API base; никаких ключей и паролей.

## 5. Production blockers — исправить до реальных данных

### P0-01. Добавить корневой `.dockerignore`

**Доказательство:** prod Compose строит backend с `context: .`; `.dockerignore` отсутствует.
Docker передаёт builder-у весь context до выполнения `COPY`, поэтому то, что Dockerfile копирует
только `backend/`, не предотвращает передачу `.env` удалённому builder-у или cache backend.

Минимальный allowlist-подход предпочтительнее длинного blacklist. В context backend нужны только:

```text
*
!settings.gradle.kts
!build.gradle.kts
!backend/
!backend/**
```

Если Dockerfile перейдёт на wrapper, добавить только `gradlew`, `gradle/wrapper/**`. В любом случае
явно исключить `.env*`, `.git`, `outputs`, `docs`, `frontend`, `node_modules`, `dist`, `build`, logs,
IDE files и secret paths. Проверка готовности: `docker build` проходит, а `docker buildx` context
не содержит секреты/outputs.

### P0-02. Реализовать web/Caddy и executable production Compose

Текущий `docker-compose.prod.yml` — developer scaffold: он собирает backend из source, содержит
локальный PostgreSQL и не имеет web/edge service. Зафиксированная production-схема использует
managed PostgreSQL и готовые image digests. `vite preview` и `python -m http.server` остаются
только локальными инструментами.

Нужны:

- `frontend/Dockerfile`: pinned Node build stage, `npm ci`, Vite build, затем Caddy web image;
- `deploy/Caddyfile` и проверяемые production/staging Compose services;
- только Caddy публикует `80/443`; API/worker не имеют host ports, managed DB не имеет public IP;
- `80 -> 443`; TLS 1.2/1.3, автоматическое renewal и мониторинг срока сертификата;
- SPA fallback только для UI path. `/api/*`, `/actuator/*`, `/v3/api-docs/*` никогда не должны
  превращать backend 404/401/500 в `index.html`;
- hashed assets: `Cache-Control: public,max-age=31536000,immutable`;
- `index.html`, API, auth и финансовые ответы: `Cache-Control: no-store`;
- согласованные Caddy/Spring body/header/connection timeouts и request rate limits;
- backend healthcheck и graceful shutdown.

### P0-03. Корректно настроить trusted proxy headers

Статус backend-части на 2026-07-26: реализовано. Login endpoint использует единый
`ClientAddressResolver`, а не читает servlet remote address или forwarding header самостоятельно.
`server.forward-headers-strategy=none` запрещает неявное доверие на уровне контейнера. По умолчанию
trusted proxy allowlist пуст, поэтому клиентский `X-Forwarded-For` игнорируется. При явно заданных
`TRUSTED_PROXY_CIDRS` resolver идёт по цепочке справа налево и останавливается на ближайшем
недоверенном hop; malformed, duplicate, слишком длинная или слишком глубокая цепочка fail-closed
сворачивается к непосредственному peer. CIDR и IP literals валидируются без DNS lookup, `/0` и CIDR
с host bits запрещены.

IPv4 нормализуется до одного dotted-decimal представления, IPv4-mapped IPv6 — до IPv4. Для audit
псевдонимизируется полный нормализованный адрес; IP throttle группирует IPv6 по `/64`, чтобы rotation
interface identifiers не давала тривиальный обход. Успешный login очищает только email scope: общий
NAT/IP failure bucket намеренно сохраняется, иначе одна валидная учётная запись могла бы обнулять
защиту. Unit/integration tests покрывают trusted/untrusted peer, strict right-to-left proxy chain,
spoofed prefix, malformed/multiple headers, IPv4/IPv6/CIDR и общий NAT bucket.

Остаток относится к deployment: выделить фиксированный private subnet Caddy→backend, передать только
его CIDR, не публиковать backend port, настроить Caddy и coarse login rate limit. Production E2E
должен доказать адрес через настоящий Caddy и невозможность прямого/spoofed доступа. Если перед Caddy
позже появится CDN/load balancer, его ranges добавляются только после включения strict trusted-proxy
parsing на Caddy и отдельного regression test. Порог IP guard нужно откалибровать по числу
пользователей за общим NAT и реальной телеметрии, не ослабляя email guard.

### P0-04. Разделить migration owner, runtime и backup DB roles

Статус backend-границы на 2026-07-25: one-shot/runtime разделение и no-DDL API test реализованы.
Создание production DB users, точные grants, backup role и credential delivery остаются deployment
работой.

Текущий scaffold передаёт backend тот же `POSTGRES_USER`, которым инициализируется PostgreSQL.
Целевая managed DB должна выдать разные credentials. При SQL injection, RCE или утечке runtime
атакующий сможет не только читать данные, но и менять DDL, triggers и audit protections.

Нужны как минимум:

- `migration_owner`: владелец schema, доступен только one-shot migration job;
- `app_runtime`: `CONNECT/USAGE` и точные DML/sequence privileges, без
  `CREATE/ALTER/DROP`, superuser, role creation и bypass RLS;
- отдельный `backup_reader` без DML/DDL;
- `spring.flyway.user/password` отделены от `spring.datasource.*`;
- миграции выполняются отдельным одноразовым step перед rollout, затем owner credential недоступен
  приложению;
- DB integration test с runtime role доказывает работу API и невозможность удалить audit trigger
  или таблицу.

### P0-05. Реализовать выбранную backup/DR policy и доказать RPO/RTO

Managed PostgreSQL и replication не заменяют backup. Цели уже выбраны: RPO 1 час, RTO 4 часа,
короткое ночное maintenance window. В репозитории пока нет исполняемых backup/restore scripts и
результата timed restore drill.

До production реализовать и проверить:

- provider physical backups managed PostgreSQL;
- hourly encrypted `pg_dump` custom format в private S3 с versioning/Object Lock;
- weekly encrypted copy у другого российского provider/customer-controlled storage;
- retention: hourly 48h, daily 14d, weekly 8w, monthly 12m до финального customer approval;
- checksum, immutable/retention policy, alert о пропущенном backup;
- backup не содержит plaintext secret files рядом с dump;
- monthly restore в изолированную БД и quarterly full DR exercise;
- checksum/manifest, backup-age alert, ключ шифрования вне primary cloud и bounded temp storage;
- drill перед launch должен измеренно подтвердить RPO 1h/RTO 4h либо цели пересматриваются;
- документированный порядок восстановления, смены credentials и DNS/roll-forward.

### P0-06. Оформить и контролировать временное исключение SEC-01

Для initial public launch выбрано отсутствие application MFA, VPN и IP allowlisting. Это не
считается устранённым риском: SEC-01 должен быть подписан project owner/customer до появления
реальных employee/payroll data и внесён в release manifest.

Обязательные compensating controls: только именные учётные записи, отсутствие shared/default и
оставленного bootstrap credential, сильные уникальные пароли, проверенные короткие session limits,
email/IP throttling за trusted Caddy, auth/audit alerts и отрепетированная emergency revocation.
Cloud-provider и GitHub MFA обязательны независимо от SEC-01.

SEC-01 истекает через 90 дней после первого launch либо немедленно после credential attack,
существенного роста пользователей или privileged incident. Затем внедряется MFA (предпочтительно
WebAuthn/passkey, TOTP как переходный вариант) либо оформляется новое датированное исключение по
телеметрии. Будущий step-up реализуется backend action grant, а не только UI modal.

Бизнес-решение зафиксировано отдельно: все руководители могут видеть зарплату, подтверждать расчёт
и отмечать выплату; four-eyes не требуется. Это должно быть явно отражено в access matrix,
backend authorization и audit, а не расширено случайно через роль `ADMIN`.

### P0-07. CI/CD security gates и воспроизводимая supply chain

В репозитории не найден CI workflow, dependency bot, SAST/secret scan, image scan или SBOM.

Минимальный protected pipeline:

1. backend `check` под фиксированным JDK 21;
2. frontend `npm ci && npm run check`;
3. integration/Testcontainers;
4. dependency audit с явным, срочным и истекающим waiver-процессом;
5. secret scanning (включая history), SAST, container scan;
6. CycloneDX/SPDX SBOM и provenance;
7. build только из protected branch/tag, без production secrets на PR;
8. image digest deploy, подпись image и запрет deploy при failed gates;
9. миграции и rollback/roll-forward rehearsal на копии схемы.

Статус backend/repository-среза на 2026-07-27: Gradle 9.0.0 distribution закреплён официальным
`distributionSha256Sum`; checked-in wrapper JAR сверяется offline с официальным SHA-256. Создана
strict `gradle/verification-metadata.xml`: 441 component/800 artifacts, ровно один SHA-256 на
artifact, без broad trusted/ignored exceptions. Отдельная fail-closed проверка включена в backend
`check`; offline compile/checkstyle проходит, а временный tampering probe подтверждает отказ Gradle
при несовпавшем JAR checksum.

Остаток P0-07 относится к CI/release/deployment: унифицировать Dockerfile с wrapper, pin base image
по digest, добавить maintained wrapper-validation action, dependency/SAST/secret/image scans,
SBOM/provenance/signing, protected build и promotion exact digest. Generated verification metadata
нельзя обновлять в недоверенном runner или обходить `lenient/off` режимом.

### P0-08. Ввести проверяемые application roles API/worker/combined

Статус backend-части на 2026-07-25: реализовано. Валидируемый `app.runtime.role` поддерживает
`API`, `WORKER`, `COMBINED` и `MIGRATION`; неизвестное значение останавливает startup. Все
`@Scheduled` компоненты, metric refreshers, login-throttle cleanup и LiveSklad probe принадлежат
только `WORKER`/`COMBINED`. Архитектурный тест запрещает добавить scheduler без role guard,
condition tests проверяют все роли и invalid-сценарий, а integration smoke-test поднимает полный
API-контекст с PostgreSQL без worker-owned beans, Flyway bean и DDL privilege.

Остаток относится к deployment: явно передать роли отдельным контейнерам, не публиковать business
HTTP worker наружу, не запускать несколько `COMBINED` replica и проверить lease/lock для задач,
которые допускают несколько worker. Backend migration/readiness закрыты в P0-09; остаётся deployment wiring.

### P0-09. Отделить one-shot migration от runtime readiness

Статус backend-части на 2026-07-25: реализовано. Роль `MIGRATION` запускает тот же jar в минимальном
non-web контексте без JPA/scheduler, принудительно включает Flyway, валидирует результат по версии
последней migration из image и завершает процесс. `API`/`WORKER` принудительно отключают Flyway.
`schemaVersionReadiness` читает `flyway_schema_history` через runtime DataSource и сравнивает её с
версией, вычисленной из упакованных SQL migrations; Flyway bean и DDL credential не нужны.

Integration tests покрывают empty DB, upgrade V11→V12 и API startup под PostgreSQL-пользователем
без `CREATE` на schema. Остаток относится к deployment: создать реальные `migration_owner` и
`app_runtime`, выдать точные grants, передать credentials только соответствующим one-shot/runtime
контейнерам и включить migration evidence/rollback rehearsal в release process.

### P0-10. Реализовать independent monitoring и release evidence

Статус backend-части на 2026-07-25: реализовано. Runtime содержит Prometheus registry; endpoint
`/actuator/prometheus` имеет отдельную stateless security chain и fail-closed контракт: без
настроенного токена — `404`, без правильного Bearer token — `401`. Integration test подтверждает,
что при `management.server.port` endpoint доступен с токеном только на отдельном management port,
а на application port отсутствует. Actuator по умолчанию deny-all и явно открывает только
read-only health/info/metrics/prometheus; `/livez` и `/readyz` остаются минимальными probe aliases.

`/actuator/info` публикует безопасную release identity: build version/time, runtime role, ожидаемую
schema version и только валидированные optional release ID/image digest. Та же code/schema identity
есть в bounded `storeanalytics.release.info`; общие metric tags различают API/WORKER. Persisted sync
job gauges теперь отдельно показывают просроченные worker leases.

Остаток относится к deployment: задать отдельный private management port/network и rotated scrape
secret, поднять Prometheus/Alertmanager с ограниченной retention, настроить внешний Timeweb/другой
HTTPS/TLS monitor и доставку alerts. Alerts должны покрыть readiness, TLS expiry, backup age/restore,
sync freshness, 5xx/p95, JVM/disk, DB pools и worker leases. Release manifest должен связать
проверенные image digests, schema/OpenAPI versions, checks/scans/waivers, approver, pre-deploy backup
и previous known-good release; runtime info является corroborating evidence, а не заменой manifest.

## 6. Высокий приоритет после замыкания периметра

### P1-01. Сессии запрещают безопасный multi-replica backend

Используются container `HttpSession` и in-memory `SessionRegistryImpl`. Рестарт разлогинивает
пользователей; concurrent-session limit действует только внутри одного JVM. Два backend replica
могут одновременно принять больше сессий и по-разному видеть logout/expiration.

Решение уже принято: initial production запускает ровно один API replica. Перед горизонтальным
масштабированием внедряется Spring Session JDBC и кластерно-согласованный session registry; Redis
только ради сессий не нужен. Sticky session не заменяет shared revocation. Тесты через два API
инстанса обязательны только в release, который впервые включает scaling.

Backend-компенсирующий контроль на 2026-07-27 реализован: пользователь видит до трёх активных
сессий и может отозвать одну/все другие. Наружу выходит только HMAC `sessionReference`,
`lastSeenAt` и `current`; raw cookie ID, IP и User-Agent отсутствуют. Revoke требует CSRF,
идемпотентен для неизвестного reference и публикует bounded pseudonymous monitoring event.
Это не делает in-memory registry кластерным и не меняет запрет multi-replica.

### P1-02. Закрыть React Router advisory

Текущие `react-router`/`react-router-dom` 7.18.1 попадают в диапазон
`GHSA-qwww-vcr4-c8h2`. Advisory относится только к unstable RSC APIs; текущий SPA использует
`BrowserRouter` и не включает RSC/actions, поэтому найденный путь не выглядит эксплуатируемым в
этом приложении. Но production audit должен быть зелёным.

Порядок: проверить официальный fixed release, обновить обе зависимости согласованно, выполнить
unit/E2E/build и удалить waiver. Если немедленное обновление ломает приложение, временный waiver
должен содержать доказательство «RSC не используется», владельца, срок максимум 30 дней и запрет
добавления RSC до исправления. Нельзя бездумно применять предложенный npm downgrade.

### P1-03. Ограничить upstream/raw payload и JSON complexity

Статус P1-03A на 2026-07-26: транспортные и parser limits реализованы. Каждый LiveSklad response
ограничен до десериализации: заявленный `Content-Length` проверяется сразу, а фактически прочитанные
байты — bounded stream, поэтому отсутствующий/chunked или заниженный header не обходит лимит.
Успешные ответы принимаются только как JSON без content encoding; клиент явно запрашивает
`Accept-Encoding: identity`.

LiveSklad использует копию application `ObjectMapper` с отдельными Jackson `StreamReadConstraints`:
document length, token count, nesting depth, string/name/number length. Глобальный mapper приложения
не изменяется и не получает integration-specific limits. Конфигурация имеет production defaults и
жёсткие safety ceilings. Нарушения преобразуются в `LiveSkladPayloadRejectedException` с bounded
reason и без содержимого upstream payload. Тесты покрывают declared/chunked oversize, точную
границу, глубину, длину строки, Content-Type/Content-Encoding и изоляцию общего mapper.

Статус P1-03B на 2026-07-26: collection/position counts ограничены до накопления и normalization;
превышение отклоняет документ целиком без truncation. Все raw persistence paths используют единый
`PreparedRawPayload`: сначала сериализация и UTF-8 size check, затем canonical SHA-256 и только после
этого repository lookup/insert. Operational raw limit по умолчанию 4 MiB, а migration V13 добавляет
независимый DB safety ceiling 16 MiB через `octet_length(payload::text)`. Payload rejection больше
не retryable для durable sync job. Integration tests подтверждают Flyway migration и прежнюю
deduplication/hash семантику store/return sync.

Статус P1-03C на 2026-07-26: завершён. Counter
`storeanalytics.livesklad.payload.rejections` создаёт только семь заранее ограниченных series с
label `reason`: `response_too_large`, `json_complexity`, `unsupported_content_type`,
`unsupported_content_encoding`, `collection_record_count`, `document_position_count` и
`raw_payload_too_large`. URL, store/document IDs, exception class и содержимое payload не являются
labels и не попадают в сообщение rejection.

`RawPayloadBoundaryArchitectureTest` проверяет backend web-пакеты и весь frontend source: внутренние
`rawPayload`, `RawRecordVersion`, `PreparedRawPayload` и raw table names не могут появиться в API/UI
contracts. Текущий аудит также не обнаружил таких ссылок в UI/API.

`SyncJobWorker` переведён на отдельный `syncWorkerScheduler`: один thread и fixed-delay scheduling
ограничивают concurrency до одной sync-фазы и не накапливают параллельные повторы, а медленный
LiveSklad call не занимает общий scheduler. От отдельного node-local circuit breaker сейчас
осознанно отказались: persisted job state, capped exponential retry, `Retry-After`, request budget
и connect/read timeout уже образуют durable recovery-контур; локальное open/half-open состояние
разошлось бы между replicas и дублировало бы его. Возвращаться к circuit breaker следует только при
появлении конкурентных direct calls или подтверждённом SLO-инциденте, определив единый
upstream-scoped state.

Backend P2-07 теперь применяет explicit raw allowlist и versioned legacy inventory. Реальные сроки,
legal/data-owner approval, backup/restore rehearsal и post-purge reconciliation остаются
P2-07 deployment gates.

### P1-04. Перевести report backfill в durable async job

`POST /api/admin/reports/backfill` выполняет годовой backfill синхронно внутри одной транзакции.
Frontend уже вынужден задавать увеличенный timeout. Caddy/browser timeout или рестарт разрывает
ответ, но не даёт понятного статуса; длинная транзакция держит locks и усложняет retry.

Сделать `POST -> 202 {jobId}`, idempotency key, persisted state/progress/error/cancel, bounded worker
и polling/SSE status. Повтор после сетевой ошибки не должен создавать дубликаты. Ограничить число
jobs на store и глобально.

Статус P1-04 на 2026-07-26: завершён. Flyway V14 добавляет отдельную
`report_backfill_jobs` с cursor, progress, bounded error summary, retry state, cancel flag и
database lease. Создание сериализовано advisory lock, уникальный partial index гарантирует не более
одной активной задачи на магазин, а `REPORT_BACKFILL_MAX_ACTIVE_JOBS` ограничивает глобальную
очередь. `Idempotency-Key` привязан к администратору и identity запроса; повтор после потерянного
ответа возвращает исходный job, а попытка использовать ключ для другого store/year завершается
стабильным conflict.

Worker доступен только ролям `WORKER|COMBINED`, использует отдельный single-thread scheduler и
`FOR UPDATE SKIP LOCKED`. Каждый из 12 месяцев и финальный annual шаг выполняются собственной
транзакцией: создание immutable snapshot и продвижение cursor атомарны. После падения process lease
восстанавливает job; transient database failures получают capped retry, детерминированные ошибки
завершают задачу без бесконечного повтора. Cancellation идемпотентна и останавливает работу между
атомарными шагами.

API теперь возвращает `202`, предоставляет list/detail/cancel, а ADMIN UI опрашивает persisted
status раз в 5 секунд и повторно использует тот же idempotency key после сетевой ошибки. Добавлены
bounded-cardinality step timer и gauges failed/retrying/expired lease, unit/model/architecture
tests и PostgreSQL/Testcontainers integration test реальных idempotency, claim, retry и cancel
переходов.

### P1-05. Pagination и дешёвые list projections

Report list загружает все `ReportSnapshot` entities (включая JSON payload), затем фильтрует год/type
в Java. Admin users, payroll runs, schemes и некоторые списки также не имеют pagination. При росте
это создаёт memory/latency/N+1 риск.

Фильтры, latest revision и summary projection должны выполняться SQL-запросом; payload загружается
только detail endpoint. Ввести единый cursor/page contract, server max page size и стабильную
сортировку. Лимиты применяются backend, а не только UI.

Статус P1-05 на 2026-07-26: завершён. Для медленно растущих административных и архивных наборов
выбран единый page-contract: `items/page/size/totalElements/totalPages/hasNext/hasPrevious`.
Backend принимает страницы `0..10000`, размер `1..100` и отклоняет выход за границы стабильной
ошибкой `INVALID_REQUEST`; default — 20. Все запросы имеют детерминированный tie-breaker по UUID.

Report archive теперь фильтрует store/year/type/status и вычисляет latest revision в JPQL, возвращая
только scalar summary projection. JSON payload читается исключительно detail endpoint; отдельный
`/reports/years` устраняет прежнюю полную загрузку архива ради фильтра годов. Payroll history
фильтруется по месяцу в SQL и возвращает минимальный projection без дорогой повторной freshness
оценки; полный расчет остаётся detail-only. Пользователи загружаются страницей, а store access —
одной batch-выборкой вместо N+1. Rating/payroll schemes также имеют bounded page API, а поиск
последней payroll scheme больше не загружает всю историю.

Flyway V15 добавляет индексы для стабильного report archive и case-insensitive admin-user order.
Frontend переведён на новый envelope, серверные фильтры и page navigation архива/пользователей;
payroll audit запрашивает только выбранный месяц. Unit/controller/context/migration tests проверяют
границы, metadata contract, отсутствие payload decoding в list path и валидность запросов.

### P1-06. HTTP security headers и cache policy

Spring Security даёт часть default headers, но production Caddy пока не реализован, поэтому итоговую
политику нельзя проверить. Нужны HTTP headers, а не только `<meta name="referrer">`:

- CSP сначала в Report-Only, затем enforced; никогда не разрешать `unsafe-inline` для scripts;
- `frame-ancestors 'none'`, `object-src 'none'`, `base-uri 'none'`, `form-action 'self'`;
- `Referrer-Policy: no-referrer`, `X-Content-Type-Options: nosniff`;
- `Permissions-Policy` с запретом неиспользуемых sensor/camera/mic/geolocation;
- HSTS только после проверки всего HTTPS-контура;
- no-store для HTML/API/auth/payroll и защита от shared proxy cache;
- при logout рассмотреть `Clear-Site-Data` для cache/storage с аккуратным тестом cookies.

В проекте могут быть React inline style attributes, поэтому CSP для styles нужно измерить в
Report-Only; разрешение inline styles не должно автоматически распространяться на scripts.

### P1-07. Формализовать backend/frontend compatibility

Исходное замечание: OpenAPI был только runtime source of truth, а TypeScript/Zod схемы
поддерживались вручную. Закрытые `z.enum`/`z.literal` могли обрушить экран при добавлении нового
backend enum, несмотря на заявленный safe fallback в документации.

Нужны:

- versioned OpenAPI artifact, генерируемый backend в CI без production login;
- OpenAPI breaking-change check относительно released baseline;
- generated transport types или generated runtime schemas; business view-model остаётся ручным;
- consumer contract tests для реально используемых ответов;
- `apiContractVersion` в `/api/system/status` и build version frontend;
- политика N/N-1: сначала backward-compatible backend, затем immutable SPA; `index.html` no-store;
- неизвестный enum отображается как `UNKNOWN`, но не меняет бизнес-расчёт на клиенте.

Также `VITE_API_BASE_URL` в production запрещает `http(s)://`, но пока допускает protocol-relative
`//host`, backslash и необычные формы URL. Принимается только пустая строка или нормализованный
same-origin root-relative prefix, не начинающийся с `//`; лучше вообще удалить runtime API URL из
production и всегда использовать `/api`.

Статус P1-07 на 2026-07-27: backend/frontend часть завершена. Канонический
`contracts/openapi/current.json` генерируется отдельной Gradle-задачей через Testcontainers и
эфемерного test-admin без production login. Immutable baselines `v1.json`–`v7.json` отделены от
current; gate относительно текущего v7 запрещает удаления path/operation/schema/property/response,
новые required параметры/поля и изменения enum/type/format.

Frontend использует исправленный pinned `@hey-api/openapi-ts` на Node 22.18+ и TypeScript 6;
transport-типы генерируются в `src/api/generated`, а `npm run check` сравнивает их с временной
чистой генерацией. Ручные Zod-схемы сохранены как runtime boundary/business view-model.
Consumer tests проверяют используемые status/auth/session/report endpoints, contract version и
поведение future enum.

`/api/system/status` возвращает независимый `apiContractVersion=7`, SPA показывает его вместе со
своей build version. Response enum имеют fail-safe `UNKNOWN`: неизвестные роль/job status/phase/
quality action/report type не дают полномочий, не включают mutation и не получают выдуманный
бизнес-смысл. Production API base принимает только пустой/`/` или нормализованный root-relative prefix.

Политика N/N-1: сначала backward-compatible backend, затем immutable SPA; фактический
`index.html: no-store` и проверка edge headers остаются частью Caddy-задачи P1-06.

### P1-08. E2E security regression suite

25 unit-тестов полезны, но не покрывают browser + Caddy + API/worker + managed PostgreSQL. Нужен Playwright
на disposable environment со сценариями:

- login CSRF, token rotation, logout, idle/absolute expiry, forced password change;
- MANAGER не видит admin и не может обратиться к чужому store/employee/payroll/report UUID;
- revoke role/store/password действует на уже открытую вкладку;
- 401/403/409/429 и correlation ID;
- duplicate click/retry approve/paid/backfill не удваивает операцию;
- строки `<script>`, event handlers и spreadsheet-formula prefixes в names/reasons отображаются
  как текст и безопасно экспортируются;
- CSP не ломает lazy chunks, charts, fonts и API;
- desktop/tablet/mobile, keyboard navigation и slow network;
- backend 404/500 не подменяется SPA index.

### P1-09. Централизованный security/audit monitoring

Статус backend: выполнено, SIEM schema зафиксирована 2026-07-27. Backend выдаёт ECS JSON в
stdout, отдельные bounded security/business-audit event families и counters. Login throttle, 401,
403, CSRF, session, password/admin changes, payroll transitions, manual/scheduled sync, backfill и
retention имеют явные сигналы. Business monitoring публикуется только после commit persistent
audit.

Оба потока проходят через общий fail-closed envelope `event_schema_version=1` с обязательными
category/type/outcome/severity/key ID и точной required/optional allowlist для каждого event type.
Неизвестные поля, envelope collisions, сырые значения в `*_ref`, управляющие символы,
неограниченные labels и неподдерживаемые типы отклоняются до metric increment и log emission.

Email, client IP, user и audit target уходят только как domain-separated HMAC-SHA-256 references с
обязательным deployment secret и безопасным key ID. Raw payload, credentials, cookie/CSRF/token и
произвольные exception/user strings в event contract запрещены и покрыты redaction/cardinality
тестами.

Осталось в deployment: bounded container log rotation, off-host append-only shipping/retention,
контроль самого shipper, alert rules/routing и внешние сигналы failed backup, disk/DB pool saturation.

### P1-10. Bootstrap и break-glass runbook

Статус backend: выполнено 2026-07-26. First-admin creation сериализовано PostgreSQL advisory lock,
работает только при пустой `app_users`, всегда требует смены временного пароля и атомарно пишет
`BOOTSTRAP_ADMIN_CREATED` в persistent audit. Параллельный/повторный запуск не создаёт пользователя;
оставленные credentials дают отдельный `bootstrap_admin_skipped` security signal.

`BREAK_GLASS_USER_IDS` задаёт только UUID аварийных аккаунтов. Каждый их успешный login создаёт
`BREAK_GLASS_LOGIN_SUCCEEDED` в immutable audit, bounded counter и pseudonymous WARN event.
Сценарии lost admin, compromised admin и будущей потери MFA factors описаны в
`docs/archive/legacy-contracts/bootstrap-and-break-glass.md`.

Осталось в deployment: one-time config-tree mounts, физическое удаление/rotation bootstrap files,
alert routing/rehearsal и подготовленный customer-owned break-glass credential. Application MFA
остаётся отдельным риском SEC-01 и не считается реализованным этим блоком.

### P1-11. Password policy привести к актуальной модели риска

Статус backend: выполнено 2026-07-26 с явно принятым продуктовым отклонением по длине. Минимум
оставлен равным 12 символам. Пока MFA не реализована, это слабее актуальной NIST-рекомендации
минимума 15 для single-factor password и остаётся частью риска SEC-01.

Ручной denylist из восьми значений заменён offline blocklist из 46 146 уникальных SHA-256
отпечатков policy-eligible паролей, полученных из первых 1 000 000 записей SecLists. Полный пароль
или его prefix никогда не отправляется внешнему сервису; source checksum, generated checksum,
лицензия и воспроизводимый generator находятся рядом с application resource. Загрузка fail-closed
проверяет формат, отсутствие дублей и ожидаемое число записей.

Новые/reset/bootstrap пароли проходят Unicode NFC до policy check и bcrypt. Legacy non-NFC bcrypt
остаётся доступным для исходной формы и после успешного login прозрачно rehash-ится в NFC, поэтому
включение нормализации не блокирует существующие аккаунты. Длина считается в Unicode code points;
сохраняются bcrypt cost 12, 72-byte UTF-8 ceiling, generic errors и throttling. Composition rules,
запрет paste/password managers и плановая смена без признака компрометации не вводятся.

Исходное замечание до выполнения:

Bcrypt cost 12, salt, rehash, byte ceiling, generic errors и throttling — хорошие меры. Но local
common-password list слишком мал для известных скомпрометированных паролей. Для password-only
аккаунтов актуальная NIST guidance рекомендует минимум 15 символов; текущий минимум — 12.

После UX-проверки: минимум 15 для single-factor либо допускаемый меньший минимум только при MFA;
offline breached/common blocklist без отправки полного пароля внешнему сервису; Unicode NFC;
разрешить password managers/paste и длинные passphrases; не вводить бессмысленные composition rules
и плановую смену без признака компрометации.

## 7. Средний приоритет и hardening

### P2-01. Docker/host hardening

- pin Gradle/JRE/Caddy images по digest и планировать обновления; managed PostgreSQL обновлять по provider plan;
- backend: `read_only`, tmpfs `/tmp`, `cap_drop: ALL`, `no-new-privileges`, non-root (уже есть),
  pids/memory/CPU limits и log size;
- healthcheck backend, `stop_grace_period`, `server.shutdown=graceful`;
- firewall: наружу только SSH с ключами и 443; PostgreSQL/8080 не публиковать;
- unattended security updates с maintenance window, fail2ban/host monitoring, NTP и disk alerts;
- отдельный deploy-user без общего доступа к secret files и Docker socket;
- dev PostgreSQL сейчас публикуется как `5432:5432` с default `change_me`; привязать к
  `127.0.0.1:5432:5432` и не разрешать default password вне локального profile.

### P2-02. Actuator/OpenAPI defence in depth

Backend правильно ограничивает metrics/Swagger ролью ADMIN и скрывает health details. В production
лучше вынести management на отдельный internal port/network и запретить на public Caddy route. Наружу не
публиковать `/v3/api-docs`, Swagger, metrics и aggregate health. Public `/actuator/info` раскрывает
версию и облегчает fingerprinting; оставлять только при осознанной необходимости.

### P2-03. Correlation ID доверять только как client hint

Статус backend: выполнено 2026-07-27.

Сервер теперь всегда генерирует authoritative UUID для каждого запроса. Он возвращается в
`X-Correlation-ID`, включается в публичный `ApiError.correlationId` и записывается в MDC как
`request.id`. Единственное безопасное входное значение сохраняется только как отдельный
диагностический hint `client.correlation_id`; malformed, CRLF, слишком длинные и дублирующиеся
значения игнорируются. Client hint не отражается в ответе и не используется для прав, throttling,
idempotency или бизнес-логики.

Тесты покрывают spoofing допустимым client value, разные request ID при повторении одного hint,
header injection, неоднозначные дубли заголовка, lifecycle MDC и совпадение идентификатора в MVC и
security error responses.

### P2-04. Скрипты и локальные артефакты

Статус backend: выполнено 2026-07-27.

`common.sh` больше не выполняет dotenv через `source`: dependency-free Python parser читает его
только как UTF-8 data и экспортирует allowlist из трех LiveSklad-переменных. Command substitution и
shell metacharacters остаются literal data, неизвестные переменные не меняют process environment, а
дубли, invalid syntax, control characters и файл больше 64 KiB отклоняются fail-closed.

`change-own-password.sh` и import script разрешают HTTP только для exact `localhost` или
`127.0.0.1`; remote origin обязан использовать HTTPS. Userinfo, path, query и fragment запрещены.
Curl ограничен разрешенным protocol, normal TLS verification, connect/total timeout и response 64
KiB. Печать ответа ограничена 8192 bytes, control bytes экранируются.

`outputs/` исключен из Git. Classification generator атомарно создает только новый output mode
`0600` с текущим оператором-владельцем и не перезаписывает существующий файл. Политика: удалить
после review/import, максимум 30 дней без отдельно зафиксированного incident/legal hold; общие и
неуправляемые облачные каталоги запрещены.

CSV/XLSX exporter сейчас отсутствует. Его добавление требует отдельного formula-injection test:
значения с `=`, `+`, `-`, `@` должны сохраняться как данные, а не исполняемые формулы. До такой
реализации экспорт в spreadsheet-форматы не считается разрешенным production capability.

Негативные тесты покрывают dotenv code execution/allowlist/duplicates, URL bypasses, response
truncation/terminal escapes, script preflight и artifact mode/no-overwrite; они включены в Gradle.

### P2-05. Idempotency и optimistic concurrency

Payroll имеет optimistic version и DB constraints, но для всех high-risk POST нужен единый ответ
на timeout/retry. Использовать `Idempotency-Key` с user/action/resource/body hash, ограниченным TTL
и сохранённым результатом. Для plan/schedule/config edits добавить version/ETag + `If-Match`, если
двое руководителей могут редактировать одновременно. UI disable/confirm не защищает от двух вкладок
и прямого API-клиента.

Статус P2-05A на 2026-07-27: завершён. Все пять финансово значимых payroll POST требуют
`Idempotency-Key`. Flyway V16 хранит короткоживущий receipt, привязанный к actor/key, action,
resource и SHA-256 канонического body; точный успешный response сохраняется в той же транзакции, что
и payroll mutation. PostgreSQL advisory lock сериализует одновременные retries, несовпадающая
identity возвращает стабильный `409 IDEMPOTENCY_KEY_CONFLICT`, TTL по умолчанию 24 часа, cleanup
выполняется ограниченными batch. Frontend contract v2 генерирует криптографический ключ и сохраняет
его после timeout/network/5xx. PostgreSQL integration test доказывает single execution при двух
конкурентных запросах. Report backfill сохраняет собственную durable idempotency-модель, связанную
с жизненным циклом job.

Статус P2-05B на 2026-07-27: завершён. План возвращает strong ETag; update требует `If-Match`,
а создание отсутствующего месяца — `If-None-Match: *`. Для графика введён отдельный versioned
aggregate дня `work_schedule_day_revisions`: GET полного дня возвращает даже пустой resource,
`revision`, shifts и ETag, а replace-day требует `If-Match`. Отсутствующая precondition
возвращает стабильный `428 PRECONDITION_REQUIRED`, устаревшая — `412 PRECONDITION_FAILED`.
Store-level pessimistic lock сериализует первую запись пустого дня, а PostgreSQL concurrent test
доказывает, что из двух запросов с одной ревизией commit получает только один. Mutable employee
rating setting уже проверяет явную version; rating/payroll schemes являются append-only
effective-dated revisions и не получают ложный `If-Match`.

### P2-06. Ресурсные лимиты и bulkheads

Явно задать Tomcat max header/post/form size, connection timeout, graceful shutdown и Hikari pool
limits. Разделить executors/bulkheads для scheduled sync, LiveSklad probes, report generation,
retention и метрик, чтобы медленный upstream не вытеснил критическую работу. Ограничить period,
items, batch, concurrent jobs и response size на backend. Провести k6/Gatling тест типичного дня,
месячного payroll, годового отчёта и sync storm с SLO p95/p99.

Статус backend-реализации на 2026-07-27: добавлен единый валидируемый `app.resources`,
который является источником фактических Tomcat/Hikari настроек. Зафиксированы header/form/swallow,
connection/keep-alive, connection/thread/queue/parameter budgets, Hikari pool/timeouts/lifetime,
graceful shutdown. Каждая `@Scheduled` задача обязана архитектурным тестом указывать отдельный
scheduler: sync execution/control, report backfill/annual, LiveSklad probe, retention, metrics и
cleanup изолированы concurrency=1. Saturation test доказывает, что заблокированный внешний probe не
вытесняет metrics. Work-schedule ограничен 31 днём, 10 000 строк ответа и 500 сменами дня;
payroll bulk classification — 500 элементами и reason 2 000 символов. Проверки повторяются в
service boundary. Общий `/api` body ограничен валидируемыми 2 MiB до Spring Security:
проверяется и declared length, и реально прочитанный encoded stream, поэтому chunked/unknown и
understated length не обходят границу. Переполнение стабильно возвращает
`413 PAYLOAD_TOO_LARGE` в общем `ApiError` contract с correlation/no-store/nosniff и без
отражения body или лимита. Подготовлен k6 harness с раздельными p95/p99/error-rate SLO.

Остаток P2-06 является deployment/staging acceptance gate: выполнить сценарий на
production-подобной кардинальности одновременно с реальным sync job, подобрать API/worker pool
значения относительно лимита PostgreSQL и приложить k6/Prometheus/PostgreSQL evidence. Локальный
пустой database run не считается таким доказательством. Детали — `docs/archive/legacy-contracts/resource-limits.md`.

### P2-07. Retention и privacy

Статус backend-среза на 2026-07-27: завершён. LiveSklad raw больше не сохраняется целиком:
entity-specific closed allowlist применяется до byte limit, SHA-256 и persistence. Инвентарь
разрешённых полей и их назначение зафиксированы в `docs/archive/legacy-contracts/data-retention.md`; неизвестные поля
рекурсивно отбрасываются, а неожиданная структура разрешённого поля отклоняется без вывода
значения. Hash/dedup относится именно к сохраняемой проекции.

Flyway V18 маркирует новые/повторно проверенные payload как policy version 1, а прежние full raw —
как legacy version 0. Dry-run показывает отдельный `legacy_raw_payload_versions`; legacy latest
evidence не удаляется без более новой версии. Physical cleanup по-прежнему выключен по умолчанию.
При включении backend fail-fast требует approval reference, backup checkpoint reference и свежий
restore-test timestamp. Delete-run повторно считает оставшихся кандидатов и пишет reconciliation
summary вместе с безопасными ссылками в immutable audit.

Raw/sync provenance не является единственным источником finalized financial snapshot. Необходимые
нормализованные sale/return facts, payroll source fingerprints, immutable payroll/report payloads и
точные monthly-to-annual links находятся вне automatic retention; finalized reports immutable и
бессрочны. Интеграционный тест подтверждает, что purge raw версии очищает только optional pointer,
не удаляя sale fact и financial amounts.

Остаток P2-07 — осознанный deployment/data-owner gate: утвердить реальные сроки, выполнить и
зафиксировать backup/restore rehearsal, сначала проверить dry-run/legacy inventory, затем после
первого purge сверить normalized financial counts/hashes и report integrity. Значения конфигурации
являются аттестацией и не заменяют внешнее доказательство выполнения процедуры.

### P2-08. Release и migration safety

Статус backend-среза на 2026-07-27: завершён. Отдельный `MIGRATION` runtime остаётся non-web,
без JPA/schedulers, валидирует checksum и итоговую packaged schema version. Он теперь fail-bounded:
typed config ограничивает PostgreSQL lock timeout (5s), statement timeout (10m) и Flyway lock
retries (10); невалидные или фактически безграничные значения отклоняются. Интеграционный тест
мигрирует empty PostgreSQL 16 и предыдущую V17 schema в V18. V18 является additive: тест исполняет
N-1 raw INSERT после upgrade и подтверждает fail-closed marker legacy policy 0.

`SystemStatus` уже отдаёт release identity, API contract version и server time; readiness сверяет
packaged migration version с `flyway_schema_history`. Rollback остаётся только image rollback:
автоматического down-migration нет, destructive changes требуют отдельного expand/migrate/contract
цикла и restore plan.

Source-level upgrade до Spring Boot 4.1.0 завершён: modular starters, Spring Security 7.1.0,
native Jackson 3.1.4, springdoc 3.0.3 и Testcontainers 2.0.5 адаптированы; deprecated Boot Jackson 2
bridge не используется. Dependency lock обновлён, полный backend suite и OpenAPI generation
перепроверены. Это закрывает риск неподдерживаемой OSS-линии в коде, но не доказывает production
container/staging rollout.

Остаток P2-08 — deployment/staging acceptance gate: прогнать V17→V18 и последующие risky migration
на production-подобном объёме с наблюдением locks/runtime; поднять настоящий N-1 image после
additive migration; выполнить authenticated smoke, canary/быстрый roll-forward и назначить release
owner. Локальные Testcontainers доказывают контракт и fail-fast настройки, но не production lock
duration или работу операторского runbook.

## 8. Как приложение будут пытаться сломать

| Сценарий | Текущая защита | Остаточный риск / обязательный тест |
| --- | --- | --- |
| CSRF на login/logout/payroll | CSRF включён, unsafe methods требуют header, token ротируется | Проверить через реальный browser и Caddy; CORS не считать CSRF-защитой |
| Stored XSS через store/employee/product/reason из LiveSklad | React по умолчанию escaping; `dangerouslySetInnerHTML` в SPA не найден | CSP и malicious-string E2E; не передавать raw HTML в charts/tooltips |
| IDOR: подмена storeId/employeeId/reportId | `@PreAuthorize`, parent-store checks, architecture test | Матрица ролей на каждый новый endpoint; UI guard не является защитой |
| SQL injection/mass assignment | constant/parameterized SQL, request DTO | Запрет request-controlled SQL/sort; fuzz filters и будущие export endpoints |
| Brute force/credential stuffing | bcrypt, generic error, offline compromised-password blocklist, trusted-proxy-aware email+IP DB throttle и bounded alerts | MFA; targeted lockout остаётся DoS-вектором; проверить реальный Caddy/NAT contour |
| Session theft/shared PC | HttpOnly/Secure/SameSite, rotation, idle/absolute/concurrent limits и self-service revoke other sessions | MFA, CSP/no-store на реальном edge, shared registry до второго replica и kiosk/shared-device policy |
| Повтор `approve/paid/backfill` после timeout | transactional `Idempotency-Key` receipts, durable backfill identity, locks/ETag и concurrent tests | Browser/Caddy E2E с реальным потерянным ответом и повтором |
| Sync/backfill DoS | bounded durable backfill, period/page/job/body/raw/JSON limits и scheduler bulkheads | Production-sized load evidence, provider quotas и явные limits для будущих export/stream routes |
| SSRF | LiveSklad URL config-only, exact host allowlist, prod HTTPS | Не разрешать URL из UI; DNS/rebinding/redirect policy тестировать при расширении integrations |
| Утечка секрета через image/build | secret files и config-tree в prod | `.dockerignore`, secret scan, permissions, remote builder policy |
| Компрометация runtime DB user | DB triggers/immutable audit | отдельный непривилегированный app role; off-host logs/backups |
| Supply-chain package/image | locks, wrapper hashes, strict SHA-256 dependency verification и CI integrity gate | SBOM, image digest/signature, registry scan и update policy |
| Disk exhaustion | bounded retention/rollups и raw allowlist реализованы; destructive purge default-off | Утвердить сроки, выполнить restore/purge rehearsal, настроить log/backup quotas, disk alerts и capacity plan |
| Поддельный webhook в будущем | endpoint пока отсутствует/fail-closed | HMAC/signature, timestamp, nonce/replay store до включения route |
| CSV/XLSX formula injection | export contract пока не зафиксирован | neutralization tests для `= + - @`, безопасный MIME/download headers |
| Host/header poisoning | backend не формирует публичные links активно | Caddy canonical host, reject unknown Host, trusted forwarded headers |

## 9. Недостающие файлы и артефакты

Это не требование создать всё в одном PR. Порядок — P0, затем P1.

### Deployment/operations

- `.dockerignore`;
- `frontend/Dockerfile`;
- `deploy/Caddyfile` с routing, CSP/security/cache headers и trusted proxy rules;
- production Compose с web/API/worker/migrate/backup roles, healthchecks, limits, secrets и networks;
- `deploy/compose.production.yml`, `deploy/compose.staging.yml` и safe env examples;
- `deploy/systemd/store-analytics.service`;
- `scripts/deploy.sh`, `scripts/rollback.sh`, `scripts/smoke-test.sh`;
- `scripts/backup-postgres.sh`, `scripts/restore-postgres.sh`, `scripts/verify-backup.sh`;
- `infra/` с переносимым OpenTofu/Terraform/Ansible минимумом либо исчерпывающим runbook;
- `docs/production-runbook.md`;
- `docs/disaster-recovery.md` с RPO/RTO и restore drill;
- `docs/incident-response.md`;
- `docs/secrets-inventory-and-rotation.md` без значений секретов;
- `docs/data-classification-and-retention.md`;
- `docs/threat-model.md`;
- `docs/access-control-matrix.md`;
- monitoring/alert rules и dashboard as code.

### CI/supply chain

- `.github/workflows/ci.yml`, image build, staging/production deploy и security scan workflows;
- production manual approval, deployment lock и release manifest;
- build once/publish GHCR/promote exact accepted digest без server-side rebuild;
- dependency update configuration;
- SAST, secret scan, image scan;
- OpenAPI diff job;
- SBOM/provenance/signing;
- Gradle wrapper checksum и dependency verification metadata;
- release manifest с backend/frontend/schema/contract versions.

### Tests/contracts

- versioned OpenAPI baseline;
- generated frontend transport layer или schemas;
- Playwright E2E project;
- authorization matrix integration tests;
- trusted-proxy integration tests;
- idempotency/concurrency tests;
- backup restore and migration performance tests;
- load profile и SLO/error budget;
- CSP Report-Only collection и regression check.

## 10. Статус backend-контрактов

1. Закрыт: `apiContractVersion=7`, immutable OpenAPI baselines и N/N-1 rollout policy.
2. Закрыт: единый bounded page contract со стабильной сортировкой и metadata.
3. Закрыт для report backfill: durable async `jobId`, progress/state, cancel, retry и idempotency.
4. Закрыт для текущих high-risk payroll/backfill commands: transactional `Idempotency-Key`.
5. Закрыт для mutable plan/day-schedule: strong ETag, `If-Match`/`If-None-Match`, стабильные
   `412 PRECONDITION_FAILED` и `428 PRECONDITION_REQUIRED`.
6. Не реализован: step-up/MFA contract — enrollment, challenge, recovery, action grant и audit.
7. Backend session management закрыт: opaque self-service list, revoke one/all-other, CSRF,
   concurrent registration test и bounded audit signal. IP/User-Agent намеренно не раскрываются;
   frontend-экран остаётся отдельной UX-задачей, multi-replica — P1-01.
8. Закрыт: общий fail-closed security/business-audit SIEM contract `event_schema_version=1`;
   публичный raw audit table намеренно не добавлялся.
9. Request-side backend закрыт: global actual-byte limit и стабильный `413 PAYLOAD_TOO_LARGE`.
   Для response остаются endpoint cardinality/field limits; будущие export/download требуют
   отдельного явного byte/stream contract до включения route.
10. Закрыт на уровне backend/frontend contract: immutable baselines, breaking-change gate и N/N-1
    rollout order; фактические cache/edge guarantees остаются deployment-задачей P1-06.

Audit log не следует просто отдавать frontend как JPA/table dump. Если экран аудита нужен, создать
отдельный read-only DTO endpoint с role/store scope, pagination, redaction и audit самого просмотра.

## 11. Рекомендуемые frontend-слои и хуки

Сохранять текущую модульную структуру и React Query. Нужны не новые глобальные state-библиотеки, а
несколько чётких application boundaries:

- `api/generated` или эквивалент — только transport types/schemas;
- `api/client` — cookies, CSRF, timeout, error normalization, no-store;
- feature API modules — endpoint + query keys + invalidation, без JSX;
- view-model/formatters — `null` vs `0`, money/date/percent, unknown enum;
- route capability guard для UX, при неизменной backend authorization;
- `useAuthenticatedMutation` — единая 401/403/409/429/idempotency реакция;
- `useConflictAwareMutation` — version/ETag и refresh-on-conflict;
- `useDangerousAction` — текст причины, step-up grant и явное подтверждение;
- `useSessionBroadcast` через `BroadcastChannel` — logout/revoke между вкладками без хранения token;
- top-level `ErrorBoundary` с release + correlation ID, но без payload/PII;
- telemetry adapter с allowlist полей и возможностью полностью отключить;
- compatibility check на bootstrap без жёсткого падения при обычном rolling deploy.

Нельзя помещать auth/session/CSRF в Redux/localStorage. React Query хранит server state, а формы —
локальный transient state. После logout/401 очищаются query cache, CSRF state и чувствительные
view-model; browser back/forward cache проверяется E2E.

## 12. Приёмочный checklist production

Запуск разрешается только когда для каждого пункта есть ссылка на код/конфигурацию и результат
проверки, а не устное «настроено на сервере».

### Security

- [ ] `.dockerignore` доказан inspection-ом build context.
- [ ] Secrets имеют `0600`, не tracked, не в image/layers/logs/frontend/CI artifacts.
- [ ] Same-origin HTTPS и TLS audit пройдены.
- [ ] CSP enforced, security headers проверены browser/scan.
- [ ] Trusted proxy и login throttle тесты пройдены.
- [ ] SEC-01 подписан, не истёк и compensating controls доказаны; cloud/GitHub MFA включены.
- [ ] SEC-01 содержит owner/co-signature, launch date, expiry и incident-triggered termination.
- [ ] Все руководители имеют документированный store/payroll scope; four-eyes не требуется.
- [ ] Customer-owned cloud/domain/password manager и named developer/deploy access подтверждены.
- [ ] Bootstrap credential удалён после initial admin и recovery procedure проверена.
- [ ] Runtime DB role не может менять DDL/audit protections.
- [ ] Dependency/SAST/secret/image scans зелёные или имеют короткий approved waiver.
- [ ] IDOR/CSRF/XSS/concurrency E2E зелёные.
- [ ] Incident, break-glass и rotation procedures отрепетированы.

### Reliability

- [ ] Backup создаётся, шифруется, уходит off-host и мониторится.
- [ ] Managed PostgreSQL доступен только по private VPC и TLS `verify-full`.
- [ ] Hourly encrypted dump, immutable S3 и weekly off-provider copy реально создаются.
- [ ] Restore drill подтвердил RPO 1 час и RTO 4 часа.
- [ ] Health/readiness/graceful API/worker shutdown работают через Compose/Caddy.
- [ ] Disk, DB pool, heap, latency, failed jobs и cert expiry имеют alerts.
- [ ] Sync/report/payroll load test соответствует SLO.
- [ ] API/worker roles не дублируют schedules; API replica ровно один.
- [ ] One-shot migration работает с `migration_owner`; API/worker — без DDL credential; readiness проверяет schema version.
- [ ] Migration rehearsal и forward-only/roll-forward план выполнены.

### Contract/release

- [ ] OpenAPI baseline и breaking diff присутствуют в CI.
- [ ] Backend/frontend versions и compatibility policy задокументированы.
- [ ] N/N-1 rollout протестирован либо deploy атомарный с контролируемым downtime.
- [ ] `index.html` no-store, hashed assets immutable, API no-store.
- [ ] Production smoke test проверяет login, store scope, KPI, payroll и logout.
- [ ] Images построены CI один раз, приняты в ephemeral staging и развернуты по тем же digests.
- [ ] Release manifest содержит image/schema/OpenAPI versions, scans, waiver, approver и backup ID.
- [ ] Production ничего не собирает из source и хранит previous known-good images.

## 13. Предлагаемый порядок работ

### Этап A — замкнуть безопасный периметр

`.dockerignore` -> secrets register -> web/Caddy image -> executable Compose -> same-origin HTTPS ->
trusted proxy -> DB roles -> backup/restore.

### Этап B — privileged access и release gates

API/worker/migrate roles -> SEC-01 controls -> CI/GHCR/SBOM -> dependency waiver/fix -> OpenAPI/E2E.

### Этап C — нагрузка и масштабирование

Async report backfill -> pagination/projections -> payload limits/bulkheads -> observability/SLO ->
Spring Session перед вторым replica.

### Этап D — rehearsal

Staging deploy -> migration rehearsal -> restore drill -> security regression -> load test ->
incident tabletop -> production checklist sign-off.

## 14. Сильные стороны, которые важно не потерять

- Frontend не ходит в LiveSklad и не получает upstream credentials/raw errors.
- Session cookie вместо browser-stored bearer token.
- CSRF защищает login/logout и mutations, token обновляется после auth.
- Explicit CORS allowlist, wildcard запрещён.
- `anyRequest().denyAll()` и method-level store authorization.
- Architecture test требует authorization на store-scoped controller methods.
- Generic login/internal errors и correlation ID.
- Bcrypt cost 12, rehash, 72-byte ceiling и DB-backed throttling.
- Session fixation protection, idle/absolute/concurrent limits, security-version invalidation.
- LiveSklad HTTPS/host allowlist/timeouts и отсутствие user-controlled integration URL.
- JPA `open-in-view=false`, `ddl-auto=validate`, Flyway и parameterized SQL.
- DTO вместо наружной сериализации JPA/upstream payload.
- Immutable/retention-aware audit и dry-run physical cleanup по умолчанию.
- React escaping, отсутствие production `dangerouslySetInnerHTML`/Web Storage auth.
- Central API client, runtime Zod validation, cache clear на logout/401.
- Production sourcemaps выключены, lazy chunks и frontend build проходят.

## 15. Внешние ориентиры

- OWASP ASVS 5.0.0: https://owasp.org/www-project-application-security-verification-standard/
- Spring Security CSRF for SPA: https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html
- Spring Session JDBC: https://docs.spring.io/spring-session/reference/configuration/jdbc.html
- Docker build context и `.dockerignore`: https://docs.docker.com/build/concepts/context/
- PostgreSQL backup/restore: https://www.postgresql.org/docs/current/backup.html
- Vite env security: https://vite.dev/guide/env-and-mode
- NIST SP 800-63B password/MFA guidance: https://pages.nist.gov/800-63-4/sp800-63b.html
- OWASP CSP Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Content_Security_Policy_Cheat_Sheet.html
- OWASP Secrets Management Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html
- React Router advisory: https://github.com/advisories/GHSA-qwww-vcr4-c8h2

## 16. Зафиксированные решения и оставшиеся go-live gates

### Решения, которые больше не являются открытыми вопросами

1. Кабинет доступен через публичный Internet на customer-owned domain.
2. Initial provider recommendation — Timeweb Cloud в российском регионе.
3. Одна application VM: Ubuntu LTS, Docker Compose, Caddy, только public 80/443 и restricted SSH.
4. Managed PostgreSQL 16 находится вне VM, доступен по private VPC и TLS `verify-full`.
5. Runtime: один API replica; отдельный worker является target state; Spring Session JDBC нужен
   только перед будущим horizontal API scaling.
6. Production использует immutable backend/web image digests из GHCR; staging и production не
   пересобирают принятый artifact.
7. Масштаб первого года: до трёх магазинов и примерно 30 сотрудников; Kubernetes, Redis, broker и
   microservices не входят в initial design.
8. RPO — 1 час, RTO — 4 часа, короткое ночное maintenance window допустимо.
9. Backup layers: provider physical backup, hourly encrypted S3 dump с Object Lock, weekly
   off-provider copy, monthly restore и quarterly DR exercise.
10. Все руководители могут видеть зарплату, подтверждать расчёт и отмечать выплату. Принцип двух
    лиц не требуется; store scope и каждое изменение остаются server-authorized и audited.
11. Initial public launch допускает SEC-01 без application MFA/VPN/IP allowlist только как
    подписанное 90-дневное исключение. Cloud-provider и GitHub MFA обязательны сразу.
12. Telegram и Yandex AI — будущие неблокирующие asynchronous adapters через durable outbox/jobs;
    они не участвуют в расчёте core metrics и не блокируют публикацию отчёта.

### Решения, которые остаются customer/go-live gates

- CONFIRM: customer владеет и оплачивает cloud, domain, S3, GitHub recovery path, bot и LLM accounts.
- CONFIRM: точный Timeweb region, VM/DB plans и budget либо HA PostgreSQL profile.
- CONFIRM: initial availability objective 99.5% и правила исключения maintenance.
- CONFIRM: data-freshness SLA и sync schedule.
- CONFIRM: final retention для raw LiveSklad, payroll, reports, audit, backups и security logs.
- CONFIRM: alternative Russian provider/customer storage для weekly copy и хранение encryption key.
- CONFIRM: support hours, severity response targets и независимые emergency alert recipients.
- CONFIRM: кто подписывает SEC-01, фиксирует дату первого launch и владеет задачей MFA к expiry.
- CONFIRM: personal-data roles, provider/subprocessor list и incident/legal procedure.

До закрытия этих gates применяются уже выбранные fail-safe defaults: Internet считается враждебным,
один API replica, DB не public, secrets root-owned `0600`, production не строит source, raw хранится
минимально необходимый срок, backup шифруется до upload, а неизвестные routes/roles/enums работают
fail-closed.

## 17. Findings фронтенд-интеграции contract v7 (2026-07-27)

### BE-FE-01 — direct sync конкурирует с durable sync jobs

`POST /api/sync/stores|employees|sales|returns` выполняется синхронно и не использует тот же
server-side mutual exclusion, что `/api/sync/jobs/backfill`.

- Evidence: `ActiveSyncJobException` применяется в `SyncJobService`; direct controllers вызывают
  sync services напрямую. Frontend блокирует direct action при активной job, но это только UX.
- Risk: второй tab, curl или race между list и POST может запустить конкурирующую обработку одного
  connection и увеличить нагрузку/конфликты записи.
- Recommendation: объединить manual и scheduled запуск единым durable job API либо в backend
  добавить connection-scoped lock/idempotency и стабильный `409 ACTIVE_SYNC_JOB`.

### BE-FE-02 — synchronous direct sync зависит от HTTP timeout

Direct sync возвращает итог только после завершения работы. Frontend допускает 120 секунд, но
reverse proxy/load balancer может оборвать запрос раньше, хотя backend продолжит работу.

- Risk: оператор увидит timeout и повторит операцию, не зная, завершился ли первый запуск.
- Recommendation: production-путь должен возвращать `202 + jobId`, а UI — читать durable status.
  До миграции согласовать Nginx/Caddy upstream timeout, запретить автоматические retry для POST и
  вернуть безопасный correlation/syncRun ID при известном результате.

### BE-FE-03 — CORS contract не включает concurrency/idempotency headers

`SecurityConfig` разрешает только `Content-Type`, CSRF и correlation headers. `If-Match` и
`Idempotency-Key` не входят в allowed headers, а `ETag` не exposed.

- Current deployment: same-origin Nginx/Caddy и Vite proxy не требуют CORS, поэтому выбранный
  production topology не блокируется.
- Risk: отдельный frontend origin/staging без proxy сломает optimistic concurrency и idempotent
  report backfill на preflight/недоступном ETag.
- Recommendation: сохранить same-origin как обязательную границу. Если cross-origin когда-либо
  будет разрешён, синхронно обновить allowed/exposed headers и добавить integration test preflight.

### BE-FE-04 — import item string limits не описаны contract

`ProductCategoryImportRequest` ограничивает пакет 10 000 элементов и `changeReason` 2 000 символов,
