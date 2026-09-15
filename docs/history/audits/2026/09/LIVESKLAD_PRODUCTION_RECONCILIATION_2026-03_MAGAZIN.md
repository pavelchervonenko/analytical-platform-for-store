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
verdict_scope: "МАГАЗИН, 2026-03-01..2026-03-31 inclusive, Europe/Kaliningrad; F000148 recovery passed, zero-net F000175 remains blocked by the positive-amount recovery contract, four loaded returns have lost original-sale attribution, and three analytics/eight payroll order-work assignments are wrong."
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
  - sanitized production read-only SQL snapshots captured on 2026-09-14 and 2026-09-15
  - targeted read-only LiveSklad API verification of six returns and their original sales
  - exact document, product-group, category, payroll, employee and attach-rate reconstruction
  - current application formulas, classification rules and recovery guards
  - exact F000148 recovery terminal state and independent post-recovery monthly audit
required_reviewers:
  - product
  - operations
---

# Сверка LiveSklad ↔ production: март 2026, МАГАЗИН

## Текущий вердикт

`FAIL_ACTION_REQUIRED`. Разрешённый exact-target recovery `F000148` завершён `PROCESSED` и прошёл
независимый post-check. Чистая выручка `STORE` теперь совпадает точно. Остаточная финансовая
разница образована только zero-net возвратом `F000175`: приложение завышает чистое количество на
1 единицу и себестоимость на 50.00 ₽, поэтому GP занижена на 50.00 ₽; revenue effect равен нулю.

Отдельно доказаны четыре ошибки атрибуции уже загруженных возвратов: `F000144`, `F000146`,
`F000156`, `F000168`. Прямой API LiveSklad вернул их parent sale и original position links без
нарушений, а январский/февральский production-аудит подтвердил наличие всех четырёх исходных
продаж и позиций. Эти возвраты уже входят в `STORE`, но ошибочно остаются в `UNASSIGNED` и поэтому
искажают сотрудников и `SELLERS`.

Третья независимая проблема — классификация девяти выданных order works. Три работы ошибочно
попали в аксессуары вместо `SETUP_SERVICE`; восемь ремонтных работ должны находиться в payroll
`PAID_REPAIR`. Чистка тач-пада и клавиатуры остаётся payroll `SERVICE` по ранее принятой семантике
чистки, но её analytics category также должна быть `SETUP_SERVICE`.

Другие исправления не выполнялись. Exact provider external IDs, parent sales и original positions
`F000148` и `F000175` подтверждены прямым read-only API LiveSklad; обе исходные продажи и все
четыре связанные позиции уже есть и активны в production. Оба возврата относятся к нерейтинговому
`EMP-B802A9C3FDD3`, поэтому итоговый expected scope `SELLERS` установлен точно. Нулевая
себестоимость четырёх других товаров совпадает с XLSX и ранее разрешена пользователем: это
ограничение качества GP, а не межсистемное расхождение.

## Результат разрешённого recovery `F000148`

Recovery ID `fe1c19f2-0eb0-4bd8-b252-7cff60dfdf06` с фиксированными ожиданиями 128,198.00 ₽ и
трёх позиций перешёл `RECEIVED → PROCESSED`; `terminalFailure=false`, `errorCode=null`. До POST
runner подтвердил успешный последний logical-backup service result и public readiness. После POST
независимый мартовский read-only audit подтвердил:

- active return `F000148`, дата бизнеса `2026-03-04`, payment 0;
- parent `B002918`, тот же магазин и исходный нерейтинговый сотрудник;
- 3/3 active positions, exact original item links, quantity 3, revenue 128,198.00 ₽, cost
  106,000.00 ₽;
- изменение мартовского `STORE` ровно на −3 ед. / −128,198.00 ₽ revenue / −106,000.00 ₽ cost /
  −22,198.00 ₽ GP;
- missing cost, `UNMAPPED`, `EXCLUDE` и новые `ZERO_UNEXPECTED` не появились.

| Показатель после `F000148` | LiveSklad | Приложение | Разница приложения | Статус | Причина |
|---|---:|---:|---:|---|---|
| Чистая выручка STORE | 59,342,797.00 | 59,342,797.00 | 0.00 ₽ | PASS | `F000148` восстановлен; `F000175` zero-net |
| Себестоимость STORE | 51,722,512.00 | 51,722,562.00 | +50.00 ₽ | FAIL | отсутствует cost return `F000175` |
| Валовая прибыль STORE | 7,620,285.00 | 7,620,235.00 | −50.00 ₽ | FAIL | тот же zero-net return |
| Валовая маржа | 12.8411% | 12.8410% | −0.0001 п. п. | FAIL | cost/GP `F000175` |
| Чистое количество STORE | 2,122 | 2,123 | +1 ед. | FAIL | отсутствует одна return position |
| Return documents / item-bearing | 37 / 36 | 36 / 35 | −1 / −1 | FAIL | только `F000175` |

Временные production runner, uploads и exact `NOPASSWD` после post-check удалены. Повторный
запуск установленной команды запрещён; отдельная проверка вернула `cleanup=verified`. Локальные
hash-locked scripts сохранены для воспроизводимости.

