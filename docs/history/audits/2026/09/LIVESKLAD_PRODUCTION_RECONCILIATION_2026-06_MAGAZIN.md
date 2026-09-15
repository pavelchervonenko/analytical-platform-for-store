---
doc_schema: 1
doc_type: evidence
status: historical
owner: product
audience:
  - developer
  - operator
snapshot_date: 2026-09-14
verdict: PASS_WITH_LIMITS
verdict_scope: "МАГАЗИН, 2026-06-01..2026-06-30 inclusive, Europe/Kaliningrad; LiveSklad XLSX to production reconciliation after exact bounded classification correction."
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
  - sanitized production read-only SQL snapshot captured on 2026-09-14
  - current and deployed application formulas and classification code
required_reviewers:
  - product
  - operations
---

# Сверка LiveSklad ↔ production: июнь 2026, МАГАЗИН

## Вердикт

`PASS_WITH_LIMITS`. Полнота синхронизации, STORE-финансы, документы, позиции, продажи, возвраты,
выданные заказы, движение денег, структура продаж, аналитические и зарплатные категории после
исправления доказательно совпали. Две работы «Замена заднего стекла IPhone» в заказах `A000418`
и `A000522` переведены из `GLASS_IPHONE / ACCESSORY` в подтверждённые заказчиком
`SETUP_SERVICE / PAID_REPAIR`.

Общая выручка, себестоимость, GP и число единиц не изменились. Ограничения доверия остались только
у исходных данных: девять разрешённых пользователем `ZERO_UNEXPECTED` costs, четыре возврата
товара без денежного возврата и немоделируемые приложением 31,000.00 ₽ оплаты заказов.

## Исправление и повторная сверка

После явного разрешения пользователя применена атомарная exact-target transaction версии
`customer-approved-2026-09-14-paid-repair-june-v1` с интервалом
`[2026-06-01, 2026-07-01)`:

- изменены ровно две item snapshots: `A000418` и `A000522`;
- создано ровно одно bounded analytics assignment и одно bounded payroll assignment для одного
  product ID;
- перед записью подтверждены 2/2 exact manifest rows, отсутствие других июньских facts этого
  товара, overlapping assignments, approved/paid payroll, approved/archived reports и active sync;
- независимый `BEGIN READ ONLY` verifier подтвердил 2 строки / 2 ед. / 11,000.00 ₽ revenue /
  5,000.00 ₽ cost, обе категории, две audit-log записи и неизменность контрольного факта вне
  периода;
- полный повторный месячный audit изменился только в ожидаемых category, payroll, attach-rate,
  employee-category и двух item projections; STORE totals, документы, возвраты и деньги не
  изменились.

## Scope, фильтры и источники

| Параметр | Зафиксированное значение | Доказательство |
|---|---|---|
| Магазин | `МАГАЗИН` | выбор пользователя; единственный production store с точным именем |
| Период | `2026-06-01..2026-06-30`, обе даты включительно | содержимое XLSX и `business_date` SQL |
| Business timezone | `Europe/Kaliningrad`, начало дня `00:00` | production store и текущий period contract |
| «Товары и работы» | магазин `МАГАЗИН`; июнь; `Продажа`, `Возврат`, `Установка в заказ`; orders `Выдан` | effective XLSX content |
| «Продажи» | магазин `МАГАЗИН`; июнь; продажи и sale returns | 1,015 unique document rows |
| «Заказы» | магазин `МАГАЗИН`; дата выдачи в июне; выданные заказы | 6 unique rows, включая один нулевой заказ |

XLSX не сохраняют все экранные фильтры как машиночитаемые metadata. Поэтому таблица разделяет
переданные пользователем параметры и проверяемое effective content.

| Файл | Строк данных | Размер | SHA-256 |
|---|---:|---:|---|
| `Отчёт по товарам и работам (10).xlsx` | 2,650 | 1,147,302 B | `8b18172a23893c07175cce9b0a97543706c90343bc98fc8bbc591e0739ad23f5` |
| `Отчёт по продажам (10).xlsx` | 1,015 | 491,575 B | `3b006cb0217b3387e50434deaa9797374c6842f149d8680337e2c2abd524354f` |
| `Отчёт по заказам (8).xlsx` | 6 | 21,322 B | `3b0ad754cfb6c262d2da790ee57a824498c6e27248a2171b619c68ae35abcfe1` |

