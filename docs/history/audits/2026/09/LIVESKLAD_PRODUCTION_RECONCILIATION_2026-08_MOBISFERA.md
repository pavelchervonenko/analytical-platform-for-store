---
doc_schema: 1
doc_type: evidence
status: historical
owner: product
audience:
  - developer
  - operator
snapshot_date: 2026-09-13
verdict: PASS_WITH_LIMITS
verdict_scope: "МобиСфера, 2026-08-01..2026-08-31 inclusive, Europe/Kaliningrad; read-only LiveSklad XLSX to production reconciliation."
source_of_truth:
  - docs/current/product/business-metrics.md
  - docs/current/product/sales-and-returns.md
  - docs/current/product/classification.md
  - docs/current/product/payroll.md
  - docs/current/product/attach-rate.md
  - docs/decisions/ADR-0001-return-employee-attribution.md
verification_sources:
  - three user-supplied LiveSklad XLSX reports identified by SHA-256 in this record
  - sanitized production read-only SQL snapshot captured on 2026-09-13
  - one sanitized LiveSklad API date-semantics probe
  - production order synchronization implementation inspected at the reconciled revision
required_reviewers:
  - product
  - operations
---

# Сверка LiveSklad ↔ production: август 2026, МобиСфера

## Вердикт

`PASS_WITH_LIMITS`. В области `STORE` чистая выручка, себестоимость, валовая прибыль,
маржа, документы, товарные единицы, продажи, возвраты продаж, позиции выданных заказов,
структура, 20 аналитических и четыре зарплатные категории совпали. Все 535 документов и
1 003 группы `document + product` совпали по типу, количеству, сумме и себестоимости.

В области `SELLERS` итог трёх рейтинговых продавцов также совпал полностью. Ненулевые различия
между двумя продавцами сформированы тремя возвратами: приложение в соответствии с ADR-0001
относит их к сотруднику исходной продажи, а XLSX показывает сотрудника документа возврата.
Это ожидаемое различие семантики, а не потеря или двойной учёт.

Ограничения доверия:

- две продажи имеют `ZERO_UNEXPECTED` по текущей автоматической quality policy. Пользователь
  подтвердил, что нулевая себестоимость допустима; конкретный документ поступления найти не
  удалось. Значение принято как допустимое source value, но его происхождение не доказано;
- XLSX не содержит аналитических и зарплатных категорий, поэтому category tables доказывают
  сохранность и агрегацию действующих production assignments, но не заменяют их независимое
  бизнес-согласование;
- отчёт заказов содержит восемь выданных order records, а приложение хранит шесть
  position-bearing sales facts; два пустых заказа не представлены order-level фактами;
- оплаты продаж сверены точно, но 55 000.00 ₽ оплат выданных заказов не представлены в
  `sales_payments`: order sync намеренно обнуляет/удаляет payments для `orderPosition`;
- XLSX показывает время как UTC+3, тогда как business timezone магазина и production —
  `Europe/Kaliningrad` (UTC+2 в августе); на границы периода это не повлияло.

Никакие backfill/resync, изменения БД, категорий, кода или production не выполнялись.

## Зафиксированный scope и источники

| Поле | Значение | Доказательство |
|---|---|---|
| Магазин | `МобиСфера` | выбор пользователя; один target store в read-only SQL |
| Период | `2026-08-01..2026-08-31`, обе даты включительно | business-date SQL и содержимое XLSX |
| Бизнес-часовой пояс | `Europe/Kaliningrad` | store configuration и period contract |
| Бизнес-день | `00:00..24:00` local | store configuration |
| Production snapshot | 2026-09-13 | sanitized runtime identity хранится только в [current project state](../../../../current/project-state.md) |
| Режим | read-only | транзакция `BEGIN READ ONLY`; временный audit и production-копии удалены после чтения |

| Файл | Наблюдаемый effective filter | Строк данных | Размер | SHA-256 |
|---|---|---:|---:|---|
| `Отчёт по товарам и работам (4).xlsx` | магазин `МобиСфера`; август; `Продажа`, `Возврат`, `Установка в заказ` | 1 007 | 431 502 B | `62c3f8222dcaeaf1a5f6e7bd7a874ddc65db1b7711824527f0b3ea40abd390d8` |
| `Отчёт по продажам (4).xlsx` | магазин `МобиСфера`; август; продажи и sale returns | 529 | 249 074 B | `ca39527e65c8d33e3c53dda09858f17f5b5dbd559c4e7b0e0b45d85eb329849d` |
| `Отчёт по заказам (3).xlsx` | магазин `МобиСфера`; дата выдачи в августе; выданные заказы | 8 | 22 529 B | `47d41a7e8d3865d25690c40cbbe87d9e16d5e4f89bf0a3d1fccca9713bf4396e` |

XLSX не сохраняют все экранные настройки фильтра как машиночитаемые метаданные. Поэтому выше
разделены подтверждённые пользователем параметры и effective filter, доказуемый содержимым.
В orders XLSX есть заказ, созданный 30 июля и выданный 7 августа, что подтверждает фактический
отбор по выдаче, а не только по созданию.