`F000175` намеренно не отправлялся: deployed API и schema constraint требуют
`expectedNetAmount > 0`, а доказанное значение документа равно 0.00 ₽. Передача ложной
положительной суммы, ручной SQL или обход expectation guard запрещены. По отдельному разрешению
локально подготовлены non-negative source contract, forward migration V51 и тесты с сохранением
обязательного положительного position count и exact worker verification. Они не развёрнуты:
`F000175` остаётся заблокирован до reviewed deployment и fresh production preflight.

### Локальная проверка zero-net forward-fix

- DTO, service guard, worker expectation и V51 принимают `0.00`, но продолжают отклонять
  отрицательную сумму; `expectedPositionCount` остаётся строго положительным.
- PostgreSQL integration test подтвердил сохранение zero-net expectation новым constraint.
- End-to-end sync test подтвердил item-bearing return с `net=0.00`, `cost=50.00`, original
  document/item links и idempotent replay.
- Flyway fresh/representative upgrades до V51, Java checkstyle и OpenAPI compatibility прошли.
- Production runtime, recovery queue и facts этим изменением не затрагивались.

## Исходная диагностика до recovery `F000148`

## Scope, фильтры и источники

| Параметр | Зафиксированное значение | Доказательство |
|---|---|---|
| Магазин | `МАГАЗИН` | выбор пользователя и содержимое файлов |
| Период | `2026-03-01..2026-03-31`, обе даты включительно | XLSX и production `business_date` |
| Business timezone | `Europe/Kaliningrad`, начало дня `00:00` | production store и period contract |
| «Товары и работы» | магазин `МАГАЗИН`; март; `Продажа`, `Возврат`, `Установка в заказ`; заказы `Выдан` | 2,226 detail rows |
| «Продажи» | магазин `МАГАЗИН`; март; продажи и возвраты продаж | 893 document rows + total |
| «Заказы» | магазин `МАГАЗИН`; дата выдачи в марте; выданные заказы | 8 orders + total |

Полные экранные параметры LiveSklad не встроены в XLSX как машиночитаемые metadata. Поэтому
зафиксированы выбранные пользователем фильтры, а фактический scope независимо проверен по всем
датам, типам, status и document numbers внутри файлов.

| Файл | Полезные строки | Размер | SHA-256 |
|---|---:|---:|---|
| `Отчёт по товарам и работам март.xlsx` | 2,226 + total | 960,124 B | `0d06371d204db9139d3cf6fa81a23d8db2b7c371fcb87716985b068eb7fce8d1` |
| `Отчёт по продажам март.xlsx` | 893 + total | 431,995 B | `26b81f0a059ca76e8af4bf3546cf2de387e7f7126562625758bdac59718d0f7b` |
| `Отчёт по заказам март.xlsx` | 8 + total | 22,514 B | `68d64782535b00cfec01c90c28aa888dade5da1ec57f3b0503b7b8216a39e1b1` |

Свежий read-only production запуск подтвердил runtime identity из
[`project-state.md`](../../../../current/project-state.md), магазин и timezone. SQL выполнялся с
read-only guards и завершился `ROLLBACK`. Использованы существующие owner key, разблокированный
agent socket и Windows TCP proxy; новые SSH-ключи не создавались. Секреты, полные provider payloads
и персональные данные в evidence не сохранены.

## Семантика сравнения

- знак факта: `SALE=+1`, `RETURN=-1`; GP = revenue − cost;
- `STORE` включает все факты магазина; `SELLERS` — только рейтинговых сотрудников;
- возврат относится сотруднику исходной продажи, а не сотруднику, указанному в return XLSX;
- аналитическая и зарплатная классификация рассчитаны отдельно;
- подтверждённый ремонт — analytics `SETUP_SERVICE`, payroll `PAID_REPAIR`; чистка без ремонта —
  analytics/payroll `SETUP_SERVICE / SERVICE`;
- attach-rate использует чистые item units, отдельно для `STORE` и `SELLERS`;
- merchandise return, документ возврата и движение денег не смешиваются;
- количество выданных заказов считается по уникальному order number, позиции работ — отдельно;
- нулевая себестоимость допустима, но остаётся quality limitation.

## Полнота синхронизации

Исторические sync runs содержат и successful, и failed/partial rows. Полнота поэтому установлена
не по статусу одной job, а по exact sets и values:

- прямые продажи: 856/856 документов; 2,193/2,193 единицы; 62,111,444.00 ₽ и cost
  54,193,277.00 ₽ совпали;
- item-bearing returns: LiveSklad 36 документов / 79 raw rows / 80 единиц; production 34
  документа / 75 facts / 76 единиц;
- ещё один cash-only return `F000152` есть с обеих сторон и не содержит товарных позиций;
- missing item-bearing returns: ровно `F000148`, `F000175`; only-app item documents — 0;
- восемь уникальных выданных заказов и все девять работ присутствуют; amount/cost совпадают;
- у всех 898 общих item-document keys quantity, revenue и cost совпадают; value mismatches — 0;
- 2,210 LiveSklad product groups против 2,206 production; четыре only-Live groups — позиции
  `F000148` и `F000175`; only-app/value mismatches — 0;
- deleted documents/items, missing cost, `UNMAPPED`, `EXCLUDE` и ambiguous rename matches — 0;
- production attach recomputation mismatch — 0;
- четыре из 34 загруженных item-bearing returns имеют потерянные original links; остальные 30
  связаны и атрибутированы согласованно.

Полный мартовский backfill несколько раз останавливался в `RETURNS` на provider rate limit и
ошибках окон; более поздний bounded job успешно закрыл конец периода. Это объясняет историю jobs,
но не само наличие двух cash-less документов: текущий return discovery использует cash
transactions, а у `F000148` и `F000175` оплаты нет.