## Production и полнота синхронизации

Свежий read-only запуск подтвердил runtime identity из
[`project-state.md`](../../../../current/project-state.md) и healthy-состояние трёх сервисов. Exact
месячный audit принимал ноль аргументов, работал с PostgreSQL read-only guards и завершился
`ROLLBACK`.

Исторических parent job/run строк, пересекающих контрольную точку начала июня, audit не вернул.
Поэтому полнота доказана предметно, а не по старому job status:

- 1,020 ключей `source type + document_number` в XLSX и production; missing/extra — 0;
- 966 direct-sale, 49 sale-return и 5 ненулевых order document numbers совпали;
- production хранит 7 order-position документов для 7 позиций этих пяти заказов;
- 2,639 групп `document + product` совпали по quantity, revenue, cost и GP;
- все 1,022 production document facts имеют `last_sync_status=SUCCESS` и доступную raw version;
- удалённых документов/строк, `UNMAPPED`, `EXCLUDE` и missing cost — 0;
- header totals всех production documents равны active item totals;
- 9 строк `ZERO_UNEXPECTED` присутствуют с теми же нулевыми costs в LiveSklad XLSX.

Это доказывает полное текущее покрытие выбранного периода. Backfill или повторная синхронизация для
устранения обнаруженной classification error не нужны.

## STORE: месячный итог

Знак: `SALE=+1`, `RETURN=-1`. Orders добавлены ровно один раз через семь `orderPosition` facts.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка | 59,901,942.00 | 59,901,942.00 | 0.00 ₽ | 0.00% | PASS | — | 2,639 product groups exact |
| Себестоимость | 49,700,662.65 | 49,700,662.65 | 0.00 ₽ | 0.00% | PASS_WITH_LIMIT | 9 source zero-cost rows | item reconciliation |
| Валовая прибыль | 10,201,279.35 | 10,201,279.35 | 0.00 ₽ | 0.00% | PASS_WITH_LIMIT | зависит от source costs | `R-C` в обеих системах |
| Валовая маржа | 17.03% | 17.03% | 0.00 п. п. | 0.00% | PASS_WITH_LIMIT | то же | единое финальное округление |
| Чистое количество | 2,509 ед. | 2,509 ед. | 0 ед. | 0.00% | PASS | — | signed quantity всех позиций |
| Direct sales | 966 док. / 2,607 ед. / 62,199,420.00 ₽ | то же | 0 | 0.00% | PASS | — | exact document and item totals |
| Sale returns | 49 док. / −105 ед. / −2,328,478.00 ₽ | то же | 0 | 0.00% | PASS | — | все returns active и linked |
| Выданные заказы | 6 док.; 5 ненулевых; 7 ед.; 31,000.00 ₽ | 5 order numbers / 7 position facts / 31,000.00 ₽ | 0 по ненулевым фактам | 0.00% | EXPLAINED | нулевой `A000429` не создаёт position fact | orders + goods + SQL |
| Возвраты заказов | 0 | 0 | 0 | — | PASS | отсутствуют | orders return total + production facts |

## STORE: структура продаж после смысловой проверки

Колонка LiveSklad ниже использует исходные позиции и утверждённую семантику заказчика. XLSX не
содержит category fields; для 2,648 корректно классифицированных строк категория реконструирована
из exact-matched production snapshot. Две work-позиции классифицированы независимо по source type,
названию и orders report.

| Показатель | LiveSklad / ожидается | Приложение после исправления | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Техника | 868 ед. / 53,481,426.00 ₽ / GP 4,641,056.00 ₽ | то же | 0 | 0.00% | PASS | — | category item match |
| Аксессуары | 1,027 ед. / 3,619,808.00 ₽ / GP 2,773,215.35 ₽ | то же | 0 | 0.00% | PASS | — | post-correction audit |
| Услуги | 614 ед. / 2,800,708.00 ₽ / GP 2,787,008.00 ₽ | то же | 0 | 0.00% | PASS | — | post-correction audit |
| Допы | 1,641 ед. / 6,420,516.00 ₽ / GP 5,560,223.35 ₽ | то же | 0 | 0.00% | PASS_WITH_LIMIT | сумма не менялась при переносе accessory → service | recomputation |

## Все аналитические категории STORE

