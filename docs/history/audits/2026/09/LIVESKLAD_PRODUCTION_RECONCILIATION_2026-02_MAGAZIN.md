---
doc_schema: 1
doc_type: evidence
status: historical
owner: product
audience:
  - developer
  - operator
snapshot_date: 2026-09-15
verdict: FAIL_ACTION_REQUIRED
verdict_scope: "МАГАЗИН, 2026-02-01..2026-02-28 inclusive, Europe/Kaliningrad; five cashless returns are absent, six loaded returns have lost original-sale attribution, and two paid repairs are assigned to payroll SERVICE."
source_of_truth:
  - docs/current/product/business-metrics.md
  - docs/current/product/sales-and-returns.md
  - docs/current/product/classification.md
  - docs/current/product/payroll.md
  - docs/current/product/attach-rate.md
  - docs/current/product/employees-and-rating.md
  - docs/current/product/periods.md
  - docs/decisions/ADR-0001-return-employee-attribution.md
verification_sources:
  - three user-supplied LiveSklad XLSX reports identified by SHA-256 in this record
  - sanitized production read-only SQL snapshot captured on 2026-09-15
  - targeted read-only LiveSklad API verification of eleven returns and their original sales
  - exact document, item, employee, category, payroll and attach-rate reconstruction
  - current application formulas, classification rules and recovery guards
required_reviewers:
  - product
  - operations
---

# Сверка LiveSklad ↔ production: февраль 2026, МАГАЗИН

## Текущий вердикт

`FAIL_ACTION_REQUIRED`. Production завышает февральский `STORE` на 8 единиц, 142,470.00 ₽
чистой выручки, 111,700.00 ₽ себестоимости и 30,770.00 ₽ валовой прибыли. Разница полностью
разложена на пять отсутствующих возвратов: `F000106`, `F000117`, `F000118`, `F000121`,
`F000127`. Во всех пяти прямой LiveSklad API подтвердил отсутствие кассовой транзакции. Это
доказывает дефект покрытия cash-transaction discovery, а не отличие исходного отчёта.

Ещё шесть уже загруженных возвратов — `F000102`, `F000103`, `F000104`, `F000108`, `F000109`,
`F000122` — правильно входят в `STORE`, но имеют null original document/item links и ошибочно
атрибутированы `UNASSIGNED`. Прямой API установил исходную продажу, исходную позицию и сотрудника
для всех 11 позиций без нарушений. Поэтому employee totals и `SELLERS` сейчас неверны, хотя эти
шесть документов не создают дельту магазина.

Отдельная ошибка — payroll-классификация двух выданных работ: `A000201` и `A000210` должны быть
`PAID_REPAIR`, а не `SERVICE`. Их аналитическая категория `SETUP_SERVICE` уже верна. Работа
`A000213` («Перепрошивка») правильно остаётся `SETUP_SERVICE / SERVICE`.

Никакие исправления февраля не выполнялись. Таблицы «после исправления» ниже являются только
детерминированной локальной симуляцией, не post-commit доказательством. Production остаётся на
release `v0.1.0-pilot.31` и schema `48`; локально накопленные V49/V51 и recovery code не
развёрнуты.

## Scope, фильтры и источники

| Параметр | Зафиксированное значение | Доказательство |
|---|---|---|
| Магазин | `МАГАЗИН` | выбор пользователя и содержимое всех файлов |
| Период | `2026-02-01..2026-02-28`, обе даты включительно | XLSX и production `business_date` |
| Business timezone | `Europe/Kaliningrad`, начало дня `00:00` | production store и period contract |
| «Товары и работы» | магазин `МАГАЗИН`; февраль; `Продажа`, `Возврат`, `Установка в заказ`; orders `Выдан` | 1,974 detail rows |
| «Продажи» | магазин `МАГАЗИН`; февраль; продажи и возвраты продаж | 793 document rows + total |
| «Заказы» | магазин `МАГАЗИН`; дата выдачи в феврале; статус `Выдан` | 6 orders + total |

Полные UI-параметры LiveSklad не встроены в XLSX как metadata. Поэтому зафиксированы фильтры,
сообщённые пользователем, а фактический scope проверен по всем датам, типам, статусам, магазинам и
document numbers внутри файлов.

| Файл | Полезные строки | Размер | SHA-256 |
|---|---:|---:|---|
| `Отчёт по товарам и работам февраль.xlsx` | 1,974 + total | 851,958 B | `6f33860ba8f1485726b1c7d5922c70ab32552e922fb10501f5fa1b9948d821d4` |
| `Отчёт по продажам февраль.xlsx` | 793 + total | 380,262 B | `2af74c1906956fdfdae2dfc771205b408521d1f47e378622e0d762aa19904815` |
| `Отчёт по заказам февраль.xlsx` | 6 + total | 21,149 B | `930476ae85f5d945b79df91985f3d4f7da6ac39f6d354b0b250a2912feba235a` |

Свежий production audit выполнен через уже существующие SSH key, agent socket и Windows TCP
proxy; новые ключи не создавались. SQL использовал read-only guards и завершился `ROLLBACK`.
Секреты, полные provider payloads и персональные данные в документ не включены.