## STORE: исходный итог месяца

Во всех таблицах разница равна `Приложение − LiveSklad`. Под LiveSklad понимается проверенная
товарная семантика трёх отчётов, а не только колонка оплаты.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка | 59,342,797.00 | 59,470,995.00 | +128,198.00 ₽ | +0.22% | FAIL | 2 missing returns | exact item decomposition |
| Себестоимость | 51,722,512.00 | 51,828,562.00 | +106,050.00 ₽ | +0.21% | FAIL | те же returns | XLSX cost per position |
| Валовая прибыль | 7,620,285.00 | 7,642,433.00 | +22,148.00 ₽ | +0.29% | FAIL | те же returns | revenue − cost |
| Валовая маржа | 12.8411% | 12.8507% | +0.0096 п. п. | +0.07% | FAIL | завышены revenue и GP | единое финальное округление |
| Чистое количество | 2,122 ед. | 2,126 ед. | +4 ед. | +0.19% | FAIL | отсутствуют 4 return units | item reconciliation |
| Продажи | 856 док. / 2,193 ед. / 62,111,444.00 ₽ | то же | 0 | 0.00% | PASS | — | 856 exact documents |
| Возвраты продаж | 37 док.; 36 item-bearing; −80 ед.; −2,810,437.00 ₽ | 35 док.; 34 item-bearing; −76 ед.; −2,682,239.00 ₽ | −2 док. / +4 ед. / +128,198.00 ₽ | +4.56% по абсолютной revenue | FAIL | `F000148`, `F000175` | goods + sales + SQL |
| Возвраты заказов | 0 | 0 | 0 | — | PASS | отсутствуют | orders + production |
| Выданные заказы | 8 orders / 9 works / 9 ед. / 41,790.00 ₽ | 8 unique numbers / 9 facts / 9 ед. / 41,790.00 ₽ | 0 | 0.00% | PASS | `A000221` содержит две работы | exact order decomposition |
| Аксессуары | 964 ед. / 2,551,738.00 ₽ | 969 ед. / 2,572,728.00 ₽ | +5 ед. / +20,990.00 ₽ | +0.82% | FAIL | `F000148`, `F000175` + 3 work classifications | semantic reconstruction |
| Услуги и допы | 372 ед. / 2,011,184.00 ₽ | 370 ед. / 1,999,174.00 ₽ | −2 ед. / −12,010.00 ₽ | −0.60% | FAIL | missing service return + 3 works outside service | semantic reconstruction |

## Документы и позиции финансовой разницы

| Документ | Тип | Позиция | Qty | Revenue | Cost | GP | Production | Статус |
|---|---|---|---:|---:|---:|---:|---|---|
| `F000148` | sale return | iPhone 17 Pro 512GB | −1 | −119,218 | −105,800 | −13,418 | отсутствует | FAIL |
| `F000148` | sale return | защитное стекло iPhone | −1 | −2,990 | −200 | −2,790 | отсутствует | FAIL |
| `F000148` | sale return | перенос данных | −1 | −5,990 | 0 | −5,990 | отсутствует | FAIL |
| `F000175` | sale return | прозрачный чехол iPhone | −1 | 0 | −50 | +50 | отсутствует | FAIL |
| **Итого** | 2 docs | 4 positions | **−4** | **−128,198** | **−106,050** | **−22,148** | — | FAIL |

Пользователь передал exact IDs и показанные LiveSklad parent numbers. Независимый read-only API
LiveSklad подтвердил обе пары без нарушений:

| Return | Return external ID | Parent sale | Parent external ID | Original employee | Positions / qty / revenue / cost |
|---|---|---|---|---|---:|
| `F000148` | `69a887b8a005f81e96e785f1` | `B002918` | `69a83bcedef589073ed65378` | `EMP-B802A9C3FDD3` | 3 / 3 / 128,198 / 106,000 |
| `F000175` | `69c67dbf35f1a26e5b3bc638` | `B003539` | `69c66a48db593d1c2361fd6c` | `EMP-B802A9C3FDD3` | 1 / 1 / 0 / 50 |

Для каждой позиции API `salePositionId` существует в parent document и указывает на тот же
product ID. Production содержит оба parent external IDs и exact original position IDs активными,
с тем же сотрудником, количеством, revenue и cost. Parent recovery/resync не нужен. Provider IDs
не выводились из document number и не подбирались эвристически.

## Четыре существующих orphan returns

Прямой LiveSklad API-аудит вернул четыре точных документа и их parent sales; `detectedViolations`
пуст. Для каждой позиции совпали return number, store, type, product ID, original position ID,
quantity, revenue и cost. Parent sale и exact original item присутствуют в production.

| Return | Return external ID | Parent sale | Parent external ID | Original employee | Qty / revenue / cost / GP | Production сейчас |
|---|---|---|---|---|---:|---|
| `F000144` | `69a433f7a005f81a3c9e4d77` | `B002562` | `699984feb3671d8416e003be` | `EMP-B802A9C3FDD3` | −1 / −5,490 / −950 / −4,540 | `UNASSIGNED`, links null |
| `F000146` | `69a6c927a005f858edc750b0` | `B001323` | `695bcd18096f1a2a348e54c2` | `EMP-B802A9C3FDD3` | −1 / −98,990 / −93,100 / −5,890 | `UNASSIGNED`, links null |
| `F000156` | `69b1a7206571d4567c078c85` | `B002025` | `697ce87e4785e24112f13fc6` | `EMP-B802A9C3FDD3` | −1 / −27,990 / −15,000 / −12,990 | `UNASSIGNED`, links null |
| `F000168` | `69bfbf2085d2ba71c2d15ce1` | `B002210` | `69885e2ca44502639d375a99` | `EMP-EE11A56D7AF0` | −1 / −77,980 / −51,000 / −26,980 | `UNASSIGNED`, links null |

