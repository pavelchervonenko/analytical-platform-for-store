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
verdict_scope: "МАГАЗИН, 2026-01-01..2026-01-31 inclusive, Europe/Kaliningrad; STORE and SELLERS monetary metrics are exact, four loaded returns have lost original-sale attribution, and four paid repairs are assigned to payroll SERVICE."
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
  - targeted read-only LiveSklad API verification of four returns and their original sales
  - exact document, item, employee, category, payroll and attach-rate reconstruction
  - current application formulas, classification rules and recovery guards
required_reviewers:
  - product
  - operations
---

# Сверка LiveSklad ↔ production: январь 2026, МАГАЗИН

## Текущий вердикт

`FAIL_ACTION_REQUIRED`, но не из-за итогов магазина. Денежные и товарные показатели `STORE`
совпадают с LiveSklad: 1,990 ед., 57,588,024.94 ₽ чистой выручки, 48,773,342.00 ₽
себестоимости и 8,814,682.94 ₽ валовой прибыли. Совпадают 840/840 документов и 2,087/2,087
нормализованных товарных групп; value mismatches = 0.

Остались две доказанные ошибки приложения:

1. Возвраты `F000061`, `F000066`, `F000086`, `F000096` правильно входят в `STORE`, но имеют
   null original document/item links и атрибутированы `UNASSIGNED`. LiveSklad API подтвердил
   указанные пользователем parent sales и исходного сотрудника для каждой позиции. Production SQL
   подтвердил, что все четыре parent sale отсутствуют в БД. Ошибка меняет employee-разрез, но не
   `STORE` и не `SELLERS`, потому что исходный сотрудник не участвует в рейтинге.
2. Четыре платные работы `A000134`, `A000158`, `A000166`, `A000169` должны быть зарплатной
   категорией `PAID_REPAIR`, но production относит их к `SERVICE`. Их аналитическая категория
   `SETUP_SERVICE` уже верна.

Никакие исправления января не выполнялись. Таблицы ожидаемого состояния построены локальной
детерминированной симуляцией, а не post-commit проверкой. Production остаётся на release
`v0.1.0-pilot.31` и schema `48`; накопленные локальные recovery/classification changes не
развёрнуты.

## Scope, фильтры и источники

| Параметр | Зафиксированное значение | Доказательство |
|---|---|---|
| Магазин | `МАГАЗИН` | выбор пользователя и содержимое трёх файлов |
| Период | `2026-01-01..2026-01-31`, обе даты включительно | XLSX и production `business_date` |
| Business timezone | `Europe/Kaliningrad`, начало дня `00:00` | production store/period contract |
| «Товары и работы» | магазин `МАГАЗИН`; январь; `Продажа`, `Возврат`, `Установка в заказ`; orders `Выдан` | 2,091 detail rows |
| «Продажи» | магазин `МАГАЗИН`; январь; продажи и возвраты продаж | 836 document rows + total |
| «Заказы» | магазин `МАГАЗИН`; дата выдачи в январе; статус `Выдан` | 7 orders + total |

Полные UI-параметры LiveSklad не встроены в XLSX как metadata. Поэтому зафиксированы фильтры,
сообщённые пользователем, а фактический scope проверен по всем датам, типам, статусам, магазинам и
номерам документов внутри файлов.

| Файл | Полезные строки | Размер | SHA-256 |
|---|---:|---:|---|
| `Отчёт по товарам и работам январь.xlsx` | 2,091 + total | 907,261 B | `6ed360eb927e32125a87832c13829f0918c659b17d607289dc7e1b1750d30a6f` |
| `Отчёт по продажам январь.xlsx` | 836 + total | 399,268 B | `2b5941e59406cfb5a1cff855ec87a38f75b9ef9be9f4e482e16d5f6fbd573449` |
| `Отчёт по заказам январь.xlsx` | 7 + total | 22,007 B | `d4e41ab4c4ff9dd39a1be89fc704b89bad695fe4b6e49324be656b60507f8ad3` |