## Семантика сравнения

- знак факта: `SALE=+1`, `RETURN=-1`; GP = revenue − cost;
- `STORE` включает все факты магазина, `SELLERS` — только рейтинговых сотрудников;
- возврат относится сотруднику исходной продажи, не сотруднику, оформившему возврат;
- аналитическая и зарплатная классификация считаются отдельно;
- аксессуары и услуги классифицированы по подтверждённой заказчиком смысловой семантике;
- платный ремонт: analytics `SETUP_SERVICE`, payroll `PAID_REPAIR`;
- attach-rate использует чистые item units, с отдельными числителем и знаменателем;
- товарный возврат, возврат денег и движение денег не смешиваются;
- issued order count и число его ненулевых работ — разные показатели;
- нулевая себестоимость допустима по решению пользователя, но остаётся quality limitation.

## Полнота синхронизации

Полнота установлена по exact sets и values, а не по статусу последней job:

- production backfill завершён `SUCCESS`: 377 steps, 25 retries;
- sales: 753/753 документов, 1,923/1,923 units, 50,308,764.71 ₽ revenue и
  42,826,970.00 ₽ cost — exact;
- returns: LiveSklad 40 документов / 77 raw rows / 78 units; production 35 документов / 70 units;
- missing returns — ровно пять указанных выше; only-app documents — 0;
- issued orders: 6, из них 3 нулевых (`A000174`, `A000178`, `A000218`) и 3 с работой; все три
  ненулевых order facts присутствуют и совпадают;
- у всех 791 общих documents и всех общих product groups quantity, revenue и cost совпадают;
  value mismatches — 0;
- deleted documents/items, missing cost, `UNMAPPED`, `EXCLUDE` и ambiguous rename — 0;
- из 35 загруженных returns 29 связаны с исходной продажей; шесть orphan returns не связаны;
- ожидаемая return attribution coverage — 40/40, текущая — 29/40.

История jobs сама по себе не доказывает полноту. Текущий код сначала получает тип кассовой статьи
`saleReturn`, затем обнаруживает документы только по cash-register transactions и лишь после этого
запрашивает detail. У пяти отсутствующих возвратов API подтвердил `transactionCount=0`, поэтому
обычный backfill их не обнаруживает.

## STORE: итог месяца

Во всех таблицах разница равна `Приложение − LiveSklad`.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка | 48,453,946.50 ₽ | 48,596,416.50 ₽ | +142,470.00 ₽ | +0.29% | FAIL | 5 missing returns | 8 exact positions |
| Себестоимость | 41,326,456.00 ₽ | 41,438,156.00 ₽ | +111,700.00 ₽ | +0.27% | FAIL | те же returns | XLSX + API + SQL |
| Валовая прибыль | 7,127,490.50 ₽ | 7,158,260.50 ₽ | +30,770.00 ₽ | +0.43% | FAIL | те же returns | revenue − cost |
| Валовая маржа | 14.7098% | 14.7300% | +0.0202 п. п. | +0.14% | FAIL | завышены revenue и GP | единое финальное округление |
| Чистое количество | 1,848 ед. | 1,856 ед. | +8 ед. | +0.43% | FAIL | отсутствуют 8 return units | item reconciliation |
| Продажи | 753 док. / 1,923 ед. / 50,308,764.71 ₽ | то же | 0 | 0.00% | PASS | — | 753 exact documents |
| Возвраты продаж | 40 док. / −78 ед. / −1,862,318.21 ₽ | 35 док. / −70 ед. / −1,719,848.21 ₽ | −5 док. / +8 ед. / +142,470.00 ₽ | +7.65% по абсолютной revenue | FAIL | 5 cashless returns | reports + API + SQL |
| Возвраты заказов | 0 | 0 | 0 | — | PASS | отсутствуют | orders + production |
| Выданные заказы | 6 orders; 3 nonzero works / 3 ед. / 7,500.00 ₽ | 3 persisted work facts / 3 ед. / 7,500.00 ₽ | 3 zero orders не создают facts; value 0 | 0.00% | PASS | разные уровни подсчёта | exact order decomposition |
| Аксессуары | 888 ед. / 2,222,700.00 ₽ | 892 ед. / 2,222,700.00 ₽ | +4 ед. / 0.00 ₽ | 0.00% revenue | FAIL | четыре missing zero-revenue case positions | category reconstruction |
| Услуги | 308 ед. / 1,655,564.50 ₽ | 309 ед. / 1,658,564.50 ₽ | +1 ед. / +3,000.00 ₽ | +0.18% | FAIL | warranty `F000127` отсутствует | category reconstruction |
| Допы (аксессуары + услуги) | 1,196 ед. / 3,878,264.50 ₽ | 1,201 ед. / 3,881,264.50 ₽ | +5 ед. / +3,000.00 ₽ | +0.08% | FAIL | missing returns | semantic reconstruction |

### Деньги отдельно от товара

