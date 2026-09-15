---
doc_schema: 1
doc_type: evidence
status: historical
owner: product
audience:
  - developer
  - operator
snapshot_date: 2026-09-14
verdict: PASS_WITH_LIMIT
verdict_scope: "МАГАЗИН, 2026-04-01..2026-04-30 inclusive, Europe/Kaliningrad; post-correction document, item, STORE, SELLERS, category, payroll and attach-rate differences are zero. Limitation: three source-matching ZERO_UNEXPECTED costs accepted by the customer."
source_of_truth:
  - docs/current/product/business-metrics.md
  - docs/current/product/sales-and-returns.md
  - docs/current/product/classification.md
  - docs/current/product/payroll.md
  - docs/current/product/attach-rate.md
  - docs/current/product/periods.md
  - docs/decisions/ADR-0001-return-employee-attribution.md
verification_sources:
  - three user-supplied LiveSklad XLSX reports identified by SHA-256 in this record
  - targeted read-only LiveSklad API verification of two active zero-payment returns and their original sales
  - sanitized production read-only SQL snapshot captured on 2026-09-14
  - post-correction production read-only SQL snapshot captured on 2026-09-14
  - independent post-apply guard verification and full XLSX recomputation
  - current application formulas, classification rules and recovery guards
  - exact document, product-group, employee and attach-rate reconstruction
required_reviewers:
  - product
  - operations
---

# Сверка LiveSklad ↔ production: апрель 2026, МАГАЗИН

## Текущий вердикт

`PASS_WITH_LIMIT`. После отдельного разрешения пользователя выполнены два exact validated
recovery и пять bounded classification corrections. Повторный production-аудит и независимая
реконструкция из исходных XLSX дают нулевую разницу по суммам, единицам, документам, товарным
группам, `STORE`, `SELLERS`, каждому сотруднику, аналитическим и зарплатным категориям и
attach-rate.

Единственное ограничение — три `ZERO_UNEXPECTED` с нулевой себестоимостью. Такие же нули есть в
LiveSklad XLSX; пользователь подтвердил, что нулевая себестоимость допустима. Поэтому это не
межсистемное расхождение, но абсолютная валовая прибыль зависит от качества этих исходных данных.

## Результат после согласованных исправлений

### Выполненные операции и независимые guards

| Операция | Точный scope | Результат | Доказательство |
|---|---|---|---|
| validated recovery | `F000180 / 69ceb3cc35f1a21707bfcd44`; 1 позиция; 18,000.00 ₽ | `PROCESSED`, terminal failure false | API recovery result + post-audit |
| validated recovery | `F000217 / 69ef6362b4a90302358d473b`; 7 позиций; 112,990.00 ₽ | `PROCESSED`, terminal failure false | API recovery result + post-audit |
| bounded classification | `A000307`, `A000316`, `A000317`, `A000358`, `A000368`; только `[2026-04-01, 2026-05-01)` | 5 строк → `SETUP_SERVICE / PAID_REPAIR` | transactional apply + independent `--verify` |
| assignment scope | 2 exact products; 5 ед.; 17,000.00 ₽ revenue; 9,700.00 ₽ cost | 2 analytics + 2 payroll assignments; audit records 2 + 2 | post-apply database guards |
| cleanup | временные runners, sudo allowlist, uploads и remote audit file | `cleanup=verified` | exact hash-checked remover + absence check |

До записи preflight подтвердил exact IDs, магазин, даты, сотрудников, названия, quantity, price,
discount, revenue, cost, `SERVICE` source kind и current classifications. Также подтверждены: нет
пересекающихся assignments, активных апрельских sync jobs, утверждённых/оплаченных payroll runs и
утверждённых/архивных report snapshots. Запись выполнена одной транзакцией; широкие backfill,
resync и deploy не запускались.

### Финальный STORE

Во всех строках разница равна `Приложение − LiveSklad`.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка | 52,351,810.00 | 52,351,810.00 | 0.00 ₽ | 0.00% | PASS | — | full item recomputation |
| Себестоимость | 45,998,132.00 | 45,998,132.00 | 0.00 ₽ | 0.00% | PASS_WITH_LIMIT | 3 одинаковых source zero costs | XLSX + production quality |
| Валовая прибыль | 6,353,678.00 | 6,353,678.00 | 0.00 ₽ | 0.00% | PASS_WITH_LIMIT | зависит от тех же source zero costs | revenue − cost |
| Валовая маржа | 12.1365% | 12.1365% | 0.0000 п. п. | 0.00% | PASS_WITH_LIMIT | та же оговорка cost | единое финальное округление |
| Чистое количество | 1,918 ед. | 1,918 ед. | 0 ед. | 0.00% | PASS | — | full item recomputation |
| Продажи | 831 док. / 2,025 ед. / 54,701,970.00 ₽ | то же | 0 | 0.00% | PASS | — | 831/831 exact documents |
| Возвраты продаж | 44 док. / −112 ед. / −2,367,160.00 ₽ | то же | 0 | 0.00% | PASS | — | 44/44 exact documents |
| Возвраты заказов | 0 | 0 | 0 | — | PASS | отсутствуют | orders report + production |
| Выданные заказы | 6 строк; 5 ненулевых; 5 ед.; 17,000.00 ₽ | 5 facts / 5 ед. / 17,000.00 ₽ | 0 по ненулевым фактам | 0.00% | EXPLAINED | `A000278` имеет нулевую сумму и не создаёт fact | orders + goods + SQL |
| Аксессуары | 856 ед. / 2,447,964.00 ₽ | то же | 0 ед. / 0.00 ₽ | 0.00% | PASS | — | semantic category reconstruction |
| Услуги и допы | 352 ед. / 1,860,554.00 ₽ | то же | 0 ед. / 0.00 ₽ | 0.00% | PASS | — | semantic category reconstruction |