Формат значения: `quantity / revenue / cost / GP`. Разница считается как приложение минус
LiveSklad expectation.

| Категория | LiveSklad / ожидается | Приложение | Разница | Статус |
|---|---:|---:|---:|---|
| `ACCESSORY_IPAD_MAC` | 9 / 34,740.00 / 9,300.00 / 25,440.00 | то же | 0 | PASS |
| `ACCESSORY_PODS_WATCH` | 15 / 35,490.00 / 9,105.10 / 26,384.90 | то же | 0 | PASS_WITH_LIMIT |
| `CASE_APPLE_IPHONE` | 139 / 486,107.00 / 161,095.60 / 325,011.40 | то же | 0 | PASS |
| `CASE_SAMSUNG` | 37 / 159,253.00 / 49,350.00 / 109,903.00 | то же | 0 | PASS |
| `CHARGER_CABLE` | 307 / 1,407,774.00 / 393,947.95 / 1,013,826.05 | то же | 0 | PASS_WITH_LIMIT |
| `FILM_PHONE` | 53 / 85,571.00 / 3,605.00 / 81,966.00 | то же | 0 | PASS |
| `GLASS_CAMERA_IPHONE` | 102 / 299,641.00 / 70,025.00 / 229,616.00 | то же | 0 | PASS |
| `GLASS_CAMERA_SAMSUNG` | 13 / 38,014.00 / 8,400.00 / 29,614.00 | то же | 0 | PASS |
| `GLASS_IPHONE` | 233 / 702,732.00 / 57,219.00 / 645,513.00 | то же | 0 | PASS |
| `GLASS_SAMSUNG` | 30 / 84,000.00 / 7,815.00 / 76,185.00 | то же | 0 | PASS |
| `IPAD_MAC` | 54 / 2,879,560.00 / 2,659,300.00 / 220,260.00 | то же | 0 | PASS |
| `IPHONE_NEW_ASIS` | 393 / 33,900,522.00 / 31,697,270.00 / 2,203,252.00 | то же | 0 | PASS |
| `IPHONE_USED` | 141 / 5,640,826.00 / 4,169,300.00 / 1,471,526.00 | то же | 0 | PASS |
| `OTHER_ACCESSORY_PRODUCT` | 89 / 286,486.00 / 76,730.00 / 209,756.00 | то же | 0 | PASS |
| `PODS_WATCH_OTHER_DEVICE` | 146 / 2,967,700.00 / 2,704,100.00 / 263,600.00 | то же | 0 | PASS |
| `PREMIUM_PROTECTION` | 2 / 28,980.00 / 0.00 / 28,980.00 | то же | 0 | PASS |
| `SAMSUNG_NEW` | 124 / 7,590,678.00 / 7,141,300.00 / 449,378.00 | то же | 0 | PASS |
| `SAMSUNG_USED` | 10 / 502,140.00 / 469,100.00 / 33,040.00 | то же | 0 | PASS |
| `SETUP_SERVICE` | 376 / 1,006,989.00 / 13,700.00 / 993,289.00 | то же | 0 | PASS |
| `WARRANTY_GENERIC` | 236 / 1,764,739.00 / 0.00 / 1,764,739.00 | то же | 0 | PASS |

## Зарплатные категории STORE

Аналитическая и зарплатная проекции не смешивались. Колонка XLSX «Зарплата за продажу» равна
нулю и не является payroll KPI приложения.

| Payroll category | LiveSklad / ожидается | Приложение | Разница | Статус | Причина |
|---|---:|---:|---:|---|---|
| `ACCESSORY` | 1,027 / 3,619,808.00 / cost 846,592.65 / GP 2,773,215.35 | то же | 0 | PASS | — |
| `PAID_REPAIR` | 2 / 11,000.00 / cost 5,000.00 / GP 6,000.00 | то же | 0 | PASS | bounded assignment |
| `SERVICE` | 612 / 2,789,708.00 / cost 8,700.00 / GP 2,781,008.00 | то же | 0 | PASS | paid repair остаётся отдельным фондом |
| `TECH_TIER_1` | 698 / 49,866,416.00 / cost 45,541,570.00 / GP 4,324,846.00 | то же | 0 | PASS | — |
| `TECH_TIER_2` | 170 / 3,615,010.00 / cost 3,298,800.00 / GP 316,210.00 | то же | 0 | PASS | — |