`Оплачено` в отчёте продаж даёт net cash movement 48,588,916.50 ₽; production signed payments
равны 48,588,916.50 ₽ точно. Колонка `Возвращено` во всех 40 строках возвратов равна 0 и не может
считаться признаком отсутствия товарного возврата. Разница cash − merchandise 142,470.00 ₽ состоит
только из `F000106` 40,490.00 ₽, `F000121` 71,990.00 ₽ и `F000127` 29,990.00 ₽. `F000117` и
`F000118` тоже не имеют cash transaction, но их merchandise revenue равна нулю. Исправление
payment ledger не требуется.

## Пять отсутствующих возвратов: каждая позиция

Значения ниже показаны со знаком возврата. Source API подтвердил exact return position ID,
original position ID и product ID для каждой строки; violations = 0.

| Return / parent | Return position | Категория analytics / payroll | Qty | Revenue | Cost | GP | Production | Статус |
|---|---|---|---:|---:|---:|---:|---|---|
| `F000106` / `B000498` | `6980f58a4785e2de7525fc55` | `IPHONE_USED / TECH_TIER_1` | −1 | −40,490 | −30,000 | −10,490 | отсутствует | FAIL |
| `F000117` / `B002331` | `698eecf8475ae098d4ba3470` | `CASE_APPLE_IPHONE / ACCESSORY` | −1 | 0 | −50 | +50 | отсутствует | FAIL |
| `F000118` / `B002346` | `698f3fcbb8859871ce98cc6c` | `CASE_APPLE_IPHONE / ACCESSORY` | −1 | 0 | −50 | +50 | отсутствует | FAIL |
| `F000121` / `B002215` | `699193374bec11b7291ce7b2` | `IPHONE_NEW_ASIS / TECH_TIER_1` | −1 | −71,990 | −67,500 | −4,490 | отсутствует | FAIL |
| `F000121` / `B002215` | `699193374bec112af91ce7b3` | `CASE_APPLE_IPHONE / ACCESSORY` | −1 | 0 | −50 | +50 | отсутствует | FAIL |
| `F000127` / `B002478` | `69989e63b3671dfbc3d8a206` | `IPHONE_USED / TECH_TIER_1` | −1 | −26,990 | −14,000 | −12,990 | отсутствует | FAIL |
| `F000127` / `B002478` | `69989e63b3671d4d6ad8a207` | `WARRANTY_GENERIC / SERVICE` | −1 | −3,000 | 0 | −3,000 | отсутствует | FAIL |
| `F000127` / `B002478` | `69989e63b3671dcc7ad8a208` | `CASE_APPLE_IPHONE / ACCESSORY` | −1 | 0 | −50 | +50 | отсутствует | FAIL |
| **Итого** | 8 positions | — | **−8** | **−142,470** | **−111,700** | **−30,770** | — | FAIL |

Exact return external IDs, предоставленные пользователем, совпали с API: `F000106`
`6980f58a4785e2f29325fc56`, `F000117` `698eecf8475ae062ceba3471`, `F000118`
`698f3fcbb8859858a698cc6d`, `F000121` `699193374bec113bf41ce7b5`, `F000127`
`69989e63b3671d4480d8a209`. Все пять parent numbers пользователя подтверждены, а source parent
external IDs и positions существуют. У каждого документа cash transaction count = 0.

## Шесть существующих orphan returns

Эти документы уже учтены в STORE с верными значениями. Ошибка ограничена original links,
employee/SELLERS и разрезами по сотруднику. API → production position matching: 11/11 exact.

| Return | Parent sale | Original employee | Positions | Qty | Revenue | Cost | GP | Production сейчас |
|---|---|---|---:|---:|---:|---:|---:|---|
| `F000102` | `B002049` | `EMP-B802A9C3FDD3` | 6 | −6 | −73,660 | −44,625 | −29,035 | `UNASSIGNED`, links null |
| `F000103` | `B002019` | `EMP-B802A9C3FDD3` | 1 | −1 | −2,000 | −50 | −1,950 | `UNASSIGNED`, links null |
| `F000104` | `B002019` | `EMP-B802A9C3FDD3` | 1 | −1 | −1,000 | −100 | −900 | `UNASSIGNED`, links null |
| `F000108` | `B001664` | `EMP-B802A9C3FDD3` | 1 | −1 | −3,500 | −850 | −2,650 | `UNASSIGNED`, links null |
| `F000109` | `B001983` | `EMP-B802A9C3FDD3` | 1 | −1 | −3,000 | −1,000 | −2,000 | `UNASSIGNED`, links null |
| `F000122` | `B000795` | `EMP-B802A9C3FDD3` | 1 | −1 | −3,490 | −900 | −2,590 | `UNASSIGNED`, links null |
| **Итого** | 5 sales | нерейтинговый | **11** | **−11** | **−86,650** | **−47,525** | **−39,125** | FAIL |

