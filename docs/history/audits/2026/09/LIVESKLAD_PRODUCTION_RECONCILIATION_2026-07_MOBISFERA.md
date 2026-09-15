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
verdict_scope: "МобиСфера, 2026-07-01..2026-07-31 inclusive, Europe/Kaliningrad; LiveSklad XLSX to production reconciliation, authorized exact correction and independent post-commit verification."
source_of_truth:
  - docs/current/product/business-metrics.md
  - docs/current/product/sales-and-returns.md
  - docs/current/product/classification.md
  - docs/current/product/payroll.md
  - docs/current/product/attach-rate.md
  - docs/decisions/ADR-0001-return-employee-attribution.md
verification_sources:
  - three user-supplied LiveSklad XLSX reports identified by SHA-256 in this record
  - sanitized production read-only SQL snapshot captured on 2026-09-14
  - authorized exact one-item production correction committed on 2026-09-14
  - independent read-only post-commit verification captured on 2026-09-14
  - production classification and order synchronization implementation inspected at the reconciled revision
required_reviewers:
  - product
  - operations
---

# Сверка LiveSklad ↔ production: июль 2026, МобиСфера

## Вердикт

`PASS_WITH_LIMITS` после разрешённого пользователем exact-target исправления и независимой
post-commit проверки. Все 318 ключей документов и 555 групп `document + product` совпадают;
missing/extra/deleted facts, `UNMAPPED`, missing cost и `ZERO_UNEXPECTED` отсутствуют.

Заказ `A2` теперь имеет `SETUP_SERVICE / PAID_REPAIR` только в интервале
`[2026-07-01, 2026-08-01)`. Независимый verifier после `COMMIT` подтвердил точную строку:
1 ед., 6,000.00 ₽ revenue, 3,000.00 ₽ cost и 3,000.00 ₽ GP. Общая выручка, себестоимость,
GP, документы, количество, магазин и сотрудник не менялись. Recovery-version не назначена ни
одной посторонней позиции; audit-записи присутствуют.

| Показатель | LiveSklad | Приложение после correction | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| STORE / чистая выручка | 15,680,200.00 | 15,680,200.00 | 0.00 ₽ | 0.00% | PASS | — | полный исходный match + post-fix invariance |
| STORE / себестоимость | 13,956,145.40 | 13,956,145.40 | 0.00 ₽ | 0.00% | PASS | — | exact item match |
| STORE / валовая прибыль | 1,724,054.60 | 1,724,054.60 | 0.00 ₽ | 0.00% | PASS | — | revenue minus cost |
| STORE / аксессуары | 194 ед. / 548,558.00 ₽ / GP 421,482.60 ₽ | то же | 0 | 0.00% | PASS | `A2` исключён | exact corrected item |
| STORE / услуги | 77 ед. / 250,424.00 ₽ / GP 243,424.00 ₽ | то же | 0 | 0.00% | PASS | `A2` добавлен | exact corrected item |
| STORE / допы | 271 ед. / 798,982.00 ₽ / GP 664,906.60 ₽ | то же | 0 | 0.00% | PASS | внутренний перенос без изменения итога | recomputation |
| SELLERS / аксессуары | 194 ед. / 548,558.00 ₽ / GP 421,482.60 ₽ | то же | 0 | 0.00% | PASS | ranking item перенесён | seller cohort + manifest |
| SELLERS / услуги | 77 ед. / 250,424.00 ₽ / GP 243,424.00 ₽ | то же | 0 | 0.00% | PASS | ranking item перенесён | seller cohort + manifest |
| `GLASS_IPHONE` | 43 ед. / 112,822.00 ₽ / cost 7,673.00 ₽ | то же | 0 | 0.00% | PASS | `A2` исключён | bounded analytics assignment |
| `SETUP_SERVICE` | 62 ед. / 176,794.00 ₽ / cost 7,000.00 ₽ | то же | 0 | 0.00% | PASS | `A2` добавлен | bounded analytics assignment |
| Payroll `ACCESSORY` | 194 ед. / 548,558.00 ₽ | то же | 0 | 0.00% | PASS | `A2` перенесён | bounded payroll assignment |
| Payroll `PAID_REPAIR` | 1 ед. / 6,000.00 ₽ / cost 3,000.00 ₽ | то же | 0 | 0.00% | PASS | подтверждённый платный ремонт | independent verifier |
| Attach STORE `GLASS_IPHONE` | 43 / 134 / 32.09% | 43 / 134 / 32.09% | 0 / 0 / 0.00 п. п. | 0.00% | PASS | numerator −1 | item-level recomputation |
| Attach STORE `SETUP_SERVICE` | 62 / 185 / 33.51% | 62 / 185 / 33.51% | 0 / 0 / 0.00 п. п. | 0.00% | PASS | numerator +1 | item-level recomputation |
| Attach SELLERS `GLASS_IPHONE` | 43 / 134 / 32.09% | 43 / 134 / 32.09% | 0 / 0 / 0.00 п. п. | 0.00% | PASS | numerator −1 | seller manifest |
| Attach SELLERS `SETUP_SERVICE` | 62 / 184 / 33.70% | 62 / 184 / 33.70% | 0 / 0 / 0.00 п. п. | 0.00% | PASS | numerator +1 | seller manifest |
| `EMP-E22076FA5CEA / GLASS_IPHONE` | 12 / 37 / 32.43% | 12 / 37 / 32.43% | 0 / 0 / 0.00 п. п. | 0.00% | PASS | classification fixed | exact employee/item relation |
| `EMP-E22076FA5CEA / SETUP_SERVICE` | 25 / 57 / 43.86% | 24 / 58 / 41.38% | −1 / +1 / −2.48 п. п. | −5.65% rate | EXPLAINED | только original-sale attribution `F4`, `F8` | ADR-0001 links |

Документ исправления: `A2`. Первопричина — слово `стекл` срабатывало раньше fallback по
`source_kind=SERVICE`. Повторная синхронизация июля не требуется: item snapshot и обе
effective-dated проекции исправлены транзакционно. Candidate rule v8 и regression tests
подготовлены локально, но код production по договорённости пока не развёрнут.

Июльским показателям можно доверять. Ограничения: шесть employee-level переносов возвратов по
ADR-0001 остаются объяснёнными; order payments не входят в `sales_payments`; XLSX показывает UTC+3
при business timezone `Europe/Kaliningrad`, но границы месяца не затронуты. Сентябрьский `A79`
намеренно остался без изменений и будет проверяться в сверке сентября.

## Исходная детальная сверка до correction

Следующие разделы сохраняют полный первоначальный read-only baseline. Финансовые, документные,
возвратные и employee-total таблицы остаются актуальны, поскольку correction не меняла суммы,
количества, документы, магазин или сотрудника. Category/payroll/attach таблицы в baseline нужны
как доказательство состояния «до»; финальное состояние затронутых строк зафиксировано выше.