Три первых parent employees не участвуют в рейтинге; `F000168` должен уменьшать `SELLERS` и KPI
`EMP-EE11A56D7AF0`. Широкая повторная синхронизация исходных продаж не нужна: все четыре parent
sales и exact items уже есть и активны. Нужна guarded relink-операция, недоступная в текущем
deployed runtime до согласованного deployment режима `EXISTING_ORPHAN_RELINK`.

## Движение денег отдельно от продаж

| Показатель | LiveSklad | Приложение | Разница | Статус | Объяснение |
|---|---:|---:|---:|---|---|
| Signed payment movement | 59,299,486.00 | 59,299,486.00 | 0.00 | PASS | payment ledger exact |
| Merchandise без orders | 59,301,007.00 | до восстановления 59,429,205.00 | +128,198.00 | FAIL | `F000148`, `F000175` отсутствуют |
| Cash − LiveSklad merchandise | −1,521.00 | — | — | EXPLAINED | деньги и товарные возвраты различаются |
| Orders amount / paid в source report | 41,790.00 / 41,790.00 | 41,790.00 order facts; отдельные order payments не создаются | 0 amount | PASS | защита от двойного денежного учёта |

Вся ненулевая cash-versus-merchandise разница раскладывается в два документа:

- `F000152`: merchandise 0, payment movement −129,719.00 ₽; приложение хранит это движение точно;
- `F000148`: merchandise −128,198.00 ₽, payment 0; item document в приложении отсутствует.

Колонка отчёта «Возврат» равна нулю и не является источником товарных возвратов. Это подтверждает,
что продажи, товарное движение и деньги нельзя сводить по одной колонке.

## STORE: структура продаж

Формат — `quantity / revenue / cost / GP`; разница — приложение минус ожидание.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Техника | 786 / 54,779,875 / 51,191,722 / 3,588,153 | 787 / 54,899,093 / 51,297,522 / 3,601,571 | +1 / +119,218 / +105,800 / +13,418 | +0.22% revenue | FAIL | iPhone из `F000148` | exact item |
| Аксессуары | 964 / 2,551,738 / 506,490 / 2,045,248 | 969 / 2,572,728 / 517,740 / 2,054,988 | +5 / +20,990 / +11,250 / +9,740 | +0.82% | FAIL | 2 return items + 3 order works | exact items + semantics |
| Услуги и допы | 372 / 2,011,184 / 24,300 / 1,986,884 | 370 / 1,999,174 / 13,300 / 1,985,874 | −2 / −12,010 / −11,000 / −1,010 | −0.60% | FAIL | `F000148` + 3 order works | exact items + semantics |

## Все аналитические категории STORE

Формат — `quantity / revenue / cost / GP`; разница — приложение минус ожидание.

| Категория | LiveSklad / ожидается | Приложение | Разница | Статус | Причина |
|---|---:|---:|---:|---|---|
| `ACCESSORY_IPAD_MAC` | 6 / 28,200 / 10,050 / 18,150 | то же | 0 | PASS | — |
| `ACCESSORY_PODS_WATCH` | 28 / 33,929 / 4,880 / 29,049 | то же | 0 | PASS_WITH_LIMIT | 3 source zero costs |
| `CASE_APPLE_IPHONE` | 257 / 116,510 / 41,870 / 74,640 | 258 / 116,510 / 41,920 / 74,590 | +1 / 0 / +50 / −50 | FAIL | `F000175` |
| `CASE_SAMSUNG` | 3 / 19,470 / 5,900 / 13,570 | то же | 0 | PASS | — |
| `CHARGER_CABLE` | 235 / 955,805 / 249,865 / 705,940 | то же | 0 | PASS_WITH_LIMIT | 1 source zero cost |
| `FILM_PHONE` | 36 / 77,411 / 2,390 / 75,021 | то же | 0 | PASS | — |
| `GLASS_CAMERA_IPHONE` | 62 / 200,650 / 39,850 / 160,800 | то же | 0 | PASS | — |
| `GLASS_CAMERA_SAMSUNG` | 2 / 9,980 / 1,400 / 8,580 | то же | 0 | PASS | — |
| `GLASS_IPHONE` | 216 / 604,753 / 43,600 / 561,153 | 219 / 617,743 / 49,800 / 567,943 | +3 / +12,990 / +6,200 / +6,790 | FAIL | `F000148`, `A000221`, `A000240` |
| `GLASS_SAMSUNG` | 8 / 21,930 / 2,350 / 19,580 | то же | 0 | PASS | — |
| `IPAD_MAC` | 35 / 1,826,580 / 1,714,100 / 112,480 | то же | 0 | PASS | — |
| `IPHONE_NEW_ASIS` | 421 / 39,772,716 / 38,312,912 / 1,459,804 | 422 / 39,891,934 / 38,418,712 / 1,473,222 | +1 / +119,218 / +105,800 / +13,418 | FAIL | `F000148` |
| `IPHONE_USED` | 179 / 8,069,610 / 6,349,790 / 1,719,820 | то же | 0 | PASS | orphan relink не меняет STORE category |
| `OTHER_ACCESSORY_PRODUCT` | 112 / 491,100 / 109,335 / 381,765 | то же | 0 | PASS | — |
| `PODS_WATCH_OTHER_DEVICE` | 117 / 2,528,679 / 2,305,920 / 222,759 | то же | 0 | PASS | — |
| `PREMIUM_PROTECTION` | 6 / 138,877 / 0 / 138,877 | то же | 0 | PASS | — |
| `SAMSUNG_NEW` | 31 / 2,444,320 / 2,385,400 / 58,920 | то же | 0 | PASS | — |
| `SAMSUNG_USED` | 3 / 137,970 / 123,600 / 14,370 | то же | 0 | PASS | — |
| `SETUP_SERVICE` | 252 / 902,407 / 24,300 / 878,107 | 250 / 890,397 / 13,300 / 877,097 | −2 / −12,010 / −11,000 / −1,010 | FAIL | `F000148` + 3 order works |
| `WARRANTY_GENERIC` | 114 / 969,900 / 0 / 969,900 | то же | 0 | PASS | — |