Свежий production audit выполнен существующими SSH key, agent socket и Windows TCP proxy; новые
ключи не создавались. SQL выполнялся read-only и завершился `ROLLBACK`. Точечный LiveSklad API
audit прочитал только четыре exact returns, их четыре parent sales и нужные employee records.
Одноразовый скрипт не устанавливался, `sudoers` не менялся; после выполнения все пять временных
production-файлов удалены и отсутствие постоянных файлов проверено. Секреты, payloads и персональные
данные в этот документ не включены.

Контрольные SHA-256 обезличенных локальных артефактов: production snapshot
`e9bd0961b35127b5169464cee8607b52e62fa15520d77ebd9fc004260a2e3d03`, source audit
`3fdf104c3892b1866003d392496d1c566582108015113f89fa17dee24a8839a5`, итоговый verifier
`4f2da86065965e4ff17a9209dd7684bbb42117b1d4112d766b024d392384ee64`.

## Семантика сравнения

- знак факта: `SALE=+1`, `RETURN=-1`; GP = revenue − cost;
- `STORE` включает все факты магазина, `SELLERS` — только сотрудников с active assignment и
  `participates_in_ranking=true`;
- возврат относится сотруднику исходной продажи, а не сотруднику, оформившему возврат;
- аналитическая и зарплатная классификация считаются отдельно;
- аксессуары и услуги классифицированы по подтверждённой заказчиком смысловой семантике;
- платный ремонт: analytics `SETUP_SERVICE`, payroll `PAID_REPAIR`;
- attach-rate использует чистые item units с отдельными числителем и знаменателем;
- товарный возврат и движение денег не смешиваются;
- issued order count и число его ненулевых work facts — разные показатели;
- нулевая себестоимость допустима по решению пользователя.

## Полнота синхронизации и проверка до позиции

- backfill за январь завершён `SUCCESS`: 416 steps, 27 retries;
- sales: 795/795 документов, 2,069/2,069 ед., 60,128,913.94 ₽ revenue и
  50,822,260.00 ₽ cost — exact;
- returns: 41/41 документов, −83/−83 ед., −2,567,889.00 ₽ revenue и
  −2,060,118.00 ₽ cost — exact;
- issued orders: 7; четыре ненулевые работы присутствуют и совпадают, три нулевых заказа
  `A000105`, `A000106`, `A000108` ожидаемо не создают item facts;
- document set: 840/840, only-LiveSklad = 0, only-app = 0, document value mismatches = 0;
- product groups: 2,087/2,087, only-source/app = 0, value mismatches = 0, rename/ambiguity = 0;
- deleted documents/items, missing cost, `ZERO_UNEXPECTED`, `UNMAPPED`, `EXCLUDE` = 0;
- из 41 returns 37 имеют original links, четыре являются orphan returns;
- ожидаемая attribution coverage 41/41, текущая 37/41.

Исторические failed/partial runs присутствуют, но сами по себе не определяют полноту: финальная
полнота доказана exact sets и values. Все 840 XLSX timestamps отличаются от приложения на 59–60
минут: XLSX показывает время на час позже `Europe/Kaliningrad`, секунды в XLSX усечены. Ни один
документ не пересёк границу business date, поэтому месячный scope и итоги не изменились.

В исходном detail есть три шаблона дословно повторённых строк. `B001558` (2 строки стекла,
2 ед., 5,980 ₽) и `B001892` (2 строки стекла, 2 ед., 4,980 ₽) нормализованы приложением до одной
строки каждая; quantity/revenue/cost не изменены. У `B001374` две визуально одинаковые строки имеют
разные source item IDs и поэтому корректно остаются двумя facts. Отсюда raw row count 2,091 против
2,089, но не расхождение товарных единиц.

## STORE: итог месяца

