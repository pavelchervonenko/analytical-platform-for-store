---
doc_schema: 1
doc_type: evidence
status: historical
owner: integration
audience:
  - developer
  - operator
snapshot_date: 2026-10-06
verdict: IN_PROGRESS
verdict_scope: Fresh financial comparisons, the full exact RC backend gate and seller UI corrections are locally verified; runtime attach evidence, classification decisions, CI/image publication and production release gates remain open.
source_of_truth:
  - docs/current/integrations/livesklad/synchronization.md
  - docs/current/api/employee-rating.md
  - docs/current/frontend/sales-structure-and-attach-map.md
  - docs/runbooks/livesklad-historical-sales-refresh.md
  - docs/runbooks/production-deployment.md
  - docs/maintenance/payroll-redesign.md
  - frontend/src/dashboard/OverviewManagementSections.tsx
  - frontend/src/dashboard/OverviewPage.test.tsx
  - backend/src/main/java/com/storeanalytics/sync/service/SalesSyncPersistence.java
  - backend/src/test/java/com/storeanalytics/sync/service/SalesSyncSourceNameTest.java
required_reviewers:
  - integration
  - backend-data
  - security-privacy
---

# Production corrections: локальная контрольная точка

6 октября владелец снял прежнюю отсрочку исправлений и поручил подготовить качественную
production-версию. Исправления выполняются вместе с оставшимся анализом. Этот документ
фиксирует проверенные локальные результаты и ограничения; deployment или запись исправлений
в production-факты на этом checkpoint не выполнены.

Последний полный exact RC `:backend:check` завершился успешно: 2 000 tests в 420 classes
и отдельная OpenAPI-проверка, ноль failures/errors/skips. Source fingerprint до/после/finalization
совпал; dependency transport остановлен. Frontend check даёт 327 PASS; локальные визуальные
проверки и независимые reviews выполнены. CI, images и production acceptance ещё не подтверждены.

## Свежая финансовая проверка

Read-only snapshot получен 6 октября 06:56:40.623314 UTC штатной no-argument командой.
Проверены checksum, 20 manifests, число строк, private modes, роль, REPEATABLE READ READ ONLY
и resolver. Бизнес-зона сравнения — Europe/Kaliningrad; исходные отчёты охватывают 1–4 октября
включительно и экспортированы владельцем 5 октября, точное время неизвестно.

Повторены четыре matcher по двум магазинам: 222 документа, 554 позиции, финансовых и identity
differences нет. Reference каждого из 12 сотрудников пересчитан на этом baseline. Повторного
финансового чтения LiveSklad и общего атомарного cutoff эта проверка не доказывает.

В сравнении со старым baseline одна позиция получила новое имя внутри обычного scheduled окна;
финансовые значения не изменились. Две первоначальные source drift позиции сохранили прежние
имена. Старый supplemental attach package поэтому не запущен: подготовлен отдельный пакет
с новым baseline и теми же scope/field/cap guards. Он прошёл 32 локальные проверки, включая
14 SQL cases в изолированном PostgreSQL; проверен координатором и staged с exact hash.
Выполнение production read-only экспорта ожидается. Отдельный calculator прошёл 24 synthetic
checks; actual warranty/role/roster/API comparison остаётся UNAVAILABLE.

## Исправление карты attach-rate

Восемь новых UI cases воспроизвели сравнение FINALIZED, неизвестной history, другой формулы
или другого store/period с текущим магазином. Исправление разрешает comparison/residual только
для совместимых LIVE responses одного периода и магазина. Отсутствующий employee metric
не считается нулём; preliminary store metric исключает относительную оценку даже при более
раннем разрешающем employee response.

Дополнительно шесть cases воспроизвели смешение архивной выручки/структуры с текущей прибылью.
Прибыль теперь присоединяется только к LIVE того же магазина/периода и при совпадении выручки
сотрудника. В FINALIZED прибыль отсутствует в snapshot и не подставляется из текущего KPI.

32 targeted tests PASS. Полный `npm run check` прошёл contracts, lint, 327 tests в 61 файле
и production build с Node 22.23.1. Точный RC frontend после генерации нового client также
прошёл полный check; его 51 build artifact byte-identical визуально проверенной сборке.
`npm run visual:local` прошёл LIVE и FINALIZED отдельно: по три desktop/tablet/mobile проверки.
Координатор просмотрел таблицу продавцов, attach-map и seller columns; независимый reviewer
сверил 18 capture hashes и исходники, уникальные изображения просмотрены без blockers.
Fixtures синтетические, URL только локальный. Real backend, authentication, production UI
и фактическое наличие этого дефекта в исходном отчётном периоде так не подтверждены.

## Backend и подготовка релиза

Source-local name correction сохраняет имя принятой SALE позиции независимо от более поздней
карточки товара. Ранее получены четыре targeted и 31 related PostgreSQL-backed checks PASS.
Production historical replay ещё не выполнен.

