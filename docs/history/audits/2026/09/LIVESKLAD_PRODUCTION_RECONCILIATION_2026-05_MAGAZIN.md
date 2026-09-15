---
doc_schema: 1
doc_type: evidence
status: historical
owner: product
audience:
  - developer
  - operator
snapshot_date: 2026-09-14
verdict: PARTIAL
verdict_scope: "МАГАЗИН, 2026-05-01..2026-05-31 inclusive, Europe/Kaliningrad; post-correction STORE and aggregate SELLERS metrics are exact, while individual EBC/EE11 attribution remains limited by F000244."
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
  - targeted read-only LiveSklad API verification of 36 active returns and their original sales
  - current application formulas, classification rules and recovery guards
  - exact post-correction document, position, category, employee and attach-rate reconciliation
required_reviewers:
  - product
  - operations
---

# Сверка LiveSklad ↔ production: май 2026, МАГАЗИН

## Текущий вердикт

`PARTIAL`. После согласованных точечных исправлений итог `STORE`, агрегат `SELLERS`, все документы,
товарные группы, коммерческая структура, аналитические и зарплатные категории совпадают с
проверенной семантикой LiveSklad. Ненулевая финансовая разница устранена полностью.

Май нельзя закрыть как полный `PASS`: возврат `F000244` по-прежнему не связан с исходной продажей
`B002456`. Это не меняет показатели магазина или общий итог рейтинговых продавцов, но переносит
одну возвращённую позицию между `EMP-EBC62AF18E20` и `EMP-EE11A56D7AF0`, искажая их личные KPI и
attach-rate. Детальное post-correction доказательство приведено в конце документа.

## Исходный вердикт до исправлений

`FAIL_ACTION_REQUIRED`. Диагностика была завершена, но исправления ещё не применялись. На тот
момент production-итогу мая доверять было нельзя: приложение завышало чистую выручку на 77,070.00 ₽,
себестоимость на 2,650.00 ₽, GP на 74,420.00 ₽ и чистое количество на 6 ед. Причина полностью
разложена до шести позиций четырёх возвратов с нулевой оплатой: `F000229`, `F000230`, `F000247`,
`F000250`.

Отдельно обнаружены две нефинансовые ошибки проекции:

- `F000244` присутствует с правильными суммами, но не связан с исходной продажей `B002456` и
  поэтому отнесён не тому сотруднику;
- работа `A000409` «Чистка тач-пада и клавиатуры с разборкой» ошибочно отнесена к аксессуарам,
  хотя по инструкции заказчика это `SETUP_SERVICE / SERVICE`.

До завершения этой диагностики ни один backfill, recovery, relink, category update, deploy или
иной production write не выполнялся. Следующие разделы сохраняют исходное pre-correction
доказательство; актуальный результат повторной сверки находится в конце документа.

## Scope, фильтры и источники

| Параметр | Зафиксированное значение | Доказательство |
|---|---|---|
| Магазин | `МАГАЗИН` | выбор пользователя; production store с точным именем |
| Период | `2026-05-01..2026-05-31`, обе даты включительно | содержимое XLSX и `business_date` SQL |
| Business timezone | `Europe/Kaliningrad`, начало дня `00:00` | production store и текущий period contract |
| «Товары и работы» | магазин `МАГАЗИН`; май; `Продажа`, `Возврат`, `Установка в заказ`; orders `Выдан` | effective XLSX content |
| «Продажи» | магазин `МАГАЗИН`; май; продажи и sale returns | 807 unique document rows |
| «Заказы» | магазин `МАГАЗИН`; дата выдачи в мае; выданные заказы | 6 unique rows, включая один нулевой заказ |

XLSX не сохраняют все экранные фильтры как машиночитаемые metadata. Поэтому зафиксированы
переданные пользователем параметры и отдельно проверено effective content каждого файла.

| Файл | Строк данных | Размер | SHA-256 |
|---|---:|---:|---|
| `Отчёт по товарам и работам май.xlsx` | 2,014 | 872,264 B | `87652630f5cd5166170aa98b40d3e65ae756722e5c90e73a98df5df6fb974cc6` |
| `Отчёт по продажам май.xlsx` | 807 | 392,915 B | `2bd1dd6587046b43ea91815a2c077c399885e091b78015b0197b16cd1f373edb` |
| `Отчёт по заказам май.xlsx` | 6 | 21,367 B | `af0e2cdd384941fb80e6e9b0fee52a886741ab023d36098acc39fd6826418c45` |

Свежий read-only запуск подтвердил runtime identity из
[`project-state.md`](../../../../current/project-state.md) и healthy-состояние production. SQL
аудит работал с read-only guards и завершился `ROLLBACK`. Для source-проверки возвратов
использован существующий SSH-ключ и одноразовый read-only helper; новые ключи не создавались,
секреты и персональные данные не сохранялись.

## Семантика сравнения

- знак факта: `SALE=+1`, `RETURN=-1`; GP = revenue − cost;
- STORE включает все факты магазина, SELLERS — только пять рейтинговых сотрудников;
- для SELLERS возврат относится сотруднику исходной продажи по ADR-0001, а не сотруднику,
  указанному в return document XLSX;
- аналитические и зарплатные категории рассчитаны раздельно;
- attach-rate использует чистые item units: числитель и знаменатель после возвратов;
- колонка `Возврат` и cash transaction не подменяют merchandise return;
- нулевая себестоимость допустима по решению пользователя, но остаётся quality limitation.

## Полнота синхронизации

Исторические sync runs содержат как успешные, так и failed/partial записи, поэтому старый статус
не использован как доказательство полноты. Текущее покрытие проверено по документам и позициям:

- LiveSklad: 812 ненулевых document keys — 771 sale, 36 sale return, 5 order positions;
- production: 808 — 771 sale, 32 sale return, 5 order positions;
- only-Live: ровно `F000229`, `F000230`, `F000247`, `F000250`; only-app — 0;
- у 808 общих документов quantity, revenue и cost совпали без единого value mismatch;
- после агрегации одинаковых строк LiveSklad имеет 1,997 product groups, production — 1,991;
  only-Live — ровно шесть позиций четырёх возвратов; only-app и value mismatches — 0;
- deleted documents/items, `UNMAPPED`, `EXCLUDE`, missing cost и ambiguous matches — 0;
- все присутствующие production documents имеют успешную последнюю синхронизацию и raw version;
- source API независимо вернул 36 активных returns / 74 позиции / 80 ед. /
  2,098,080.00 ₽ revenue / 1,626,505.00 ₽ cost — точно как XLSX.

Итог: sales и ненулевые orders покрыты полностью; returns покрыты на 32/36 документов и 68/74
позиций. Причина пропуска доказана ниже.

## STORE: месячный итог