## Зарплатные категории STORE

| Показатель | LiveSklad / ожидается | Приложение | Разница, ₽/ед. | Статус | Причина | Доказательство |
|---|---:|---:|---:|---|---|---|
| `ACCESSORY` | 964 / 2,551,738 / 506,490 / 2,045,248 | 969 / 2,572,728 / 517,740 / 2,054,988 | +5 / +20,990 / +11,250 / +9,740 | FAIL | missing returns + 3 order works | item decomposition |
| `SERVICE` | 364 / 1,977,394 / 5,000 / 1,972,394 | 370 / 1,999,174 / 13,300 / 1,985,874 | +6 / +21,780 / +8,300 / +13,480 | FAIL | `F000148`; 6 repairs still in SERVICE; one cleaning absent | semantics |
| `PAID_REPAIR` | 8 / 33,790 / 19,300 / 14,490 | 0 | −8 / −33,790 / −19,300 / −14,490 | FAIL | repair assignments отсутствуют | source work + business rule |
| `TECH_TIER_1` | 646 / 51,505,016 / 48,206,302 / 3,298,714 | 647 / 51,624,234 / 48,312,102 / 3,312,132 | +1 / +119,218 / +105,800 / +13,418 | FAIL | iPhone из `F000148` | exact item |
| `TECH_TIER_2` | 140 / 3,274,859 / 2,985,420 / 289,439 | то же | 0 | PASS | — | item recomputation |

## Выданные заказы и платные ремонты

В orders report восемь уникальных документов. `A000221` содержит две work positions, поэтому
production корректно хранит девять `orderPosition` facts. Amount 41,790.00 ₽, cost 24,300.00 ₽ и
GP 17,490.00 ₽ совпали полностью; различие 8/9 — документ против позиции, а не дубликат.

| Документ | Работа | Qty / revenue / cost / GP | Сейчас | Ожидается | Статус |
|---|---|---:|---|---|---|
| `A000221` | замена заднего стекла iPhone | 1 / 5,000 / 3,500 / 1,500 | `GLASS_IPHONE / ACCESSORY` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_ANALYTICS_AND_PAYROLL |
| `A000221` | замена аккумулятора | 1 / 4,000 / 2,500 / 1,500 | `SETUP_SERVICE / SERVICE` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_PAYROLL |
| `A000240` | замена заднего стекла iPhone | 1 / 5,000 / 2,500 / 2,500 | `GLASS_IPHONE / ACCESSORY` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_ANALYTICS_AND_PAYROLL |
| `A000256` | чистка разъёма и замена контакта | 1 / 4,000 / 2,000 / 2,000 | `SETUP_SERVICE / SERVICE` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_PAYROLL |
| `A000261` | чистка тач-пада и клавиатуры с разборкой | 1 / 8,000 / 5,000 / 3,000 | `ACCESSORY_IPAD_MAC / ACCESSORY` | `SETUP_SERVICE / SERVICE` | FAIL_ANALYTICS_AND_PAYROLL |
| `A000276` | замена аккумулятора | 1 / 4,000 / 2,000 / 2,000 | `SETUP_SERVICE / SERVICE` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_PAYROLL |
| `A000282` | замена аккумулятора | 1 / 3,300 / 2,300 / 1,000 | `SETUP_SERVICE / SERVICE` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_PAYROLL |
| `A000290` | замена аккумулятора | 1 / 4,500 / 2,300 / 2,200 | `SETUP_SERVICE / SERVICE` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_PAYROLL |
| `A000302` | замена дисплея | 1 / 3,990 / 2,200 / 1,790 | `SETUP_SERVICE / SERVICE` | `SETUP_SERVICE / PAID_REPAIR` | FAIL_PAYROLL |

## SELLERS отдельно от STORE

Production `SELLERS` сейчас содержит 414 ед. / 11,511,383.00 ₽ revenue / 10,248,714.00 ₽ cost /
1,262,669.00 ₽ GP. Уже доказанный parent link `F000168 → B002210 → EMP-EE11A56D7AF0`
уменьшает этот scope до 413 ед. / 11,433,403.00 ₽ / 10,197,714.00 ₽ / 1,235,689.00 ₽.