Полный baseline `:backend:check` выполнен с JDK 21: 1 880 tests, восемь failures, ноль errors
и skips. Compile, Checkstyle, OpenAPI, operator security и supply-chain задачи прошли до
test failure. Исходники менялись во время этого baseline-прогона; он не является проверкой
точного release candidate. Три failures связаны с новыми migration assertions и исправлены;
пять прежних context failures в targeted suite проходят без изменения webhook test fixture,
причина начального context failure отдельно не доказана. Первый полный backend gate точного RC
остановлен после независимого обнаружения двух pagination blockers. Exit 143 сохранён,
исходный fingerprint не изменился; fresh OpenAPI case прошёл, полного test result нет.
Новый полный gate запускается после исправления и фиксации нового набора исходников.

CASH/ORDER listing принимали неполные либо несогласованные declared totals. Четырнадцать
actual loopback HTTP regression cases воспроизвели дефект до исправления: empty/short pages,
negative/excess/changed totals и переходы между null и declared. Общая проверка SALE/CASH/ORDER
прошла 69 targeted checks в девяти classes, ноль failures/errors/skips и Checkstyle violations.
Включены real HTTP положительные границы, payload/budget limits, прежние RETURN/ORDER PostgreSQL
regressions и historical partial-fetch case. Independent review сверил source/log/XML pins:
ноль blockers. Новая HTTP→PG end-to-end fixture не заявляется. Неполный CASH
может пропустить RETURN discovery, неполный ORDER — changed/issued documents; actual влияние
на октябрьские числа этой локальной проверкой не установлено. Latest 47 backend source/test
pins сверены между root и чистым RC; повторный полный gate запущен с неизменными стандартными
параметрами проверки и проверенным TLS transport. Он остановлен при дополнительном repair blocker:
exit 143, unchanged fingerprint, закрытый proxy, частично подтверждены OpenAPI и Checkstyle.
Ни один остановленный прогон не объявляется PASS.

В новом default-off механизме covering BACKFILL SUCCESS мог снять dependency block без
перечитывания известного cashless RETURN. Статически доказаны SALE-only parent publication,
cash-only candidate collection и unconditional cursor advance после одного job evidence.
Durable exact dependent cohort, accepted observation/current inherited tuple и known cashless
BACKFILL refresh прошли финальный combined targeted checkpoint: 178 cases в 17 classes, ноль
failures/errors/skips и Checkstyle Main/Test violations. Два отдельных actual PostgreSQL before
cases дали ожидаемые assertion failures: ложный repair advance и пропуск известного возврата.
Сохранённые failing after checkpoints выявили только ошибки fixture merge/immutable identity/
SQL qualification; проверки не ослаблялись. Наличие timestamp/raw last_seen не считается
обновлением зависимого факта. Независимый final review обнаружил concurrent webhook equal-clock
race нового known-return пути. Actual PostgreSQL before case подтвердил SUCCESS nested webhook
с изменённой суммой и null dateChange, затем прежний BACKFILL не отверг ранее полученный detail:
один expected assertion failure, ноль errors/skips. Первый before fixture failure сохранён
отдельно и не считается causal proof. Version-anchor correction сохраняет webhook facts/raw
и откатывает устаревший batch; fresh retry с новым чтением проходит. Все 24 operational
PostgreSQL cases проверены. Independent final review сверил 11 final file pins, 1 752 tested-source
pins, три causal BEFORE, все 17 XML reports и оба Checkstyle reports: ноль blockers.
50 backend pins совпадают в root и RC; RC отдельно сохраняет deployed hotfixes. Полный frozen
gate выполняется отдельно; production activation не выполнена.

Последующий полный exact RC gate завершился с 2 000 tests в 420 classes: 11 failures,
ноль errors/skips. Все failures — ожидания прежней packaged schema в десяти test classes;
source fingerprint неизменен, proxy остановлен. OpenAPI generation, compatibility,
supply-chain, operator security и Checkstyle проходят. Sanitized failure XML и full XML digests
сохранены до нового запуска. Обновлены только 13 latest-schema expectation literals;
исторические migration targets, rollback fixtures и остальные assertions не менялись.
Повторный targeted gate десяти classes прошёл: 20 tests, ноль failures/errors/skips,
main/test Checkstyle без нарушений. Независимый review подтвердил сохранение historical targets
и силы assertions. Последние 60 backend pins совпали в root и RC. Повторный полный exact gate
затем прошёл на неизменных исходниках: 2 000 tests в 420 classes и одна отдельная OpenAPI-проверка,
ноль failures/errors/skips, Gradle exit 0. Compile, Checkstyle, compatibility, supply-chain
и operator security проходят. Source fingerprint до/после/finalization совпал; proxy закрыт.

Исторический SALE механизм прошёл targeted review. Проверенные условия приёмки:

- Неполное или несогласованное declared total не разрешает публикацию либо soft-delete.
- Daily quota pause не расходует failure allowance и ждёт фактического reset.
- Изменение материальных зависимостей связанных RETURN откатывает весь batch; repair scope
  сохраняется независимо от cursor и retention job. Snapshot name входит в postcondition:
  actual V86 SETUP_SERVICE regression воспроизведён before FAIL → after PASS, clock-only
  unchanged name positive case сохранён.
- SALE-only SUCCESS не становится доказательством full-phase weekly coverage и не снимает
  failure hold RETURNS/ORDERS.
- Дополнительные transaction locks сохраняют безопасный порядок с retention.

Combined targeted Gradle command завершился успешно: 127 cases в 15 classes, ноль failures,
errors и skips; main/test Checkstyle без нарушений. Включены 16 core PostgreSQL, семь coverage
и 13 stability cases, три ранее падавших classes и real HTTP budget test. Independent review
сверил 42 frozen source/test pins, XML counts/hashes, Checkstyle и successful command log.
Это не полный CI gate и не подтверждение production-фактов.

22 release-config cases и общий deploy-release-safety suite PASS. Render production Compose
проверил передачу семи параметров worker, выключенный API scheduler и сохранённую TLS validation;
effective production configuration не читалась. Отдельный чистый RC создан поверх фактически
развёрнутого commit; deployed AI и first-attempt fixes сохраняются. Frontend dependencies
установлены в RC через `npm ci` из lockfile и локального cache без сети и credentials.

## Что остаётся открытым

Свежий historical quality audit подтверждает шесть отсутствующих ранее известных RETURN.
Их direct source details перечитаны отдельным fixed plan: шесть документов, девять позиций,
восемь запросов, семь GET, ноль retries. Per-response quota proof и TLS validation сохранены;
raw bodies остались только в памяти, финансовые numeric tokens обработаны через Decimal.
Quantity/net/cost каждого документа совпали с retained expectations, включая три zero-net
возврата. Source state/deletion flags отсутствуют; active status не выведен по догадке.
Отдельное bounded чтение шести исходных продаж завершено: 17 позиций и все девять обязательных
child/original/product/work связей подтверждены. У пяти родителей из baseline совпали все
12 позиций, business dates, occurrence и effective source versions; quantity/gross/discount/net/cost
по позициям и parent net/cost также без дельт. Отсутствующий шестой
родитель относится к декабрю 2025 года, вне границы D-020; импорт/relink из этого наблюдения
не следует. Source state flags отсутствуют и у родителей. Fresh DB/recovery queue и monthly
post-check ещё ожидаются; selected reads не доказывают полноту всех возвратов.

Отдельный frozen read-only package для current recovery queue и payments прошёл 31 synthetic
check и независимый review. Actual SQL rehearsal в минимальной typed PostgreSQL 14 дал восемь
checks и 13 psql attempts: exact SELECT разрешаются через EXPLAIN без ANALYZE, fixed receipt
selector и privacy guards проверены, unsafe role/grants/schema/cap отказывают до fact output,
READ ONLY запрещает запись. Strict validator отклонил намеренно пустой financial cohort;
финансовая полнота этой fixture не заявляется. 34 actual evidence hashes сверены независимым
review, временная БД удалена, 13 frozen package hashes сохранены. Full migration history,
production PostgreSQL 16/TLS/grants и operator execution остаются непроверенными.

Пять ранее известных отсутствующих декабрьских
parents находятся вне выбранной истории: решение владельца `D-020` исключает их из дефектов
загрузки. Старые указания импорта декабря и blanket relink отменены в working manifest;
финансовые факты возвратов внутри истории сохраняются, payroll `Q-10` остаётся открытым.
Шесть старых relink targets уже связаны структурно; повторять их recovery нельзя без нового
доказательства. Ledger, classification и месячные финансовые/attach post-check не завершены.

Нужны actual API responses и подготовленная read-only проекция гарантий/ролей/roster, решения
по неизвестным категориям, подтверждённые shifts для соответствующих показателей и отдельная
проверка ledger/retained returns. Владелец подтвердил отсутствие планов: плановые метрики
остаются недоступными по контракту, данные не придумываются.

Generated API/client проверены: semantic changes ограничены job enum и новой версией transport
contract; published baselines сохранены, compatibility checker не ослаблялся. Frontend consumer
test обновлён под новую версию. Полный exact backend gate прошёл; требуются CI/image publication,
backup/restore и exact production-read-only preflight. GitHub connection пока не подтверждён.
Deployment следует
только после подтверждения показанного конкретного release plan по действующему runbook.
Исходные XLSX, detailed comparisons, test output и изображения находятся в ignored local
`outputs/reconciliation/2026-10-01_2026-10-04/`; в документе нет business amounts, employee
names, customer payloads, credentials или снимков production.