Во всех таблицах разница равна `Приложение − LiveSklad`.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка | 57,588,024.94 ₽ | 57,588,024.94 ₽ | 0.00 ₽ | 0.00% | PASS | — | 840 exact documents |
| Себестоимость | 48,773,342.00 ₽ | 48,773,342.00 ₽ | 0.00 ₽ | 0.00% | PASS | — | 2,087 exact product groups |
| Валовая прибыль | 8,814,682.94 ₽ | 8,814,682.94 ₽ | 0.00 ₽ | 0.00% | PASS | — | revenue − cost |
| Валовая маржа | 15.3065% | 15.3065% | 0.0000 п. п. | 0.00% | PASS | — | единое финальное округление |
| Чистое количество | 1,990 ед. | 1,990 ед. | 0 ед. | 0.00% | PASS | — | item reconciliation |
| Продажи | 795 док. / 2,069 ед. / 60,128,913.94 ₽ | то же | 0 | 0.00% | PASS | — | 795 exact documents |
| Возвраты продаж | 41 док. / −83 ед. / −2,567,889.00 ₽ | то же | 0 | 0.00% | PASS | — | все returns загружены |
| Возвраты заказов | 0 | 0 | 0 | — | PASS | отсутствуют | orders + production |
| Выданные заказы | 7 orders; 4 works / 4 ед. / 27,000.00 ₽ | 4 persisted work facts / 4 ед. / 27,000.00 ₽ | 3 zero orders не создают facts; value 0 | 0.00% | PASS | разные уровни подсчёта | exact order decomposition |
| Аксессуары | 938 ед. / 2,547,318.00 ₽ | то же | 0 | 0.00% | PASS | — | analytics category reconstruction |
| Услуги | 346 ед. / 1,747,533.94 ₽ | то же | 0 | 0.00% | PASS | — | paid repairs остаются analytics services |
| Допы | 1,284 ед. / 4,294,851.94 ₽ | то же | 0 | 0.00% | PASS | — | accessories + services |

Sales-only merchandise total без order works равен 57,561,024.94 ₽, cost 48,762,142.00 ₽,
GP 8,798,882.94 ₽ и также совпадает между двумя LiveSklad reports и production.

## Движение денег отдельно от товара

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Signed payments | 57,544,724.94 ₽ | 57,544,724.94 ₽ | 0.00 ₽ | 0.00% | PASS | — | sales report `Оплачено` ↔ payment ledger |
| Merchandise без orders | 57,561,024.94 ₽ | 57,561,024.94 ₽ | 0.00 ₽ | 0.00% | PASS | — | sales/goods/SQL |
| Cash − merchandise | −16,300.00 ₽ | −16,300.00 ₽ | 0.00 ₽ | 0.00% | PASS_EXPLAINED | две продажи оплачены не на всю сумму | exact documents |

Дельта состоит только из `B001691`: merchandise 194,999 ₽, paid 179,999 ₽, разница −15,000 ₽;
и `B001529`: merchandise 117,290 ₽, paid 115,990 ₽, разница −1,300 ₽. Все 41 строки возврата
имеют 0 в отдельной колонке `Возврат`, но их signed `Оплачено` равно −2,567,889 ₽. Поэтому ноль в
колонке `Возврат` не означает отсутствие товарного или денежного возврата.

## Четыре существующих orphan returns

Все четыре документа и позиции уже учтены в `STORE` с правильными знаками и значениями. Ошибка
ограничена original links и employee attribution. Source API violations = 0.