Это окончательный source-linked `SELLERS` total: API подтвердил, что оба missing returns и три
других orphan returns относятся нерейтинговому сотруднику и не входят в `SELLERS`.
Классификация order works не меняет общий SELLERS revenue/cost/GP, но переносит 3 единицы /
18,000.00 ₽ / cost 11,000.00 ₽ из аксессуаров в услуги и 7 ranking repair units в payroll
`PAID_REPAIR`.

| Показатель | Source-linked ожидание | Приложение | Разница | Статус | Причина |
|---|---:|---:|---:|---|---|
| SELLERS total | 413 / 11,433,403 / 10,197,714 / 1,235,689 | 414 / 11,511,383 / 10,248,714 / 1,262,669 | +1 / +77,980 / +51,000 / +26,980 | FAIL | `F000168` не атрибутирован source employee |
| Техника | 162 / 10,650,906 / 10,079,904 / 571,002 | 163 / 10,728,886 / 10,130,904 / 597,982 | +1 / +77,980 / +51,000 / +26,980 | FAIL | `F000168` |
| Аксессуары | 191 / 433,190 / 95,810 / 337,380 | 194 / 451,190 / 106,810 / 344,380 | +3 / +18,000 / +11,000 / +7,000 | FAIL | 3 order works |
| Услуги | 60 / 349,307 / 22,000 / 327,307 | 57 / 331,307 / 11,000 / 320,307 | −3 / −18,000 / −11,000 / −7,000 | FAIL | те же 3 order works |

`F000148` и `F000175` изменяют только `STORE` и residual non-ranking employee scope. Четыре
existing orphan returns изменяют `UNASSIGNED`, но в `SELLERS` входит только `F000168`.

## Каждый сотрудник

Таблица показывает production и окончательное ожидаемое состояние после двух missing-return
recoveries и четырёх orphan relinks. Классификация не меняет employee total, только
employee/category и employee/payroll cells.

| Employee alias | Ranking | Production qty / revenue / cost / GP | Ожидается после return corrections | Остаток | Статус |
|---|---|---:|---:|---|---|
| `EMP-B802A9C3FDD3` | нет | 1,716 / 48,170,062 / 41,739,898 / 6,430,164 | 1,709 / 47,909,394 / 41,524,798 / 6,384,596 | 2 missing + 3 orphan returns | FAIL_CURRENT |
| `EMP-C92D2C99CC54` | да | 144 / 4,425,839 / 3,994,815 / 431,024 | то же | total exact; classification pending | PASS_TOTAL |
| `EMP-EBC62AF18E20` | да | 2 / 9,000 / 6,000 / 3,000 | то же | total exact; classification pending | PASS_TOTAL |
| `EMP-EE11A56D7AF0` | да | 268 / 7,076,544 / 6,247,899 / 828,645 | 267 / 6,998,564 / 6,196,899 / 801,665 | orphan `F000168` | FAIL_CURRENT |
| `UNASSIGNED` | нет | −4 / −210,450 / −160,050 / −50,400 | 0 / 0 / 0 / 0 | после guarded relink | FAIL_CURRENT |

Raw employee в return XLSX — обработавший возврат. Для 31 загруженного return document он
отличается от сотрудника исходной продажи; это ожидаемая ADR-0001 семантика, а не 31 ошибка.
Ошибками являются только четыре null original links и два отсутствующих документа.

По классификации сотрудников доказаны следующие точные переносы:

- `EMP-EBC62AF18E20`: 1 analytics unit / 5,000 ₽ из `GLASS_IPHONE` в `SETUP_SERVICE`; две
  repair units / 9,000 ₽ / cost 6,000 ₽ в `PAID_REPAIR`;
- `EMP-C92D2C99CC54`: 1 / 5,000 / 2,500 из `GLASS_IPHONE` в `SETUP_SERVICE`; две repair units /
  9,000 ₽ / cost 4,500 ₽ в `PAID_REPAIR`;
- `EMP-EE11A56D7AF0`: 1 cleaning unit / 8,000 / 5,000 из `ACCESSORY_IPAD_MAC / ACCESSORY` в
  `SETUP_SERVICE / SERVICE`; три repair units / 11,290 / 6,500 в `PAID_REPAIR`;
- `EMP-B802A9C3FDD3`: одна repair unit / 4,500 / 2,300 в `PAID_REPAIR`.

## Attach-rate STORE