## Attach-rate STORE

Все 14 application values после исправления арифметически совпали с реконструкцией и с
production projection. Исправление изменило только два числителя; знаменатели не изменились.

| Метрика | LiveSklad / ожидается N/B/rate | Приложение N/B/rate | Разница | Статус | Причина |
|---|---:|---:|---:|---|---|
| `ACCESSORY_IPAD` | 9 / 26 / 34.62% | то же | 0 | PASS | — |
| `ACCESSORY_PODS_WATCH` | 15 / 109 / 13.76% | то же | 0 | PASS | — |
| `CASE_APPLE_IPHONE` | 139 / 534 / 26.03% | то же | 0 | PASS | — |
| `CASE_SAMSUNG` | 37 / 134 / 27.61% | то же | 0 | PASS | — |
| `CHARGER_CABLE` | 307 / 668 / 45.96% | то же | 0 | PASS | — |
| `FILM_PHONE` | 53 / 668 / 7.93% | то же | 0 | PASS | — |
| `GLASS_CAMERA_IPHONE` | 102 / 534 / 19.10% | то же | 0 | PASS | — |
| `GLASS_CAMERA_SAMSUNG` | 13 / 134 / 9.70% | то же | 0 | PASS | — |
| `GLASS_IPHONE` | 233 / 534 / 43.63% | то же | 0 | PASS | — |
| `GLASS_SAMSUNG` | 30 / 134 / 22.39% | то же | 0 | PASS | — |
| `PREMIUM_PROTECTION` | 10 / 848 / 1.18% | то же | 0 | PASS | — |
| `SETUP_SERVICE` | 376 / 696 / 54.02% | то же | 0 | PASS | — |
| `WARRANTY_GENERIC_NEW` | 152 / 517 / 29.40% | то же | 0 | PASS | — |
| `WARRANTY_GENERIC_USED` | 76 / 151 / 50.33% | то же | 0 | PASS | — |

## SELLERS и сотрудники

Ranking cohort содержит пять сотрудников; `EMP-B802A9C3FDD3` активен, но не участвует в
рейтинге. STORE и SELLERS не смешивались. App использует сотрудника исходной продажи для возврата,
XLSX — сотрудника return document.

| Показатель SELLERS | LiveSklad report employee | Приложение original-sale employee | Разница | Разница, % | Статус | Причина |
|---|---:|---:|---:|---:|---|---|
| Вся выручка | 59,431,352.00 | 59,556,342.00 | +124,990.00 | +0.21% | EXPLAINED | `F000268`, `F000295` перешли к non-ranking original seller |
| Себестоимость | 49,255,562.65 | 49,383,382.65 | +127,820.00 | +0.26% | EXPLAINED | те же returns |
| GP | 10,175,789.35 | 10,172,959.35 | −2,830.00 | −0.03% | EXPLAINED | те же returns |
| Аксессуары после semantic correction | 1,027 / 3,619,808.00 / GP 2,773,215.35 | 1,028 / 3,623,308.00 / GP 2,775,795.35 | +1 / +3,500.00 / +2,580.00 | +0.10% revenue | EXPLAINED | `F000295` |
| Техника | 860 / 53,010,836.00 / GP 4,615,566.00 | 861 / 53,132,326.00 / GP 4,610,156.00 | +1 / +121,490.00 / −5,410.00 | +0.23% revenue | EXPLAINED | `F000268` |
| Услуги после semantic correction | 614 / 2,800,708.00 / GP 2,787,008.00 | то же | 0 | 0.00% | PASS | обе work-позиции ranking |

### Итоги каждого сотрудника

Разница — application минус XLSX report employee. Все шесть ненулевых totals полностью
раскладываются на десять return documents ниже; unresolved employee difference отсутствует.