| Return / parent | Return item / original item | Analytics / payroll | Qty | Revenue | Cost | GP | Production сейчас |
|---|---|---|---:|---:|---:|---:|---|
| `F000061` / `B000975` | `6957a26f214c110b850ba83d` / `694e6f963ec5fb85666049cc` | `IPHONE_NEW_ASIS / TECH_TIER_1` | −1 | −100,990 | −92,800 | −8,190 | `UNASSIGNED`, links null |
| `F000066` / `B001238` | `6960fd73096f1a712dce62a1` / `69550817e491ff5af39833fd` | `IPHONE_NEW_ASIS / TECH_TIER_1` | −1 | −113,990 | −106,000 | −7,990 | `UNASSIGNED`, links null |
| `F000086` / `B001181` | `69711ea4f0a757d3535c8d5e` / `6953c67d1f6ee48026553a6e` | `IPHONE_USED / TECH_TIER_1` | −1 | −34,990 | −25,000 | −9,990 | `UNASSIGNED`, links null |
| `F000096` / `B000304` | `6979db31fe1a2598655b8ba5` / `692d6779d4ab6d5c0dd3dd23` | `IPHONE_USED / TECH_TIER_1` | −1 | −47,990 | −57,000 | +9,010 | `UNASSIGNED`, links null |
| **Итого** | 4 positions | — | **−4** | **−297,960** | **−280,800** | **−17,160** | FAIL |

LiveSklad API подтвердил parent number, parent external ID, тип `sale`, тот же магазин, original
position ID, product ID и исходного сотрудника `EMP-B802A9C3FDD3` для всех четырёх строк. SQL
вернул `document_count=0`, `active_item_count=0` для каждого parent. External-ID creation timestamps
указывают на декабрь 2025 (`B000304` — 1 декабря; `B000975` — 26 декабря; `B001181` — 30 декабря;
`B001238` — 31 декабря), но это техническая дата идентификатора, не сохранённая API business date;
перед исправлением exact business dates надо подтвердить повторным read-only preflight.

## STORE и SELLERS не смешиваются

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| STORE quantity | 1,990 | 1,990 | 0 | 0.00% | PASS | — | full facts |
| STORE revenue | 57,588,024.94 ₽ | 57,588,024.94 ₽ | 0.00 ₽ | 0.00% | PASS | — | full facts |
| STORE cost | 48,773,342.00 ₽ | 48,773,342.00 ₽ | 0.00 ₽ | 0.00% | PASS | — | full facts |
| STORE GP | 8,814,682.94 ₽ | 8,814,682.94 ₽ | 0.00 ₽ | 0.00% | PASS | — | full facts |
| SELLERS quantity | 589 | 589 | 0 | 0.00% | PASS | orphan employee не рейтинговый | ranking roster + API |
| SELLERS revenue | 16,743,312.00 ₽ | 16,743,312.00 ₽ | 0.00 ₽ | 0.00% | PASS | то же | three ranking employees |
| SELLERS cost | 14,367,896.00 ₽ | 14,367,896.00 ₽ | 0.00 ₽ | 0.00% | PASS | то же | three ranking employees |
| SELLERS GP | 2,375,416.00 ₽ | 2,375,416.00 ₽ | 0.00 ₽ | 0.00% | PASS | то же | revenue − cost |
| SELLERS margin | 14.1873% | 14.1873% | 0.0000 п. п. | 0.00% | PASS | — | scope recomputation |

## Каждый сотрудник

Здесь LiveSklad semantics уже применяет возврат к сотруднику исходной продажи. Employee в самой
строке XLSX возврата является оформителем и не использовался как источник attribution.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| `EMP-B802A9C3FDD3` (не ranking) | 1,401 / 40,844,712.94 / 34,405,446 / 6,439,266.94 | 1,405 / 41,142,672.94 / 34,686,246 / 6,456,426.94 | +4 / +297,960 / +280,800 / +17,160 | +0.73% revenue | FAIL | четыре его returns находятся в `UNASSIGNED` | source API + SQL |
| `EMP-C92D2C99CC54` (ranking) | 239 / 6,923,266 / 6,060,024 / 863,242 | то же | 0 | 0.00% | PASS | — | exact employee reconstruction |
| `EMP-EBC62AF18E20` (ranking) | 2 / 15,000 / 8,700 / 6,300 | то же | 0 | 0.00% | PASS | — | exact employee reconstruction |
| `EMP-EE11A56D7AF0` (ranking) | 348 / 9,805,046 / 8,299,172 / 1,505,874 | то же | 0 | 0.00% | PASS | — | exact employee reconstruction |
| `UNASSIGNED` | 0 | −4 / −297,960 / −280,800 / −17,160 | −4 / −297,960 / −280,800 / −17,160 | — | FAIL | те же четыре orphan returns | exact balancing delta |