`F000102` состоит из used iPhone, warranty, cable, zero-net case, data-transfer service и glass;
остальные orphan returns содержат по одной позиции. Для каждой из 11 позиций source API вернул
exact original `salePositionId`, совпадающий product ID и immutable quantity/revenue/cost;
production содержит тот же return item, но `original_item_external_id` равен null. Source API
показал одну кассовую транзакцию у каждого orphan return, что объясняет, почему discovery нашёл их.

| Return | Return position | Analytics / payroll | Qty | Revenue | Cost | Ошибка |
|---|---|---|---:|---:|---:|---|
| `F000102` | `697f55d521d7c6038cbc30fe` | `IPHONE_USED / TECH_TIER_1` | −1 | −57,990 | −44,000 | employee/link |
| `F000102` | `697f55d521d7c61e1bbc3100` | `WARRANTY_GENERIC / SERVICE` | −1 | −5,700 | 0 | employee/link |
| `F000102` | `697f55d521d7c64fd5bc3101` | `CHARGER_CABLE / ACCESSORY` | −1 | −1,990 | −400 | employee/link |
| `F000102` | `697f55d521d7c6a052bc3102` | `CASE_APPLE_IPHONE / ACCESSORY` | −1 | 0 | −50 | employee/link |
| `F000102` | `697f55d521d7c6a5b4bc3103` | `SETUP_SERVICE / SERVICE` | −1 | −4,990 | 0 | employee/link |
| `F000102` | `697f55d521d7c6cb99bc30ff` | `GLASS_IPHONE / ACCESSORY` | −1 | −2,990 | −175 | employee/link |
| `F000103` | `697f7de44785e253810ebdab` | `FILM_PHONE / ACCESSORY` | −1 | −2,000 | −50 | employee/link |
| `F000104` | `697f947b4785e22ad50f7d0e` | `ACCESSORY_PODS_WATCH / ACCESSORY` | −1 | −1,000 | −100 | employee/link |
| `F000108` | `69830aa394614482281472a4` | `OTHER_ACCESSORY_PRODUCT / ACCESSORY` | −1 | −3,500 | −850 | employee/link |
| `F000109` | `698330d1806311cb7dde8af3` | `ACCESSORY_PODS_WATCH / ACCESSORY` | −1 | −3,000 | −1,000 | employee/link |
| `F000122` | `699195c04bec11c2df1d24c9` | `CHARGER_CABLE / ACCESSORY` | −1 | −3,490 | −900 | employee/link |

## STORE и SELLERS не смешиваются

| Scope / показатель | LiveSklad semantics | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| STORE quantity | 1,848 | 1,856 | +8 | +0.43% | FAIL | 5 missing returns | exact source attribution |
| STORE revenue | 48,453,946.50 | 48,596,416.50 | +142,470.00 | +0.29% | FAIL | 5 missing returns | exact source attribution |
| STORE cost | 41,326,456.00 | 41,438,156.00 | +111,700.00 | +0.27% | FAIL | 5 missing returns | exact source attribution |
| STORE GP | 7,127,490.50 | 7,158,260.50 | +30,770.00 | +0.43% | FAIL | 5 missing returns | exact source attribution |
| SELLERS quantity | 508 | 511 | +3 | +0.59% | FAIL | только `F000117`, `F000121` | ranking roster + parent employee |
| SELLERS revenue | 13,628,146.00 | 13,700,136.00 | +71,990.00 | +0.53% | FAIL | `F000121` | parent `EMP-EE11A56D7AF0` |
| SELLERS cost | 11,737,361.00 | 11,804,961.00 | +67,600.00 | +0.58% | FAIL | `F000117`, `F000121` | two exact parent employees |
| SELLERS GP | 1,890,785.00 | 1,895,175.00 | +4,390.00 | +0.23% | FAIL | те же returns | revenue − cost |
| SELLERS margin | 13.8741% | 13.8333% | −0.0408 п. п. | −0.29% | FAIL | те же returns | scope recomputation |

Шесть orphan returns и три missing returns `F000106/F000118/F000127` относятся нерейтинговому
`EMP-B802A9C3FDD3` и не входят в `SELLERS`. Поэтому их нельзя переносить в seller scope только для
того, чтобы получить STORE total.

## Каждый сотрудник

| Employee alias | Ranking | Live qty / revenue / cost / GP | Приложение qty / revenue / cost / GP | Дельта приложения | Статус | Причина |
|---|---|---:|---:|---:|---|---|
| `EMP-B802A9C3FDD3` | нет | 1,340 / 34,825,800.50 / 29,589,095 / 5,236,705.50 | 1,356 / 34,982,930.50 / 29,680,720 / 5,302,210.50 | +16 / +157,130 / +91,625 / +65,505 | FAIL | 3 missing + 6 orphan returns |
| `EMP-C92D2C99CC54` | да | 206 / 5,971,361 / 5,206,446 / 764,915 | 207 / 5,971,361 / 5,206,496 / 764,865 | +1 / 0 / +50 / −50 | FAIL | `F000117` |
| `EMP-EBC62AF18E20` | да | 1 / 4,000 / 2,500 / 1,500 | то же | 0 | PASS_TOTAL | payroll category `A000210` wrong |
| `EMP-EE11A56D7AF0` | да | 301 / 7,652,785 / 6,528,415 / 1,124,370 | 303 / 7,724,775 / 6,595,965 / 1,128,810 | +2 / +71,990 / +67,550 / +4,440 | FAIL | `F000121` |
| `UNASSIGNED` | нет | 0 / 0 / 0 / 0 | −11 / −86,650 / −47,525 / −39,125 | −11 / −86,650 / −47,525 / −39,125 | FAIL | 6 orphan returns |