Структура также совпала: devices — 710 ед. / 48,043,292.00 ₽; accessories — 856 ед. /
2,447,964.00 ₽; services/additional revenue — 352 ед. / 1,860,554.00 ₽. Во всех трёх группах
quantity, revenue, cost и GP имеют нулевую разницу.

### Финальное покрытие документов, сотрудников и категорий

- document keys: `880/880`; only-Live — 0; only-app — 0; value mismatches — 0;
- product groups после агрегации одинаковых строк товара внутри документа: `2,071/2,071`;
  only-Live — 0; only-app — 0; value mismatches — 0; classification conflicts — 0;
- 12 различий raw-row count остаются только объяснённой агрегацией одинакового товара:
  quantity, revenue и cost у каждой группы совпадают;
- `STORE`: 2,074 application facts, zero semantic delta;
- `SELLERS`: 697 facts; 671 ед.; 18,460,987.00 ₽ revenue; 16,344,694.00 ₽ cost;
  2,116,293.00 ₽ GP; zero semantic delta;
- каждый сотрудник, employee/category и employee/payroll: ненулевых различий 0;
- возвраты `F000180` и `F000217` повторно совпали с XLSX по всем 8 позициям и отнесены к
  сотрудникам исходных `B003690` и `B004400`; attribution failures — 0;
- все аналитические категории: ненулевых различий 0;
- все зарплатные категории: ненулевых различий 0; `PAID_REPAIR` = 5 ед. / 17,000.00 ₽ /
  9,700.00 ₽ cost / 7,300.00 ₽ GP;
- attach-rate: ненулевых semantic differences по числителю, знаменателю и rate — 0 отдельно для
  `STORE` и каждого рейтингового сотрудника; production recomputation mismatches — 0.

### Деньги, полнота и доверие

Payment ledger совпадает отдельно: LiveSklad paid movement и application signed payments равны
52,447,800.00 ₽. `F000180`, `F000217` и исходная `B003690` имеют нулевые cash events; поэтому
разница cash-versus-merchandise 112,990.00 ₽ объяснена семантикой движения денег и не является
ошибкой STORE revenue.

Исторические failed sync-run rows сохранены как evidence, но финальная полнота подтверждена не их
статусом, а exact document/item sets и values. Recovery увеличил successful RETURNS coverage до
44 созданных документов. Широкая повторная синхронизация для апреля не нужна.

Апрельским показателям можно доверять для `STORE`, `SELLERS`, сотрудников, структуры, categories,
payroll и attach-rate с единственной оговоркой о трёх исходных нулевых себестоимостях. Системные
исправления cash-less return discovery и общего приоритета paid-repair rules остаются кандидатами
в согласованный batch deploy после завершения помесячных проверок; апрельские данные уже
исправлены точечно и такого deploy не ждут.

Ниже сохранено полное evidence состояния **до исправлений**, чтобы причина и величина каждой
исходной разницы оставались воспроизводимыми.

## Scope, фильтры и источники

| Параметр | Зафиксированное значение | Доказательство |
|---|---|---|
| Магазин | `МАГАЗИН` | выбор пользователя; единственный магазин в scope до июля |
| Период | `2026-04-01..2026-04-30`, обе даты включительно | содержимое XLSX и production `business_date` |
| Business timezone | `Europe/Kaliningrad`, начало дня `00:00` | production store и period contract |
| «Товары и работы» | магазин `МАГАЗИН`; апрель; `Продажа`, `Возврат`, `Установка в заказ`; заказы `Выдан` | effective XLSX content |
| «Продажи» | магазин `МАГАЗИН`; апрель; продажи и возвраты продаж | 875 document rows |
| «Заказы» | магазин `МАГАЗИН`; дата выдачи в апреле; выданные заказы | 6 rows, включая один нулевой заказ |

Экранные фильтры LiveSklad не сохранены в XLSX как полные машиночитаемые metadata. Поэтому audit
фиксирует параметры пользователя и независимо проверяет фактическое содержимое файлов.

| Файл | Строк данных | Размер | SHA-256 |
|---|---:|---:|---|
| `Отчёт по товарам и работам апрель.xlsx` | 2,086 | 901,882 B | `7e8c3eda0196c5828401712d2b8b4869a4ff040946fcd813402ef5ea04d0d678` |
| `Отчёт по продажам апрель.xlsx` | 875 | 419,466 B | `8524ee3c3f9798b6d64d63170b51bc937d689bd4195f2fe686cbefeda61cf466` |
| `Отчёт по заказам апрель.xlsx` | 6 | 21,291 B | `1135248c0b1ea26b294df228060a932d2e98cab9e421249b208a7fed05c567e5` |