LiveSklad означает проверенную бизнес-семантику трёх отчётов; приложение — текущий production.
Разница везде считается как приложение минус LiveSklad.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка | 47,868,126.00 | 47,945,196.00 | +77,070.00 ₽ | +0.16% | FAIL | 4 пропущенных zero-payment returns | 6 only-Live positions |
| Себестоимость | 40,565,593.00 | 40,568,243.00 | +2,650.00 ₽ | +0.01% | FAIL | отсутствует cost `F000230` | exact source/API position totals |
| Валовая прибыль | 7,302,533.00 | 7,376,953.00 | +74,420.00 ₽ | +1.02% | FAIL | сумма тех же четырёх returns | `R-C` |
| Валовая маржа | 15.2555% | 15.3862% | +0.1307 п. п. | +0.86% | FAIL | завышены revenue и GP | единое финальное округление |
| Чистое количество | 1,915 ед. | 1,921 ед. | +6 ед. | +0.31% | FAIL | отсутствуют 6 return units | item reconciliation |
| Продажи | 771 док. / 1,990 ед. / 49,951,206.00 ₽ | то же | 0 | 0.00% | PASS | — | все sale documents и product groups exact |
| Возвраты продаж | 36 док. / −80 ед. / −2,098,080.00 ₽ | 32 док. / −74 ед. / −2,021,010.00 ₽ | −4 док. / +6 ед. / +77,070.00 ₽ | +3.67% по абсолютной revenue | FAIL | 4 zero-payment returns не обнаружены cash-based sync | XLSX + API + SQL |
| Выданные заказы | 6 строк; 5 ненулевых; 5 ед.; 15,000.00 ₽ | 5 position facts / 5 ед. / 15,000.00 ₽ | 0 по ненулевым фактам | 0.00% | EXPLAINED | нулевой `A000376` не создаёт fact | orders + goods + SQL |
| Возвраты заказов | 0 | 0 | 0 | — | PASS | отсутствуют | orders report и production facts |

## Документы и позиции, формирующие финансовую разницу

У всех четырёх returns `transactionDate=null`: товарный возврат существует, но денежного события
нет. Текущий discovery path нашёл остальные 32 returns через cash event и пропустил эти четыре.
LiveSklad API подтвердил переданные пользователем return IDs, parent sale, original position и
product IDs; detected violations — 0.

| Return / external_id | Дата XLSX | Original sale / external_id | Original employee | Позиции | Signed qty / revenue / cost / GP | Статус |
|---|---|---|---|---:|---:|---|
| `F000229` / `69f9c23914775ce82db23b63` | 05.05 | `B004409` / `69ee62672a67e62f8ba0337d` | `EMP-DD4F314C0A50` | Care 35,550 + установка покрытия 1,490 | −2 / −37,040.00 / 0.00 / −37,040.00 | MISSING_RETURN |
| `F000230` / `69f9dff3bafcec175adab6f6` | 05.05 | `B004409` / `69ee62672a67e62f8ba0337d` | `EMP-DD4F314C0A50` | СЗУ 7,990 + чехол 3,990 | −2 / −11,980.00 / −2,650.00 / −9,330.00 | MISSING_RETURN |
| `F000247` / `6a1079746c1fce447047f350` | 22.05 | `B002993` / `69ab01094ab91faba6122fa5` | `EMP-B802A9C3FDD3` | Care 15,990 | −1 / −15,990.00 / 0.00 / −15,990.00 | MISSING_RETURN |
| `F000250` / `6a147a84370f9f2066dac89b` | 25.05 | `B004860` / `6a09b6559f4f9fb32ed05bfc` | `EMP-789FAC07DBB9` | Care 12,060 | −1 / −12,060.00 / 0.00 / −12,060.00 | MISSING_RETURN |
| **Итого** | — | — | — | **6** | **−6 / −77,070.00 / −2,650.00 / −74,420.00** | **EXACT DELTA** |

## STORE: структура продаж

Категории LiveSklad ниже — не скрытые поля XLSX, а реконструкция по exact-matched позициям и
утверждённой заказчиком смысловой классификации. `A000409` уже отнесён к ожидаемой услуге.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Техника | 678 ед. / 43,435,399.00 ₽ / GP 3,495,489.00 ₽ | то же | 0 | 0.00% | PASS | — | category item match |
| Аксессуары | 859 ед. / 2,698,631.00 ₽ / GP 2,081,448.00 ₽ | 862 / 2,715,111.00 / GP 2,093,278.00 | +3 / +16,480.00 ₽ / +11,830.00 ₽ GP | +0.61% revenue | FAIL | `F000230` и `A000409` | position decomposition |
| Услуги | 378 ед. / 1,734,096.00 ₽ / GP 1,725,596.00 ₽ | 381 / 1,794,686.00 / GP 1,788,186.00 | +3 / +60,590.00 ₽ / +62,590.00 ₽ GP | +3.49% revenue | FAIL | `F000229`, `F000247`, `F000250`; `A000409` отсутствует в service | position decomposition |
| Допы | 1,237 ед. / 4,432,727.00 ₽ / GP 3,807,044.00 ₽ | 1,243 / 4,509,797.00 / GP 3,881,464.00 | +6 / +77,070.00 ₽ / +74,420.00 ₽ GP | +1.74% revenue | FAIL | все 6 missing return positions; перенос `A000409` внутри допов нейтрален | recomputation |

## Все аналитические категории STORE

Формат: `quantity / revenue / cost / GP`. В дельте приложение минус ожидание.