## Зафиксированный scope и источники

| Поле | Значение | Доказательство |
|---|---|---|
| Магазин | `МобиСфера` | выбор пользователя; один target store в read-only SQL |
| Период | `2026-07-01..2026-07-31`, обе даты включительно | выбор пользователя, effective content XLSX и business-date SQL |
| Бизнес-часовой пояс | `Europe/Kaliningrad` | production store configuration |
| Бизнес-день | `00:00..24:00` local | production store configuration |
| Production snapshot | основной срез, correction и post-commit verify 2026-09-14 | runtime identity повторно проверена и совпала с [current project state](../../../../current/project-state.md) |
| Режим | read-only diagnosis → отдельно разрешённая exact transaction → read-only verify | preflight `ROLLBACK`; apply `COMMIT`; verifier `ROLLBACK` |

| Файл | Наблюдаемый effective filter | Строк данных | Размер | SHA-256 |
|---|---|---:|---:|---|
| `Отчёт по товарам и работам (7).xlsx` | `МобиСфера`; июль; `Продажа`, `Возврат`, `Установка в заказ`; order status `Выдан` | 556 | 244 148 B | `a2380700b6e3c66e5564b6e77c4c3b301fde01df8ffff3949b65b84152bd3e35` |
| `Отчёт по продажам (7).xlsx` | `МобиСфера`; июль; продажи и sale returns | 316 | 155 649 B | `4df4dd0355809e42c8a9e112841340716eaa078e5eb7ab7ff9046bc3cba0a1b4` |
| `Отчёт по заказам (6).xlsx` | `МобиСфера`; дата выдачи в июле; выданные заказы | 2 | 18 703 B | `06c01d3562f012644767d25875d2f1445a1d5b2a4e8947e0cc129e520cac44e3` |

XLSX не сохраняют все экранные настройки фильтра как машиночитаемые metadata. Поэтому таблица
разделяет подтверждённые пользователем параметры и effective filter, доказуемый содержимым.
В товарах/работах и продажах первая операция датирована 9 июля, последняя — 31 июля; за
1–8 июля строк нет ни в XLSX, ни в production. В orders XLSX оба заказа созданы и выданы в июле.

Customer/contact columns не использовались в production-запросах и не включены в evidence.
Сотрудники ниже заменены устойчивыми salted aliases.

## Семантика трёх отчётов

- В «Товарах и работах» `Себестоимость = Сумма - Валовая прибыль`; продажи положительные,
  sale returns отрицательные. Позиции заказов представлены отдельным source type
  `orderPosition`.
- В «Продажах» строка возврата имеет отрицательные `Сумма продажи` и `Оплачено`. Отдельная
  колонка `Возврат` во всех 316 строках равна нулю. Для каждого документа выполнено
  `Оплачено - Возврат = Сумма продажи`; товарный возврат не потерян.
- «Заказы» — отдельный order-level отчёт. `Сумма заказа = работа + запчасти`, а GP равна
  сумме минус cost. В июле обе записи имеют ненулевые позиции и статус выдачи.
- Аналитическая, attach-rate и зарплатная классификации независимы. Нулевая колонка
  «Зарплата за продажу» не является payroll category/KPI приложения.
- `STORE` использует все факты магазина. `SELLERS` использует только трёх активных
  ranking-eligible сотрудников; четвёртый сотрудник остаётся только в `STORE`.

## Полнота синхронизации

Для июля есть успешный full-period backfill с границами
`2026-06-30T22:00Z..2026-07-31T22:00Z`, завершивший фазы sales, returns и orders. Более ранние
failed/partial runs сохранились в истории, но перекрыты успешными runs. По каждому scope есть
successful windows с теми же полными границами месяца.

Предметное доказательство полноты:

- 318 из 318 ключей `source_document_type + document_number` есть с обеих сторон;
- source-only = 0, app-only = 0;
- 555 из 555 групп `document + product` сопоставлены; unmatched = 0;
- для всех документов и product groups delta quantity/revenue/cost/GP = 0;
- 556 из 556 item rows присутствуют; row-structure mismatch = 0;
- 12 из 12 sale returns присутствуют; все имеют существующую исходную продажу того же магазина;
- оба order-position facts совпали с текущими orders XLSX по quantity, amount и cost;
- deleted documents/items в scope = 0; missing cost, `ZERO_UNEXPECTED`, `UNMAPPED`, `EXCLUDE` = 0.

Следовательно, поставленные source facts синхронизированы полностью. Ошибка `A2` относится не
к покрытию синхронизации, а к детерминированной классификации уже загруженной позиции.

## Итог месяца и магазин: STORE

Разница вычислена как `Приложение - LiveSklad`; процент — относительно LiveSklad.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка | 15 680 200.00 | 15 680 200.00 | 0.00 ₽ | 0.00% | PASS | — | signed sum 556 positions |
| Себестоимость | 13 956 145.40 | 13 956 145.40 | 0.00 ₽ | 0.00% | PASS | — | exact item match |
| Валовая прибыль | 1 724 054.60 | 1 724 054.60 | 0.00 ₽ | 0.00% | PASS | — | `revenue - cost` |
| Маржа | 10.9951% | 10.9951% | 0.0000 п. п. | 0.00% | PASS | — | без промежуточного округления |
| Продаж | 304 | 304 | 0 док. | 0.00% | PASS | — | `sale/SALE` document keys |
| Единиц в продажах | 539 | 539 | 0 ед. | 0.00% | PASS | — | positive sale quantity |
| Возвратов продаж | 12 | 12 | 0 док. | 0.00% | PASS | — | `saleReturn/RETURN` keys |
| Единиц в возвратах продаж | -18 | -18 | 0 ед. | 0.00% | PASS | — | signed return quantity |
| Выручка возвратов продаж | -403 570.00 | -403 570.00 | 0.00 ₽ | 0.00% | PASS | товар и деньги проверены отдельно | 12 documents |
| Возвратов заказов | 0 | 0 | 0 док. | — | PASS | order return money/facts отсутствуют | orders XLSX + production facts |
| Выданных заказов | 2 | 2 | 0 док. | 0.00% | PASS | обе записи имеют position fact | `A2`, `A3` |
| Единиц в выданных заказах | 2 | 2 | 0 ед. | 0.00% | PASS | — | two order positions |
| Выручка позиций заказов | 12 900.00 | 12 900.00 | 0.00 ₽ | 0.00% | PASS | — | `A2`, `A3` |
| Чистых товарных единиц | 523 | 523 | 0 ед. | 0.00% | PASS | `539 - 18 + 2` | all positions |
| Документных ключей | 318 | 318 | 0 ключей | 0.00% | PASS | — | exact key sets |
| Строк товаров/работ | 556 | 556 | 0 строк | 0.00% | PASS | — | exact row structure |
| Групп документ + товар | 555 | 555 | 0 групп | 0.00% | PASS | одно переименование matched | amount/cost/qty/type match |