Свежий production read-only запуск подтвердил runtime identity из
[`project-state.md`](../../../../current/project-state.md), schema version 48, точный магазин и
timezone. SQL выполнялся с read-only guards и завершился `ROLLBACK`. Использованы существующие
owner key, разблокированный agent socket и Windows TCP proxy; новые SSH-ключи не создавались.
Секреты, provider payloads и персональные данные в evidence не сохранены.

## Семантика сравнения

- знак факта: `SALE=+1`, `RETURN=-1`; GP = revenue − cost;
- `STORE` включает все факты магазина; `SELLERS` — только пять рейтинговых сотрудников;
- возврат в `SELLERS` относится сотруднику исходной продажи по ADR-0001, а не обработавшему
  возврат сотруднику из XLSX;
- аналитическая и зарплатная классификация рассчитаны раздельно;
- все платные ремонтные работы Future Store являются услугами, но в payroll входят в
  `PAID_REPAIR`;
- attach-rate использует чистые item units после возвратов;
- merchandise return и движение денег не смешиваются;
- нулевая себестоимость допустима по решению пользователя, но остаётся quality limitation.

## Полнота синхронизации

Исторические sync runs содержат и успешные, и failed записи. Поэтому их статусы не приняты за
доказательство полноты; текущее покрытие установлено по exact sets и значениям:

- LiveSklad: 880 ненулевых document keys — 831 sale, 44 sale return, 5 order positions;
- production: 878 — 831 sale, 42 sale return, 5 order positions;
- only-Live: ровно `F000180`, `F000217`; only-app — 0;
- у всех 878 общих документов quantity, revenue и cost совпадают; value mismatches — 0;
- после агрегации одинакового товара внутри документа LiveSklad содержит 2,071 product groups,
production — 2,063; only-Live — ровно 8 API-подтверждённых позиций двух возвратов; only-app и
  value mismatch — 0;
- deleted documents/items, missing cost, `UNMAPPED`, `EXCLUDE` и ambiguous rename matches — 0;
- production attach recomputation mismatch — 0;
- все 42 загруженных возврата имеют исходные links; нарушения атрибуции store/employee — 0.

Итог: продажи и ненулевые выданные заказы покрыты полностью; возвраты продаж — только 42/44
документа, 99/107 строк и 104/112 единиц. Два missing return имеют нулевую оплату и в XLSX, и в
API. Текущий period sync обнаруживает returns через `saleReturn` cash transactions; следовательно,
cash-less документ этим discovery path не находится. Source API доказал active status, store,
date, parents, original positions и original-sale employees обоих документов.

## STORE: итог месяца

Во всех таблицах разница равна `Приложение − LiveSklad`. Под LiveSklad понимается проверенная
бизнес-семантика трёх отчётов, а не отдельная денежная колонка.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка | 52,351,810.00 | 52,482,800.00 | +130,990.00 ₽ | +0.25% | FAIL | 2 missing zero-payment returns | 8 only-Live positions |
| Себестоимость | 45,998,132.00 | 46,070,007.00 | +71,875.00 ₽ | +0.16% | FAIL | те же returns | exact position cost |
| Валовая прибыль | 6,353,678.00 | 6,412,793.00 | +59,115.00 ₽ | +0.93% | FAIL | сумма тех же returns | revenue − cost |
| Валовая маржа | 12.1365% | 12.2188% | +0.0823 п. п. | +0.68% | FAIL | завышены revenue и GP | единое финальное округление |
| Чистое количество | 1,918 ед. | 1,926 ед. | +8 ед. | +0.42% | FAIL | отсутствуют 8 return units | item reconciliation |
| Продажи | 831 док. / 2,025 ед. / 54,701,970.00 ₽ | то же | 0 | 0.00% | PASS | — | all sale documents and groups exact |
| Возвраты продаж | 44 док. / −112 ед. / −2,367,160.00 ₽ | 42 док. / −104 ед. / −2,236,170.00 ₽ | −2 док. / +8 ед. / +130,990.00 ₽ | +5.53% по абсолютной revenue | FAIL | `F000180`, `F000217` отсутствуют | document + item sets |
| Выданные заказы | 6 строк; 5 ненулевых; 5 ед.; 17,000.00 ₽ | 5 facts / 5 ед. / 17,000.00 ₽ | 0 по ненулевым фактам | 0.00% | EXPLAINED | нулевой `A000278` не создаёт fact | orders + goods + SQL |
| Возвраты заказов | 0 | 0 | 0 | — | PASS | отсутствуют | orders report and production facts |

## Документы и позиции финансовой разницы

| Return / user-supplied external ID | Дата XLSX | Expected original | Позиции | Signed qty / revenue / cost / GP | Payment | Статус |
|---|---|---|---:|---:|---:|---|
| `F000180` / `69ceb3cc35f1a21707bfcd44` | 02.04 21:22 | `B003690 / 69ce6193db593d3244e20bec` | 1 | −1 / −18,000 / −6,000 / −12,000 | 0 | API_VERIFIED_MISSING_RETURN |
| `F000217` / `69ef6362b4a90302358d473b` | 27.04 16:23 | `B004400 / 69ee3a12b4a9035caa7ae383` | 7 | −7 / −112,990 / −65,875 / −47,115 | 0 | API_VERIFIED_MISSING_RETURN |
| **Итого** | — | — | **8** | **−8 / −130,990 / −71,875 / −59,115** | **0** | **EXACT STORE DELTA** |

Позиционная раскладка:

| Return | Позиция | Qty | Revenue | Cost | GP | Ожидаемая analytics / payroll |
|---|---|---:|---:|---:|---:|---|
| `F000180` | iPhone 11 128GB White, used | −1 | −18,000 | −6,000 | −12,000 | `IPHONE_USED / TECH_TIER_1` |
| `F000217` | iPhone 17 256GB Black, new | −1 | −78,990 | −64,100 | −14,890 | `IPHONE_NEW_ASIS / TECH_TIER_1` |
| `F000217` | Future Store Check++ | −1 | −14,990 | 0 | −14,990 | `WARRANTY_GENERIC / SERVICE` |
| `F000217` | защитное стекло Remax | −1 | −2,990 | −175 | −2,815 | `GLASS_IPHONE / ACCESSORY` |
| `F000217` | установка защитного покрытия | −1 | −1,490 | 0 | −1,490 | `SETUP_SERVICE / SERVICE` |
| `F000217` | перенос данных | −1 | −8,990 | 0 | −8,990 | `SETUP_SERVICE / SERVICE` |
| `F000217` | чехол Mag Noble Collection | −1 | −3,550 | −1,600 | −1,950 | `CASE_APPLE_IPHONE / ACCESSORY` |
| `F000217` | обновление ПО | −1 | −1,990 | 0 | −1,990 | `SETUP_SERVICE / SERVICE` |

Для `F000180` локально существовали два композиционных кандидата: `B003690` в 15:31 и `B003704`
в 21:23. Provider detail однозначно указал `B003690`; поэтому `B003704`, проведённая через минуту
после возврата, не используется для атрибуции. Для `F000217` provider подтвердил единственный
точный композиционный match `B004400`.

### Exact API identifiers позиций

| Return | Return position external ID | Original position external ID | Product external ID | Revenue / cost | Link status |
|---|---|---|---|---:|---|
| `F000180` | `69ceb3cc35f1a249d9bfcd43` | `69ce5fc535f1a25300b8a724` | `69c80a4135f1a22e9452b1ef` | 18,000 / 6,000 | MATCH |
| `F000217` | `69ef6362b4a90319188d4738` | `69ee3709b4a903e9d87abc8e` | `69109c94bd63ebb20e666957` | 8,990 / 0 | MATCH |
| `F000217` | `69ef6362b4a90334e38d4735` | `69ee3666b4a903fae57ab3d9` | `691208bc09e647dcb9541e1b` | 14,990 / 0 | MATCH |
| `F000217` | `69ef6362b4a9035c118d4739` | `69ee37e6b4a903ee6b7ac778` | `6996eab5b3671d7cdfb9f159` | 3,550 / 1,600 | MATCH |
| `F000217` | `69ef6362b4a90368b38d4737` | `69ee36fa2a67e60b349ef356` | `69109fa0bd63eb02f766a041` | 1,490 / 0 | MATCH |
| `F000217` | `69ef6362b4a903d4718d4734` | `69ee34cc2a67e621139ed40d` | `69734d5cb4f1c32555396c41` | 78,990 / 64,100 | MATCH |
| `F000217` | `69ef6362b4a903dc5d8d4736` | `69ee36cd2a67e618799ef128` | `69714d9b23340119fe23601b` | 2,990 / 175 | MATCH |
| `F000217` | `69ef6362b4a903e7f28d473a` | `69ee380c2a67e674bb9f0212` | `6971211723340125e7231221` | 1,990 / 0 | MATCH |

Independent verifier сопоставил API↔XLSX по нормализованному имени, quantity, revenue и cost,
API return↔parent по original position/product IDs и API↔SQL по only-Live set и полной STORE
дельте. Результат: `PASS`, unexplained documents 0, unexplained positions 0.

Для source-проверки временно устанавливался root-owned exact runner с `NOPASSWD` только для одной
команды без аргументов. Он выполнял только auth и `GET` requests, принимал фиксированные два IDs и
не выводил credentials или PII. После сохранения sanitized результата runner, отдельный sudoers и
все загруженные production `/tmp` copies удалены; повторный `sudo -n` запуск запрещён,
`cleanup=verified`. Локальные source-audit/install/remove scripts сохранены для воспроизводимости.

## Движение денег отдельно от продаж

| Показатель | LiveSklad | Приложение | Разница | Статус | Объяснение |
|---|---:|---:|---:|---|---|
| Paid movement | 52,447,800.00 | 52,447,800.00 | 0 | PASS | signed payment ledger exact |
| Net sales merchandise без orders | 52,334,810.00 | 52,465,800.00 | +130,990.00 | FAIL | missing merchandise returns |
| Cash − expected sales merchandise | +112,990.00 | — | — | EXPLAINED | `B003690` +18,000 и `F000180` −18,000 cash-less и взаимно нейтральны; `F000217` −112,990 cash-less |

Колонка «Возврат» в отчёте продаж для обоих missing documents равна нулю. Это не отменяет
товарный возврат и не является причиной исключать signed merchandise facts.
Выданные заказы на 17,000.00 ₽ учитываются в `STORE`, но не входят в sales payment ledger и
поэтому в этой таблице отделены.

## STORE: структура продаж