| Категория | LiveSklad / ожидается | Приложение | Разница | Статус | Причина |
|---|---:|---:|---:|---|---|
| `ACCESSORY_IPAD_MAC` | 6 / 19,560 / 3,740 / 15,820 | 7 / 24,060 / 5,740 / 18,320 | +1 / +4,500 / +2,000 / +2,500 | FAIL | `A000409` |
| `ACCESSORY_PODS_WATCH` | 26 / 46,600 / 9,680 / 36,920 | то же | 0 | PASS_WITH_LIMIT | 2 source zero costs внутри категории |
| `CASE_APPLE_IPHONE` | 102 / 276,951 / 87,040 / 189,911 | то же | 0 | PASS | — |
| `CASE_SAMSUNG` | 19 / 92,480 / 32,550 / 59,930 | то же | 0 | PASS | — |
| `CHARGER_CABLE` | 235 / 1,018,460 / 292,126 / 726,334 | 236 / 1,026,450 / 293,676 / 732,774 | +1 / +7,990 / +1,550 / +6,440 | FAIL | `F000230` |
| `FILM_PHONE` | 46 / 80,510 / 3,040 / 77,470 | то же | 0 | PASS | — |
| `GLASS_CAMERA_IPHONE` | 71 / 188,485 / 46,850 / 141,635 | то же | 0 | PASS | — |
| `GLASS_CAMERA_SAMSUNG` | 4 / 11,960 / 2,550 / 9,410 | то же | 0 | PASS | — |
| `GLASS_IPHONE` | 233 / 614,424 / 55,032 / 559,392 | то же | 0 | PASS | — |
| `GLASS_SAMSUNG` | 25 / 64,311 / 6,235 / 58,076 | то же | 0 | PASS | — |
| `IPAD_MAC` | 38 / 3,027,800 / 2,858,300 / 169,500 | то же | 0 | PASS | — |
| `IPHONE_NEW_ASIS` | 331 / 27,454,740 / 26,181,180 / 1,273,560 | то же | 0 | PASS | — |
| `IPHONE_USED` | 150 / 6,247,256 / 4,663,590 / 1,583,666 | то же | 0 | PASS | STORE finance unaffected by employee orphan |
| `OTHER_ACCESSORY_PRODUCT` | 92 / 284,890 / 78,340 / 206,550 | 93 / 288,880 / 79,440 / 209,440 | +1 / +3,990 / +1,100 / +2,890 | FAIL | `F000230` |
| `PODS_WATCH_OTHER_DEVICE` | 83 / 1,597,690 / 1,423,550 / 174,140 | то же | 0 | PASS | — |
| `PREMIUM_PROTECTION` | 1 / 13,612 / 0 / 13,612 | то же | 0 | PASS | — |
| `SAMSUNG_NEW` | 72 / 4,895,943 / 4,629,590 / 266,353 | то же | 0 | PASS | — |
| `SAMSUNG_USED` | 4 / 211,970 / 183,700 / 28,270 | то же | 0 | PASS | — |
| `SETUP_SERVICE` | 226 / 604,223 / 8,500 / 595,723 | 226 / 601,213 / 6,500 / 594,713 | 0 / −3,010 / −2,000 / −1,010 | FAIL | missing `F000229` −1,490 и misclassified `A000409` +4,500 |
| `WARRANTY_GENERIC` | 151 / 1,116,261 / 0 / 1,116,261 | 154 / 1,179,861 / 0 / 1,179,861 | +3 / +63,600 / 0 / +63,600 | FAIL | `F000229`, `F000247`, `F000250` |

## Зарплатные категории STORE

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| `ACCESSORY` | 859 / 2,698,631 / cost 617,183 / GP 2,081,448 | 862 / 2,715,111 / 621,833 / 2,093,278 | +3 / +16,480 / +4,650 / +11,830 | +0.61% revenue | FAIL | `F000230`, `A000409` | item categories |
| `SERVICE` | 378 / 1,734,096 / cost 8,500 / GP 1,725,596 | 381 / 1,794,686 / 6,500 / 1,788,186 | +3 / +60,590 / −2,000 / +62,590 | +3.49% revenue | FAIL | missing services и `A000409` | item categories |
| `PAID_REPAIR` | 0 | 0 | 0 | — | PASS | `A000409` — чистка, не подтверждённый ремонт | customer semantics |
| `TECH_TIER_1` | 576 / 41,148,659 / 37,866,960 / 3,281,699 | то же | 0 | 0.00% | PASS | — | item categories |
| `TECH_TIER_2` | 102 / 2,286,740 / 2,072,950 / 213,790 | то же | 0 | 0.00% | PASS | — | item categories |

Колонка XLSX «Зарплата за продажу» равна нулю и не является payroll KPI приложения.

## Attach-rate STORE

| Показатель | LiveSklad N/B/rate | Приложение N/B/rate | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| `ACCESSORY_IPAD` | 6 / 18 / 33.33% | то же | 0 | 0.00 п. п. | PASS | `A000409` должен уйти из категории, но эта метрика использует корректный semantic numerator | item recomputation |
| `ACCESSORY_PODS_WATCH` | 26 / 68 / 38.24% | то же | 0 | 0.00 п. п. | PASS | — | item recomputation |
| `CASE_APPLE_IPHONE` | 102 / 481 / 21.21% | то же | 0 | 0.00 п. п. | PASS | — | item recomputation |
| `CASE_SAMSUNG` | 19 / 76 / 25.00% | то же | 0 | 0.00 п. п. | PASS | — | item recomputation |
| `CHARGER_CABLE` | 235 / 557 / 42.19% | 236 / 557 / 42.37% | +1 / 0 / +0.18 п. п. | +0.18 п. п. | FAIL | `F000230` | exact missing position |
| `FILM_PHONE` | 46 / 557 / 8.26% | то же | 0 | 0.00 п. п. | PASS | — | item recomputation |
| `GLASS_CAMERA_IPHONE` | 71 / 481 / 14.76% | то же | 0 | 0.00 п. п. | PASS | — | item recomputation |
| `GLASS_CAMERA_SAMSUNG` | 4 / 76 / 5.26% | то же | 0 | 0.00 п. п. | PASS | — | item recomputation |
| `GLASS_IPHONE` | 233 / 481 / 48.44% | то же | 0 | 0.00 п. п. | PASS | — | item recomputation |
| `GLASS_SAMSUNG` | 25 / 76 / 32.89% | то же | 0 | 0.00 п. п. | PASS | — | item recomputation |
| `PREMIUM_PROTECTION` | 7 / 676 / 1.04% | то же | 0 | 0.00 п. п. | PASS | — | item recomputation |
| `SETUP_SERVICE` | 226 / 576 / 39.24% | то же | 0 | 0.00 п. п. | PASS_WITH_EXPLAINED_CANCELLATION | `F000229` −1 и `A000409` +1 взаимно погасились | item decomposition |
| `WARRANTY_GENERIC_NEW` | 84 / 403 / 20.84% | 87 / 403 / 21.59% | +3 / 0 / +0.75 п. п. | +0.75 п. п. | FAIL | 3 missing Care returns | exact missing positions |
| `WARRANTY_GENERIC_USED` | 61 / 154 / 39.61% | то же | 0 | 0.00 п. п. | PASS | — | item recomputation |

Текущий quality counter attach-rate также содержит 1 `classification_issue_item` из-за
`A000409`; после корректной классификации ожидается 0.

## SELLERS: отдельный scope

`EMP-B802A9C3FDD3` не входит в рейтинг; остальные пять сотрудников входят. Поэтому STORE и
SELLERS имеют разные итоги. Основная таблица использует требуемую original-sale attribution.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка SELLERS | 47,892,696.00 | 47,953,776.00 | +61,080.00 ₽ | +0.13% | FAIL | 3 ranking missing returns; `F000247` non-ranking | original-sale reconstruction |
| Себестоимость SELLERS | 40,559,093.00 | 40,561,743.00 | +2,650.00 ₽ | +0.01% | FAIL | `F000230` | item reconstruction |
| GP SELLERS | 7,333,603.00 | 7,392,033.00 | +58,430.00 ₽ | +0.80% | FAIL | те же missing returns | `R-C` |
| Маржа SELLERS | 15.3126% | 15.4149% | +0.1023 п. п. | +0.67% | FAIL | revenue/GP завышены | recomputation |
| Чистое количество SELLERS | 1,917 | 1,922 | +5 | +0.26% | FAIL | один из 6 units относится non-ranking seller | employee attribution |
| Техника SELLERS | 678 / 43,439,989 / GP 3,507,579 | то же | 0 | 0.00% | PASS | — | category reconstruction |
| Аксессуары SELLERS | 860 / 2,702,621 / GP 2,084,438 | 863 / 2,719,101 / GP 2,096,268 | +3 / +16,480 / +11,830 GP | +0.61% revenue | FAIL | `F000230`, `A000409` | position decomposition |
| Услуги SELLERS | 379 / 1,750,086 / GP 1,741,586 | 381 / 1,794,686 / GP 1,788,186 | +2 / +44,600 / +46,600 GP | +2.55% revenue | FAIL | ranking missing services; `F000247` исключён | position decomposition |