Проверка компонентов итога:

| Тип | Документы | Единицы | Выручка | Себестоимость | Валовая прибыль | Разница приложения |
|---|---:|---:|---:|---:|---:|---:|
| Прямые продажи | 304 | 539 | 16 070 870.00 | 14 318 265.40 | 1 752 604.60 | 0.00 |
| Sale returns | 12 | -18 | -403 570.00 | -369 120.00 | -34 450.00 | 0.00 |
| Позиции заказов | 2 | 2 | 12 900.00 | 7 000.00 | 5 900.00 | 0.00 |
| **STORE** | **318** | **523** | **15 680 200.00** | **13 956 145.40** | **1 724 054.60** | **0.00** |

## Структура продаж: STORE и SELLERS

По действующему контракту `Допы = Аксессуары + Услуги`. Здесь LiveSklad-side classification
для `A2` установлена независимо по source work semantics; поэтому таблица показывает реальную
категорийную разницу, которую механическая реконструкция из production assignment скрыла бы.

| Scope / группа | LiveSklad: ед. / выручка / GP | Приложение: ед. / выручка / GP | Разница | Разница, % | Статус | Причина |
|---|---|---|---|---:|---|---|
| STORE / Техника | 252 / 14 881 218.00 / 1 059 148.00 | 252 / 14 881 218.00 / 1 059 148.00 | 0 | 0.00% | PASS | — |
| STORE / Аксессуары | 194 / 548 558.00 / 421 482.60 | 195 / 554 558.00 / 424 482.60 | +1 / +6 000.00 / +3 000.00 | +1.09% revenue | FAIL | `A2` work classified as accessory |
| STORE / Услуги | 77 / 250 424.00 / 243 424.00 | 76 / 244 424.00 / 240 424.00 | -1 / -6 000.00 / -3 000.00 | -2.40% revenue | FAIL | тот же `A2` |
| STORE / Допы | 271 / 798 982.00 / 664 906.60 | 271 / 798 982.00 / 664 906.60 | 0 | 0.00% | PASS_WITH_INTERNAL_SHIFT | ошибка взаимно компенсируется | `A2` |
| SELLERS / Техника | 251 / 14 819 318.00 / 1 057 248.00 | 251 / 14 819 318.00 / 1 057 248.00 | 0 | 0.00% | PASS | — |
| SELLERS / Аксессуары | 194 / 548 558.00 / 421 482.60 | 195 / 554 558.00 / 424 482.60 | +1 / +6 000.00 / +3 000.00 | +1.09% revenue | FAIL | `A2` принадлежит ranking seller |
| SELLERS / Услуги | 77 / 250 424.00 / 243 424.00 | 76 / 244 424.00 / 240 424.00 | -1 / -6 000.00 / -3 000.00 | -2.40% revenue | FAIL | тот же `A2` |
| SELLERS / Допы | 271 / 798 982.00 / 664 906.60 | 271 / 798 982.00 / 664 906.60 | 0 | 0.00% | PASS_WITH_INTERNAL_SHIFT | ошибка взаимно компенсируется | `A2` |

## Аналитические категории STORE

Во всех строках, кроме двух, приложение совпало по quantity, revenue, cost и GP. Для `A2`
ожидаемая source category — `SETUP_SERVICE`: это `is_work=true`, и orders XLSX содержит
6 000.00 ₽ в «Сумма за работу», 0.00 ₽ в «Сумма за запчасти», 3 000.00 ₽ в
«Себестоимость работ».

| Категория | Ед. LS/App | Выручка LS/App | Себестоимость LS/App | GP LS/App | Статус |
|---|---:|---:|---:|---:|---|
| `ACCESSORY_IPAD_MAC` | 2 / 2 | 6 631.00 / 6 631.00 | 1 400.00 / 1 400.00 | 5 231.00 / 5 231.00 | PASS |
| `ACCESSORY_PODS_WATCH` | 4 / 4 | 8 100.00 / 8 100.00 | 2 300.00 / 2 300.00 | 5 800.00 / 5 800.00 | PASS |
| `CASE_APPLE_IPHONE` | 28 / 28 | 75 282.00 / 75 282.00 | 26 305.00 / 26 305.00 | 48 977.00 / 48 977.00 | PASS |
| `CASE_SAMSUNG` | 8 / 8 | 23 550.00 / 23 550.00 | 8 600.00 / 8 600.00 | 14 950.00 / 14 950.00 | PASS |
| `CHARGER_CABLE` | 55 / 55 | 198 702.00 / 198 702.00 | 54 717.40 / 54 717.40 | 143 984.60 / 143 984.60 | PASS |
| `FILM_PHONE` | 16 / 16 | 26 470.00 / 26 470.00 | 2 240.00 / 2 240.00 | 24 230.00 / 24 230.00 | PASS |
| `GLASS_CAMERA_IPHONE` | 18 / 18 | 45 561.00 / 45 561.00 | 12 300.00 / 12 300.00 | 33 261.00 / 33 261.00 | PASS |
| `GLASS_CAMERA_SAMSUNG` | 5 / 5 | 13 650.00 / 13 650.00 | 2 950.00 / 2 950.00 | 10 700.00 / 10 700.00 | PASS |
| `GLASS_IPHONE` | 43 / 44 | 112 822.00 / 118 822.00 | 7 673.00 / 10 673.00 | 105 149.00 / 108 149.00 | **FAIL `A2`** |
| `GLASS_SAMSUNG` | 6 / 6 | 15 070.00 / 15 070.00 | 1 490.00 / 1 490.00 | 13 580.00 / 13 580.00 | PASS |
| `IPAD_MAC` | 15 / 15 | 745 360.00 / 745 360.00 | 686 100.00 / 686 100.00 | 59 260.00 / 59 260.00 | PASS |
| `IPHONE_NEW_ASIS` | 91 / 91 | 8 071 608.00 / 8 071 608.00 | 7 747 770.00 / 7 747 770.00 | 323 838.00 / 323 838.00 | PASS |
| `IPHONE_USED` | 43 / 43 | 2 235 750.00 / 2 235 750.00 | 1 769 100.00 / 1 769 100.00 | 466 650.00 / 466 650.00 | PASS |
| `OTHER_ACCESSORY_PRODUCT` | 9 / 9 | 22 720.00 / 22 720.00 | 7 100.00 / 7 100.00 | 15 620.00 / 15 620.00 | PASS |
| `PODS_WATCH_OTHER_DEVICE` | 57 / 57 | 1 177 110.00 / 1 177 110.00 | 1 078 200.00 / 1 078 200.00 | 98 910.00 / 98 910.00 | PASS |
| `SAMSUNG_NEW` | 44 / 44 | 2 547 410.00 / 2 547 410.00 | 2 443 900.00 / 2 443 900.00 | 103 510.00 / 103 510.00 | PASS |
| `SAMSUNG_USED` | 2 / 2 | 103 980.00 / 103 980.00 | 97 000.00 / 97 000.00 | 6 980.00 / 6 980.00 | PASS |
| `SETUP_SERVICE` | 62 / 61 | 176 794.00 / 170 794.00 | 7 000.00 / 4 000.00 | 169 794.00 / 166 794.00 | **FAIL `A2`** |
| `WARRANTY_GENERIC` | 15 / 15 | 73 630.00 / 73 630.00 | 0.00 / 0.00 | 73 630.00 / 73 630.00 | PASS |