Формат ячеек: `qty / revenue / cost / GP`. У `EMP-C92D2C99CC54` source raw rows 244 против 242
app rows из-за нормализации `B001558/B001892`; все четыре бизнес-значения exact.

## Аналитические категории

Все категории совпали по quantity, revenue, cost и GP. Разница в raw row count `GLASS_IPHONE`
равна −2 и полностью объяснена двумя консолидациями без изменения значений.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| `ACCESSORY_PODS_WATCH` | 11 / 9,639 / 1,860 / 7,779 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `CASE_APPLE_IPHONE` | 50 / 11,690 / 3,215 / 8,475 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `CASE_SAMSUNG` | 3 / 15,490 / 4,100 / 11,390 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `CHARGER_CABLE` | 231 / 921,960 / 227,820 / 694,140 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `FILM_PHONE` | 26 / 60,440 / 1,300 / 59,140 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `GLASS_CAMERA_IPHONE` | 84 / 281,860 / 52,300 / 229,560 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `GLASS_CAMERA_SAMSUNG` | 3 / 10,990 / 1,650 / 9,340 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `GLASS_IPHONE` | 203 / 593,010 / 43,480 / 549,530 | то же | 0 | 0.00% | PASS_EXPLAINED | 212 source rows → 210 app rows | exact source IDs and grouped values |
| `GLASS_SAMSUNG` | 2 / 5,990 / 425 / 5,565 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `IPAD_MAC` | 5 / 412,760 / 386,600 / 26,160 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `IPHONE_NEW_ASIS` | 417 / 41,641,049 / 38,922,304 / 2,718,745 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `IPHONE_USED` | 168 / 8,075,570 / 6,139,840 / 1,935,730 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `OTHER_ACCESSORY_PRODUCT` | 325 / 636,249 / 166,158 / 470,091 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `PODS_WATCH_OTHER_DEVICE` | 108 / 2,549,707 / 2,241,190 / 308,517 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `PREMIUM_PROTECTION` | 2 / 42,060 / 0 / 42,060 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `SAMSUNG_NEW` | 8 / 614,087 / 569,900 / 44,187 | то же | 0 | 0.00% | PASS | — | item reconstruction |
| `SETUP_SERVICE` | 274 / 944,900 / 11,200 / 933,700 | то же | 0 | 0.00% | PASS | paid repairs analytics correct | item reconstruction |
| `WARRANTY_GENERIC` | 70 / 760,573.94 / 0 / 760,573.94 | то же | 0 | 0.00% | PASS | — | item reconstruction |

## Зарплатные категории

| Показатель | LiveSklad/customer semantics | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| `ACCESSORY` | 938 / 2,547,318 / 502,308 / 2,045,010 | то же | 0 | 0.00% | PASS | — | exact semantic reconstruction |
| `PAID_REPAIR` | 4 / 27,000 / 11,200 / 15,800 | 0 / 0 / 0 / 0 | −4 / −27,000 / −11,200 / −15,800 | −100.00% | FAIL | четыре ремонта отнесены в `SERVICE` | exact order positions |
| `SERVICE` | 342 / 1,720,533.94 / 0 / 1,720,533.94 | 346 / 1,747,533.94 / 11,200 / 1,736,333.94 | +4 / +27,000 / +11,200 / +15,800 | +1.57% revenue | FAIL | зеркальная дельта `PAID_REPAIR` | exact order positions |
| `TECH_TIER_1` | 596 / 50,691,986 / 45,974,544 / 4,717,442 | то же | 0 | 0.00% | PASS | — | exact semantic reconstruction |
| `TECH_TIER_2` | 110 / 2,601,187 / 2,285,290 / 315,897 | то же | 0 | 0.00% | PASS | — | exact semantic reconstruction |