| Метрика | LiveSklad N/B/rate | Приложение N/B/rate | Разница N/B/п. п. | Статус | Причина |
|---|---:|---:|---:|---|---|
| `ACCESSORY_IPAD` | 5 / 24 / 20.83% | то же | 0 | PASS | cleaning name не входит в iPad numerator |
| `ACCESSORY_PODS_WATCH` | 28 / 116 / 24.14% | то же | 0 | PASS_WITH_LIMIT | zero costs не меняют units |
| `CASE_APPLE_IPHONE` | 257 / 600 / 42.83% | 258 / 601 / 42.93% | +1 / +1 / +0.10 | FAIL | `F000175`, iPhone `F000148` |
| `CASE_SAMSUNG` | 3 / 34 / 8.82% | то же | 0 | PASS | — |
| `CHARGER_CABLE` | 235 / 634 / 37.07% | 235 / 635 / 37.01% | 0 / +1 / −0.06 | FAIL | phone denominator `F000148` |
| `FILM_PHONE` | 36 / 634 / 5.68% | 36 / 635 / 5.67% | 0 / +1 / −0.01 | FAIL | phone denominator `F000148` |
| `GLASS_CAMERA_IPHONE` | 62 / 600 / 10.33% | 62 / 601 / 10.32% | 0 / +1 / −0.01 | FAIL | iPhone denominator `F000148` |
| `GLASS_CAMERA_SAMSUNG` | 2 / 34 / 5.88% | то же | 0 | PASS | — |
| `GLASS_IPHONE` | 216 / 600 / 36.00% | 219 / 601 / 36.44% | +3 / +1 / +0.44 | FAIL | glass return + 2 repair works |
| `GLASS_SAMSUNG` | 8 / 34 / 23.53% | то же | 0 | PASS | — |
| `PREMIUM_PROTECTION` | 0 / 785 / 0.00% | 0 / 786 / 0.00% | 0 / +1 / 0.00 | FAIL_COUNT | device denominator `F000148` |
| `SETUP_SERVICE` | 252 / 645 / 39.07% | 250 / 646 / 38.70% | −2 / +1 / −0.37 | FAIL | transfer return + 3 order works + phone denominator |
| `WARRANTY_GENERIC_NEW` | 53 / 452 / 11.73% | 53 / 453 / 11.70% | 0 / +1 / −0.03 | FAIL | new-phone denominator `F000148` |
| `WARRANTY_GENERIC_USED` | 67 / 182 / 36.81% | то же | 0 | PASS | — |

Production recomputation соответствует сохранённым production facts: internal mismatches 0.
Разница выше возникает между неполными/неверно классифицированными facts и source semantics.

Для `SELLERS` значения ниже окончательные: `F000148/F000175` относятся нерейтинговому сотруднику
и не меняют этот scope. Доказаны следующие изменения:

- `EMP-EBC62AF18E20`: `GLASS_IPHONE` 1/0/null → 0/0/null; `SETUP_SERVICE` 1/0/null → 2/0/null;
- `EMP-C92D2C99CC54`: `GLASS_IPHONE` 16/37/43.24% → 15/37/40.54%;
  `SETUP_SERVICE` 11/45/24.44% → 12/45/26.67%;
- `EMP-EE11A56D7AF0`: после `F000168` и cleaning reclass — `CASE_APPLE_IPHONE`
  40/74/54.05%, `CHARGER_CABLE` 30/77/38.96%, `FILM_PHONE` 4/77/5.19%,
  `GLASS_CAMERA_IPHONE` 6/74/8.11%, `GLASS_IPHONE` 30/74/40.54%,
  `PREMIUM_PROTECTION` 0/97/0%, `SETUP_SERVICE` 26/78/33.33%,
  `WARRANTY_GENERIC_USED` 11/21/52.38%;
- у остальных seller metric cells изменений нет.

## Построчная проверка, границы и округление

2,226 XLSX detail rows превращаются в 2,214 ожидаемых facts после консолидации 12 повторных
occurrences одинакового товара внутри документа. Production содержит 2,210 facts из-за четырёх
missing return positions. Все 12 консолидаций имеют exact quantity/amount/cost:

| Документ | Сводимая группа | XLSX rows → app rows | Qty | Amount | Статус |
|---|---|---:|---:|---:|---|
| `B002822` | iPhone 17 Pro Max | 3 → 1 | 3 | 313,470 | EXPLAINED_AGGREGATION |
| `B002823` | блок Baseus 30W | 2 → 1 | 2 | 8,000 | EXPLAINED_AGGREGATION |
| `B002844` | iPhone 17 Pro | 2 → 1 | 2 | 206,980 | EXPLAINED_AGGREGATION |
| `B002959` | iPhone 17 | 2 → 1 | 2 | 133,000 | EXPLAINED_AGGREGATION |
| `B003011` | стекло Remax iPhone | 2 → 1 | 2 | 2,990 | EXPLAINED_AGGREGATION |
| `B003053` | блок Baseus 30W | 2 → 1 | 2 | 7,980 | EXPLAINED_AGGREGATION |
| `B003167` | стекло Remax iPhone | 2 → 1 | 2 | 3,980 | EXPLAINED_AGGREGATION |
| `B003346` | стекло SupGlass iPhone Pro | 2 → 1 | 2 | 2,000 | EXPLAINED_AGGREGATION |
| `B003346` | стекло SupGlass iPhone Pro Max | 2 → 1 | 2 | 2,000 | EXPLAINED_AGGREGATION |
| `B003513` | iPhone 17 | 2 → 1 | 2 | 132,980 | EXPLAINED_AGGREGATION |
| `B003554` | iPhone 16 Pro Max | 2 → 1 | 2 | 173,980 | EXPLAINED_AGGREGATION |

Одна source rename у `B003299` (`PlayStation 5 Slim ... 1 rev.` против snapshot без suffix) имеет
exact product group, quantity 1, amount 46,000 и cost; ambiguity 0.

Все 898 общих timestamps в XLSX на 59–60 минут позже production local representation: 879 × 59
минут из-за обрезанных секунд, 19 × 60 минут. Ни одна дата не пересекает границу марта;
финансовый эффект 0. Округлительных расхождений не найдено.

## ZERO_UNEXPECTED