Если механически использовать сотрудника return document из XLSX, SELLERS дают 1,913 ед. /
47,738,236.00 ₽ revenue / 40,449,893.00 ₽ cost / 7,288,343.00 ₽ GP. Это не целевой KPI:
разница с semantic expectation полностью создаётся 21 переносом возвратов к исходной продаже.

### Итог каждого сотрудника

LiveSklad здесь означает semantic expectation после source-validated original-sale attribution.
Разница — приложение минус ожидание.

| Сотрудник | Ranking | LiveSklad: qty / revenue / cost / GP | Приложение: qty / revenue / cost / GP | Разница qty / revenue / cost / GP | Revenue delta, % | Статус / документ |
|---|---|---:|---:|---:|---:|---|
| `EMP-789FAC07DBB9` | да | 370 / 9,243,490 / 7,912,612 / 1,330,878 | 371 / 9,255,550 / 7,912,612 / 1,342,938 | +1 / +12,060 / 0 / +12,060 | +0.13% | FAIL — `F000250` |
| `EMP-B802A9C3FDD3` | нет | −2 / −24,570 / 6,500 / −31,070 | −1 / −8,580 / 6,500 / −15,080 | +1 / +15,990 / 0 / +15,990 | n/a | FAIL — `F000247` |
| `EMP-C92D2C99CC54` | да | 339 / 10,349,072 / 9,112,070 / 1,237,002 | то же | 0 | 0.00% | PASS |
| `EMP-DD4F314C0A50` | да | 503 / 8,657,511 / 6,732,956 / 1,924,555 | 507 / 8,706,531 / 6,735,606 / 1,970,925 | +4 / +49,020 / +2,650 / +46,370 | +0.57% | FAIL — `F000229`, `F000230` |
| `EMP-EBC62AF18E20` | да | 254 / 7,596,870 / 6,702,564 / 894,306 | 253 / 7,563,880 / 6,696,564 / 867,316 | −1 / −32,990 / −6,000 / −26,990 | −0.43% | FAIL — `F000244` wrongly assigned here |
| `EMP-EE11A56D7AF0` | да | 451 / 12,045,753 / 10,098,891 / 1,946,862 | 452 / 12,078,743 / 10,104,891 / 1,973,852 | +1 / +32,990 / +6,000 / +26,990 | +0.27% | FAIL — `F000244` absent here |

Employee-category проверка охватила 96 пар: 86 совпали, 10 ненулевых дельт полностью
раскладываются на пять проблемных документов:

| Employee / category | Разница qty / revenue / cost / GP | Документ | Статус |
|---|---:|---|---|
| `EMP-789FAC07DBB9 / WARRANTY_GENERIC` | +1 / +12,060 / 0 / +12,060 | `F000250` | FAIL |
| `EMP-B802A9C3FDD3 / WARRANTY_GENERIC` | +1 / +15,990 / 0 / +15,990 | `F000247` | FAIL |
| `EMP-DD4F314C0A50 / CHARGER_CABLE` | +1 / +7,990 / +1,550 / +6,440 | `F000230` | FAIL |
| `EMP-DD4F314C0A50 / OTHER_ACCESSORY_PRODUCT` | +1 / +3,990 / +1,100 / +2,890 | `F000230` | FAIL |
| `EMP-DD4F314C0A50 / SETUP_SERVICE` | +1 / +1,490 / 0 / +1,490 | `F000229` | FAIL |
| `EMP-DD4F314C0A50 / WARRANTY_GENERIC` | +1 / +35,550 / 0 / +35,550 | `F000229` | FAIL |
| `EMP-EBC62AF18E20 / ACCESSORY_IPAD_MAC` | +1 / +4,500 / +2,000 / +2,500 | `A000409` | FAIL |
| `EMP-EBC62AF18E20 / SETUP_SERVICE` | −1 / −4,500 / −2,000 / −2,500 | `A000409` | FAIL |
| `EMP-EBC62AF18E20 / IPHONE_USED` | −1 / −32,990 / −6,000 / −26,990 | `F000244` | FAIL |
| `EMP-EE11A56D7AF0 / IPHONE_USED` | +1 / +32,990 / +6,000 / +26,990 | `F000244` | FAIL |

Employee-payroll содержит 23 пары: 15 совпали, 8 ненулевых. Они являются тем же разложением:
`789/SERVICE +12,060`; `B802/SERVICE +15,990`; `DD4/ACCESSORY +11,980` и `DD4/SERVICE
+37,040`; `EBC/ACCESSORY +4,500`, `EBC/SERVICE −4,500`, `EBC/TECH_TIER_1 −32,990` и
`EE11/TECH_TIER_1 +32,990` (revenue; cost/GP совпадают с документами выше).

### Атрибуция возвратов

Source API подтвердил original sale/position для всех 36 returns. У 15 XLSX employee уже равен
original-sale employee. Ещё 21 должен переноситься по ADR-0001: для 16 существующих returns
production уже делает это правильно, четыре отсутствуют целиком, а `F000244` — единственный
существующий orphan. Его суммы верны, но original links в приложении пусты.