`PREMIUM_PROTECTION`, `UNMAPPED` и `EXCLUDE` в июльских facts не встречаются. Внутри matched
product groups classification conflicts нет. Одно текущее имя изменилось против snapshot:
`B3`, external_id `6a4f8156aca185fef50c743d`, сохранил suffix «магазин» в snapshot. Quantity,
amount, cost и category сопоставлены однозначно; ambiguity и финансового эффекта нет.

## Зарплатные категории

Текущая механическая раскладка приложения совпадает с XLSX, если XLSX наследует ту же
production category. Независимая source-семантика доказывает, что `A2` не должен оставаться
`ACCESSORY`. Пользователь 2026-09-14 подтвердил точный payroll target `PAID_REPAIR` для платного
ремонта; это решение не смешивается с analytics target `SETUP_SERVICE`.

| Payroll category | LiveSklad semantic expectation | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| `ACCESSORY` | 194 ед. / 548 558.00 | 195 ед. / 554 558.00 | +1 / +6 000.00 | +1.09% | FAIL | `A2` ошибочно в accessory | source work columns |
| `SERVICE` | 76 ед. / 244 424.00 | 76 ед. / 244 424.00 | 0 / 0.00 | 0.00% | PASS | `A2` не является обычной услугой payroll | exact facts + business decision |
| `PAID_REPAIR` | 1 ед. / 6 000.00 | 0 ед. / 0.00 | -1 / -6 000.00 | -100.00% | FAIL | `A2` ещё не назначен в target bucket | source work columns + business decision |
| `TECH_TIER_1` | 185 ед. / 13 301 748.00 | 185 ед. / 13 301 748.00 | 0 / 0.00 | 0.00% | PASS | — | exact facts |
| `TECH_TIER_2` | 67 ед. / 1 579 470.00 | 67 ед. / 1 579 470.00 | 0 / 0.00 | 0.00% | PASS | — | exact facts |

Для текущих четырёх production buckets totals равны:

| Current payroll category | Ед. | Выручка | Себестоимость | GP |
|---|---:|---:|---:|---:|
| `ACCESSORY` | 195 | 554 558.00 | 130 075.40 | 424 482.60 |
| `SERVICE` | 76 | 244 424.00 | 4 000.00 | 240 424.00 |
| `TECH_TIER_1` | 185 | 13 301 748.00 | 12 371 670.00 | 930 078.00 |
| `TECH_TIER_2` | 67 | 1 579 470.00 | 1 450 400.00 | 129 070.00 |

Из 13 текущих пар `employee + payroll category` три совпали полностью, десять различаются
только из-за шести возвратов. Для `EMP-E22076FA5CEA / ACCESSORY` добавляется отдельная
классификационная ошибка `A2`: app превышает semantic expectation на 1 ед., 6 000.00 ₽ revenue,
3 000.00 ₽ cost и 3 000.00 ₽ GP. После correction аналогичный минус должен исчезнуть в
`PAID_REPAIR`.

## Сотрудники и SELLERS

| Сотрудник | Scope | Единицы LS → app | Выручка LS → app | Cost LS → app | GP LS → app | Разница revenue, % | Статус |
|---|---|---:|---:|---:|---:|---:|---|
| `EMP-677C05FBCA1C` | SELLERS | 156 → 163 (+7) | 4 647 988.00 → 4 907 288.00 (+259 300.00) | 4 155 999.00 → 4 397 329.00 (+241 330.00) | 491 989.00 → 509 959.00 (+17 970.00) | +5.5788% | EXPLAINED |
| `EMP-7919F0EF8E94` | SELLERS | 187 → 181 (-6) | 6 287 071.00 → 6 001 771.00 (-285 300.00) | 5 647 491.00 → 5 379 061.00 (-268 430.00) | 639 580.00 → 622 710.00 (-16 870.00) | -4.5379% | EXPLAINED |
| `EMP-C054BACD273C` | только STORE | 1 → 1 (0) | 61 900.00 → 61 900.00 (0) | 60 000.00 → 60 000.00 (0) | 1 900.00 → 1 900.00 (0) | 0.0000% | PASS |
| `EMP-E22076FA5CEA` | SELLERS | 179 → 178 (-1) | 4 683 241.00 → 4 709 241.00 (+26 000.00) | 4 092 655.40 → 4 119 755.40 (+27 100.00) | 590 585.60 → 589 485.60 (-1 100.00) | +0.5552% | EXPLAINED_WITH_CLASSIFICATION_LIMIT |

| Показатель SELLERS | LiveSklad report employee | Приложение original-sale employee | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка | 15 618 300.00 | 15 618 300.00 | 0.00 ₽ | 0.00% | PASS | все shifts внутри cohort | return links |
| Себестоимость | 13 896 145.40 | 13 896 145.40 | 0.00 ₽ | 0.00% | PASS | — | exact aggregation |
| Валовая прибыль | 1 722 154.60 | 1 722 154.60 | 0.00 ₽ | 0.00% | PASS | — | exact aggregation |
| Маржа | 11.0265% | 11.0265% | 0.0000 п. п. | 0.00% | PASS | — | recomputation |
| Товарные единицы | 522 | 522 | 0 ед. | 0.00% | PASS | — | three ranking employees |

Проверены все 51 встречающиеся пары `employee + analytics category`. Финальная semantic
comparison имеет 17 ненулевых строк: 16 текущих shifts образованы шестью возвратами, а `A2`
добавляет `GLASS_IPHONE` и усиливает `SETUP_SERVICE` у одного сотрудника.