Ожидаемая структура учитывает оба missing return и исправленную смысловую классификацию
`A000317`.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Техника | 710 / 48,043,292 / cost 45,483,702 / GP 2,559,590 | 712 / 48,140,282 / 45,553,802 / 2,586,480 | +2 / +96,990 / +70,100 / +26,890 | +0.20% revenue | FAIL | phone positions двух returns | item decomposition |
| Аксессуары | 856 / 2,447,964 / 503,730 / 1,944,234 | 859 / 2,457,004 / 506,505 / 1,950,499 | +3 / +9,040 / +2,775 / +6,265 | +0.37% | FAIL | 2 позиции `F000217` + `A000317` | item decomposition |
| Услуги | 352 / 1,860,554 / 10,700 / 1,849,854 | 355 / 1,885,514 / 9,700 / 1,875,814 | +3 / +24,960 / −1,000 / +25,960 | +1.34% | FAIL | 4 позиции `F000217`, минус перенос `A000317` в service | item decomposition |
| Допы | 1,208 / 4,308,518 / 514,430 / 3,794,088 | 1,214 / 4,342,518 / 516,205 / 3,826,313 | +6 / +34,000 / +1,775 / +32,225 | +0.79% | FAIL | missing extras; перенос `A000317` внутри допов нейтрален | recomputation |

## Все аналитические категории STORE

Формат — `quantity / revenue / cost / GP`; разница — приложение минус ожидание.

| Категория | LiveSklad / ожидается | Приложение | Разница | Статус | Причина |
|---|---:|---:|---:|---|---|
| `ACCESSORY_IPAD_MAC` | 6 / 23,760 / 5,150 / 18,610 | то же | 0 | PASS | — |
| `ACCESSORY_PODS_WATCH` | 27 / 43,840 / 7,915 / 35,925 | то же | 0 | PASS_WITH_LIMIT | 2 source zero costs |
| `CASE_APPLE_IPHONE` | 131 / 130,541 / 43,760 / 86,781 | 132 / 134,091 / 45,360 / 88,731 | +1 / +3,550 / +1,600 / +1,950 | FAIL | `F000217` |
| `CASE_SAMSUNG` | 14 / 60,380 / 17,400 / 42,980 | то же | 0 | PASS | — |
| `CHARGER_CABLE` | 225 / 921,102 / 242,590 / 678,512 | то же | 0 | PASS | — |
| `FILM_PHONE` | 46 / 83,710 / 3,455 / 80,255 | то же | 0 | PASS | — |
| `GLASS_CAMERA_IPHONE` | 63 / 173,776 / 41,525 / 132,251 | то же | 0 | PASS | — |
| `GLASS_CAMERA_SAMSUNG` | 6 / 19,218 / 4,000 / 15,218 | то же | 0 | PASS | — |
| `GLASS_IPHONE` | 226 / 599,424 / 47,400 / 552,024 | 228 / 604,914 / 48,575 / 556,339 | +2 / +5,490 / +1,175 / +4,315 | FAIL | `F000217` + `A000317` |
| `GLASS_SAMSUNG` | 18 / 53,360 / 5,450 / 47,910 | то же | 0 | PASS | — |
| `IPAD_MAC` | 36 / 2,331,380 / 2,255,400 / 75,980 | то же | 0 | PASS | — |
| `IPHONE_NEW_ASIS` | 379 / 33,372,075 / 32,744,802 / 627,273 | 380 / 33,451,065 / 32,808,902 / 642,163 | +1 / +78,990 / +64,100 / +14,890 | FAIL | `F000217` |
| `IPHONE_USED` | 149 / 6,478,137 / 4,892,150 / 1,585,987 | 150 / 6,496,137 / 4,898,150 / 1,597,987 | +1 / +18,000 / +6,000 / +12,000 | FAIL | `F000180` |
| `OTHER_ACCESSORY_PRODUCT` | 94 / 338,853 / 85,085 / 253,768 | то же | 0 | PASS_WITH_LIMIT | 1 source zero cost |
| `PODS_WATCH_OTHER_DEVICE` | 89 / 1,772,520 / 1,658,250 / 114,270 | то же | 0 | PASS | — |
| `PREMIUM_PROTECTION` | 3 / 39,590 / 0 / 39,590 | то же | 0 | PASS | — |
| `SAMSUNG_NEW` | 50 / 3,712,250 / 3,609,700 / 102,550 | то же | 0 | PASS | — |
| `SAMSUNG_USED` | 7 / 376,930 / 323,400 / 53,530 | то же | 0 | PASS | — |
| `SETUP_SERVICE` | 228 / 612,400 / 10,700 / 601,700 | 230 / 622,370 / 9,700 / 612,670 | +2 / +9,970 / −1,000 / +10,970 | FAIL | 3 returns missing; `A000317` absent from service |
| `WARRANTY_GENERIC` | 121 / 1,208,564 / 0 / 1,208,564 | 122 / 1,223,554 / 0 / 1,223,554 | +1 / +14,990 / 0 / +14,990 | FAIL | `F000217` |