| Сотрудник | Ranking | LiveSklad: qty / revenue / cost / GP | App: qty / revenue / cost / GP | Разница qty / revenue / cost / GP | Revenue delta, % | Статус |
|---|---|---:|---:|---:|---:|---|
| `EMP-789FAC07DBB9` | да | 518 / 11,943,570.00 / 10,032,534.35 / 1,911,035.65 | 520 / 11,953,950.00 / 10,039,430.75 / 1,914,519.25 | +2 / +10,380.00 / +6,896.40 / +3,483.60 | +0.09% | EXPLAINED |
| `EMP-B802A9C3FDD3` | нет | 8 / 470,590.00 / 445,100.00 / 25,490.00 | 6 / 345,600.00 / 317,280.00 / 28,320.00 | −2 / −124,990.00 / −127,820.00 / +2,830.00 | −26.56% | EXPLAINED |
| `EMP-C92D2C99CC54` | да | 429 / 13,559,977.00 / 12,062,119.15 / 1,497,857.85 | 437 / 13,749,387.00 / 12,187,212.75 / 1,562,174.25 | +8 / +189,410.00 / +125,093.60 / +64,316.40 | +1.40% | EXPLAINED |
| `EMP-DD4F314C0A50` | да | 666 / 11,544,034.00 / 8,472,940.25 / 3,071,093.75 | 666 / 11,508,954.00 / 8,444,890.25 / 3,064,063.75 | 0 / −35,080.00 / −28,050.00 / −7,030.00 | −0.30% | EXPLAINED |
| `EMP-EBC62AF18E20` | да | 370 / 10,074,940.00 / 8,485,664.15 / 1,589,275.85 | 372 / 10,199,930.00 / 8,613,484.15 / 1,586,445.85 | +2 / +124,990.00 / +127,820.00 / −2,830.00 | +1.24% | EXPLAINED |
| `EMP-EE11A56D7AF0` | да | 518 / 12,308,831.00 / 10,202,304.75 / 2,106,526.25 | 508 / 12,144,121.00 / 10,098,364.75 / 2,045,756.25 | −10 / −164,710.00 / −103,940.00 / −60,770.00 | −1.34% | EXPLAINED |

### Атрибуция возвратов

Все 49 returns имеют найденную, активную original sale в том же магазине; app employee равен
original-sale employee. У 39 XLSX employee совпадает. Десять ожидаемых ADR-0001 отличий:

| Return | Return external_id | XLSX employee | App/original employee | Original sale | Original external_id | Signed qty / revenue / cost / GP | Статус |
|---|---|---|---|---|---|---:|---|
| `F000263` | `6a1f02f91d19e2a4c75869d3` | `EMP-C92D2C99CC54` | `EMP-789FAC07DBB9` | `B005313` | `6a1f01f01d19e26d5d585782` | −1 / −500.00 / 0.00 / −500.00 | EXPLAINED |
| `F000266` | `6a23ebdf4e292b57925e75f7` | `EMP-C92D2C99CC54` | `EMP-DD4F314C0A50` | `B005351` | `6a2156b87f2ba369d928bd81` | −1 / −49,990.00 / −41,500.00 / −8,490.00 | EXPLAINED |
| `F000268` | `6a26a1c34e292b6edc80605a` | `EMP-EBC62AF18E20` | `EMP-B802A9C3FDD3` | `B004013` | `69dcf48668fd2c30838ab425` | −1 / −121,490.00 / −126,900.00 / +5,410.00 | EXPLAINED |
| `F000269` | `6a29379b6c85c87018ebda19` | `EMP-EE11A56D7AF0` | `EMP-C92D2C99CC54` | `B005251` | `6a1c57c7ce47a26e132d6894` | −1 / −5,990.00 / −2,100.00 / −3,890.00 | EXPLAINED |
| `F000283` | `6a2ff3e37e0c5dd76386366a` | `EMP-789FAC07DBB9` | `EMP-DD4F314C0A50` | `B005678` | `6a2e93e57e0c5d64e36ed5f2` | −1 / −1,490.00 / −50.00 / −1,440.00 | EXPLAINED |
| `F000287` | `6a32e2926883c96afa7eeddb` | `EMP-789FAC07DBB9` | `EMP-C92D2C99CC54` | `B005817` | `6a32e1d16883c96ea97ee9cc` | −1 / −6,400.00 / −5,600.00 / −800.00 | EXPLAINED |
| `F000291` | `6a3599e171f54c594f5b93a6` | `EMP-C92D2C99CC54` | `EMP-EE11A56D7AF0` | `B005882` | `6a35879371f54c78465b48ab` | −9 / −154,300.00 / −92,540.00 / −61,760.00 | EXPLAINED |
| `F000295` | `6a3ad074380cb68615b004b3` | `EMP-EBC62AF18E20` | `EMP-B802A9C3FDD3` | `B004230` | `69e6253a2a67e6cbd8212656` | −1 / −3,500.00 / −920.00 / −2,580.00 | EXPLAINED |
| `F000296` | `6a3bdb7b7151c744c4562569` | `EMP-789FAC07DBB9` | `EMP-C92D2C99CC54` | `B006020` | `6a3bdad8380cb6410ac202c7` | −1 / −2,990.00 / −1,246.40 / −1,743.60 | EXPLAINED |
| `F000307` | `6a42864d380cb643cc285d07` | `EMP-DD4F314C0A50` | `EMP-EE11A56D7AF0` | `B006188` | `6a4285687151c7b813bbca62` | −2 / −16,400.00 / −13,500.00 / −2,900.00 | EXPLAINED |