Raw employee в return XLSX — обработавший возврат. Сотрудник метрики устанавливается по исходной
продаже согласно ADR-0001. Для всех 11 проблемных returns эта атрибуция доказана API, а не взята из
return report.

## Аналитические категории

Формат значений: `qty / revenue / cost / GP`; разница — приложение минус LiveSklad semantics.

| Категория | LiveSklad | Приложение | Разница, ₽/ед. | Статус |
|---|---:|---:|---:|---|
| `ACCESSORY_IPAD_MAC` | 4 / 17,960 / 3,950 / 14,010 | то же | 0 | PASS |
| `ACCESSORY_PODS_WATCH` | 11 / 23,420 / 4,000 / 19,420 | то же | 0 | PASS_WITH_LIMIT |
| `CASE_APPLE_IPHONE` | 295 / 60,570 / 28,730 / 31,840 | 299 / 60,570 / 28,930 / 31,640 | +4 / 0 / +200 / −200 | FAIL |
| `CASE_SAMSUNG` | 2 / 2,000 / 550 / 1,450 | то же | 0 | PASS |
| `CHARGER_CABLE` | 193 / 834,325 / 210,110 / 624,215 | то же | 0 | PASS |
| `FILM_PHONE` | 25 / 42,520 / 1,785 / 40,735 | то же | 0 | PASS |
| `GLASS_CAMERA_IPHONE` | 59 / 205,550 / 41,950 / 163,600 | то же | 0 | PASS |
| `GLASS_CAMERA_SAMSUNG` | 1 / 2,500 / 750 / 1,750 | то же | 0 | PASS |
| `GLASS_IPHONE` | 189 / 550,630 / 38,040 / 512,590 | то же | 0 | PASS |
| `GLASS_SAMSUNG` | 6 / 13,670 / 1,800 / 11,870 | то же | 0 | PASS |
| `IPAD_MAC` | 13 / 614,880 / 568,900 / 45,980 | то же | 0 | PASS |
| `IPHONE_NEW_ASIS` | 372 / 33,968,283 / 32,253,961 / 1,714,322 | 373 / 34,040,273 / 32,321,461 / 1,718,812 | +1 / +71,990 / +67,500 / +4,490 | FAIL |
| `IPHONE_USED` | 143 / 6,317,920 / 4,709,650 / 1,608,270 | 145 / 6,385,400 / 4,753,650 / 1,631,750 | +2 / +67,480 / +44,000 / +23,480 | FAIL |
| `OTHER_ACCESSORY_PRODUCT` | 103 / 469,555 / 111,720 / 357,835 | то же | 0 | PASS |
| `PODS_WATCH_OTHER_DEVICE` | 100 / 2,029,442 / 1,787,260 / 242,182 | то же | 0 | PASS |
| `PREMIUM_PROTECTION` | 4 / 52,420 / 0 / 52,420 | то же | 0 | PASS |
| `SAMSUNG_NEW` | 20 / 1,426,197 / 1,378,000 / 48,197 | то же | 0 | PASS |
| `SAMSUNG_USED` | 4 / 218,960 / 181,600 / 37,360 | то же | 0 | PASS |
| `SETUP_SERVICE` | 203 / 761,451 / 3,700 / 757,751 | то же | 0 | PASS |
| `WARRANTY_GENERIC` | 101 / 841,693.50 / 0 / 841,693.50 | 102 / 844,693.50 / 0 / 844,693.50 | +1 / +3,000 / 0 / +3,000 | FAIL |

16/20 аналитических категорий совпадают по business values; четыре дельты полностью образованы
восемью missing return positions. Orphan relink меняет employee/category cells, но не STORE
category total.

## Зарплатные категории

| Категория | LiveSklad semantics qty / revenue / cost / GP | Приложение | Разница, ₽/ед. | Статус | Причина |
|---|---:|---:|---:|---|---|
| `ACCESSORY` | 888 / 2,222,700 / 443,385 / 1,779,315 | 892 / 2,222,700 / 443,585 / 1,779,115 | +4 / 0 / +200 / −200 | FAIL | missing cases |
| `PAID_REPAIR` | 2 / 6,500 / 3,500 / 3,000 | 0 / 0 / 0 / 0 | −2 / −6,500 / −3,500 / −3,000 | FAIL | `A000201`, `A000210` |
| `SERVICE` | 306 / 1,649,064.50 / 200 / 1,648,864.50 | 309 / 1,658,564.50 / 3,700 / 1,654,864.50 | +3 / +9,500 / +3,500 / +6,000 | FAIL | warranty return + two repairs |
| `TECH_TIER_1` | 545 / 42,303,310 / 38,876,511 / 3,426,799 | 548 / 42,442,780 / 38,988,011 / 3,454,769 | +3 / +139,470 / +111,500 / +27,970 | FAIL | missing phones |
| `TECH_TIER_2` | 107 / 2,272,372 / 2,002,860 / 269,512 | то же | 0 | PASS | — |