## Зарплатные категории STORE

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| `ACCESSORY` | 856 / 2,447,964 / 503,730 / 1,944,234 | 859 / 2,457,004 / 506,505 / 1,950,499 | +3 / +9,040 / +2,775 / +6,265 | +0.37% | FAIL | `F000217`, `A000317` | item classification |
| `SERVICE` | 347 / 1,843,554 / 1,000 / 1,842,554 | 355 / 1,885,514 / 9,700 / 1,875,814 | +8 / +41,960 / +8,700 / +33,260 | +2.28% | FAIL | missing service returns + 4 repairs in SERVICE | item classification |
| `PAID_REPAIR` | 5 / 17,000 / 9,700 / 7,300 | 0 | −5 / −17,000 / −9,700 / −7,300 | −100.00% | FAIL | все 5 order works misclassified | source kind + customer rule |
| `TECH_TIER_1` | 601 / 45,665,242 / 43,253,852 / 2,411,390 | 603 / 45,762,232 / 43,323,952 / 2,438,280 | +2 / +96,990 / +70,100 / +26,890 | +0.21% | FAIL | phone positions returns | item classification |
| `TECH_TIER_2` | 109 / 2,378,050 / 2,229,850 / 148,200 | то же | 0 | 0.00% | PASS | — | item classification |

Колонка XLSX «Зарплата за продажу» равна нулю и не подменяет payroll KPI приложения.

## Платные ремонты в выданных заказах

Все пять ненулевых order positions имеют source kind `is_work=true`; orders report показывает
всю сумму как work, parts = 0. Поэтому это аналитические услуги и payroll `PAID_REPAIR`.

| Документ | Позиция | Qty / revenue / cost / GP | Сейчас | Ожидается | Статус |
|---|---|---:|---|---|---|
| `A000307` | Замена аккумулятора | 1 / 3,500 / 1,800 / 1,700 | `SETUP_SERVICE / SERVICE` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_PAYROLL |
| `A000316` | Замена аккумулятора | 1 / 4,500 / 2,500 / 2,000 | `SETUP_SERVICE / SERVICE` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_PAYROLL |
| `A000317` | Замена стекла на камеру | 1 / 2,500 / 1,000 / 1,500 | `GLASS_IPHONE / ACCESSORY` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_ANALYTICS_AND_PAYROLL |
| `A000358` | Замена аккумулятора | 1 / 3,500 / 2,200 / 1,300 | `SETUP_SERVICE / SERVICE` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_PAYROLL |
| `A000368` | Замена аккумулятора | 1 / 3,000 / 2,200 / 800 | `SETUP_SERVICE / SERVICE` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_PAYROLL |

Нулевой выданный заказ `A000278` содержит 0 amount/work/parts/cost/payment и корректно не создаёт
финансовый fact.

## SELLERS отдельно от STORE

Оба подтверждённых original employees входят в рейтинг, поэтому aggregate `SELLERS` содержит оба
missing return. Их индивидуальное распределение установлено exact provider parent links.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| SELLERS total | 671 / 18,460,987 / cost 16,344,694 / GP 2,116,293 | 679 / 18,591,977 / 16,416,569 / 2,175,408 | +8 / +130,990 / +71,875 / +59,115 | +0.71% revenue | FAIL | оба returns принадлежат ranking cohort | semantic recomputation |
| Техника | 262 / 17,243,826 / 16,184,718 / 1,059,108 | 264 / 17,340,816 / 16,254,818 / 1,085,998 | +2 / +96,990 / +70,100 / +26,890 | +0.56% | FAIL | phone returns | item decomposition |
| Аксессуары | 313 / 734,912 / 151,476 / 583,436 | 316 / 743,952 / 154,251 / 589,701 | +3 / +9,040 / +2,775 / +6,265 | +1.23% | FAIL | `F000217`, `A000317` | item decomposition |
| Услуги | 96 / 482,249 / 8,500 / 473,749 | 99 / 507,209 / 7,500 / 499,709 | +3 / +24,960 / −1,000 / +25,960 | +5.18% | FAIL | `F000217`, `A000317` | item decomposition |

## Каждый сотрудник

Ожидаемые employee totals ниже используют API-подтверждённые original-sale links. Все шесть
сотрудников сопоставлены окончательно.

| Employee alias | Ranking | LiveSklad expected qty / revenue / cost / GP | Приложение | Разница приложения | Статус |
|---|---|---:|---:|---:|---|
| `EMP-789FAC07DBB9` | да | 179 / 4,159,620 / 3,597,640 / 561,980 | то же | 0 | PASS |
| `EMP-B802A9C3FDD3` | нет | 1,247 / 33,890,823 / 29,653,438 / 4,237,385 | то же | 0 | PASS |
| `EMP-C92D2C99CC54` | да | 120 / 3,541,490 / 3,171,844 / 369,646 | то же | 0 | PASS |
| `EMP-DD4F314C0A50` | да | 111 / 2,693,982 / 2,285,200 / 408,782 | 118 / 2,806,972 / 2,351,075 / 455,897 | +7 / +112,990 / +65,875 / +47,115 | FAIL; `F000217` |
| `EMP-EBC62AF18E20` | да | 36 / 798,730 / 687,930 / 110,800 | то же | 0 | PASS |
| `EMP-EE11A56D7AF0` | да | 225 / 7,267,165 / 6,602,080 / 665,085 | 226 / 7,285,165 / 6,608,080 / 677,085 | +1 / +18,000 / +6,000 / +12,000 | FAIL; `F000180` |

Raw employee в return XLSX обозначает обработавшего возврат. Для 40 загруженных returns он
отличается от employee исходной продажи; это ожидаемая ADR-0001 семантика, а не 40 ошибок
приложения. Все загруженные returns имеют согласованные original links. Source API gate относится
только к двум отсутствующим документам.