| Return / external_id | XLSX employee → original employee | Original sale / external_id | Signed qty / revenue / cost / GP | Статус |
|---|---|---|---:|---|
| `F000224` / `69f4a6fefaf85e79ff489165` | `C92` → `EE11` | `B004504` / `69f4a6dc8db3957e1c795e9c` | −2 / −13,480 / −11,475 / −2,005 | PASS_ATTRIBUTED |
| `F000227` / `69f87f48faf85e3c1f727530` | `EBC` → `B802` | `B003874` / `69d78bebb9ed974a64083a3f` | −1 / −60,990 / −46,000 / −14,990 | PASS_ATTRIBUTED |
| `F000228` / `69f8a8258db3953f6fa9088f` | `EBC` → `789` | `B004273` / `69e8c50f2a67e6f3834ef4aa` | −1 / −65,500 / −49,000 / −16,500 | PASS_ATTRIBUTED |
| `F000229` / `69f9c23914775ce82db23b63` | `EBC` → `DD4` | `B004409` / `69ee62672a67e62f8ba0337d` | −2 / −37,040 / 0 / −37,040 | MISSING_RETURN |
| `F000230` / `69f9dff3bafcec175adab6f6` | `EBC` → `DD4` | `B004409` / `69ee62672a67e62f8ba0337d` | −2 / −11,980 / −2,650 / −9,330 | MISSING_RETURN |
| `F000231` / `69fa2645fcafc8624ccacc89` | `EBC` → `C92` | `B004582` / `69fa1f22bafcecf0b1e207c7` | −1 / −81,000 / −76,420 / −4,580 | PASS_ATTRIBUTED |
| `F000233` / `69fe11dd03a8a24ca307dd2a` | `C92` → `789` | `B004657` / `69fe116503a8a2548107d758` | −2 / −88,450 / −63,550 / −24,900 | PASS_ATTRIBUTED |
| `F000234` / `6a01a05d3e4f8a4026d5c3bc` | `C92` → `B802` | `B004208` / `69e4d75e2a67e637680c634f` | −1 / −3,990 / −1,000 / −2,990 | PASS_ATTRIBUTED |
| `F000237` / `6a0604de279a724147087d59` | `789` → `EE11` | `B004778` / `6a05d0a1279a726bcf039cca` | −1 / −64,990 / −59,100 / −5,890 | PASS_ATTRIBUTED |
| `F000238` / `6a076813279a7246a8202e61` | `EBC` → `DD4` | `B004811` / `6a075a809f4f9f36f6b65e88` | −6 / −149,990 / −87,750 / −62,240 | PASS_ATTRIBUTED |
| `F000242` / `6a0c33006c1fce2040f72cbb` | `EBC` → `EE11` | `B004360` / `69ecd4d6b4a9030d6a6cacc6` | −1 / −56,990 / −48,300 / −8,690 | PASS_ATTRIBUTED |
| `F000243` / `6a0c5342370f9f3da05824c5` | `C92` → `EE11` | `B004905` / `6a0b4ed6279a72c9f6569735` | −1 / −26,990 / −15,000 / −11,990 | PASS_ATTRIBUTED |
| `F000244` / `6a0c61fe6c1fce5a57fe52e1` | `EBC` → `EE11` | `B002456` / `69933838b3671d4eac7f715b` | −1 / −32,990 / −6,000 / −26,990 | FAIL_ORPHAN |
| `F000245` / `6a105fe3370f9f1b2aa1a111` | `EBC` → `EE11` | `B004986` / `6a105eeb370f9f7a04a17fba` | −5 / −79,390 / −64,037 / −15,353 | PASS_ATTRIBUTED |
| `F000246` / `6a107811370f9f800ea449f9` | `EBC` → `B802` | `B002993` / `69ab01094ab91faba6122fa5` | −1 / −73,490 / −62,200 / −11,290 | PASS_ATTRIBUTED |
| `F000247` / `6a1079746c1fce447047f350` | `EBC` → `B802` | `B002993` / `69ab01094ab91faba6122fa5` | −1 / −15,990 / 0 / −15,990 | MISSING_RETURN |
| `F000248` / `6a133df76c1fce353e669deb` | `EBC` → `DD4` | `B005047` / `6a12fca06c1fce9827632449` | −17 / −187,000 / −120,562 / −66,438 | PASS_ATTRIBUTED |
| `F000249` / `6a1348ad370f9f6fabc35dbe` | `EBC` → `C92` | `B005051` / `6a1308eb370f9f7fcfc07a1a` | −1 / −490 / −100 / −390 | PASS_ATTRIBUTED |
| `F000250` / `6a147a84370f9f2066dac89b` | `EBC` → `789` | `B004860` / `6a09b6559f4f9fb32ed05bfc` | −1 / −12,060 / 0 / −12,060 | MISSING_RETURN |
| `F000251` / `6a149c55370f9f0769dc0339` | `EBC` → `EE11` | `B005071` / `6a142b356c1fce17da73ea42` | −5 / −83,380 / −58,099 / −25,281 | PASS_ATTRIBUTED |
| `F000257` / `6a1acb8cce47a2851b1aa1d0` | `C92` → `EE11` | `B005101` / `6a15964225ca8cff752e8486` | −1 / −2,990 / −750 / −2,240 | PASS_ATTRIBUTED |

Для `F000244` API дополнительно совпал по original position
`69933669b3671d56567f45e8`, return position `6a0c61fe6c1fceed05fe52e0` и product
`69708eff560dfc7f2d747ae5`. Это исключает предположение и доказывает referential orphan в
приложении.

### Attach-rate SELLERS

Из 70 employee/metric комбинаций 50 совпали. Все 20 ненулевых раскладываются только на
`F000229`, `F000230`, `F000244`, `F000250` и `A000409`; non-ranking `F000247` в SELLERS не входит.

| Employee / metric | LiveSklad N/B/rate | App N/B/rate | Delta N/B/п. п. | Причина |
|---|---:|---:|---:|---|
| `789 / WARRANTY_GENERIC_NEW` | 21 / 81 / 25.93% | 22 / 81 / 27.16% | +1 / 0 / +1.23 | `F000250` |
| `DD4 / CHARGER_CABLE` | 51 / 99 / 51.52% | 52 / 99 / 52.53% | +1 / 0 / +1.01 | `F000230` |
| `DD4 / SETUP_SERVICE` | 122 / 102 / 119.61% | 123 / 102 / 120.59% | +1 / 0 / +0.98 | `F000229` |
| `DD4 / WARRANTY_GENERIC_NEW` | 22 / 67 / 32.84% | 23 / 67 / 34.33% | +1 / 0 / +1.49 | `F000229` |
| `EBC / CASE_APPLE_IPHONE` | 12 / 74 / 16.22% | 12 / 73 / 16.44% | 0 / −1 / +0.22 | `F000244` denominator |
| `EBC / CHARGER_CABLE` | 30 / 88 / 34.09% | 30 / 87 / 34.48% | 0 / −1 / +0.39 | `F000244` denominator |
| `EBC / FILM_PHONE` | 9 / 88 / 10.23% | 9 / 87 / 10.34% | 0 / −1 / +0.11 | `F000244` denominator |
| `EBC / GLASS_CAMERA_IPHONE` | 5 / 74 / 6.76% | 5 / 73 / 6.85% | 0 / −1 / +0.09 | `F000244` denominator |
| `EBC / GLASS_IPHONE` | 31 / 74 / 41.89% | 31 / 73 / 42.47% | 0 / −1 / +0.58 | `F000244` denominator |
| `EBC / PREMIUM_PROTECTION` | 0 / 110 / 0.00% | 0 / 109 / 0.00% | 0 / −1 / 0.00 | `F000244` denominator |
| `EBC / SETUP_SERVICE` | 21 / 92 / 22.83% | 20 / 91 / 21.98% | −1 / −1 / −0.85 | `A000409` + `F000244` |
| `EBC / WARRANTY_GENERIC_USED` | 5 / 19 / 26.32% | 5 / 18 / 27.78% | 0 / −1 / +1.46 | `F000244` denominator |
| `EE11 / CASE_APPLE_IPHONE` | 25 / 119 / 21.01% | 25 / 120 / 20.83% | 0 / +1 / −0.18 | `F000244` denominator |
| `EE11 / CHARGER_CABLE` | 66 / 135 / 48.89% | 66 / 136 / 48.53% | 0 / +1 / −0.36 | `F000244` denominator |
| `EE11 / FILM_PHONE` | 9 / 135 / 6.67% | 9 / 136 / 6.62% | 0 / +1 / −0.05 | `F000244` denominator |
| `EE11 / GLASS_CAMERA_IPHONE` | 15 / 119 / 12.61% | 15 / 120 / 12.50% | 0 / +1 / −0.11 | `F000244` denominator |
| `EE11 / GLASS_IPHONE` | 58 / 119 / 48.74% | 58 / 120 / 48.33% | 0 / +1 / −0.41 | `F000244` denominator |
| `EE11 / PREMIUM_PROTECTION` | 3 / 163 / 1.84% | 3 / 164 / 1.83% | 0 / +1 / −0.01 | `F000244` denominator |
| `EE11 / SETUP_SERVICE` | 30 / 142 / 21.13% | 30 / 143 / 20.98% | 0 / +1 / −0.15 | `F000244` denominator |
| `EE11 / WARRANTY_GENERIC_USED` | 22 / 38 / 57.89% | 22 / 39 / 56.41% | 0 / +1 / −1.48 | `F000244` denominator |