| Сотрудник / категория | Delta quantity / revenue / cost / GP | Документы | Причина |
|---|---:|---|---|
| `EMP-677C05FBCA1C / CASE_APPLE_IPHONE` | +1 / +1 490.00 / +105.00 / +1 385.00 | `F13` | return attribution |
| `EMP-677C05FBCA1C / GLASS_IPHONE` | +1 / +1 990.00 / +125.00 / +1 865.00 | `F13` | return attribution |
| `EMP-677C05FBCA1C / IPHONE_NEW_ASIS` | +1 / +71 900.00 / +69 900.00 / +2 000.00 | `F13` | return attribution |
| `EMP-677C05FBCA1C / PODS_WATCH_OTHER_DEVICE` | +1 / +30 900.00 / +29 000.00 / +1 900.00 | `F10` | return attribution |
| `EMP-677C05FBCA1C / SAMSUNG_NEW` | +2 / +145 800.00 / +142 200.00 / +3 600.00 | `F5`, `F6` | return attribution |
| `EMP-677C05FBCA1C / WARRANTY_GENERIC` | +1 / +7 220.00 / 0 / +7 220.00 | `F13` | return attribution |
| `EMP-7919F0EF8E94 / CASE_APPLE_IPHONE` | -1 / -1 490.00 / -105.00 / -1 385.00 | `F13` | return attribution |
| `EMP-7919F0EF8E94 / GLASS_IPHONE` | -1 / -1 990.00 / -125.00 / -1 865.00 | `F13` | return attribution |
| `EMP-7919F0EF8E94 / IPAD_MAC` | -1 / -59 900.00 / -56 100.00 / -3 800.00 | `F8` | return attribution |
| `EMP-7919F0EF8E94 / IPHONE_NEW_ASIS` | -1 / -71 900.00 / -69 900.00 / -2 000.00 | `F13` | return attribution |
| `EMP-7919F0EF8E94 / SAMSUNG_NEW` | -2 / -145 800.00 / -142 200.00 / -3 600.00 | `F5`, `F6` | return attribution |
| `EMP-7919F0EF8E94 / SETUP_SERVICE` | +1 / +3 000.00 / 0 / +3 000.00 | `F4` | return attribution |
| `EMP-7919F0EF8E94 / WARRANTY_GENERIC` | -1 / -7 220.00 / 0 / -7 220.00 | `F13` | return attribution |
| `EMP-E22076FA5CEA / GLASS_IPHONE` | +1 / +6 000.00 / +3 000.00 / +3 000.00 | `A2` | **classification error** |
| `EMP-E22076FA5CEA / IPAD_MAC` | +1 / +59 900.00 / +56 100.00 / +3 800.00 | `F8` | return attribution |
| `EMP-E22076FA5CEA / PODS_WATCH_OTHER_DEVICE` | -1 / -30 900.00 / -29 000.00 / -1 900.00 | `F10` | return attribution |
| `EMP-E22076FA5CEA / SETUP_SERVICE` | -2 / -9 000.00 / -3 000.00 / -6 000.00 | `F4`, `A2` | attribution + classification |

## Доказательство атрибуции возвратов

| Return | Return external_id | XLSX employee | App/original employee | Original sale | Original external_id | Сумма / cost / GP | Проверки |
|---|---|---|---|---|---|---:|---|
| `F10` | `6a675d9ce861c2246ef1ee8a` | `EMP-677C05FBCA1C` | `EMP-E22076FA5CEA` | `B260` | `6a675975c309375be6c06a9d` | -30 900.00 / -29 000.00 / -1 900.00 | exists, active, same store |
| `F13` | `6a6cf1e3b75c90ff234a606d` | `EMP-677C05FBCA1C` | `EMP-7919F0EF8E94` | `B301` | `6a6cc0efaa17fa28140ad0cc` | -82 600.00 / -70 130.00 / -12 470.00 | exists, active, same store |
| `F4` | `6a5f8276c3093744c8482d90` | `EMP-7919F0EF8E94` | `EMP-E22076FA5CEA` | `B33` | `6a526c1718597635886e8261` | -3 000.00 / 0 / -3 000.00 | exists, active, same store |
| `F5` | `6a61f25bc3093738de7053f1` | `EMP-677C05FBCA1C` | `EMP-7919F0EF8E94` | `B205` | `6a610ca8c309375dde642135` | -72 900.00 / -71 100.00 / -1 800.00 | exists, active, same store |
| `F6` | `6a61fb17e861c2683aa2d310` | `EMP-677C05FBCA1C` | `EMP-7919F0EF8E94` | `B206` | `6a61f2d4c30937365d7065e6` | -72 900.00 / -71 100.00 / -1 800.00 | exists, active, same store |
| `F8` | `6a64a2aae861c273fbccbc46` | `EMP-E22076FA5CEA` | `EMP-7919F0EF8E94` | `B229` | `6a6496fcc309370cd89aaa53` | -59 900.00 / -56 100.00 / -3 800.00 | exists, active, same store |

Остальные шесть returns имеют одинакового XLSX и original-sale employee. У всех 12 returns
`original_missing=false`, `attributed_to_original_employee=true`, `same_store=true`.

Item-level вклад шести документов:

| Return | Product external_id / позиция | Analytics / payroll | Quantity | Revenue | Cost | GP |
|---|---|---|---:|---:|---:|---:|
| `F10` | `696cc6a206ce0a7e0d50f7dc` / Apple Watch S11 | `PODS_WATCH_OTHER_DEVICE / TECH_TIER_2` | -1 | -30 900.00 | -29 000.00 | -1 900.00 |
| `F13` | `69734d5cb4f1c32555396c41` / iPhone 17 | `IPHONE_NEW_ASIS / TECH_TIER_1` | -1 | -71 900.00 | -69 900.00 | -2 000.00 |
| `F13` | `697cdffc4785e2f7e0f0d65b` / защитное стекло | `GLASS_IPHONE / ACCESSORY` | -1 | -1 990.00 | -125.00 | -1 865.00 |
| `F13` | `6a4f57f0aca185b8c00616fa` / Моби Сфера Check+ | `WARRANTY_GENERIC / SERVICE` | -1 | -7 220.00 | 0.00 | -7 220.00 |
| `F13` | `6a607380e861c21aae82af12` / чехол iPhone 17 | `CASE_APPLE_IPHONE / ACCESSORY` | -1 | -1 490.00 | -105.00 | -1 385.00 |
| `F4` | `69109c94bd63ebb20e666957` / перенос данных | `SETUP_SERVICE / SERVICE` | -1 | -3 000.00 | 0.00 | -3 000.00 |
| `F5` | `69c3ee15db593d52573aeb24` / Samsung S26 Ultra | `SAMSUNG_NEW / TECH_TIER_1` | -1 | -72 900.00 | -71 100.00 | -1 800.00 |
| `F6` | `69c3ee15db593d52573aeb24` / Samsung S26 Ultra | `SAMSUNG_NEW / TECH_TIER_1` | -1 | -72 900.00 | -71 100.00 | -1 800.00 |
| `F8` | `69da2bfe2a67e622c96cfbe8` / MacBook Neo | `IPAD_MAC / TECH_TIER_1` | -1 | -59 900.00 | -56 100.00 | -3 800.00 |