Затронутые employee/category и employee/payroll ячейки полностью сводятся к восьми missing
positions и пяти repair corrections; иных ненулевых остатков нет. `A000317` переносит у
`EMP-EE11A56D7AF0` 1 ед. / 2,500 ₽ / cost 1,000 ₽ из `GLASS_IPHONE / ACCESSORY` в
`SETUP_SERVICE / PAID_REPAIR`. Четыре остальные order works переносят те же суммы между
`SERVICE` и `PAID_REPAIR` у своих exact employees.

## Attach-rate STORE

| Метрика | LiveSklad N/B/rate | Приложение N/B/rate | Разница N/B/п. п. | Статус | Причина |
|---|---:|---:|---:|---|---|
| `ACCESSORY_IPAD` | 6 / 18 / 33.33% | то же | 0 | PASS | — |
| `ACCESSORY_PODS_WATCH` | 27 / 83 / 32.53% | то же | 0 | PASS | — |
| `CASE_APPLE_IPHONE` | 131 / 528 / 24.81% | 132 / 530 / 24.91% | +1 / +2 / +0.10 | FAIL | `F000217`, оба phone denominators |
| `CASE_SAMSUNG` | 14 / 57 / 24.56% | то же | 0 | PASS | — |
| `CHARGER_CABLE` | 225 / 585 / 38.46% | 225 / 587 / 38.33% | 0 / +2 / −0.13 | FAIL | phone denominators |
| `FILM_PHONE` | 46 / 585 / 7.86% | 46 / 587 / 7.84% | 0 / +2 / −0.02 | FAIL | phone denominators |
| `GLASS_CAMERA_IPHONE` | 63 / 528 / 11.93% | 63 / 530 / 11.89% | 0 / +2 / −0.04 | FAIL | phone denominators |
| `GLASS_CAMERA_SAMSUNG` | 6 / 57 / 10.53% | то же | 0 | PASS | — |
| `GLASS_IPHONE` | 226 / 528 / 42.80% | 228 / 530 / 43.02% | +2 / +2 / +0.22 | FAIL | `F000217` + `A000317` |
| `GLASS_SAMSUNG` | 18 / 57 / 31.58% | то же | 0 | PASS | — |
| `PREMIUM_PROTECTION` | 0 / 707 / 0.00% | 0 / 709 / 0.00% | 0 / +2 / 0.00 | FAIL_COUNT | phone denominators |
| `SETUP_SERVICE` | 228 / 601 / 37.94% | 230 / 603 / 38.14% | +2 / +2 / +0.20 | FAIL | 3 missing service units; `A000317` expected +1 |
| `WARRANTY_GENERIC_NEW` | 64 / 429 / 14.92% | 65 / 430 / 15.12% | +1 / +1 / +0.20 | FAIL | `F000217` |
| `WARRANTY_GENERIC_USED` | 60 / 156 / 38.46% | 60 / 157 / 38.22% | 0 / +1 / −0.24 | FAIL | `F000180` denominator |

В `SELLERS` ненулевые attach differences возникают только у подтверждённых original employees
`EMP-DD4F314C0A50` и `EMP-EE11A56D7AF0`. Максимальные изменения: DD4
`SETUP_SERVICE` 16/30/53.33% против 19/31/61.29% (+7.96 п. п.) и
`WARRANTY_GENERIC_NEW` 3/18/16.67% против 4/19/21.05% (+4.38 п. п.); EE11
`SETUP_SERVICE` 12/87/13.79% против 11/88/12.50% (−1.29 п. п.) и
`WARRANTY_GENERIC_USED` 7/22/31.82% против 7/23/30.43% (−1.39 п. п.). После correction должен
быть выполнен полный повторный employee attach audit.

## Построчная проверка, границы и округление

2,086 XLSX rows превращаются в 2,074 ожидаемых facts после консолидации 12 повторных occurrences
одинакового товара внутри документа. Production хранит 2,066 facts из-за восьми missing return
positions. Все 12 агрегаций имеют exact quantity/amount/cost и не являются дубликатами:

| Документ | Товар | XLSX rows → app rows | Qty | Amount | Статус |
|---|---|---:|---:|---:|---|
| `B003834` | стекло Remax iPhone 13/14 | 2 → 1 | 2 | 4,000 | EXPLAINED_AGGREGATION |
| `B003940` | стекло Supglass iPhone 17 Pro Max | 2 → 1 | 2 | 5,980 | EXPLAINED_AGGREGATION |
| `B004003` | блок Baseus 30W | 2 → 1 | 2 | 9,980 | EXPLAINED_AGGREGATION |
| `B004078` | iPhone 17 Pro Max 256GB | 2 → 1 | 2 | 261,000 | EXPLAINED_AGGREGATION |
| `B004130` | стекло Remax iPhone 16 Pro/17 | 2 → 1 | 2 | 3,980 | EXPLAINED_AGGREGATION |
| `B004216` | iPhone 17 Pro Max 256GB eSIM | 2 → 1 | 2 | 206,980 | EXPLAINED_AGGREGATION |
| `B004235` | Apple Watch S11 46mm | 2 → 1 | 2 | 61,980 | EXPLAINED_AGGREGATION |
| `B004247` | AirPods Pro 2 USB-C | 2 → 1 | 2 | 31,980 | EXPLAINED_AGGREGATION |
| `B004282` | стекло Remax iPhone 16 Pro/17 | 2 → 1 | 2 | 3,000 | EXPLAINED_AGGREGATION |
| `B004370` | iPhone 16 256GB | 2 → 1 | 2 | 113,800 | EXPLAINED_AGGREGATION |
| `B004370` | стекло Remax iPhone 15/16 | 2 → 1 | 2 | 4,980 | EXPLAINED_AGGREGATION |
| `B004372` | защита камеры Keephone iPhone 17 | 2 → 1 | 2 | 3,000 | EXPLAINED_AGGREGATION |