## Продажи, возвраты и движение денег

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Merchandise: sales − sale returns | 47,853,126.00 | 47,930,196.00 | +77,070.00 | +0.16% | FAIL | 4 zero-payment returns отсутствуют в item facts | sales/goods/API |
| Sales report `Оплачено` | 47,930,196.00 | signed `sales_payments` 47,930,196.00 | 0.00 | 0.00% | PASS | payment ledger не должен создавать merchandise return | payment facts |
| Sales report `Возврат` | 0.00 | — | — | — | EXPLAINED | колонка не равна merchandise returns | 36 return documents существуют |
| Orders `Оплачено` | 15,000.00 | 0.00 в `sales_payments` | −15,000.00 | −100% | NOT_MODELLED | order-position sync хранит работу, не order cash | order facts |
| Полный наблюдаемый cash | 47,945,196.00 | — | — | — | EXPLAINED | sales paid + order paid | отчёты |

Совпадение текущей app revenue 47,945,196.00 ₽ с полным cash случайно: merchandise завышена на
77,070.00 ₽ из-за пропущенных возвратов, а cash на ту же сумму выше корректной merchandise
выручки, потому что у этих возвратов не было возврата оплаты. После recovery revenue обязана
уменьшиться; payment ledger — остаться неизменным.

## Заказы

| Заказ | Дата выдачи | Позиции | Amount | Cost | GP | Статус |
|---|---|---:|---:|---:|---:|---|
| `A000376` | 01.05 | 0 | 0.00 | 0.00 | 0.00 | EXPLAINED_ZERO_ORDER |
| `A000378` | 06.05 | 1 | 3,500.00 | 2,500.00 | 1,000.00 | MATCH |
| `A000380` | 07.05 | 1 | 1,000.00 | 0.00 | 1,000.00 | MATCH |
| `A000386` | 12.05 | 1 | 2,000.00 | 1,000.00 | 1,000.00 | MATCH |
| `A000409` | 19.05 | 1 | 4,500.00 | 2,000.00 | 2,500.00 | MATCH_FINANCE; FAIL_CLASSIFICATION |
| `A000436` | 31.05 | 1 | 4,000.00 | 2,000.00 | 2,000.00 | MATCH |

Возвратов заказов нет. Все ненулевые amounts состоят из работ; parts = 0.

## Ошибка классификации `A000409`

| Поле | Доказанное значение |
|---|---|
| Document / external_id | `A000409` / `order:6a0b069e9f4f9f507ae4f269:position:6a0c8ac6370f9fb44d5f0f99` |
| Item external_id / product external_id | `6a0c8ac6370f9fb44d5f0f99` / `69b6c307685be777d337a36e` |
| Позиция | `Чистка тач-пада и клавиатуры с разборкой` |
| Source semantics | `is_work=true`; orders: work 4,500.00 ₽, parts 0.00 ₽, work cost 2,000.00 ₽ |
| Production | `ACCESSORY_IPAD_MAC / ACCESSORY` |
| Ожидается | `SETUP_SERVICE / SERVICE` |

Первопричина воспроизводится в deployed classification rule: лексическое совпадение по типу
устройства срабатывает раньше service fallback. Инструкция заказчика прямо включает комплексную
очистку и аналогичные платные работы в услуги. Это чистка, поэтому `PAID_REPAIR` без отдельного
подтверждения не назначается.

## Построчная проверка и границы периода

2,014 raw XLSX rows соответствуют 2,003 ожидаемым application item facts после консолидации 11
повторных occurrences одинакового товара внутри документа. Из-за шести missing return positions
production хранит 1,997 item facts. При отдельной группировке `document + product` получаются
1,997 source groups и 1,991 app groups; дельта — те же шесть позиций. Одиннадцать occurrences —
агрегация строк, а не дубликат факта:

| Документ | Товар | XLSX rows → app rows | Qty | Amount | Статус |
|---|---|---:|---:|---:|---|
| `B004511` | защитное стекло Remax iPhone 17 Pro | 2 → 1 | 2 | 6,980 | EXPLAINED_AGGREGATION |
| `B004515` | iPhone 17 256GB Black | 2 → 1 | 2 | 129,800 | EXPLAINED_AGGREGATION |
| `B004525` | защитное стекло Supglass | 2 → 1 | 2 | 4,980 | EXPLAINED_AGGREGATION |
| `B004655` | Apple Watch S11 42mm | 2 → 1 | 2 | 59,800 | EXPLAINED_AGGREGATION |
| `B004830` | iPhone 17 Pro Max 256GB | 2 → 1 | 2 | 207,800 | EXPLAINED_AGGREGATION |
| `B004830` | защита камеры Keephone | 2 → 1 | 2 | 4,990 | EXPLAINED_AGGREGATION |
| `B004976` | защитное стекло Remax | 2 → 1 | 2 | 4,000 | EXPLAINED_AGGREGATION |
| `B005180` | защитное стекло Supglass | 2 → 1 | 2 | 7,000 | EXPLAINED_AGGREGATION |
| `B005245` | защитное стекло Supglass | 2 → 1 | 2 | 5,980 | EXPLAINED_AGGREGATION |
| `B005258` | iPhone 17 Pro 512GB | 2 → 1 | 2 | 215,800 | EXPLAINED_AGGREGATION |
| `B005258` | защитное стекло Supglass | 2 → 1 | 2 | 5,980 | EXPLAINED_AGGREGATION |