| Документ и работа | Employee | Revenue | Cost | Analytics | Payroll: current → expected | Статус |
|---|---|---:|---:|---|---|---|
| `A000134`, замена дисплея | `EMP-EBC62AF18E20` | 10,000 | 6,500 | `SETUP_SERVICE` | `SERVICE → PAID_REPAIR` | FAIL |
| `A000158`, ремонт ОЗУ iPhone | `EMP-C92D2C99CC54` | 8,000 | 0 | `SETUP_SERVICE` | `SERVICE → PAID_REPAIR` | FAIL |
| `A000166`, замена аккумулятора | `EMP-B802A9C3FDD3` | 4,000 | 2,500 | `SETUP_SERVICE` | `SERVICE → PAID_REPAIR` | FAIL |
| `A000169`, замена аккумулятора | `EMP-EBC62AF18E20` | 5,000 | 2,200 | `SETUP_SERVICE` | `SERVICE → PAID_REPAIR` | FAIL |

Нулевая себестоимость `A000158` допустима по явному решению пользователя и не является
`ZERO_UNEXPECTED`.

## Структура продаж

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| `ACCESSORIES` | 938 / 2,547,318 / 502,308 / 2,045,010 | то же | 0 | 0.00% | PASS | — | categories |
| `DEVICES` | 706 / 53,293,173 / 48,259,834 / 5,033,339 | то же | 0 | 0.00% | PASS | — | categories |
| `SERVICES` | 346 / 1,747,533.94 / 11,200 / 1,736,333.94 | то же | 0 | 0.00% | PASS | — | categories |

Полноценная техника не входит в accessories. `DOPS = ACCESSORIES + SERVICES`; это намеренно
пересекающийся бизнес-срез, а не дополнительная взаимоисключающая группа.

## Attach-rate: STORE и каждый рейтинговый продавец

Каждая ячейка ниже имеет формат `numerator / denominator / rate`; LiveSklad reconstruction и
production совпадают. `—` означает undefined rate при denominator = 0, а не 0%.

| Метрика | STORE | `EMP-C92D2C99CC54` | `EMP-EBC62AF18E20` | `EMP-EE11A56D7AF0` | Статус |
|---|---:|---:|---:|---:|---|
| `ACCESSORY_IPAD` | 0/1/0.00% | 0/1/0.00% | 0/0/— | 0/0/— | PASS |
| `ACCESSORY_PODS_WATCH` | 11/107/10.28% | 2/21/9.52% | 0/0/— | 2/21/9.52% | PASS |
| `CASE_APPLE_IPHONE` | 50/585/8.55% | 12/68/17.65% | 0/0/— | 9/105/8.57% | PASS |
| `CASE_SAMSUNG` | 3/8/37.50% | 0/0/— | 0/0/— | 0/1/0.00% | PASS |
| `CHARGER_CABLE` | 231/593/38.95% | 16/68/23.53% | 0/0/— | 55/106/51.89% | PASS |
| `FILM_PHONE` | 26/593/4.38% | 5/68/7.35% | 0/0/— | 3/106/2.83% | PASS |
| `GLASS_CAMERA_IPHONE` | 84/585/14.36% | 8/68/11.76% | 0/0/— | 13/105/12.38% | PASS |
| `GLASS_CAMERA_SAMSUNG` | 3/8/37.50% | 0/0/— | 0/0/— | 0/1/0.00% | PASS |
| `GLASS_IPHONE` | 203/585/34.70% | 35/68/51.47% | 0/0/— | 37/105/35.24% | PASS |
| `GLASS_SAMSUNG` | 2/8/25.00% | 0/0/— | 0/0/— | 1/1/100.00% | PASS |
| `PREMIUM_PROTECTION` | 0/705/0.00% | 0/92/0.00% | 0/0/— | 0/127/0.00% | PASS |
| `SETUP_SERVICE` | 274/596/45.97% | 28/70/40.00% | 2/0/— | 31/106/29.25% | PASS |
| `WARRANTY_GENERIC_NEW` | 39/425/9.18% | 2/51/3.92% | 0/0/— | 7/68/10.29% | PASS |
| `WARRANTY_GENERIC_USED` | 33/168/19.64% | 0/17/0.00% | 0/0/— | 3/38/7.89% | PASS |