У `B004487` source name позже получил уточнение ревизии PlayStation; document, quantity, amount и
cost совпадают, ambiguity — 0. Все 878 общих timestamps в XLSX на 59–60 минут позже production
local representation (860 × 59 минут из-за обрезанных секунд, 18 × 60 минут). Ни один документ не
пересекает границу месяца; финансовый эффект — 0. Округлительных расхождений — 0.

## ZERO_UNEXPECTED

Три товарные позиции имеют cost 0 и точно такие же значения в исходном XLSX. Пользователь ранее
подтвердил, что нулевая себестоимость допускается. Они не создают межсистемную дельту, но
ограничивают абсолютную достоверность GP:

| Документ | Позиция | Qty | Revenue | Cost | Категория | Статус |
|---|---|---:|---:|---:|---|---|
| `B004159` | Чехол Flip | 1 | 4,990 | 0 | `OTHER_ACCESSORY_PRODUCT` | PASS_WITH_LIMIT |
| `B004340` | Ремешок «миланская петля» | 1 | 1,490 | 0 | `ACCESSORY_PODS_WATCH` | PASS_WITH_LIMIT |
| `B004443` | Ремешок «миланская петля» | 1 | 2,000 | 0 | `ACCESSORY_PODS_WATCH` | PASS_WITH_LIMIT |

## Классификация причин

| Класс причины | Итог | Доказательство |
|---|---|---|
| Неполное покрытие синхронизации | обнаружено | 2 only-Live returns / 8 positions |
| Пропущенный или скрытый возврат | обнаружено | `F000180`, `F000217`; API active; cash totals и transaction count 0 |
| Неверная классификация | обнаружена | 5 order works не в `PAID_REPAIR`; `A000317` также не в service |
| Неправильный магазин или сотрудник | store error — нет; return employee links — нет | exact API store и original-sale links всех 44 returns |
| Отличие формул | только merchandise vs cash | payment ledger exact; cash-less documents объяснены отдельно |
| Отличие границ периода | effect 0 | exact common set; one-hour presentation offset без перехода даты |
| Дубликат или удаление | не обнаружено | 12 exact row aggregations; deleted facts 0 |
| Ошибка исходных данных LiveSklad | финансовая ошибка не доказана | 3 разрешённых zero costs — quality limitation |
| Ошибка приложения | доказана | cash-transaction-only return discovery; source-kind priority для paid repairs |

## Безопасный план после закрытия source evidence gate

1. Provider external IDs `F000180`, `F000217`, exact parent numbers, active status, store, dates,
   position/product/original-position IDs, original employees, values и отсутствие cash
   transactions подтверждены read-only API-аудитом; violations 0.
2. Только после отдельного разрешения выполнить два независимых validated `MISSING_RETURN`
   recovery с exact expectations. Не запускать широкий backfill или resync.
3. Отдельным bounded script исправить только пять апрельских order positions на интервале
   `[2026-04-01, 2026-05-01)`: `A000317` → `SETUP_SERVICE / PAID_REPAIR`, остальные четыре →
   payroll `PAID_REPAIR`; до apply проверить exact IDs, amounts, cost, source kind и current state.
4. После каждой записи выполнить independent verifier, затем полный месячный audit по
   `STORE`, `SELLERS`, каждому сотруднику, всем categories и attach-rate.
5. Изменение общего classification/sync code можно включить в согласованный batch deploy после
   завершения помесячных сверок; точечные апрельские corrections не должны ждать deploy кода.

## Ответы по состоянию pre-correction

1. **Совпало точно:** все 831 продажи, пять ненулевых order facts, 878 общих документов, 2,063
   общие product groups, payment ledger, четыре незатронутых сотрудника и 14 из 20 analytics
   categories.
2. **Не совпало:** STORE revenue/cost/GP/margin/quantity, два return documents, структура,
   шесть analytics categories, четыре payroll categories, aggregate SELLERS, KPI двух затронутых
   employees и attach-rate; пять paid-repair assignments.
3. **Документы разницы:** финансовая — `F000180`, `F000217`; классификация — `A000307`,
   `A000316`, `A000317`, `A000358`, `A000368`.
4. **Первопричина:** cash-less returns не обнаружены cash-transaction polling path; order work
   semantics не имеет достаточного приоритета над lexical/accessory и generic service payroll
   classification.
5. **Необходимые исправления:** два exact validated missing-return recovery и пять bounded
   classification corrections — только после API evidence и отдельного разрешения.
6. **Повторная синхронизация:** широкий backfill не нужен. После точечных operations требуется
   полный read-only повторный audit.
7. **Можно ли доверять апрелю:** сейчас нет для итогов `STORE`, `SELLERS`, категорий, зарплат и
   attach-rate. Продажам, order finance, payment ledger и exact-matched facts доверять можно.
   Даже после исправлений GP будет иметь оговорку о трёх исходных zero costs.