Нельзя менять приложение так, чтобы оно повторяло processing employee возврата: это нарушит
ADR-0001. Эти differences объяснены и исправления не требуют.

## Attach-rate: STORE

Формула независимо пересчитана из signed units. Production SQL совпал с текущей сохранённой
classification (`production mismatch = 0`), но semantic correction `A2` меняет два numerator.

| Показатель | LiveSklad semantic N/B/rate | Приложение N/B/rate | Разница N/B/rate | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| `ACCESSORY_IPAD` | 2 / 9 / 22.22% | 2 / 9 / 22.22% | 0 / 0 / 0.00 | 0.00% | PASS | — | recomputation |
| `ACCESSORY_PODS_WATCH` | 4 / 49 / 8.16% | 4 / 49 / 8.16% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `CASE_APPLE_IPHONE` | 28 / 134 / 20.90% | 28 / 134 / 20.90% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `CASE_SAMSUNG` | 8 / 46 / 17.39% | 8 / 46 / 17.39% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `CHARGER_CABLE` | 55 / 180 / 30.56% | 55 / 180 / 30.56% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `FILM_PHONE` | 16 / 180 / 8.89% | 16 / 180 / 8.89% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `GLASS_CAMERA_IPHONE` | 18 / 134 / 13.43% | 18 / 134 / 13.43% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `GLASS_CAMERA_SAMSUNG` | 5 / 46 / 10.87% | 5 / 46 / 10.87% | 0 / 0 / 0.00 | 0.00% | PASS | — | same |
| `GLASS_IPHONE` | 43 / 134 / 32.09% | 44 / 134 / 32.84% | +1 / 0 / +0.75 | +2.34% rate | FAIL | `A2` counted as glass | item classification |
| `GLASS_SAMSUNG` | 6 / 46 / 13.04% | 6 / 46 / 13.04% | 0 / 0 / 0.00 | 0.00% | PASS | — | recomputation |
| `PREMIUM_PROTECTION` | 0 / 249 / 0.00% | 0 / 249 / 0.00% | 0 / 0 / 0.00 | — | PASS | — | same |
| `SETUP_SERVICE` | 62 / 185 / 33.51% | 61 / 185 / 32.97% | -1 / 0 / -0.54 | -1.61% rate | FAIL | `A2` absent from service | item classification |
| `WARRANTY_GENERIC_NEW` | 11 / 135 / 8.15% | 11 / 135 / 8.15% | 0 / 0 / 0.00 | 0.00% | PASS | — | recomputation |
| `WARRANTY_GENERIC_USED` | 4 / 45 / 8.89% | 4 / 45 / 8.89% | 0 / 0 / 0.00 | 0.00% | PASS | — | recomputation |

## Attach-rate: SELLERS

Матрицы показывают текущее application `N/B/rate`; `†` означает отличие от XLSX employee
семантики из-за возвратов, `‡` — дополнительную классификационную ошибку `A2`.

| Сотрудник | `CASE_APPLE_IPHONE` | `GLASS_IPHONE` | `GLASS_CAMERA_IPHONE` | `CASE_SAMSUNG` | `GLASS_SAMSUNG` | `GLASS_CAMERA_SAMSUNG` | `CHARGER_CABLE` |
|---|---|---|---|---|---|---|---|
| `EMP-677C05FBCA1C` | 12/44/27.27%† | 13/44/29.55%† | 4/44/9.09%† | 3/14/21.43%† | 0/14/0.00%† | 0/14/0.00%† | 19/58/32.76%† |
| `EMP-7919F0EF8E94` | 7/53/13.21%† | 18/53/33.96%† | 9/53/16.98%† | 2/11/18.18%† | 1/11/9.09%† | 0/11/0.00%† | 21/64/32.81%† |
| `EMP-E22076FA5CEA` | 9/37/24.32% | 13/37/35.14%‡ | 5/37/13.51% | 3/20/15.00% | 5/20/25.00% | 5/20/25.00% | 15/57/26.32% |

| Сотрудник | `FILM_PHONE` | `SETUP_SERVICE` | `ACCESSORY_PODS_WATCH` | `ACCESSORY_IPAD` | `WARRANTY_GENERIC_USED` | `WARRANTY_GENERIC_NEW` | `PREMIUM_PROTECTION` |
|---|---|---|---|---|---|---|---|
| `EMP-677C05FBCA1C` | 8/58/13.79%† | 14/59/23.73%† | 3/14/21.43%† | 1/4/25.00% | 1/14/7.14% | 4/44/9.09%† | 0/79/0.00%† |
| `EMP-7919F0EF8E94` | 2/64/3.13%† | 24/67/35.82%† | 0/18/0.00% | 0/4/0.00% | 1/14/7.14% | 0/50/0.00%† | 0/90/0.00%† |
| `EMP-E22076FA5CEA` | 6/57/10.53% | 23/58/39.66%†‡ | 1/17/5.88%† | 1/1/100.00% | 2/17/11.76% | 7/40/17.50% | 0/79/0.00% |

25 текущих seller cells с `†` разложены до шести returns:

| Сотрудник / metric | LiveSklad N/B/rate | Приложение N/B/rate | Delta | Документы |
|---|---:|---:|---:|---|
| `EMP-677C05FBCA1C / ACCESSORY_PODS_WATCH` | 3/13/23.08% | 3/14/21.43% | 0/+1/-1.65 п. п. | `F10` |
| `EMP-677C05FBCA1C / CASE_APPLE_IPHONE` | 11/43/25.58% | 12/44/27.27% | +1/+1/+1.69 п. п. | `F13` |
| `EMP-677C05FBCA1C / CASE_SAMSUNG` | 3/12/25.00% | 3/14/21.43% | 0/+2/-3.57 п. п. | `F5`, `F6` |
| `EMP-677C05FBCA1C / CHARGER_CABLE` | 19/55/34.55% | 19/58/32.76% | 0/+3/-1.79 п. п. | `F5`, `F6`, `F13` |
| `EMP-677C05FBCA1C / FILM_PHONE` | 8/55/14.55% | 8/58/13.79% | 0/+3/-0.76 п. п. | `F5`, `F6`, `F13` |
| `EMP-677C05FBCA1C / GLASS_CAMERA_IPHONE` | 4/43/9.30% | 4/44/9.09% | 0/+1/-0.21 п. п. | `F13` |
| `EMP-677C05FBCA1C / GLASS_CAMERA_SAMSUNG` | 0/12/0.00% | 0/14/0.00% | 0/+2/0.00 п. п. | `F5`, `F6` |
| `EMP-677C05FBCA1C / GLASS_IPHONE` | 12/43/27.91% | 13/44/29.55% | +1/+1/+1.64 п. п. | `F13` |
| `EMP-677C05FBCA1C / GLASS_SAMSUNG` | 0/12/0.00% | 0/14/0.00% | 0/+2/0.00 п. п. | `F5`, `F6` |
| `EMP-677C05FBCA1C / PREMIUM_PROTECTION` | 0/75/0.00% | 0/79/0.00% | 0/+4/0.00 п. п. | `F5`, `F6`, `F10`, `F13` |
| `EMP-677C05FBCA1C / SETUP_SERVICE` | 14/56/25.00% | 14/59/23.73% | 0/+3/-1.27 п. п. | `F5`, `F6`, `F13` |
| `EMP-677C05FBCA1C / WARRANTY_GENERIC_NEW` | 3/41/7.32% | 4/44/9.09% | +1/+3/+1.77 п. п. | `F5`, `F6`, `F13` |
| `EMP-7919F0EF8E94 / CASE_APPLE_IPHONE` | 8/54/14.81% | 7/53/13.21% | -1/-1/-1.60 п. п. | `F13` |
| `EMP-7919F0EF8E94 / CASE_SAMSUNG` | 2/13/15.38% | 2/11/18.18% | 0/-2/+2.80 п. п. | `F5`, `F6` |
| `EMP-7919F0EF8E94 / CHARGER_CABLE` | 21/67/31.34% | 21/64/32.81% | 0/-3/+1.47 п. п. | `F5`, `F6`, `F13` |
| `EMP-7919F0EF8E94 / FILM_PHONE` | 2/67/2.99% | 2/64/3.13% | 0/-3/+0.14 п. п. | `F5`, `F6`, `F13` |
| `EMP-7919F0EF8E94 / GLASS_CAMERA_IPHONE` | 9/54/16.67% | 9/53/16.98% | 0/-1/+0.31 п. п. | `F13` |
| `EMP-7919F0EF8E94 / GLASS_CAMERA_SAMSUNG` | 0/13/0.00% | 0/11/0.00% | 0/-2/0.00 п. п. | `F5`, `F6` |
| `EMP-7919F0EF8E94 / GLASS_IPHONE` | 19/54/35.19% | 18/53/33.96% | -1/-1/-1.23 п. п. | `F13` |
| `EMP-7919F0EF8E94 / GLASS_SAMSUNG` | 1/13/7.69% | 1/11/9.09% | 0/-2/+1.40 п. п. | `F5`, `F6` |
| `EMP-7919F0EF8E94 / PREMIUM_PROTECTION` | 0/94/0.00% | 0/90/0.00% | 0/-4/0.00 п. п. | `F5`, `F6`, `F8`, `F13` |
| `EMP-7919F0EF8E94 / SETUP_SERVICE` | 23/71/32.39% | 24/67/35.82% | +1/-4/+3.43 п. п. | `F4`, `F5`, `F6`, `F8`, `F13` |
| `EMP-7919F0EF8E94 / WARRANTY_GENERIC_NEW` | 1/53/1.89% | 0/50/0.00% | -1/-3/-1.89 п. п. | `F5`, `F6`, `F13` |
| `EMP-E22076FA5CEA / ACCESSORY_PODS_WATCH` | 1/18/5.56% | 1/17/5.88% | 0/-1/+0.32 п. п. | `F10` |
| `EMP-E22076FA5CEA / SETUP_SERVICE` | 24/57/42.11% | 23/58/39.66% | -1/+1/-2.45 п. п. | `F4`, `F8` |

После semantic correction `A2` добавляет ещё одну cell и меняет последнюю строку:

| Сотрудник / metric | Semantic LiveSklad | Текущее приложение | Delta | Причина |
|---|---:|---:|---:|---|
| `EMP-E22076FA5CEA / GLASS_IPHONE` | 12/37/32.43% | 13/37/35.14% | +1/0/+2.71 п. п. | `A2` |
| `EMP-E22076FA5CEA / SETUP_SERVICE` | 25/57/43.86% | 23/58/39.66% | -2/+1/-4.20 п. п. | `A2` + `F4`, `F8` |

## Продажи и движение денег — разные контуры

Sales XLSX даёт 15 667 300.00 ₽ net merchandise revenue без order positions. `Оплачено`
также 15 667 300.00 ₽, `Возврат` — 0.00 ₽. Signed `sales_payments` приложения равен
15 667 300.00 ₽: delta 0.00 ₽. Для всех 316 trade documents cash identity совпала; 12 товарных
возвратов отражены отрицательным `Оплачено`, а не отдельной колонкой `Возврат`.

Orders XLSX отдельно показывает 12 900.00 ₽ `Оплачено` и 0.00 ₽ `Возврат`. Приложение хранит
12 900.00 ₽ как revenue order positions, но `OrderSyncPersistence` создаёт zero cash payload и
удаляет payments для `orderPosition`.

| Показатель денег | LiveSklad | Приложение | Разница, ₽ | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Продажи/возвраты без заказов | 15 667 300.00 | 15 667 300.00 | 0.00 | 0.00% | PASS | — | sales XLSX vs signed payments |
| Оплаты заказов | 12 900.00 | 0.00 | -12 900.00 | -100.00% | NOT_MODELLED | отличие модели, не sync gap | orders XLSX + order sync code |
| Полный наблюдаемый cash movement | 15 680 200.00 | 15 667 300.00 | -12 900.00 | -0.0823% | PASS_WITH_LIMIT | payment ledger не охватывает orders | сумма двух контуров |

Revenue/GP KPI position-based и поэтому не повреждены. Но `sales_payments` нельзя выдавать за
полный cash total магазина без order-level payment ingestion или явного UI-ограничения.

## Выданные заказы

| Заказ | Production external_id | Дата выдачи XLSX | Ед. | Сумма | Cost | GP | Статус |
|---|---|---|---:|---:|---:|---:|---|
| `A2` | `order:6a522bbeaca1854bcd37042c:position:6a5751e8e861c21596f2bb69` | 2026-07-15 | 1 | 6 000.00 | 3 000.00 | 3 000.00 | **FINANCE PASS / CLASSIFICATION FAIL** |
| `A3` | `order:6a54e41baca18586d85b6d26:position:6a5641891859763b3ea8c4f5` | 2026-07-14 | 1 | 6 900.00 | 4 000.00 | 2 900.00 | PASS |

`A2` — product external_id `691f300cd9d5829537762c4c`, `is_work=true`, работа
`Замена заднего стекла IPhone`; `A3` — product external_id `69f8bd3dfaf85ec7747ac2ae`,
`is_work=true`, работа `Ремонт Face ID`, корректно классифицированная как
`SETUP_SERVICE / SERVICE`.