У `B004897` текущее source name содержит позднюю пометку о браке, которой нет в snapshot;
document, quantity, amount и cost совпали, ambiguity — 0. Все 808 общих timestamps в XLSX на час
позже production local representation: 796 offsets по 59 минут из-за обрезанных секунд и 12 по
60 минут. Ни один документ не перешёл через границу месяца; финансовый эффект — 0. Округлительных
разниц — 0.

## ZERO_UNEXPECTED

Обе позиции точно имеют cost 0 в исходном XLSX; пользователь подтвердил, что нулевая
себестоимость допустима. Они не создают межсистемную дельту, но ограничивают доверие к GP:

| Документ | Позиция | Qty | Revenue | Cost | Категория | Статус |
|---|---|---:|---:|---:|---|---|
| `B004807` | `Ремешок миланская петля 38-41mm` | 1 | 1,000.00 | 0.00 | `ACCESSORY_PODS_WATCH` | PASS_WITH_LIMIT |
| `B005188` | `Ремешок миланская петля 38-41mm` | 1 | 1,000.00 | 0.00 | `ACCESSORY_PODS_WATCH` | PASS_WITH_LIMIT |

## Классификация причин

| Класс причины | Итог | Доказательство |
|---|---|---|
| Неполное покрытие синхронизации | обнаружено | 4 only-Live returns / 6 positions |
| Пропущенный или скрытый возврат | обнаружено | `F000229`, `F000230`, `F000247`, `F000250`; source active, `transactionDate=null` |
| Неверная классификация | обнаружена | `A000409`: работа/чистка сохранена как accessory |
| Неправильный магазин или сотрудник | магазин — нет; employee error — 1 | `F000244` source links ведут к `B002456 / EE11`, app links пусты |
| Отличие формул | только order cash | 15,000.00 ₽ orders не входят в `sales_payments` |
| Отличие границ периода | effect 0 | exact common set; one-hour presentation offset без перехода даты |
| Дубликат или удаление | не обнаружено | 11 row aggregations с exact values; deleted 0 |
| Ошибка исходных данных LiveSklad | финансовая ошибка не доказана | 2 разрешённых zero costs; 4 zero-payment returns — валидная source-семантика |
| Ошибка приложения | доказана в трёх местах | cash-only return discovery, orphan link `F000244`, rule priority `A000409` |

## Исходный план безопасного исправления

1. **Что совпало точно.** Все 771 продажи, пять ненулевых order positions, 808 общих документов,
   1,991 общая product group, sale payment ledger, техника и 15 из 20 STORE analytics categories.
2. **Что не совпало.** STORE revenue/cost/GP/margin/quantity; четыре return documents; пять
   analytics categories; ACCESSORY/SERVICE payroll; две STORE attach-метрики; employee totals и
   SELLERS attach; employee link одного существующего возврата.
3. **Документы разницы.** Финансы: `F000229`, `F000230`, `F000247`, `F000250`. Атрибуция:
   `F000244`. Классификация: `A000409`.
4. **Первопричина.** Zero-payment returns не попали в cash-based discovery; `F000244` был сохранён
   до появления guarded existing-orphan relink и остался без original links; lexical device rule
   обошёл service semantics для `A000409`.
5. **Необходимые исправления.** После отдельного разрешения: exact-target `MISSING_RETURN`
   recovery только четырёх IDs с manifest guards; bounded classification correction только
   `A000409` на `[2026-05-01, 2026-06-01)` в `SETUP_SERVICE / SERVICE`; после согласованного batch
   deploy — exact guarded relink `F000244` к `B002456` и original position. Долгосрочно discovery
   должен получать zero-payment returns независимо от cash event.
6. **Повторная синхронизация.** Широкий backfill/resync не требуется и небезопасен. Требуются две
   точечные recovery-операции: missing returns и existing orphan relink; classification исправляется
   bounded assignment, а не resync.
7. **Можно ли доверять месяцу.** Сейчас — нет для итоговых STORE/SELLERS KPI, категорий услуг и
   аксессуаров, attach-rate и показателей затронутых сотрудников. Можно доверять совпавшим sales,
   orders finance, payment ledger и exact-matched позициям с оговоркой о двух source zero costs.

После каждого разрешённого исправления обязательны независимый verifier и полный повторный
месячный audit. Целевой результат — нулевая межсистемная дельта; order cash, source zero costs и
ожидаемые ADR-0001 переносы должны остаться явно объяснёнными ограничениями.

## Результат после согласованных исправлений

14 сентября 2026 года с отдельного разрешения пользователя выполнены только следующие действия:

1. exact-target recovery активных zero-payment returns `F000229`, `F000230`, `F000247`, `F000250`;
2. bounded correction единственной майской позиции `A000409` из
   `ACCESSORY_IPAD_MAC / ACCESSORY` в `SETUP_SERVICE / SERVICE` на период
   `[2026-05-01, 2026-06-01)`.

Широкий backfill, общий resync, relink `F000244`, изменение других месяцев и deploy кода не
выполнялись. Каждый return был обработан отдельно и затем независимо найден в read-only месячном
аудите с ожидаемыми количеством, суммой, cost, исходной продажей и сотрудником. Для `A000409`
post-apply verifier подтвердил одну майскую строку, revenue 4,500.00 ₽, cost 2,000.00 ₽ и
неизменность мартовского `A000261`.

### Итоговые STORE и SELLERS

Разница считается как приложение минус проверенная бизнес-семантика LiveSklad.

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Чистая выручка STORE | 47,868,126.00 | 47,868,126.00 | 0.00 ₽ | 0.00% | PASS | — | полный post-correction audit |
| Себестоимость STORE | 40,565,593.00 | 40,565,593.00 | 0.00 ₽ | 0.00% | PASS | — | полный post-correction audit |
| Валовая прибыль STORE | 7,302,533.00 | 7,302,533.00 | 0.00 ₽ | 0.00% | PASS | — | revenue − cost |
| Валовая маржа STORE | 15.2555% | 15.2555% | 0.0000 п. п. | 0.00% | PASS | — | единое финальное округление |
| Чистое количество STORE | 1,915 ед. | 1,915 ед. | 0 ед. | 0.00% | PASS | — | все item groups |
| Продажи | 771 док. / 1,990 ед. / 49,951,206.00 ₽ | то же | 0 | 0.00% | PASS | — | document + position reconciliation |
| Возвраты продаж | 36 док. / −80 ед. / −2,098,080.00 ₽ | то же | 0 | 0.00% | PASS | — | 36/36 returns после recovery |
| Выданные заказы | 6 строк; 5 ненулевых; 5 ед.; 15,000.00 ₽ | 5 facts / 5 ед. / 15,000.00 ₽ | 0 по ненулевым фактам | 0.00% | EXPLAINED | `A000376` имеет нулевую сумму | orders + goods + SQL |
| SELLERS, агрегат пяти рейтинговых сотрудников | 1,917 ед. / 47,892,696.00 ₽ / cost 40,559,093.00 ₽ / GP 7,333,603.00 ₽ | то же | 0 | 0.00% | PASS | — | ADR-0001 semantic comparison |