Employee-category reconciliation содержит 99 пар: 72 нулевые, 27 ненулевых. Все 27 ненулевых
дельт являются арифметическим разложением этих десяти returns по их категориям. Payroll employee
projection содержит 23 пары: 5 нулевых и 18 объяснённых теми же returns. Никаких дополнительных
документов в этих дельтах нет.

### Attach-rate SELLERS

Все 84 комбинации `audience + employee + metric` совпали с production projection. При сравнении
с XLSX employee 47 комбинаций имеют нулевую дельту, 37 объясняются только десятью ADR-0001
переносами выше. Classification correction двух order works одинаково изменила employee rows в
сверяемых проекциях и не создала новой межсистемной дельты:

- `EMP-C92D2C99CC54`: `GLASS_IPHONE` −1 numerator, `SETUP_SERVICE` +1 numerator;
- `EMP-EE11A56D7AF0`: `GLASS_IPHONE` −1 numerator, `SETUP_SERVICE` +1 numerator.

Максимальные текущие объяснённые rate deltas: `EMP-EE11A56D7AF0 / WARRANTY_GENERIC_USED`
−4.35 п. п., `EMP-DD4F314C0A50 / WARRANTY_GENERIC_USED` +3.67 п. п.,
`EMP-C92D2C99CC54 / SETUP_SERVICE` +1.54 п. п. и
`EMP-EE11A56D7AF0 / SETUP_SERVICE` −1.80 п. п. Каждая определяется позициями `F000291` или
другими перечисленными return documents, а не sync gap.

## Продажи, возвраты и движение денег

Продажа товара и движение денег проверены отдельно.

| Показатель | LiveSklad | Приложение | Разница | Статус | Причина / доказательство |
|---|---:|---:|---:|---|---|
| Merchandise direct sales minus sale returns | 59,870,942.00 | 59,870,942.00 | 0.00 | PASS | sales XLSX и signed items |
| Sales XLSX `Оплачено` | 60,117,938.00 | signed `sales_payments` 60,117,938.00 | 0.00 | PASS | payment facts exact |
| Sales XLSX `Возврат` | 0.00 | — | — | EXPLAINED | колонка не равна merchandise returns |
| Orders `Оплачено` | 31,000.00 | 0.00 в `sales_payments` | −31,000.00 | NOT_MODELLED | order-position sync хранит товары, не order cash |

Все 49 merchandise returns присутствуют, хотя XLSX-колонка `Возврат` равна нулю. У 45 signed
`Оплачено` уже отражает отрицательное движение. Четыре документа имеют merchandise return при
нулевой оплате; обе системы одинаково показывают эту source-семантику:

| Документ | Merchandise | Paid / returned | Cash minus merchandise | Статус |
|---|---:|---:|---:|---|
| `F000280` | −114,300.00 | 0.00 / 0.00 | +114,300.00 | EXPLAINED_SOURCE |
| `F000308` | −71,500.00 | 0.00 / 0.00 | +71,500.00 | EXPLAINED_SOURCE |
| `F000300` | −35,300.00 | 0.00 / 0.00 | +35,300.00 | EXPLAINED_SOURCE |
| `F000265` | −25,896.00 | 0.00 / 0.00 | +25,896.00 | EXPLAINED_SOURCE |
| **Итого** | **−246,996.00** | **0.00 / 0.00** | **+246,996.00** | **EXPLAINED_SOURCE** |

Это не пропущенные возвраты приложения и не основание менять revenue. При необходимости source
cash event проверяется отдельно в LiveSklad, но financial facts двух систем уже согласованы.