Итого exact: STORE 14/14 cells, SELLERS 42/42 cells; classification issue items = 0.

## Первопричины и безопасное исправление

| Найденное | Класс причины | Установленная первопричина | Безопасное действие |
|---|---|---|---|
| 4 orphan returns | неполное покрытие синхронизации | parent sales существуют в LiveSklad, но отсутствуют в production; без parent document/item приложение не может сохранить original links и employee attribution | подтвердить exact business dates; отдельно согласовать загрузку четырёх parent sales; затем deploy проверенного `EXISTING_ORPHAN_RELINK` и выполнить четыре независимых exact relink с pre/post evidence |
| 4 repairs в payroll `SERVICE` | неверная классификация / ошибка приложения | текущий payroll classifier не отделяет платные ремонты от обычных services | deploy накопленного правила `PAID_REPAIR`, точечно reclassify четыре items, повторить payroll и employee audit |
| XLSX время +1 час | отличие представления времени | export timezone отличается от store business timezone | код не менять; контролировать business date, особенно границы месяца |
| 2 объединённые пары строк | дубликат/нормализация представления | одинаковый document/product представлен двумя source rows, приложение хранит агрегированный fact | код/БД не менять; сравнивать quantity и value по product group |
| cash − merchandise −16,300 ₽ | отличие формул | две продажи оплачены меньше merchandise amount | не исправлять без отдельного бизнес-основания; payment ledger совпадает с report |

Обычный повтор январского backfill сам по себе недостаточен: нужные parent sales находятся вне
январского покрытия, а production schema 48 ещё не содержит подготовленный guarded orphan-relink.
Runbook требует, чтобы parent sales/items были активны и находились в том же магазине до relink.

## Симуляция ожидаемого состояния

После локальной симуляции четырёх attribution relinks и четырёх payroll reclassifications:

- STORE delta = 0 по quantity/revenue/cost/GP;
- SELLERS delta = 0;
- каждый employee delta = 0;
- каждая analytics и payroll category delta = 0;
- commercial structure delta = 0;
- STORE/SELLERS attach numerator, denominator и rate delta = 0;
- `correctedNonzero = []`.

Это проверяет математическую достаточность предложенного набора действий, но не заменяет повторную
сверку production после фактического исправления.

## Итог

1. **Совпало точно:** STORE totals, sales/returns/order values, signed payments, SELLERS totals,
   аналитические категории, структура продаж, accessories/services/dops, все 56 attach cells и
   три рейтинговых employee totals.
2. **Не совпало:** employee attribution четырёх returns и payroll category четырёх paid repairs.
3. **Документы разницы:** `F000061`, `F000066`, `F000086`, `F000096`; `A000134`, `A000158`,
   `A000166`, `A000169`.
4. **Первопричина:** отсутствующие parent sales в production и недостаточная payroll-классификация
   платных ремонтов.
5. **Исправления:** сначала загрузить и проверить exact parent sales, затем guarded relink; отдельно
   deploy/reclassify `PAID_REPAIR`; после каждого изменения повторить full reconciliation.
6. **Повторная синхронизация:** для январских сумм не нужна; для attribution нужна загрузка parent
   sales из предшествующего периода и повторная обработка/relink четырёх returns. Один январский
   rerun не поможет.
7. **Доверие:** STORE, SELLERS, analytics, commercial structure и attach-rate января пригодны без
   ограничений по суммам. Нельзя доверять employee totals нерейтингового сотрудника/`UNASSIGNED` и
   payroll `SERVICE/PAID_REPAIR` до исправления и post-fix zero-delta проверки.