Документный состав теперь совпадает полностью: 812/812 document keys, only-Live 0, only-app 0,
value mismatches 0. После нормальной агрегации повторяющихся строк совпадают 1,997/1,997
product groups; only-Live 0, only-app 0, value mismatches 0. Разница физических строк
2,014 против 2,003 объясняется ровно 11 агрегациями одинакового товара внутри документа и не
создаёт количественной или денежной разницы.

### Структура, категории и attach-rate

| Показатель | LiveSklad | Приложение | Разница, ₽/ед. | Разница, % | Статус | Причина | Доказательство |
|---|---:|---:|---:|---:|---|---|---|
| Аксессуары STORE | 859 ед. / 2,698,631.00 ₽ / cost 617,183.00 ₽ / GP 2,081,448.00 ₽ | то же | 0 | 0.00% | PASS | — | semantic commercial groups |
| Услуги STORE | 378 ед. / 1,734,096.00 ₽ / cost 8,500.00 ₽ / GP 1,725,596.00 ₽ | то же | 0 | 0.00% | PASS | — | returns + corrected `A000409` |
| Техника STORE | 678 ед. / 43,435,399.00 ₽ / cost 39,939,910.00 ₽ / GP 3,495,489.00 ₽ | то же | 0 | 0.00% | PASS | — | semantic commercial groups |
| Все 20 analytics categories STORE | expected totals | exact | 0 | 0.00% | PASS | — | category mismatch count 0 |
| Все payroll categories STORE | expected totals | exact | 0 | 0.00% | PASS | — | payroll mismatch count 0 |
| STORE attach-rate, 14 метрик | expected numerator / denominator / rate | exact | 0 | 0.00 п. п. | PASS | — | production mismatch count 0 |
| `CHARGER_CABLE` STORE attach | 235 / 557 / 42.19% | то же | 0 / 0 / 0.00 п. п. | 0.00% | PASS | восстановлен `F000230` | full recompute |
| `WARRANTY_GENERIC_NEW` STORE attach | 84 / 403 / 20.84% | то же | 0 / 0 / 0.00 п. п. | 0.00% | PASS | восстановлены три Care returns | full recompute |

Коммерческие группы `STORE` и `SELLERS` проверены раздельно; межгрупповых различий после
исправления нет. `A000409` больше не увеличивает аксессуары и правильно входит в услуги и payroll
`SERVICE`.

### Единственное оставшееся расхождение: `F000244`

Source API доказал, что `F000244` относится к исходной продаже `B002456` и сотруднику
`EMP-EE11A56D7AF0`. В production исходные ссылки пусты, поэтому возврат остаётся у
`EMP-EBC62AF18E20`.

| Показатель | Ожидается | Приложение | Разница приложения | Статус | Доказательство |
|---|---:|---:|---:|---|---|
| `EMP-EBC62AF18E20`, итог | 254 ед. / 7,596,870.00 ₽ / cost 6,702,564.00 ₽ / GP 894,306.00 ₽ | 253 / 7,563,880.00 / 6,696,564.00 / 867,316.00 | −1 ед. / −32,990.00 ₽ / −6,000.00 ₽ / −26,990.00 ₽ GP | PARTIAL | orphan `F000244` ошибочно удержан у EBC |
| `EMP-EE11A56D7AF0`, итог | 451 ед. / 12,045,753.00 ₽ / cost 10,098,891.00 ₽ / GP 1,946,862.00 ₽ | 452 / 12,078,743.00 / 10,104,891.00 / 1,973,852.00 | +1 ед. / +32,990.00 ₽ / +6,000.00 ₽ / +26,990.00 ₽ GP | PARTIAL | тот же orphan не перенесён к EE11 |
| Остальные четыре сотрудника | expected | exact | 0 | PASS | employee semantic comparison |
| Личные attach-rate EBC/EE11 | original-sale attribution | denominator сдвинут на ∓1 в 8 метриках каждого | до 1.48 п. п. | PARTIAL | numerators exact; один used iPhone denominator |
| STORE и aggregate SELLERS | expected | exact | 0 | PASS | эффекты EBC/EE11 взаимно компенсируются |

Это единственный ненулевой semantic delta. Для него не нужен широкий resync: после согласованного
batch deploy требуется exact guarded relink `F000244` к `B002456` и исходной позиции, затем ещё
один полный майский audit. До relink можно доверять STORE, aggregate SELLERS, структуре,
категориям и показателям остальных сотрудников; нельзя использовать личные KPI и attach-rate EBC
и EE11 как окончательные.

### Деньги, качество и операционная безопасность

Payment ledger также совпадает: LiveSklad paid movement и production signed payments равны
47,930,196.00 ₽. Отличие движения денег от товарной выручки объяснено четырьмя возвратами без
оплаты на общую сумму 77,070.00 ₽ и не является межсистемной ошибкой. `F000229`, `F000230`,
`F000247`, `F000250` имеют `payment_amount=0`, как и исходный отчёт.

Missing cost, `UNMAPPED`, `EXCLUDE`, deleted facts и production attach calculation mismatches — 0.
Две разрешённые пользователем позиции `ZERO_UNEXPECTED` остаются ограничением именно исходной
себестоимости и GP, но не создают разницу LiveSklad ↔ приложение.

Временные production runners, их загруженные копии и точечный `NOPASSWD` после проверки удалены;
отдельная read-only проверка вернула `cleanup=verified`. Локальные exact-target scripts сохранены
для воспроизводимости и возможного последующего code review.

### Финальные ответы после исправления

1. **Совпало точно:** STORE revenue, cost, GP, margin, quantity, документы, позиции после
   агрегации, продажи, returns, orders finance, accessories, services, devices, все analytics и
   payroll categories, STORE attach-rate и aggregate SELLERS.
2. **Не совпало:** только личная атрибуция `F000244` между EBC и EE11 и зависящие от неё личные
   attach-rate.
3. **Документ остаточной разницы:** `F000244`; финансовых документов разницы больше нет.
4. **Первопричина:** старый orphan return без original document/item links; источник и правильный
   parent уже доказаны через API.
5. **Необходимое исправление:** exact guarded relink после согласованного batch deploy; никаких
   массовых category updates или backfill.
6. **Повторная синхронизация:** для выполненных исправлений не требуется; после relink нужен
   read-only повторный audit, а не широкий resync.
7. **Доверие к месяцу:** STORE и aggregate SELLERS — да, с оговоркой о двух исходных zero costs;
   EBC/EE11 individual KPI и attach-rate — нет до relink `F000244`; остальные сотрудники — да.