## Заказы, документы и позиции

| Заказ | Positions | Amount | Cost | GP | Статус |
|---|---:|---:|---:|---:|---|
| `A000418` | 2 | 8,000.00 | 0.00 | 8,000.00 | MATCH; classification corrected |
| `A000429` | 0 | 0.00 | 0.00 | 0.00 | EXPLAINED_ZERO_ORDER |
| `A000466` | 2 | 7,000.00 | 3,200.00 | 3,800.00 | MATCH |
| `A000467` | 1 | 3,500.00 | 2,000.00 | 1,500.00 | MATCH |
| `A000470` | 1 | 4,500.00 | 2,500.00 | 2,000.00 | MATCH |
| `A000522` | 1 | 8,000.00 | 5,000.00 | 3,000.00 | MATCH; classification corrected |

2,650 XLSX rows и 2,645 app item rows дают одинаковые 2,639 product groups. Пять лишних XLSX
row occurrences — допустимая агрегация одинаковых строк:

| Документ | Товар | XLSX rows → app rows | Quantity | Amount | Статус |
|---|---|---:|---:|---:|---|
| `B005631` | `Apple iPad 11 128Gb WI-FI Silver` | 3 → 1 | 3 | 84,000.00 | EXPLAINED_AGGREGATION |
| `B005700` | `iPhone 17 Pro Max 256Gb Silver J/A e-sim New` | 2 → 1 | 2 | 183,800.00 | EXPLAINED_AGGREGATION |
| `B006024` | `CЗУ Ugreen X513 Type-C 30W белый` | 2 → 1 | 2 | 9,980.00 | EXPLAINED_AGGREGATION |
| `B006146` | `iPhone 17 Pro Max 256Gb Silver J/A e-sim New` | 2 → 1 | 2 | 195,800.00 | EXPLAINED_AGGREGATION |

Пять product names изменились после продажи, но snapshot и текущий XLSX однозначно сопоставлены
по document, quantity, revenue, cost и GP: `B005390`, `B005840`, `B006098`, `B006102`, `B006219`.
Ambiguous match — 0; финансовый эффект — 0.

Все 1,020 XLSX document timestamps на час позже Kaliningrad local time production: 1,011 offsets
равны 59 минутам из-за обрезанных секунд и 9 — 60 минутам. Exact document sets и июньские
`business_date` совпали; границы периода не затронуты.

## Доказанная и исправленная classification error

| Документ | Document external_id | Item external_id | Product external_id | Позиция | Qty | Revenue | Cost | App до | App после / ожидается |
|---|---|---|---|---|---:|---:|---:|---|---|
| `A000418` | `order:6a0f49726c1fce34883376ca:position:6a1e96e56345ff32762b8ba4` | `6a1e96e56345ff32762b8ba4` | `691f300cd9d5829537762c4c` | `Замена заднего стекла IPhone` | 1 | 3,000.00 | 0.00 | `GLASS_IPHONE / ACCESSORY` | `SETUP_SERVICE / PAID_REPAIR` |
| `A000522` | `order:6a3d05b47151c7c22c67ea6d:position:6a3e6bc37151c7dd5481dc18` | `6a3e6bc37151c7dd5481dc18` | `691f300cd9d5829537762c4c` | `Замена заднего стекла IPhone` | 1 | 8,000.00 | 5,000.00 | `GLASS_IPHONE / ACCESSORY` | `SETUP_SERVICE / PAID_REPAIR` |

Обе строки имеют `is_work=true`, source document type `orderPosition`, order report относит всю
сумму в работу и 0.00 ₽ в запчасти. До исправления production snapshot version была
`livesklad-product-rules-v6:iphone-glass`: в deployed rule v6 lexical `стекл` проверяется в
accessory branch до fallback `sourceKind=SERVICE`; это полностью воспроизводит первопричину.
После исправления обе строки имеют bounded classification version
`customer-approved-2026-09-14-paid-repair-june-v1`. Локально подготовленный candidate v8 меняет
порядок для будущей синхронизации, но по договорённости ещё не развёрнут.

## ZERO_UNEXPECTED

Нулевая себестоимость допустима по решению пользователя, однако quality marker ограничивает
доверие к GP. Девять app facts точно повторяют нули исходного XLSX:

| Документ | Позиция | Revenue | Категория | Примечание |
|---|---|---:|---|---|
| `B005313` | `Кабель Usb-c (no box)` | 500.00 | `CHARGER_CABLE` | исходный zero cost |
| `B005315` | `Кабель Usb-c (no box)` | 500.00 | `CHARGER_CABLE` | исходный zero cost |
| `B005329` | `Кабель` | 1,000.00 | `CHARGER_CABLE` | исходный zero cost |
| `B005357` | `Кабель` | 1,500.00 | `CHARGER_CABLE` | исходный zero cost |
| `B005584` | `Кабель` | 1,500.00 | `CHARGER_CABLE` | исходный zero cost |
| `B005684` | `Кабель` | 1,670.00 | `CHARGER_CABLE` | исходный zero cost |
| `B006109` | `Ремешок миланская петля 38-41mm` | 1,500.00 | `ACCESSORY_PODS_WATCH` | исходный zero cost |
| `B006147` | `Кабель Usb-c (no box)` | 1,490.00 | `CHARGER_CABLE` | исходный zero cost |
| `F000263` | возврат `Кабель Usb-c (no box)` | −500.00 | `CHARGER_CABLE` | возврат исходной zero-cost продажи |

## Классификация причин

| Класс причины | Итог | Доказательство |
|---|---|---|
| Неполное покрытие синхронизации | не обнаружено | exact document/product sets; successful last sync; raw versions present |
| Пропущенный или скрытый возврат | не обнаружено | 49/49 returns; 0 deleted; original links complete |
| Неверная классификация | обнаружена и исправлена | две work-позиции `A000418`, `A000522`; independent verify PASS |
| Неправильный магазин или сотрудник | магазин — нет; 10 employee differences ожидаемы | same-store links; ADR-0001 decomposition |
| Отличие формул | order cash only | `sales_payments` не моделирует 31,000.00 ₽ order payments |
| Отличие границ периода | effect 0 | exact document set; one-hour presentation offset |
| Дубликат или удаление | не обнаружено | 4 row aggregations, но quantity/value exact; deleted 0 |
| Ошибка исходных данных LiveSklad | 9 allowed zero costs; 4 zero-payment returns | одинаково сохранены приложением |
| Ошибка приложения | исправлена для июня; общий rule fix ожидает деплоя | rule v6 precedence; bounded correction verified |

## Безопасное исправление и финальные ответы

1. **Что совпало точно.** После исправления совпали STORE revenue/cost/GP/margin/quantity; 1,020
   document keys; 2,639 product groups; 966 sales; 49 sale returns; 7 ненулевых order positions;
   cash facts sales report; все 20 analytics categories и все payroll/attach projections с учётом
   утверждённой семантики.
2. **Что не совпало.** Необъяснённых различий не осталось. SELLERS employee differences вызваны
   ADR-0001, order cash не моделируется, source zero costs и zero-payment returns сохранены как
   ограничения, а не ошибки сверки.
3. **Документы исходной разницы.** `A000418` — 3,000.00 ₽ / cost 0.00 ₽; `A000522` — 8,000.00 ₽ /
   cost 5,000.00 ₽. Обе позиции исправлены; иных classification mismatch не найдено.
4. **Первопричина.** В deployed rule v6 lexical glass rule имеет приоритет над подтверждённым
   `sourceKind=SERVICE`.
5. **Исправление.** Выполнена exact-target transaction только для двух item snapshots и одного
   product ID, с analytics `SETUP_SERVICE` и payroll `PAID_REPAIR`, effective interval
   `[2026-06-01, 2026-07-01)`. Все pre-apply guards и независимый post-commit verifier прошли.
6. **Повторная синхронизация.** Не требуется. Sync facts полны; bounded classification fix уже
   выполнен без resync.
7. **Можно ли доверять месяцу.** Да: финансовым STORE totals, документам, позициям, структуре,
   `GLASS_IPHONE`, `SETUP_SERVICE`, attach-rate и payroll categories. Ограничения: девять source
   zero costs, четыре zero-payment returns, немоделируемый order cash и ожидаемая ADR-0001
   original-sale attribution в SELLERS.

Backfill, повторная синхронизация и code deployment не выполнялись. Единственное production
изменение — описанная выше bounded exact-target classification correction; затем выполнены
независимый verifier и полный повторный read-only audit.