| Order work | Employee | Revenue | Cost | Analytics current/expected | Payroll current → expected | Статус |
|---|---|---:|---:|---|---|---|
| `A000201` «Чистка разъема и замена контакта» | `EMP-B802A9C3FDD3` | 2,500 | 1,000 | `SETUP_SERVICE` | `SERVICE → PAID_REPAIR` | FAIL |
| `A000210` «Замена аккумулятора» | `EMP-EBC62AF18E20` | 4,000 | 2,500 | `SETUP_SERVICE` | `SERVICE → PAID_REPAIR` | FAIL |
| `A000213` «Перепрошивка» | `EMP-B802A9C3FDD3` | 1,000 | 200 | `SETUP_SERVICE` | `SERVICE → SERVICE` | PASS |

## Структура продаж

| Группа | LiveSklad qty / revenue / cost / GP | Приложение | Разница, ₽/ед. | Статус | Причина |
|---|---:|---:|---:|---|---|
| `ACCESSORIES` | 888 / 2,222,700 / 443,385 / 1,779,315 | 892 / 2,222,700 / 443,585 / 1,779,115 | +4 / 0 / +200 / −200 | FAIL | missing cases |
| `DEVICES` | 652 / 44,575,682 / 40,879,371 / 3,696,311 | 655 / 44,715,152 / 40,990,871 / 3,724,281 | +3 / +139,470 / +111,500 / +27,970 | FAIL | missing phones |
| `SERVICES` | 308 / 1,655,564.50 / 3,700 / 1,651,864.50 | 309 / 1,658,564.50 / 3,700 / 1,654,864.50 | +1 / +3,000 / 0 / +3,000 | FAIL | missing warranty |

Полноценная техника не включена в accessories. `DOPS = ACCESSORIES + SERVICES`; это намеренно
пересекающийся бизнес-срез, а не четвёртая взаимоисключающая часть структуры.

## Attach-rate

### STORE

| Метрика | LiveSklad N/B/rate | Приложение N/B/rate | Разница N/B/п. п. | Статус | Причина |
|---|---:|---:|---:|---|---|
| `ACCESSORY_IPAD` | 4 / 9 / 44.44% | то же | 0 | PASS | — |
| `ACCESSORY_PODS_WATCH` | 11 / 98 / 11.22% | то же | 0 | PASS_WITH_LIMIT | two accepted zero costs |
| `CASE_APPLE_IPHONE` | 295 / 515 / 57.28% | 299 / 518 / 57.72% | +4 / +3 / +0.44 | FAIL | missing cases + phones |
| `CASE_SAMSUNG` | 2 / 24 / 8.33% | то же | 0 | PASS | — |
| `CHARGER_CABLE` | 193 / 539 / 35.81% | 193 / 542 / 35.61% | 0 / +3 / −0.20 | FAIL | phone denominator |
| `FILM_PHONE` | 25 / 539 / 4.64% | 25 / 542 / 4.61% | 0 / +3 / −0.03 | FAIL | phone denominator |
| `GLASS_CAMERA_IPHONE` | 59 / 515 / 11.46% | 59 / 518 / 11.39% | 0 / +3 / −0.07 | FAIL | iPhone denominator |
| `GLASS_CAMERA_SAMSUNG` | 1 / 24 / 4.17% | то же | 0 | PASS | — |
| `GLASS_IPHONE` | 189 / 515 / 36.70% | 189 / 518 / 36.49% | 0 / +3 / −0.21 | FAIL | iPhone denominator |
| `GLASS_SAMSUNG` | 6 / 24 / 25.00% | то же | 0 | PASS | — |
| `PREMIUM_PROTECTION` | 0 / 652 / 0.00% | 0 / 655 / 0.00% | 0 / +3 / 0.00 | FAIL_COUNT | device denominator |
| `SETUP_SERVICE` | 203 / 545 / 37.25% | 203 / 548 / 37.04% | 0 / +3 / −0.21 | FAIL | tier-1 denominator |
| `WARRANTY_GENERIC_NEW` | 42 / 392 / 10.71% | 42 / 393 / 10.69% | 0 / +1 / −0.02 | FAIL | new iPhone denominator |
| `WARRANTY_GENERIC_USED` | 63 / 147 / 42.86% | 64 / 149 / 42.95% | +1 / +2 / +0.09 | FAIL | missing used phone+warranty |

### SELLERS

Из 42 seller attach cells 33 совпадают. Все девять ненулевых дельт приведены ниже; у
`EMP-EBC62AF18E20` все 14 cells exact, а undefined 0/0 rates остаются undefined с обеих сторон.