Семантика установлена по колонкам, строкам, production-коду и документации LiveSklad. В отчёте
«Товары и работы» `Себестоимость = Сумма - Валовая прибыль`; продажи имеют положительный знак,
sale returns — отрицательный. Позиции заказа входят по дате добавления позиции, не по дате
выдачи. Отчёт заказов отдельно показывает суммы, оплату, возврат и GP по order record.
Описания LiveSklad: [товары и работы](https://help.livesklad.com/ru/articles/292-%D0%BF%D0%BE%D0%B4%D1%80%D0%BE%D0%B1%D0%BD%D1%8B%D0%B9-%D0%BE%D1%82%D1%87%D1%91%D1%82-%D0%BF%D0%BE-%D1%80%D0%B0%D0%B1%D0%BE%D1%82%D0%B0%D0%BC-%D0%B8-%D1%82%D0%BE%D0%B2%D0%B0%D1%80%D0%B0%D0%BC),
[заказы](https://help.livesklad.com/ru/articles/293-%D0%BE%D1%82%D1%87%D0%B5%D1%82-%D0%BF%D0%BE-%D0%B7%D0%B0%D0%BA%D0%B0%D0%B7%D0%B0%D0%BC-%D0%BE%D0%BF%D0%B8%D1%81%D0%B0%D0%BD%D0%B8%D0%B5),
[валовая прибыль](https://help.livesklad.com/ru/articles/230-%D0%B2%D0%B0%D0%BB%D0%BE%D0%B2%D0%B0%D1%8F-%D0%BF%D1%80%D0%B8%D0%B1%D1%8B%D0%BB%D1%8C).

Customer/contact columns не читались в production-запросах и не включены в evidence.
Сотрудники ниже заменены устойчивыми salted aliases.

## Полнота синхронизации

За август найдены 38 пересекающих период sync jobs. Промежуточные failed/cancelled runs перекрыты
последующими успешными backfill/incremental runs. Начало месяца покрыто успешным backfill до
6 августа, середина — успешным backfill до 19 августа, конец — последовательными трёхдневными
incremental окнами до 2 сентября включительно.

Предметное доказательство полноты:

- 535 из 535 документов найдены с обеих сторон; source-only = 0, app-only = 0;
- 1 003 из 1 003 групп `document + product` сопоставлены; unmatched = 0;
- для всех документов и товарных групп delta quantity/revenue/cost/GP = 0;
- активных удалённых документов/строк в августовском production scope нет;
- все 19 sale returns имеют исходную продажу, тот же магазин и атрибуцию исходному сотруднику;
  orphan/misattributed returns = 0;
- `UNMAPPED = 0`, `EXCLUDE = 0`, missing cost = 0;
- текущие шесть order-position facts совпали с текущим XLSX после возможных прежних изменений
  заказа по документу, позиции, quantity, amount и cost.

Следовательно, поставленные отчётные данные за август синхронизированы полностью. Старые ошибки
запусков остаточной разницы не формируют.

## Итог месяца и магазин: STORE

Разница вычислена как `Приложение - LiveSklad`; процент — относительно LiveSklad.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка | 25 758 754.00 | 25 758 754.00 | 0.00 ₽ | 0.00% | PASS | — | signed sum всех позиций |
| Себестоимость | 22 082 339.93 | 22 082 339.93 | 0.00 ₽ | 0.00% | PASS_WITH_SOURCE_NOTE | два допустимых source zero-cost значения без provenance | line match + quality appendix |
| Валовая прибыль | 3 676 414.07 | 3 676 414.07 | 0.00 ₽ | 0.00% | PASS_WITH_SOURCE_NOTE | рассчитана с допустимыми source zeros | `revenue - cost` |
| Маржа | 14.2725% | 14.2725% | 0.0000 п. п. | 0.00% | PASS_WITH_SOURCE_NOTE | рассчитана из той же GP | `GP / revenue` без промежуточного округления |
| Продаж | 510 | 510 | 0 док. | 0.00% | PASS | — | `sale/SALE` document keys |
| Единиц в продажах | 992 | 992 | 0 ед. | 0.00% | PASS | — | positive sale quantity |
| Возвратов продаж | 19 | 19 | 0 док. | 0.00% | PASS | — | `saleReturn/RETURN` keys |
| Единиц в возвратах продаж | -29 | -29 | 0 ед. | 0.00% | PASS | — | signed return quantity |
| Выручка возвратов продаж | -981 030.00 | -981 030.00 | 0.00 ₽ | 0.00% | PASS | возврат товара отделён от оплаты | 19 documents |
| Возвратов заказов | 0 | 0 | 0 док. | — | PASS | тип и order-return money отсутствуют | XLSX return total + production facts |
| Позиций выданных заказов | 6 | 6 | 0 ед. | 0.00% | PASS | включая нулевые позиции | six `orderPosition` documents |
| Выручка позиций заказов | 55 000.00 | 55 000.00 | 0.00 ₽ | 0.00% | PASS | — | six position groups |
| Выданных заказов как записей | 8 | 6 | -2 док. | -25.00% | EXPLAINED | два заказа без позиций | orders appendix |
| Чистых товарных единиц | 969 | 969 | 0 ед. | 0.00% | PASS | `992 - 29 + 6` | all positions |
| Документов с sales facts | 535 | 535 | 0 док. | 0.00% | PASS | — | exact document-key sets |
| Строк товаров/работ | 1 007 | 1 003 | -4 строки | -0.40% | EXPLAINED | четыре пары одинаковых строк объединены в quantity 2 | row-structure appendix |
| Групп документ + товар | 1 003 | 1 003 | 0 групп | 0.00% | PASS | три переименования сопоставлены однозначно | amount/cost/qty/type match |

Проверка компонентов итога:

| Тип | Документы | Единицы | Выручка | Себестоимость | Валовая прибыль | Разница приложения |
|---|---:|---:|---:|---:|---:|---:|
| Прямые продажи | 510 | 992 | 26 684 784.00 | 22 870 672.93 | 3 814 111.07 | 0.00 |
| Sale returns | 19 | -29 | -981 030.00 | -832 633.00 | -148 397.00 | 0.00 |
| Позиции заказов | 6 | 6 | 55 000.00 | 44 300.00 | 10 700.00 | 0.00 |
| **STORE** | **535** | **969** | **25 758 754.00** | **22 082 339.93** | **3 676 414.07** | **0.00** |

## Структура продаж: STORE и SELLERS

По действующему контракту `Услуги` включают service, warranty и protection;
`Допы = Аксессуары + Услуги`. Строки — непересекающиеся части.

| Scope / группа | LiveSklad: ед. / выручка / GP | Приложение: ед. / выручка / GP | Разница | Разница, % | Статус | Причина |
|---|---|---|---|---:|---|---|
| STORE / Техника | 414 / 24 083 945.00 / 2 268 613.00 | 414 / 24 083 945.00 / 2 268 613.00 | 0 | 0.00% | PASS | — |
| STORE / Аксессуары | 372 / 988 366.00 / 765 658.07 | 372 / 988 366.00 / 765 658.07 | 0 | 0.00% | PASS_WITH_LIMIT | две zero-cost позиции |
| STORE / Услуги | 183 / 686 443.00 / 642 143.00 | 183 / 686 443.00 / 642 143.00 | 0 | 0.00% | PASS | — |
| STORE / Допы | 555 / 1 674 809.00 / 1 407 801.07 | 555 / 1 674 809.00 / 1 407 801.07 | 0 | 0.00% | PASS_WITH_LIMIT | сумма двух предыдущих групп |
| SELLERS / Техника | 401 / 23 363 288.00 / 2 214 556.00 | 401 / 23 363 288.00 / 2 214 556.00 | 0 | 0.00% | PASS | return shifts внутри cohort |
| SELLERS / Аксессуары | 371 / 982 876.00 / 762 778.07 | 371 / 982 876.00 / 762 778.07 | 0 | 0.00% | PASS_WITH_LIMIT | две zero-cost позиции |
| SELLERS / Услуги | 178 / 680 343.00 / 642 543.00 | 178 / 680 343.00 / 642 543.00 | 0 | 0.00% | PASS | — |
| SELLERS / Допы | 549 / 1 663 219.00 / 1 405 321.07 | 549 / 1 663 219.00 / 1 405 321.07 | 0 | 0.00% | PASS_WITH_LIMIT | сумма аксессуаров и услуг |

## Аналитические категории STORE

LiveSklad-значения реконструированы из exact-matched XLSX-позиций после применения production
effective-dated classification. Во всех строках приложение совпало по единицам, выручке,
себестоимости и GP; `Δ = 0`, `Δ% = 0.00%`.

| Категория | Ед. LS/App | Выручка LS/App | Себестоимость LS/App | GP LS/App | Статус |
|---|---:|---:|---:|---:|---|
| `ACCESSORY_IPAD_MAC` | 7 / 7 | 17 760.00 / 17 760.00 | 4 140.00 / 4 140.00 | 13 620.00 / 13 620.00 | PASS_WITH_LIMIT |
| `ACCESSORY_PODS_WATCH` | 5 / 5 | 11 960.00 / 11 960.00 | 4 979.00 / 4 979.00 | 6 981.00 / 6 981.00 | PASS |
| `CASE_APPLE_IPHONE` | 54 / 54 | 148 580.00 / 148 580.00 | 47 205.00 / 47 205.00 | 101 375.00 / 101 375.00 | PASS |
| `CASE_SAMSUNG` | 18 / 18 | 54 710.00 / 54 710.00 | 18 140.00 / 18 140.00 | 36 570.00 / 36 570.00 | PASS |
| `CHARGER_CABLE` | 106 / 106 | 325 852.00 / 325 852.00 | 97 607.00 / 97 607.00 | 228 245.00 / 228 245.00 | PASS |
| `FILM_PHONE` | 33 / 33 | 55 922.00 / 55 922.00 | 2 870.00 / 2 870.00 | 53 052.00 / 53 052.00 | PASS |
| `GLASS_CAMERA_IPHONE` | 28 / 28 | 67 850.00 / 67 850.00 | 14 849.60 / 14 849.60 | 53 000.40 / 53 000.40 | PASS |
| `GLASS_CAMERA_SAMSUNG` | 3 / 3 | 7 500.00 / 7 500.00 | 1 950.00 / 1 950.00 | 5 550.00 / 5 550.00 | PASS |
| `GLASS_IPHONE` | 91 / 91 | 232 552.00 / 232 552.00 | 17 782.33 / 17 782.33 | 214 769.67 / 214 769.67 | PASS |
| `GLASS_SAMSUNG` | 10 / 10 | 22 920.00 / 22 920.00 | 2 235.00 / 2 235.00 | 20 685.00 / 20 685.00 | PASS |
| `IPAD_MAC` | 36 / 36 | 1 546 090.00 / 1 546 090.00 | 1 391 300.00 / 1 391 300.00 | 154 790.00 / 154 790.00 | PASS |
| `IPHONE_NEW_ASIS` | 134 / 134 | 12 965 680.00 / 12 965 680.00 | 12 246 032.00 / 12 246 032.00 | 719 648.00 / 719 648.00 | PASS |
| `IPHONE_USED` | 83 / 83 | 3 522 046.00 / 3 522 046.00 | 2 568 300.00 / 2 568 300.00 | 953 746.00 / 953 746.00 | PASS |
| `OTHER_ACCESSORY_PRODUCT` | 17 / 17 | 42 760.00 / 42 760.00 | 10 950.00 / 10 950.00 | 31 810.00 / 31 810.00 | PASS_WITH_LIMIT |
| `PODS_WATCH_OTHER_DEVICE` | 85 / 85 | 1 718 729.00 / 1 718 729.00 | 1 541 800.00 / 1 541 800.00 | 176 929.00 / 176 929.00 | PASS |
| `PREMIUM_PROTECTION` | 2 / 2 | 23 000.00 / 23 000.00 | 0.00 / 0.00 | 23 000.00 / 23 000.00 | PASS |
| `SAMSUNG_NEW` | 71 / 71 | 4 121 440.00 / 4 121 440.00 | 3 899 400.00 / 3 899 400.00 | 222 040.00 / 222 040.00 | PASS |
| `SAMSUNG_USED` | 5 / 5 | 209 960.00 / 209 960.00 | 168 500.00 / 168 500.00 | 41 460.00 / 41 460.00 | PASS |
| `SETUP_SERVICE` | 133 / 133 | 364 301.00 / 364 301.00 | 44 300.00 / 44 300.00 | 320 001.00 / 320 001.00 | PASS |
| `WARRANTY_GENERIC` | 48 / 48 | 299 142.00 / 299 142.00 | 0.00 / 0.00 | 299 142.00 / 299 142.00 | PASS |

В source XLSX нет category fields. Production assignments однозначны: конфликтов нет.
Три группы имеют изменившееся текущее название товара в XLSX против snapshot-названия в
приложении; все сопоставлены без ambiguity по документу, типу, quantity, amount и cost:
`B374`, `B392`, `B761`. Финансового или классификационного эффекта нет.

## Зарплатные категории

Колонка «Зарплата за продажу» в sales XLSX везде равна нулю; она не эквивалентна payroll KPI
приложения. Поэтому сверены sales facts, независимо разложенные по payroll category.

| Payroll category | Ед. LS/App | Выручка LS/App | Себестоимость LS/App | GP LS/App | Δ, ₽/ед. | Δ, % | Статус |
|---|---:|---:|---:|---:|---:|---:|---|
| `ACCESSORY` | 372 / 372 | 988 366.00 / 988 366.00 | 222 707.93 / 222 707.93 | 765 658.07 / 765 658.07 | 0 | 0.00% | PASS_WITH_LIMIT |
| `SERVICE` | 183 / 183 | 686 443.00 / 686 443.00 | 44 300.00 / 44 300.00 | 642 143.00 / 642 143.00 | 0 | 0.00% | PASS |
| `TECH_TIER_1` | 301 / 301 | 21 389 726.00 / 21 389 726.00 | 19 390 732.00 / 19 390 732.00 | 1 998 994.00 / 1 998 994.00 | 0 | 0.00% | PASS |
| `TECH_TIER_2` | 113 / 113 | 2 694 219.00 / 2 694 219.00 | 2 424 600.00 / 2 424 600.00 | 269 619.00 / 269 619.00 | 0 | 0.00% | PASS |

Проверены все 19 встречающихся пар `employee + payroll category`: 15 совпали полностью,
четыре объяснённые строки образованы тремя возвратами.

| Сотрудник | Payroll category | Ед. LS → app | Выручка LS → app | Δ cost | Δ GP | Причина |
|---|---|---:|---:|---:|---:|---|
| `EMP-677C05FBCA1C` | `ACCESSORY` | 161 → 159 | 396 156.00 → 391 176.00 | -1 299.00 | -3 681.00 | `F17` отнесён исходному продавцу |
| `EMP-677C05FBCA1C` | `TECH_TIER_1` | 122 → 123 | 8 442 008.00 → 8 589 818.00 | +146 900.00 | +910.00 | net `F17`, `F19`, `F30` |
| `EMP-7919F0EF8E94` | `ACCESSORY` | 113 → 115 | 297 510.00 → 302 490.00 | +1 299.00 | +3 681.00 | `F17` убран с report employee |
| `EMP-7919F0EF8E94` | `TECH_TIER_1` | 91 → 90 | 6 583 738.00 → 6 435 928.00 | -146 900.00 | -910.00 | net `F17`, `F19`, `F30` |

## Сотрудники

Таблица охватывает каждого сотрудника с sales fact. `SELLERS` — три ranking-eligible сотрудника;
остальные учитываются только в `STORE`. Формат: `LiveSklad → приложение (разница)`.

| Сотрудник | Scope | Единицы | Выручка, ₽ | Себестоимость, ₽ | GP, ₽ | Статус |
|---|---|---:|---:|---:|---:|---|
| `EMP-2B22EFE2B2B7` | только STORE | 1 → 1 (0) | 0.00 → 0.00 (0) | 0.00 → 0.00 (0) | 0.00 → 0.00 (0) | PASS |
| `EMP-677C05FBCA1C` | SELLERS | 369 → 368 (-1) | 9 955 742.00 → 10 098 572.00 (+142 830.00) | 8 501 626.00 → 8 647 227.00 (+145 601.00) | 1 454 116.00 → 1 451 345.00 (-2 771.00) | EXPLAINED |
| `EMP-7919F0EF8E94` | SELLERS | 301 → 302 (+1) | 7 933 008.00 → 7 790 178.00 (-142 830.00) | 6 887 874.93 → 6 742 273.93 (-145 601.00) | 1 045 133.07 → 1 047 904.07 (+2 771.00) | EXPLAINED |
| `EMP-B802A9C3FDD3` | только STORE | 1 → 1 (0) | 64 300.00 → 64 300.00 (0) | 64 300.00 → 64 300.00 (0) | 0.00 → 0.00 (0) | PASS |
| `EMP-C054BACD273C` | только STORE | 12 → 12 (0) | 656 357.00 → 656 357.00 (0) | 602 300.00 → 602 300.00 (0) | 54 057.00 → 54 057.00 (0) | PASS |
| `EMP-E1BC41E795BD` | только STORE | 3 → 3 (0) | 6 000.00 → 6 000.00 (0) | 6 500.00 → 6 500.00 (0) | -500.00 → -500.00 (0) | PASS |
| `EMP-E22076FA5CEA` | SELLERS | 280 → 280 (0) | 7 137 757.00 → 7 137 757.00 (0) | 6 017 129.00 → 6 017 129.00 (0) | 1 120 628.00 → 1 120 628.00 (0) | PASS |
| `EMP-EE11A56D7AF0` | только STORE | 2 → 2 (0) | 5 590.00 → 5 590.00 (0) | 2 610.00 → 2 610.00 (0) | 2 980.00 → 2 980.00 (0) | PASS |

Для `EMP-677C05FBCA1C` проценты delta: units -0.2710%, revenue +1.4346%, cost +1.7126%,
GP -0.1906%. Для `EMP-7919F0EF8E94`: units +0.3322%, revenue -1.8005%, cost -2.1139%,
GP +0.2651%. У остальных сотрудников delta и delta% равны нулю.

Итог `SELLERS` не меняется, потому что все три возврата перемещаются внутри одного cohort:

| Показатель SELLERS | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка | 25 026 507.00 | 25 026 507.00 | 0.00 ₽ | 0.00% | PASS | return shifts взаимно сокращаются | `F17`, `F19`, `F30` |
| Себестоимость | 21 406 629.93 | 21 406 629.93 | 0.00 ₽ | 0.00% | PASS | то же | same |
| Валовая прибыль | 3 619 877.07 | 3 619 877.07 | 0.00 ₽ | 0.00% | PASS | то же | same |
| Маржа | 14.4642% | 14.4642% | 0.0000 п. п. | 0.00% | PASS | то же | same |
| Товарные единицы | 950 | 950 | 0 ед. | 0.00% | PASS | то же | same |

Из 64 пар `employee + analytics category` 58 совпали по quantity, revenue, cost и GP.
Шесть ненулевых строк полностью образованы теми же возвратами:

| Сотрудник | Категория | Ед. LS → app | Выручка LS → app | Δ cost | Δ GP | Документы |
|---|---|---:|---:|---:|---:|---|
| `EMP-677C05FBCA1C` | `CHARGER_CABLE` | 46 → 44 | 130 962.00 → 125 982.00 | -1 299.00 | -3 681.00 | `F17` |
| `EMP-677C05FBCA1C` | `IPHONE_NEW_ASIS` | 53 → 55 | 5 265 312.00 → 5 452 112.00 | +175 900.00 | +10 900.00 | `F19`, `F30` |
| `EMP-677C05FBCA1C` | `IPHONE_USED` | 39 → 38 | 1 622 136.00 → 1 583 146.00 | -29 000.00 | -9 990.00 | `F17` |
| `EMP-7919F0EF8E94` | `CHARGER_CABLE` | 35 → 37 | 113 770.00 → 118 750.00 | +1 299.00 | +3 681.00 | `F17` |
| `EMP-7919F0EF8E94` | `IPHONE_NEW_ASIS` | 41 → 39 | 4 029 188.00 → 3 842 388.00 | -175 900.00 | -10 900.00 | `F19`, `F30` |
| `EMP-7919F0EF8E94` | `IPHONE_USED` | 23 → 24 | 951 100.00 → 990 090.00 | +29 000.00 | +9 990.00 | `F17` |

## Доказательство атрибуции возвратов

| Return | Return external_id | XLSX employee | App/original employee | Original sale | Original external_id | Сумма / cost / GP | Проверки |
|---|---|---|---|---|---|---:|---|
| `F17` | `6a75b825327dd46acd6f4cc3` | `EMP-7919F0EF8E94` | `EMP-677C05FBCA1C` | `B385` | `6a75b7a6d5b9fe166f6fe32d` | -43 970.00 / -30 299.00 / -13 671.00 | original exists, active, same store |
| `F19` | `6a7eea99327dd4d933053f87` | `EMP-677C05FBCA1C` | `EMP-7919F0EF8E94` | `B511` | `6a7ee9e608da7242ff783e0e` | -88 900.00 / -83 300.00 / -5 600.00 | original exists, active, same store |
| `F30` | `6a94601c197d8c087bec4b95` | `EMP-677C05FBCA1C` | `EMP-7919F0EF8E94` | `B787` | `6a943754197d8c2a46e9a174` | -97 900.00 / -92 600.00 / -5 300.00 | original exists, active, same store |

У всех 19 возвратов invariants выполнены. Нельзя менять приложение так, чтобы оно повторяло
employee документа возврата: это нарушит ADR-0001 и зарплатную/рейтинговую семантику.

## Attach-rate: STORE

Формула независимо пересчитана из matched signed units и сравнена с production
`attach_rate_item_facts_v3`; production mismatch = 0. Формат — `числитель / знаменатель / rate`.

| Показатель | LiveSklad | Приложение | Разница, ед./п. п. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| `ACCESSORY_IPAD` | 7 / 22 / 31.82% | 7 / 22 / 31.82% | 0 / 0 / 0.00 | 0.00% | PASS | — | 14-metric recomputation |
| `ACCESSORY_PODS_WATCH` | 5 / 63 / 7.94% | 5 / 63 / 7.94% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `CASE_APPLE_IPHONE` | 54 / 217 / 24.88% | 54 / 217 / 24.88% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `CASE_SAMSUNG` | 18 / 76 / 23.68% | 18 / 76 / 23.68% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `CHARGER_CABLE` | 106 / 293 / 36.18% | 106 / 293 / 36.18% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `FILM_PHONE` | 33 / 293 / 11.26% | 33 / 293 / 11.26% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `GLASS_CAMERA_IPHONE` | 28 / 217 / 12.90% | 28 / 217 / 12.90% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `GLASS_CAMERA_SAMSUNG` | 3 / 76 / 3.95% | 3 / 76 / 3.95% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `GLASS_IPHONE` | 91 / 217 / 41.94% | 91 / 217 / 41.94% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `GLASS_SAMSUNG` | 10 / 76 / 13.16% | 10 / 76 / 13.16% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `PREMIUM_PROTECTION` | 0 / 396 / 0.00% | 0 / 396 / 0.00% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `SETUP_SERVICE` | 133 / 301 / 44.19% | 133 / 301 / 44.19% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `WARRANTY_GENERIC_NEW` | 46 / 205 / 22.44% | 46 / 205 / 22.44% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `WARRANTY_GENERIC_USED` | 4 / 88 / 4.55% | 4 / 88 / 4.55% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |

## Attach-rate: SELLERS

Матрицы показывают application `N/B/rate`; без `†` LiveSklad-реконструкция совпала по всем
трём значениям. Так показаны все 42 метрики трёх рейтинговых продавцов, а 18 исключений
разложены следом.

| Сотрудник | `CASE_APPLE_IPHONE` | `GLASS_IPHONE` | `GLASS_CAMERA_IPHONE` | `CASE_SAMSUNG` | `GLASS_SAMSUNG` | `GLASS_CAMERA_SAMSUNG` | `CHARGER_CABLE` |
|---|---|---|---|---|---|---|---|
| `EMP-677C05FBCA1C` | 33/93/35.48%† | 35/93/37.63%† | 8/93/8.60%† | 6/30/20.00% | 2/30/6.67% | 0/30/0.00% | 44/123/35.77%† |
| `EMP-7919F0EF8E94` | 11/63/17.46%† | 28/63/44.44%† | 12/63/19.05%† | 6/24/25.00% | 5/24/20.83% | 1/24/4.17% | 37/87/42.53%† |
| `EMP-E22076FA5CEA` | 10/57/17.54% | 28/57/49.12% | 8/57/14.04% | 6/20/30.00% | 3/20/15.00% | 2/20/10.00% | 24/77/31.17% |

| Сотрудник | `FILM_PHONE` | `SETUP_SERVICE` | `ACCESSORY_PODS_WATCH` | `ACCESSORY_IPAD` | `WARRANTY_GENERIC_USED` | `WARRANTY_GENERIC_NEW` | `PREMIUM_PROTECTION` |
|---|---|---|---|---|---|---|---|
| `EMP-677C05FBCA1C` | 23/123/18.70%† | 34/123/27.64%† | 2/23/8.70% | 1/7/14.29% | 4/42/9.52%† | 13/81/16.05%† | 0/154/0.00%† |
| `EMP-7919F0EF8E94` | 4/87/4.60%† | 50/90/55.56%† | 0/19/0.00% | 4/7/57.14% | 0/25/0.00%† | 6/62/9.68%† | 0/122/0.00%† |
| `EMP-E22076FA5CEA` | 6/77/7.79% | 44/80/55.00% | 3/18/16.67% | 2/8/25.00% | 0/21/0.00% | 27/56/48.21% | 0/109/0.00% |

| Сотрудник / metric | LiveSklad N/B/rate | Приложение N/B/rate | Разница N/B/rate | Статус | Причина | Документы |
|---|---:|---:|---:|---|---|---|
| `EMP-677C05FBCA1C` / `CASE_APPLE_IPHONE` | 33/92/35.87% | 33/93/35.48% | 0/+1/-0.39 п. п. | EXPLAINED | return attribution | `F17`, `F19`, `F30` |
| `EMP-677C05FBCA1C` / `GLASS_IPHONE` | 35/92/38.04% | 35/93/37.63% | 0/+1/-0.41 п. п. | EXPLAINED | denominator shift | same |
| `EMP-677C05FBCA1C` / `GLASS_CAMERA_IPHONE` | 8/92/8.70% | 8/93/8.60% | 0/+1/-0.10 п. п. | EXPLAINED | denominator shift | same |
| `EMP-677C05FBCA1C` / `CHARGER_CABLE` | 46/122/37.70% | 44/123/35.77% | -2/+1/-1.93 п. п. | EXPLAINED | numerator `F17` + denominator shift | same |
| `EMP-677C05FBCA1C` / `FILM_PHONE` | 23/122/18.85% | 23/123/18.70% | 0/+1/-0.15 п. п. | EXPLAINED | denominator shift | same |
| `EMP-677C05FBCA1C` / `SETUP_SERVICE` | 34/122/27.87% | 34/123/27.64% | 0/+1/-0.23 п. п. | EXPLAINED | denominator shift | same |
| `EMP-677C05FBCA1C` / `WARRANTY_GENERIC_USED` | 4/43/9.30% | 4/42/9.52% | 0/-1/+0.22 п. п. | EXPLAINED | used-phone return | `F17` |
| `EMP-677C05FBCA1C` / `WARRANTY_GENERIC_NEW` | 13/79/16.46% | 13/81/16.05% | 0/+2/-0.41 п. п. | EXPLAINED | new-phone returns | `F19`, `F30` |
| `EMP-677C05FBCA1C` / `PREMIUM_PROTECTION` | 0/153/0.00% | 0/154/0.00% | 0/+1/0.00 п. п. | EXPLAINED | rounded rate masks denominator shift | all three |
| `EMP-7919F0EF8E94` / `CASE_APPLE_IPHONE` | 11/64/17.19% | 11/63/17.46% | 0/-1/+0.27 п. п. | EXPLAINED | return attribution | all three |
| `EMP-7919F0EF8E94` / `GLASS_IPHONE` | 28/64/43.75% | 28/63/44.44% | 0/-1/+0.69 п. п. | EXPLAINED | denominator shift | all three |
| `EMP-7919F0EF8E94` / `GLASS_CAMERA_IPHONE` | 12/64/18.75% | 12/63/19.05% | 0/-1/+0.30 п. п. | EXPLAINED | denominator shift | all three |
| `EMP-7919F0EF8E94` / `CHARGER_CABLE` | 35/88/39.77% | 37/87/42.53% | +2/-1/+2.76 п. п. | EXPLAINED | numerator `F17` + denominator shift | all three |
| `EMP-7919F0EF8E94` / `FILM_PHONE` | 4/88/4.55% | 4/87/4.60% | 0/-1/+0.05 п. п. | EXPLAINED | denominator shift | all three |
| `EMP-7919F0EF8E94` / `SETUP_SERVICE` | 50/91/54.95% | 50/90/55.56% | 0/-1/+0.61 п. п. | EXPLAINED | denominator shift | all three |
| `EMP-7919F0EF8E94` / `WARRANTY_GENERIC_USED` | 0/24/0.00% | 0/25/0.00% | 0/+1/0.00 п. п. | EXPLAINED | used-phone return | `F17` |
| `EMP-7919F0EF8E94` / `WARRANTY_GENERIC_NEW` | 6/64/9.38% | 6/62/9.68% | 0/-2/+0.30 п. п. | EXPLAINED | new-phone returns | `F19`, `F30` |
| `EMP-7919F0EF8E94` / `PREMIUM_PROTECTION` | 0/123/0.00% | 0/122/0.00% | 0/-1/0.00 п. п. | EXPLAINED | rounded rate masks denominator shift | all three |

## Продажи и движение денег — разные контуры

Sales XLSX даёт 25 703 754.00 ₽ merchandise revenue без order positions. `Оплачено` также
25 703 754.00 ₽, колонка `Возврат` — 0.00 ₽. Signed `sales_payments` приложения равен
25 703 754.00 ₽: delta 0.00 ₽ / 0.00%. В этом периоде все 19 товарных возвратов представлены
отрицательным движением в `Оплачено`; возврата товара с нулевым payment здесь не обнаружено.

Orders XLSX отдельно показывает 55 000.00 ₽ `Оплачено` и 0.00 ₽ `Возврат`. Приложение хранит
те же 55 000.00 ₽ как revenue order positions, но не хранит их payment movement: production-код
`OrderSyncPersistence` создаёт zero cash payload и вызывает `removePayments`.

| Показатель денег | LiveSklad | Приложение | Разница, ₽ | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Продажи/возвраты без заказов | 25 703 754.00 | 25 703 754.00 | 0.00 | 0.00% | PASS | — | sales XLSX vs signed payments |
| Оплаты заказов | 55 000.00 | 0.00 | -55 000.00 | -100.00% | NOT_MODELLED | отличие модели, не sync gap | orders XLSX + order sync code |
| Полный наблюдаемый cash movement | 25 758 754.00 | 25 703 754.00 | -55 000.00 | -0.2135% | PASS_WITH_LIMIT | app payment ledger не охватывает orders | сумма двух контуров |

Это не доказывает ошибку существующих revenue/GP KPI: они position-based и сходятся. Но
`sales_payments` нельзя представлять пользователю как полный cash total магазина до появления
order-level payment ingestion либо явного ограничения интерфейса.

## Выданные заказы

Все восемь order records проверены. Шесть позиционных facts совпали по сумме и cost; `A13` и
`A43` не имеют позиций и потому отсутствуют в item model.

| Заказ | Production external_id / факт | Дата выдачи XLSX | Сумма | Cost | GP | Статус |
|---|---|---|---:|---:|---:|---|
| `A13` | нет позиции | 2026-08-07 | 0.00 | 0.00 | 0.00 | EXPLAINED |
| `A15` | `order:6a71ee1ad5b9fe374a2e8e82:position:6a75827ed5b9fe2d9468102a` | 2026-08-07 | 46 000.00 | 36 000.00 | 10 000.00 | PASS |
| `A31` | `order:6a760b92d5b9feb32e7a1580:position:6a798ac4327dd4f8cb9f9d6d` | 2026-08-10 | 3 000.00 | 1 800.00 | 1 200.00 | PASS |
| `A39` | `order:6a799a2ed5b9fe09d5a2c776:position:6a80a794327dd4531a223fe7` | 2026-08-21 | 0.00 | 0.00 | 0.00 | PASS |
| `A42` | `order:6a82fceb327dd4d51640fd39:position:6a833f4b08da729695bc0d07` | 2026-08-17 | 6 000.00 | 2 500.00 | 3 500.00 | PASS |
| `A43` | нет позиции | 2026-08-21 | 0.00 | 0.00 | 0.00 | EXPLAINED |
| `A44` | `order:6a884a6308da723a45152ac2:position:6a8872656ea56f3d9dc435a2` | 2026-08-21 | 0.00 | 2 000.00 | -2 000.00 | PASS |
| `A66` | `order:6a92a7f09a57e93e46891955:position:6a9571d39a57e9520eb375f0` | 2026-08-31 | 0.00 | 2 000.00 | -2 000.00 | PASS |

`A39` демонстрирует отличие дат: его позиция добавлена 15 августа, а заказ выдан 21 августа.
Это корректно для обоих отчётов. Если нужен operational count всех выданных заказов, приложению
требуется отдельный order-level факт; backfill существующей position model этого не исправит.

## Документы, позиции и время

Сопоставление выполнялось по `source_document_type + document_number`, затем по товару;
quantity, amount, cost, kind, store, date и employee были контрольными полями. XLSX не содержит
`external_id`, поэтому production IDs использовались для исключений и API-проверки.

Четыре структурных отличия имеют две одинаковые строки quantity 1 в XLSX и одну строку quantity 2
в приложении. Units, amount и cost совпали.

| Документ | external_id | Позиция | XLSX rows → app rows | Единицы | Сумма | Статус |
|---|---|---|---:|---:|---:|---|
| `B594` | `6a859f0c08da724fb3e57e6c` | полиуретановая плёнка | 2 → 1 | 2 | 3 980.00 | EXPLAINED |
| `B798` | `6a9556f2197d8cbb3df99bd0` | защитное стекло | 2 → 1 | 2 | 6 980.00 | EXPLAINED |
| `B799` | `6a955ce39a57e95e51afcea9` | защитное стекло | 2 → 1 | 2 | 6 980.00 | EXPLAINED |
| `F31` | `6a95588b197d8c27caf9e78e` | возврат защитного стекла | 2 → 1 | -2 | -6 980.00 | EXPLAINED |

Все 535 XLSX timestamps на один час позже Kaliningrad time. 531 delta равна 59 минутам из-за
обрезанных секунд в XLSX, четыре — 60 минут. Контроль `B322`, external_id
`6a6e1c13aa17faf6281d06d2`: LiveSklad API вернул `2026-08-01T16:17:23.109Z`, production local —
`18:17:23`, XLSX — `19:17`. API list за Kaliningrad boundary вернул 510 direct sales — ровно
столько же, сколько XLSX и production. Границы августа не сдвинули ни один документ.

## Качество себестоимости

| Документ | external_id | Тип | Позиция | Signed amount | Cost | Категория | Статус |
|---|---|---|---|---:|---:|---|---|
| `B739` | `6a9086379a57e94ab16b0adf` | sale | чехол для iPad Pro 13, б/у | 1 490.00 | 0.00 | `ACCESSORY_IPAD_MAC` | SOURCE_WARNING |
| `B773` | `6a931ae9197d8c3d3cde3686` | sale | чехол, б/у | 500.00 | 0.00 | `OTHER_ACCESSORY_PRODUCT` | SOURCE_WARNING |

Их суммарная revenue и текущая GP равны 1 990.00 ₽. Это не межсистемная разница: XLSX тоже
считает cost 0.00. Пользователь 2026-09-13 подтвердил, что нулевая себестоимость допустима, но
не смог найти эти документы в интерфейсе LiveSklad и потому не подтвердил provenance конкретных
позиций. Значения принимаются как допустимые source values; вручную менять их в приложении нельзя.
Если такие позиции должны перестать создавать `ZERO_UNEXPECTED`, требуется отдельное решение о
quality policy, а не корректировка августовских фактов.

## Классификация причин

| Класс причины | Результат | Доказательство |
|---|---|---|
| Неполное покрытие синхронизации | не обнаружено | exact sets 535 docs и 1 003 product groups; успешные перекрывающие окна |
| Пропущенный или скрытый возврат | не обнаружено | 19/19 returns, STORE delta 0; order-return total 0 |
| Неверная классификация | не обнаружено в действующих assignments; re-approval вне scope XLSX | 0 unmapped/exclude/conflicts, 20 category totals exact |
| Неправильный магазин | не обнаружено | один target store; все return originals same store |
| Неправильный сотрудник | три report/app различия, все ожидаемы | `F17`, `F19`, `F30`, original-sale links |
| Отличие формул | подтверждено и объяснено | cash orders не моделируются; 8 order records vs 6 position facts |
| Отличие границ периода | financial effect 0 | API UTC probe, same business dates |
| Дубликат или удаление | financial effect 0 | four merged row pairs; no deleted August facts |
| Ошибка исходных данных LiveSklad | не установлена; два zero-cost значения допустимы по подтверждению пользователя, provenance не найден | `ZERO_UNEXPECTED` rows + business confirmation 2026-09-13 |
| Ошибка приложения | не обнаружена в существующих KPI | all STORE and SELLERS monetary/unit deltas zero; formulas recomputed |

## Вывод и безопасные дальнейшие действия

1. Точно совпали все STORE и aggregate SELLERS financial/unit metrics, documents, product groups,
   структура, категории и store attach-rate; payments продаж/возвратов также совпали.
2. Не совпадают только распределение трёх returns между двумя продавцами, 18 seller attach cells,
   техническое число строк, operational count пустых orders и полный cash ledger с учётом orders.
3. Employee differences формируют только `F17`, `F19`, `F30`; row differences — `B594`, `B798`,
   `B799`, `F31`; order-count difference — `A13`, `A43`; cash gap — `A15`, `A31`, `A42`.
4. Первопричины: ADR-0001 return attribution, position-level order model, deliberate absence of
   order payments, row aggregation, mutable product names, XLSX UTC+3 и два source zero costs.
5. Исправление данных production за август не требуется. Нули `B739` и `B773` принимаются как
   допустимые source values. Если продукту нужен полный cash KPI, operational issued-order count
   или иная zero-cost quality policy, это отдельное изменение модели/интерфейса.
6. Повторная синхронизация не требуется. Если source costs когда-либо изменят, потребуется
   отдельно согласованная точечная историческая синхронизация и повтор этой сверки.
7. Показателям августа можно доверять для STORE и SELLERS с original-sale attribution. GP/margin
   включают две допустимые source zero-cost строки без подтверждённого provenance; payment ledger
   не является полным cash total с orders; order count в fact model означает шесть
   position-bearing orders, не все восемь records.

Статус остаётся `PASS_WITH_LIMITS` из-за границ payment/order model и отсутствия provenance двух
source zeros. Оснований для backfill, ручной корректировки, смены категорий или исправления
существующих KPI не найдено.