Четыре товарные позиции имеют cost 0 и точно такой же cost в XLSX. Пользователь ранее подтвердил,
что нулевая себестоимость допускается. Они не создают межсистемную дельту, но абсолютная GP зависит
от качества исходных данных:

| Документ | Позиция | Qty | Revenue | Cost | Категория | Статус |
|---|---|---:|---:|---:|---|---|
| `B002870` | ремешок «миланская петля» | 1 | 1,310 | 0 | `ACCESSORY_PODS_WATCH` | PASS_WITH_LIMIT |
| `B003269` | ремешок «миланская петля» | 1 | 995 | 0 | `ACCESSORY_PODS_WATCH` | PASS_WITH_LIMIT |
| `B003483` | кабель USB/Type-C | 1 | 1,000 | 0 | `CHARGER_CABLE` | PASS_WITH_LIMIT |
| `B003580` | ремешок «миланская петля» | 1 | 1,490 | 0 | `ACCESSORY_PODS_WATCH` | PASS_WITH_LIMIT |

`ZERO_UNEXPECTED` влияет на quality flag и на абсолютную себестоимость/GP, но не удаляет строку,
не меняет revenue, units, категории или attach-rate.

## Классификация причин

| Класс причины | Итог | Доказательство |
|---|---|---|
| Неполное покрытие синхронизации | обнаружено | 2 only-Live returns / 4 positions |
| Пропущенный или скрытый возврат | обнаружено | `F000148`, `F000175`; merchandise есть, payment 0 |
| Неверная классификация | обнаружена | 3 analytics и 8 payroll order-work assignments |
| Неправильный магазин или сотрудник | store error — нет; employee error — да | exact parent/employee links доказаны для всех 6 проблемных returns |
| Отличие формул | только merchandise vs cash и order docs vs positions | payment ledger exact; 8 orders / 9 works explained |
| Отличие границ периода | effect 0 | 59–60-minute presentation offset без перехода даты |
| Дубликат или удаление | не обнаружено | 12 exact aggregations; deleted facts 0 |
| Ошибка исходных данных LiveSklad | финансовая ошибка не доказана | 4 разрешённых zero costs — quality limitation |
| Ошибка приложения | доказана | cash-only return discovery, lost original links, source-kind/payroll priority |

## Безопасный план после закрытия source evidence gate

Source evidence gate закрыт: exact IDs, type, store, date, parent, original positions, employees,
quantity, amount и cost подтверждены, а parents/items проверены в production.

1. `F000148` recovery и independent post-check выполнены; повтор запрещён.
2. Для zero-net `F000175` локально подготовлены non-negative recovery contract, forward migration
   V51 и тесты; выполнить review и batch deployment. После deployment выполнить один exact
   `MISSING_RETURN` recovery и independent post-check; ложную положительную сумму не использовать.
3. Четыре существующих orphan returns не восстанавливать как missing и не править SQL вручную.
   После согласованного batch deployment режима `EXISTING_ORPHAN_RELINK` выполнить четыре exact
   guarded relink по одному, с preflight и post-check каждого документа.
4. Отдельным bounded correction исправить только мартовские девять order positions: три analytics
   assignments и восемь `PAID_REPAIR`; `A000261` оставить payroll `SERVICE`. До записи сверить exact
   IDs, amounts, cost, source kind, отсутствие пересечений assignments и locked payroll snapshots.
5. После всех операций повторить полный мартовский audit: `STORE`, `SELLERS`, каждый сотрудник,
   analytics/payroll categories, documents/items, payments и attach-rate. Нулевая либо объяснённая
   разница обязательна до смены verdict.

Широкий backfill сейчас не является безопасным исправлением: он не находит cash-less returns, не
восстанавливает уже потерянные parent links без guarded mode и может повторно затронуть весь месяц.

## Ответы по текущему состоянию после `F000148`

1. **Совпало точно:** чистая выручка `STORE`, все 856 прямых продаж, восемь orders/девять works и
   их суммы, payment ledger и восстановленный `F000148` со всеми тремя original links.
2. **Не совпало:** STORE cost/GP/margin/quantity из-за `F000175`; четыре orphan attributions;
   структура, analytics/payroll categories, employee и `SELLERS` KPI, attach-rate; также 3/8
   order-work assignments. Source attribution всех шести проблемных returns доказана.
3. **Документы текущей разницы:** финансовая — `F000175`; employee attribution — `F000144`,
   `F000146`, `F000156`, `F000168`; classification — `A000221`, `A000240`, `A000256`, `A000261`,
   `A000276`, `A000282`, `A000290`, `A000302`.
4. **Первопричина:** cash-less returns недоступны cash-transaction polling path; zero-net return
   дополнительно не поддержан recovery contract; четыре ранее загруженных returns потеряли
   parent/item links; source-kind service имеет недостаточный приоритет в deployed classifier, а
   payroll ремонта не назначается автоматически.
5. **Необходимые исправления:** non-negative recovery contract и exact recovery `F000175`, четыре
   guarded orphan relink и bounded March classification correction. `F000148` повторять нельзя.
6. **Повторная синхронизация:** широкий resync не требуется и не решит причины. После точечных
   операций нужен полный read-only повторный audit.
7. **Можно ли доверять марту:** чистой выручке `STORE`, direct sales, order finance и payment
   ledger — да. STORE quantity/cost/GP, `SELLERS`, employee, category, payroll и attach-rate пока
   не окончательны. После всех исправлений останется только оговорка о четырёх разрешённых
   исходных zero costs.