| Employee / метрика | LiveSklad N/B/rate | Приложение N/B/rate | Разница N/B/п. п. | Статус | Причина |
|---|---:|---:|---:|---|---|
| `EMP-C92... / CASE_APPLE_IPHONE` | 35 / 59 / 59.32% | 36 / 59 / 61.02% | +1 / 0 / +1.70 | FAIL | `F000117` |
| `EMP-EE11... / CASE_APPLE_IPHONE` | 57 / 82 / 69.51% | 58 / 83 / 69.88% | +1 / +1 / +0.37 | FAIL | `F000121` |
| `EMP-EE11... / CHARGER_CABLE` | 28 / 90 / 31.11% | 28 / 91 / 30.77% | 0 / +1 / −0.34 | FAIL | phone denominator |
| `EMP-EE11... / FILM_PHONE` | 5 / 90 / 5.56% | 5 / 91 / 5.49% | 0 / +1 / −0.07 | FAIL | phone denominator |
| `EMP-EE11... / GLASS_CAMERA_IPHONE` | 11 / 82 / 13.41% | 11 / 83 / 13.25% | 0 / +1 / −0.16 | FAIL | iPhone denominator |
| `EMP-EE11... / GLASS_IPHONE` | 37 / 82 / 45.12% | 37 / 83 / 44.58% | 0 / +1 / −0.54 | FAIL | iPhone denominator |
| `EMP-EE11... / PREMIUM_PROTECTION` | 0 / 106 / 0.00% | 0 / 107 / 0.00% | 0 / +1 / 0.00 | FAIL_COUNT | device denominator |
| `EMP-EE11... / SETUP_SERVICE` | 14 / 92 / 15.22% | 14 / 93 / 15.05% | 0 / +1 / −0.17 | FAIL | tier-1 denominator |
| `EMP-EE11... / WARRANTY_GENERIC_NEW` | 9 / 59 / 15.25% | 9 / 60 / 15.00% | 0 / +1 / −0.25 | FAIL | new iPhone denominator |

Production attach recomputation mismatch count = 0: приложение правильно считает свои текущие
facts. Дельты вызваны неполными facts, а не отдельной ошибкой формулы attach-rate.

## Каждый документ и каждая товарная позиция

- source documents 796; production current 791; only-source 5; only-app 0;
- source raw rows 1,974; production rows 1,955;
- source product groups 1,960; production groups 1,952; eight only-source groups — exact missing
  positions из таблицы выше;
- после детерминированной симуляции пяти recoveries и шести relinks: 796/796 documents, all
  business-value deltas 0, attribution 40/40, employee/category/payroll/attach deltas 0 после
  отдельной симуляции payroll correction;
- остаточная row-count разница −11 после симуляции — только нормальная консолидация повторных
  строк одного товара внутри документа, не value mismatch.

| Документ | Product groups source→app | Qty | Amount | Статус |
|---|---:|---:|---:|---|
| `B002087` | 2→1 одинаковых | 2 | 195,980 | EXPLAINED_AGGREGATION |
| `B002199` | 2→1 + 2→1 | 2 + 2 | 3,980 + 5,980 | EXPLAINED_AGGREGATION |
| `B002236` | 2→1 | 2 | 219,720 | EXPLAINED_AGGREGATION |
| `B002329` | 2→1 | 2 | 7,980 | EXPLAINED_AGGREGATION |
| `B002462` | 2→1 | 2 | 5,980 | EXPLAINED_AGGREGATION |
| `B002502` | 2→1 | 2 | 3,000 | EXPLAINED_AGGREGATION |
| `B002573` | 2→1 | 2 | 203,980 | EXPLAINED_AGGREGATION |
| `B002586` | 2→1 | 2 | 17,980 | EXPLAINED_AGGREGATION |
| `B002630` | 2→1 | 2 | 210,980 | EXPLAINED_AGGREGATION |
| `B002757` | 2→1 | 2 | 3,980 | EXPLAINED_AGGREGATION |

У `B002510` source name имеет suffix `1 rev.`, которого нет в snapshot; product, quantity 1,
amount 48,000 и cost совпали, ambiguity 0. Exact duplicate raw row count = 10; дубликатов facts
или финансовых сумм нет.

Все 791 общих document timestamps в XLSX на 59–60 минут позже production local presentation
(774 × 59 минут из-за обрезанных секунд, 17 × 60 минут). Прямой API показывает UTC, production
корректно переводит в `Europe/Kaliningrad`, тогда как XLSX отображает время на час вперёд. Ни один
документ не пересёк границу дня или месяца; финансовый эффект 0. Округлительных расхождений нет.

## ZERO_UNEXPECTED

| Документ | Позиция | Qty | Revenue | Cost | Категория | Статус |
|---|---|---:|---:|---:|---|---|
| `B002661` | ремешок «миланская петля 38–41 mm» | 1 | 1,000 | 0 | `ACCESSORY_PODS_WATCH` | PASS_WITH_LIMIT |
| `B002784` | ремешок «миланская петля 38–41 mm» | 1 | 500 | 0 | `ACCESSORY_PODS_WATCH` | PASS_WITH_LIMIT |