## Документы, позиции и время

Сопоставление выполнено по `source_document_type + document_number`, затем по product; quantity,
amount, cost, kind, store, date и employee использовались как контрольные поля. XLSX не содержит
`external_id`, поэтому production IDs использованы для доказательства исключений.

Все 318 timestamps в XLSX на один час позже Kaliningrad time: 315 delta равны 59 минутам из-за
секунд, обрезанных в XLSX, три — 60 минут. Первый документ `B1`:
XLSX `2026-07-09 12:05`, production local `2026-07-09 11:05:57`. Последний `B307`:
XLSX `2026-07-31 22:09`, production local `2026-07-31 21:09:10`. Business date ни у одного
документа не изменилась; на месячных границах missing/extra facts нет.

У `B3` текущее XLSX-название и production snapshot-name различаются suffix `магазин`. Это
единственное переименование; match однозначен, quantity/amount/cost совпали.

## Качество себестоимости и классификационная первопричина

| Проверка | Результат |
|---|---:|
| Missing cost active items | 0 |
| `ZERO_UNEXPECTED` active items | 0 |
| `UNMAPPED` active items | 0 |
| `EXCLUDE` active items | 0 |
| Deleted documents/items in scope | 0 / 0 |
| Product-group classification conflicts | 0 |

Для `A2` доказана следующая цепочка:

1. LiveSklad order-position payload сохранил `is_work=true`.
2. Orders XLSX независимо показывает 6 000.00 ₽ только в работе, 0.00 ₽ в запчастях и
   3 000.00 ₽ в себестоимости работ.
3. Production item сохранил `analytics_category_code=GLASS_IPHONE`,
   `analytics_category_kind=ACCESSORY`, `payroll_category_code=ACCESSORY`.
4. `classification_version=livesklad-product-rules-v5:iphone-glass` доказывает автоматическое,
   а не ручное происхождение назначения.
5. Текущий `ProductAutoClassificationRuleEngine` сначала проверяет service keywords, затем
   accessory keywords и только после них fallback `sourceKind == SERVICE`. Название содержит
   `стекл`, но не содержит распознаваемое правило `ремонт/установк`; поэтому срабатывает
   `iphone-glass` раньше source-kind fallback.
6. Тест покрывает только неизвестную service-name, но не конфликт `SERVICE + стекло`.

Первопричина установлена: ошибка приложения в приоритете auto-classification rules и test gap.

## Классификация причин

| Класс причины | Результат | Доказательство |
|---|---|---|
| Неполное покрытие синхронизации | не обнаружено | full-period success + 318/318 documents, 555/555 product groups |
| Пропущенный или скрытый возврат | не обнаружено | 12/12 returns; cash identities exact |
| Неверная классификация | **обнаружена** | `A2`, source work → `GLASS_IPHONE / ACCESSORY` |
| Неправильный магазин | не обнаружено | один target store; все return originals same store |
| Неправильный сотрудник | шесть report/app differences, все ожидаемы | `F4`, `F5`, `F6`, `F8`, `F10`, `F13`; ADR-0001 |
| Отличие формул | подтверждено и ограничено | order payments не моделируются; revenue facts совпадают |
| Отличие границ периода | financial effect 0 | exact sets, same business dates, documented one-hour XLSX offset |
| Дубликат или удаление | не обнаружено | no duplicate sales docs/rows; deleted facts = 0 |
| Ошибка исходных данных LiveSklad | не установлена | source arithmetic identities exact |
| Ошибка приложения | **обнаружена в classification** | rule version и текущий code path; financial sync error не обнаружен |

## Исходный pre-fix план безопасного исправления

Этот раздел сохраняет план на момент первоначальной диагностики. Он superseded выполненной
correction и финальным post-fix вердиктом в начале документа.

До развёртывания candidate rule не выполнять обычный backfill: пропущенных данных нет, а повтор
production-кода снова присвоит ту же категорию.

Статус безопасного исправления:

1. Payroll target для product external_id `691f300cd9d5829537762c4c` подтверждён:
   `PAID_REPAIR`. Аналитический target остаётся независимым `SETUP_SERVICE`.
2. Локально подготовлены regression cases для конфликтующих названий работ и изменён приоритет
   rule engine; warranty/protection service rules сохраняют более специфичный приоритет.
3. Локальный rule version поднят до `livesklad-product-rules-v8`. Изменённые main/test classes
   компилируются на Java 21, regression probe прошёл. Полный Gradle-прогон остаётся обязательным
   перед деплоем: в текущем окружении dependency metadata не разрешилась без сети.
4. После отдельного согласования деплоя выполнить только exact-target replay/reclassification `A2`
   с guards по store,
   document external_id, item external_id, product external_id, текущей category/version,
   quantity, amount и cost. Broad backfill не использовать.
5. Повторить эту сверку. Финансовые totals должны остаться прежними; 6 000.00 ₽ / 3 000.00 ₽ /
   3 000.00 ₽ должны перейти из аксессуаров в услуги, а affected attach-rate — совпасть с
   semantic expectation.

## Исходные pre-fix итоговые ответы

1. **Что совпало точно:** все финансовые итоги `STORE` и aggregate `SELLERS`, 318 документов,
   556 строк, 555 product groups, продажи, 12 возвратов, оба заказа, cost/GP, cash продаж,
   return links и все unaffected categories/attach metrics.
2. **Что не совпало:** `A2` неверно классифицирован как accessory; из-за этого сдвинуты
   accessory/service structure, две analytics categories, payroll category и attach-rate.
   Индивидуальные employee differences по шести returns объяснены и корректны для приложения.
3. **Документы, формирующие differences:** classification — `A2`; employee attribution —
   `F4`, `F5`, `F6`, `F8`, `F10`, `F13`; payment-model limit — `A2`, `A3`.
4. **Первопричина:** `SERVICE` fallback выполняется после generic glass rule; отсутствует
   regression test конфликтующего work-name.
5. **Необходимые исправления:** rule/test + payroll assignment `PAID_REPAIR` + guarded exact-target
   reclassification/replay; order payment ingestion — отдельная продуктовая задача, не блокирующая
   текущие revenue/GP KPI.
6. **Требуется ли повторная синхронизация:** для полноты месяца — нет. После исправления rule
   потребуется только exact-target replay/reclassification `A2`; обычный backfill не нужен.
7. **Можно ли доверять месяцу:** да для total revenue/cost/GP/margin, документов, единиц,
   aggregate `SELLERS` и unaffected metrics. Нельзя без ограничения доверять accessory/service,
   `GLASS_IPHONE`, `SETUP_SERVICE`, affected attach-rate и payroll category `A2` до correction.