Cost 0 совпадает с XLSX; пользователь ранее подтвердил допустимость. Эти строки влияют на
абсолютную cost/GP и quality flag, но не создают межсистемную разницу, не исключаются из revenue,
units, category или attach-rate. Исправление не требуется.

## Классификация причин

| Класс причины | Итог | Доказательство |
|---|---|---|
| Неполное покрытие синхронизации | обнаружено | 5 only-Live returns / 8 positions |
| Пропущенный или скрытый возврат | обнаружено | 5 active item returns, все cash transaction count 0 |
| Неверная классификация | обнаружена | 2 exact order works должны быть `PAID_REPAIR` |
| Неправильный магазин или сотрудник | store error — нет; employee error — да | 6 orphan returns; API exact parent employee |
| Отличие формул | объяснено, исправление не нужно | cash 48,588,916.50 против merchandise 48,446,446.50 |
| Отличие границ периода | effect 0 | 59–60-minute offset без перехода business date |
| Дубликат или удаление | не обнаружено | 11 exact aggregations; deleted facts 0 |
| Ошибка исходных данных LiveSklad | финансовая ошибка не доказана | 2 разрешённых zero costs — quality limitation |
| Ошибка приложения | доказана | cash-only discovery, historical lost links, payroll assignment |

## Безопасный план исправления

1. Выполнить review и единый batch deployment уже накопленных recovery changes: V49 guarded
   `EXISTING_ORPHAN_RELINK` и V51 non-negative recovery contract. Production schema 48 сейчас не
   поддерживает ни bounded orphan relink, ни zero-net returns.
2. После fresh preflight выполнить пять exact `MISSING_RETURN` recoveries по одному. Для
   `F000117` и `F000118` использовать настоящее expected net 0.00, не ложную положительную сумму.
3. Шесть существующих документов не восстанавливать как missing и не править SQL вручную. После
   deployment выполнить шесть exact `EXISTING_ORPHAN_RELINK` по API manifests с parent sale,
   original employee и всеми 11 original position links.
4. Отдельным bounded correction изменить только payroll assignments `A000201` и `A000210` на
   `PAID_REPAIR`; analytics `SETUP_SERVICE` и `A000213` не менять. До записи проверить locked
   payroll snapshots и exact item IDs/amounts/cost.
5. После каждой операции выполнить document-level post-check, а затем полный февральский audit:
   `STORE`, `SELLERS`, employees, analytics/payroll categories, documents/items, payments и
   attach-rate. Только фактический post-commit ноль позволяет сменить verdict.

Широкий resync/backfill не решает cashless discovery и orphan links, поэтому сам по себе не нужен.
Обычный backfill можно запускать только если post-check выявит независимый gap.

## Read-only API audit и cleanup

Пользователь отдельно разрешил точечный read-only LiveSklad API-аудит 11 returns. Runner
содержал только authentication POST и последующие GET; SQL/write API отсутствовали. Audit вернул
11 documents, 19 positions, quantity 19, absolute revenue 229,120, cost 159,225, 6 cash
transactions и 0 violations. Hash runner:
`cfe26c42b5ce0fad73086a5ff1854a2ac4c25375f3f5a0ddf5923345a59cd039`.

После результата временные production runner, sudoers/root install и четыре upload-файла удалены;
отдельная проверка вернула `cleanup=verified`. Удаление временных production-файлов необратимо,
но их локальные hash-locked copies сохранены в `.codex-prod-recovery/`. Production data и code не
менялись.

## Ответы по текущему состоянию

1. **Совпало точно:** 753 прямые продажи; три ненулевые order positions и их finance; payment
   ledger; 16/20 analytics categories; все общие документы и позиции по quantity/revenue/cost.
2. **Не совпало:** `STORE` quantity/revenue/cost/GP/margin; `SELLERS`; employee attribution;
   четыре analytics categories, четыре payroll categories, sales structure и attach-rate.
3. **Документы разницы:** missing — `F000106`, `F000117`, `F000118`, `F000121`, `F000127`;
   orphan attribution — `F000102`, `F000103`, `F000104`, `F000108`, `F000109`, `F000122`;
   payroll — `A000201`, `A000210`.
4. **Первопричина:** cashless returns не попадают в cash-transaction discovery; старые returns
   могут сохраняться без original links; deployed recovery contract не принимает zero-net;
   repair semantics не назначена двум order works.
5. **Необходимые исправления:** reviewed V49/V51 batch deployment, 5 exact recoveries, 6 guarded
   relinks и 2 bounded payroll assignments.
6. **Повторная синхронизация:** широкий resync не требуется и не исправит причины; нужны точечные
   операции и затем полный read-only повторный audit.
7. **Можно ли доверять февралю:** direct sales, issued-order finance и cash ledger — да. Общим
   `STORE` KPI, `SELLERS`, сотрудникам, категориям, payroll и attach-rate — пока нет. После
   исправлений должна остаться только явно принятая оговорка о двух zero-cost items.
