---
doc_schema: 1
doc_type: evidence
status: historical
owner: ai
audience:
  - developer
  - operator
snapshot_date: 2026-08-31
verdict: PASS_WITH_LIMITS
verdict_scope: "Preserved legacy evidence; commands and runtime claims require current verification."
source_of_truth:
  - "docs/current/ai/README.md"
original_content_sha256: 9f211a026aa1d378b84f9cb323a9eed2a70c6e5a40bf12f8ff40e7ed6cc18be2
required_reviewers:
  - information-architecture
---

> Historical evidence preserved during the documentation reform. Current replacements: `docs/current/ai/README.md`.

# «ИИ-разбор» — аудит текущей реализации v21/schema3

Дата аудита: 2026-08-26
Статус: этап 1 завершён, переход к проектированию контракта требует подтверждения
Production baseline: `v0.1.0-pilot.25`, `weekly-interpretation-v21`, content schema `3`

Документ фиксирует текущее поведение страницы «ИИ-разбор» от расчётных источников до интерфейса.
Это не спецификация новой версии: целевые решения будут формализованы на этапе 2. Исторический
контракт v21/schema3 остаётся immutable.

## 1. Итог аудита

Технический pipeline v21/schema3 надёжен: недельный snapshot immutable, ссылки на evidence
валидируются, публикация атомарна, опубликованная ревизия не исчезает во время обновления, а
privacy-reduced запрос не передаёт провайдеру данные сотрудников.

Продуктовый контракт страницы не соответствует задаче руководителя магазина:

1. Недельный разбор смешивается с месячным планом, причём `PLAN` имеет наивысший приоритет.
2. Сравнение выполняется с правильной предыдущей неделей, но её даты отсутствуют в public API и
   интерфейсе.
3. Основные тексты и карточки сотрудников фактически формирует backend; провайдер выбирает уже
   предопределённый primary signal и почти не добавляет полезного содержания.
4. В production-схеме провайдер не получает данные сотрудников, поэтому не может сформировать
   персональные объяснения или действия. Интерфейс при этом обещает такие действия.
5. Общие открытые data-quality issues магазина, независимо от типа и периода, могут быть ошибочно
   названы проблемой классификации.
6. Командные сравнения используют противоречивые пороги: benchmark требует минимум трёх
   достаточных сотрудников, а текст разрешает сравнение уже для двух.
7. При недоступности провайдера пропадает весь READY-отчёт, хотя почти всё его содержимое уже
   детерминированно принадлежит backend.

Вывод этапа 1: исправлять отдельные фразы в v21 недостаточно. Нужен новый versioned контракт
`v22/schema4` (рабочее обозначение), в котором числа и полезный deterministic report доступны без
LLM, месячный план отсутствует, а ИИ является ограниченным необязательным слоем объяснения.

## 2. Фактическая end-to-end цепочка

```text
sales_documents / sales_document_items / shifts / rating settings / plans
→ StoreKpiService, CategoryKpiService, AttachRateService,
  EmployeeKpiService, EmployeeCategoryKpiService, EmployeeRatingService
→ BackendWeeklyAnalyticsFactsSource
→ WeeklyAnalyticsFacts(current full week, immediately previous full week)
→ WeeklySnapshotDraftBuilder
→ immutable analytics_snapshot + facts + sufficiency + limitations + candidates
→ LlmProviderInputCompactor (v21: store-only, max 2 candidates)
→ weekly-interpretation-v21 + specialized schema3
→ YandexGPT
→ privacy-reduced semantic validation
→ backend enrichment of employees/team/limitations/text
→ immutable interpretation publication
→ WeeklyInsightV3ContentProjector → V2 presentation adapter
→ opaque public evidence EV001…EVnnn
→ GET /api/stores/{storeId}/insights/weekly/current
→ WeeklyInsightView
```

### 2.1. Что в цепочке устойчиво

- Snapshot строится в `REPEATABLE_READ` и хранит версии формул, quality policy и payload hash.
- Интерпретация публикуется только после structural/semantic validation.
- Публикация связывает snapshot, job и attempt; provenance дополнительно защищён в БД.
- Public API выдаёт только cited evidence и заменяет внутренние ссылки локальными `EVxxx`.
- Employee refs заменяются immutable employee IDs и именами из membership snapshot.
- При auto-revision предыдущая опубликованная интерпретация остаётся доступна.
- Старые content schemas читаются отдельными projector-ветками.
- v21 provider input не содержит employee facts, имён, employee refs или limitations.

### 2.2. Главная архитектурная несогласованность

Для v21 backend заранее:

- вычисляет candidates;
- выбирает обязательный primary candidate;
- ограничивает точный текст primary и secondary enum-значениями;
- после ответа снова заменяет candidate-тексты backend-owned формулировками;
- сам создаёт employee headlines, team overview, team relationships и limitations.

Провайдер остаётся обязательным условием публикации, хотя его реальная свобода сведена к
необязательному secondary insight и максимум одному store-level action. Это увеличивает стоимость
и риск недоступности без соразмерной пользовательской ценности.

## 3. Карта периодов

| Область | Текущий период | База | Комментарий |
| --- | --- | --- | --- |
| Snapshot и weekly API | Последняя полностью завершённая локальная неделя, пн–вс | Непосредственно предыдущая полная неделя | Правильно и жёстко валидируется `WeeklyAnalyticsFactsQuery` |
| Store/category/attach/employee KPI | Границы snapshot-недели включительно | Те же даты минус 7 дней | Читаются из нормализованных фактов |
| Average receipt и additional revenue per phone | Та же полная неделя | Предыдущий равный период | Совпадает с weekly comparison |
| Employee workload/rating | Та же полная неделя | Предыдущая полная неделя | Для dynamics требуются достаточные обе недели |
| Monthly plan facts | Месяц, содержащий неделю; actual/projection на конец недели | Месячная цель, не предыдущая неделя | Чужой горизонт внутри weekly report |
| Data-quality open issue count | Все OPEN issues магазина | Нет периодной базы | Не ограничен неделей и типом показателя |
| Public API | Показывает только текущую неделю | Comparison period отсутствует в контракте | Previous values доступны лишь внутри evidence как «Было» |

### 3.1. Расчёт последней недели

В timezone магазина определяется понедельник текущей недели. Отчётный период равен предыдущему
понедельнику–воскресенью. Текущая незавершённая неделя не используется.

### 3.2. Почему месячный план попал в недельный вывод

`BackendWeeklyAnalyticsFactsSource` получает `StorePlanProgressView` с `asOf = periodEnd`.
`StoreSnapshotFactProjector` добавляет `PLAN_*`, а `WeeklyAnalyticalCandidateProjector` создаёт
`PLAN` candidate при отклонении прогноза от 100% минимум на 5 п.п. Затем
`WeeklyPrimarySignalPolicy` ставит `PLAN` выше прибыльности и недельной динамики выручки.

Таким образом, корректное недельное сравнение не сломано; рядом с ним встроен другой месячный
горизонт, который вытесняет недельный сигнал.

## 4. Текущие факты, пороги и candidates

### 4.1. Магазин

| Направление | Факты | Порог candidate | Sufficiency/ограничения |
| --- | --- | --- | --- |
| Чистая выручка | продажи минус возвраты | относительное изменение не менее 5%, previous > 0 | Обычно `SUFFICIENT` |
| Валовая прибыль | net revenue минус cost | относительное изменение не менее 5%, previous > 0 | Факт отсутствует при missing cost |
| Маржа | gross profit / net revenue | Не создаёт отдельный candidate; добавляется к evidence прибыльности при совпадающем направлении | Недоступна при incomplete cost или нулевой выручке |
| Средний чек | net revenue / число продаж | Candidate не создаётся | Факт обычно не отображается |
| Доп. выручка на телефон | additional revenue / phone quantity | Candidate не создаётся | Факт обычно не отображается |
| Категория | revenue + share магазина | Изменение revenue ≥15% и max share текущей/предыдущей недели ≥3%; до 2 ростов и 2 падений | Category quality передаётся отдельным limitation, но sufficiency самого факта остаётся `SUFFICIENT` |
| Attach-rate | net add quantity / net device quantity × 100 | Изменение ≥5 на 100; denominator ≥5 в обе недели; до 2 направлений | `<3` insufficient, `3–4` limited, `≥5` sufficient |
| План | monthly progress/projection на конец недели | Отклонение projected completion от 100% минимум 5 п.п. | `LIMITED`, если plan data не complete through as-of |

Порядок store primary сейчас:

```text
SUFFICIENT before LIMITED before INSUFFICIENT
→ PLAN
→ PROFITABILITY
→ REVENUE_DYNAMICS
→ ADDITIONAL_SALES
→ ATTACH_RATE
→ CATEGORY_MIX
→ TEAM_PERFORMANCE
→ candidateRef
```

### 4.2. Сотрудник

| Проверка | Текущее правило |
| --- | --- |
| Workload insufficient | нет смен, нет часов или часы ≤ 0 |
| Workload limited | одна смена или меньше 12 часов |
| Workload sufficient | минимум 2 смены и минимум 12 часов |
| Overall insufficient | нет Employee KPI, coverage отсутствует/<50% или workload insufficient |
| Overall limited | coverage 50–<75% или workload limited |
| Overall sufficient | coverage ≥75% и workload sufficient |
| Sales structure insufficient | меньше 3 завершённых продаж |
| Sales structure limited | 3–5 завершённых продаж |
| Sales structure sufficient | 6 и более завершённых продаж |
| Employee revenue/time movement | минимум 10% относительно предыдущей недели |
| Additional share movement | минимум 3 п.п. |
| Rating score movement | минимум 5 баллов |
| Attach movement | тот же достаточный denominator обеих недель и минимум 5 на 100 |

В snapshot попадают только ranking-eligible сотрудники с текущей или предыдущей активностью.
Сотрудники вне рейтинга и unassigned исключаются. UI называет список просто «Сотрудники» и не
объясняет этот scope.

### 4.3. Команда

- Benchmark и лидер разрешены только при минимум 3 сотрудниках со статусом `SUFFICIENT`.
- Лидер считается явным при преимуществе минимум 5% над следующим значением.
- Benchmark использует quartiles/median по sufficient employees.
- Most improved требует минимум 3 сопоставимых сотрудников и рост score минимум на 5 баллов.
- Team overview, однако, говорит, что сравнение возможно уже при 2 sufficient employees.
- Tie-ветка сравнивает доступные `RATING_STRUCTURE_SCORE` facts без проверки общего team benchmark
  gate и может использовать LIMITED/INSUFFICIENT facts.

Последние два правила противоречат declared sufficiency policy.

## 5. Что реально делает v21/provider

### 5.1. Provider input

В production-v21 включены `privacyReduced=true` и bounded store signals:

- employee facts и employee candidates удаляются;
- team relationship candidates удаляются;
- limitations удаляются;
- остаётся не более двух store candidates;
- primary уже выбран backend policy;
- передаются отдельные store core/category/attach/plan facts и team eligible count.

### 5.2. Provider output

Prompt требует:

- скопировать обязательный primary candidate;
- опционально выбрать один разрешённый secondary candidate;
- вернуть не более одного store-level action;
- не формировать employee/team-owned поля;
- не писать числа, причины, даты, ранги или свободные candidate-тексты.

Specialized schema дополнительно фиксирует candidate refs и допустимые формулировки. После ответа
validator заменяет candidate narratives точными backend-owned текстами.

### 5.3. Фактическая ценность по сохранённой semantic matrix

Для 26 сценариев v21/schema3:

- provider/semantic pass: `26/26`;
- actions: `0` во всех 26 ответах;
- среднее число secondary insights: `0,0385` (один insight на всю матрицу);
- среднее число primary signals: `0,5`;
- workload blocks: `0`;
- manager-usefulness: в среднем `4,0385/5`, почти все v21-сценарии получили `4`, а не `5`;
- сценарий team tie прямо отмечен ручным reviewer как содержащий повторяющиеся персональные блоки,
  но всё равно прошёл gate.

Следовательно, существующий semantic gate хорошо проверяет безопасность и отсутствие выдумок, но
не доказывает полноту, управленческую полезность или качество финальной страницы.

## 6. Матрица текущих UI-блоков

| Блок | Пользовательское назначение | Источник/владелец | Фактическое поведение v21 | Проблема |
| --- | --- | --- | --- | --- |
| Заголовок страницы | Объяснить назначение | Frontend | «Краткие выводы и действия…» | Actions обычно отсутствуют |
| Период/статус | Показать актуальность | API + frontend | Текущая завершённая неделя, дата публикации, revision, status | Нет дат предыдущей недели и data-through date |
| Итог недели/headline | Главный вывод | Backend primary policy + display policy; provider обязан скопировать ref | PLAN может вытеснить недельную динамику; при отсутствии candidate — neutral headline | Смешение горизонтов; neutral headline без evidence |
| Ключевые показатели | Проверяемое основание headline | Backend evidence projector + frontend | До 3 current values и delta; previous отображается без дат | Нельзя сразу понять, с какой неделей сравнение |
| Контекст недели | Result/dynamics/plan | Canonical summary blocks → V2 adapter | Collapsed; v21 обычно почти пуст; допускает `PLAN_OUTLOOK` | Legacy-контракт и месячный план |
| Что работает/требует внимания | Strength/risk | Secondary insights | Primary signal сюда не попадает; secondary почти всегда отсутствует | Главный риск может быть только headline, секции пусты |
| Действия | Следующие шаги | Provider | v21 corpus: 0/26; employee actions невозможны | Страница не выполняет обещание действий |
| Категории и допродажи | Объяснить структуру/attach | Только candidate-backed secondary insight | Collapsed, максимум небольшой фрагмент, не полный KPI-блок | Не является полноценным объяснением структуры недели |
| Командные результаты | Лидер/динамика/обмен опытом | Backend team overview/relationships | Детерминированный текст и relationships | Порог 2 против benchmark 3; tie bypass |
| Карточка сотрудника | Факты, динамика, сильная сторона, риск, действие | Backend employee headline; V2 legacy slots | В v21 обычно headline + evidence/limitation; подробные summaries, category, attach и actions пусты | UI обещает больше, чем даёт контракт |
| Ограничение сотрудника | Объяснить недостаток базы | Backend limitation + frontend help/evidence | Общий текст; точные смены/часы доступны только после раскрытия evidence | Причина не видна сразу |
| Общие ограничения | Показать влияние качества | Backend manifest + frontend | Все store и employee limitations внизу | Дублируются с карточками; одинаковые тексты dedupe-ятся с потерей scope |
| Preparing/delayed/unavailable | Объяснить отсутствие отчёта | Backend query/fallback + frontend | Generic fallback вместо deterministic report | Provider failure скрывает уже рассчитанные факты |

### 6.1. Desktop/mobile

- На desktop все employee cards показаны списком, подробности раскрываются через native
  `details/summary`.
- На mobile (`≤720px`) сначала показываются 3 сотрудника, остальные раскрываются кнопкой.
- Store context и категории по умолчанию свёрнуты; важные previous values также находятся внутри
  раскрываемого evidence.
- Есть `prefers-reduced-motion`, status roles и native disclosure controls.
- Визуальных browser/e2e assertions именно для weekly page нет; текущие frontend tests проверяют
  DOM/presentation helpers, но не реальный layout, focus order, overflow и cascade.
- CSS-файл содержит несколько поколений повторных определений одних и тех же selectors и media
  queries. Финальный вид зависит от порядка поздних overrides, что повышает риск регрессии.

## 7. Каталог пользовательских текстов и владельцев

| Тип текста | Владелец сейчас | Примеры |
| --- | --- | --- |
| Page/section labels | Frontend | «ИИ-разбор», «Итоги недели», «Сотрудники», «Почему такой вывод» |
| State messages | Backend query service | «Анализируем результаты недели», «Интерпретация готовится дольше обычного» |
| Unavailable fallback | Backend fallback factory; frontend добавляет последнюю строку | «Числовые показатели…», «Есть ограничения качества данных…» |
| Primary/secondary candidate text | Backend `WeeklyCandidateDisplayPolicy` | «Выполнение плана существенно ниже…», «Чистая выручка… снизилась…» |
| Neutral store headline | Backend V3 read projector | «За неделю не выявлено существенных изменений…» |
| Neutral store result | Backend V3 validator | «По магазину нет отдельного существенного изменения…» |
| Team overview | Backend V3 validator/request schema | «Командные данные позволяют сопоставить сотрудников» |
| Employee headline | Backend V3 validator + display policy | Candidate phrase или «По сотруднику нет отдельного существенного изменения…» |
| Team relationships | Backend V3 validator | «Подтверждена возможность обмена практикой…» |
| Limitation summary | Backend V2 validator | Cost/classification specific, остальные generic |
| Evidence label/value/rounding | Backend evidence projector | «Выручка», «Отработанные часы», `Было … · изменение …` |
| Employee insufficient help | Frontend | Общая фраза про смены или coverage |
| Action title/summary | Provider, если action создан | v21 corpus actions отсутствуют |

ИИ не является владельцем основной пользовательской формулировки v21, несмотря на название
раздела.

## 8. Data-quality и availability

### 8.1. Текущая маршрутизация

| Условие | Snapshot status/limitation | Влияние |
| --- | --- | --- |
| NOT_SYNCED/ERROR/dataThrough отсутствует или раньше periodEnd | `BLOCKED`, `SOURCE_DATA_INCOMPLETE` | Provider input запрещён |
| Missing cost item | `PARTIAL`, `COST_DATA_INCOMPLETE` | Profit/margin facts недоступны |
| Unmapped item текущей недели **или любой open issue магазина** | `PARTIAL`, `CLASSIFICATION_QUALITY_LIMITED` | Categories/additional sales помечены ненадёжными |
| Attach mapping/condition issue | `PARTIAL`, `ATTACH_QUALITY_LIMITED` | Attach выводы ограничены |
| Employee overall LIMITED/INSUFFICIENT | Employee limitation | Ограничиваются все employee sections |

### 8.2. Подтверждённые дефекты качества

1. `StoreDataStatusRepository` считает OPEN issues всего магазина, исключая лишь два zero-cost
   кода. Период и issue family не передаются в weekly quality policy.
2. `WeeklySnapshotPolicyV1` трактует любое ненулевое `openQualityIssueCount` как
   `CLASSIFICATION_QUALITY_LIMITED`.
3. Поэтому return original missing, return item mismatch или payment mismatch старого периода
   могут снизить confidence текущей недели и называться классификацией.
4. Freshness сначала проверяет статус последней terminal sync. Новый FAILED job может сделать
   status `ERROR`, даже когда data-through уже покрывает отчётную неделю; quality policy блокирует
   новый snapshot только по status.
5. Unexpected zero cost сейчас считается complete cost и не скрывает profit/margin. Это известный
   отложенный риск, а не исправление в рамках текущего редизайна.

## 9. Publication/read states

### 9.1. Корректные свойства

- Если published interpretation существует, API возвращает `READY` даже во время новой snapshot
  revision.
- `UPDATING`/`UPDATE_DELAYED` не удаляют старое содержимое.
- BLOCKED snapshot без публикации даёт `UNAVAILABLE/DATA_QUALITY_BLOCKED`.
- Active generation даёт `PREPARING` или `DELAYED` по SLA.
- Terminal generation failure даёт `UNAVAILABLE/ANALYSIS_TEMPORARILY_UNAVAILABLE`.

### 9.2. Проблемы пользовательского состояния

- При provider failure API показывает generic fallback, хотя deterministic facts и candidates уже
  готовы.
- Если более новая revision не может быть опубликована, старая остаётся READY с общим
  `UPDATE_DELAYED`; причина обновления пользователю не раскрывается.
- Fallback сообщает только codes и отправляет пользователя в общий раздел качества, не показывая
  затронутые показатели и конкретный способ восстановления.

## 10. Production baseline 2026-08-17—2026-08-23

В рамках текущей рабочей сессии до фиксации аудита были подтверждены:

| Магазин | Snapshot | Quality | Published v21/schema3 | Employee cards | Limitations |
| --- | --- | --- | --- | ---: | ---: |
| МАГАЗИН | revision 2, `c7ade034-…` | PARTIAL | READY, provider/validation SUCCESS, fallback=false | 6 | 1 |
| МобиСфера | revision 2, `2efe5d7d-…` | PARTIAL | READY, provider/validation SUCCESS, fallback=false | 3 | 4 |

Для МАГАЗИН production headline «Выполнение плана существенно ниже целевого уровня» был
трассирован к monthly accessory plan evidence:

- actual amount: `3 014 641 ₽`;
- monthly target amount: `3 696 624,98 ₽`;
- projected completion: `81,55%`;
- as-of: конец завершённой недели 2026-08-23.

Это не итог недельного плана и не сравнение с 2026-08-10—2026-08-16. Текст был технически
grounded, но продуктово находился не в том отчёте и не называл горизонт.

Оба production lifecycle прошли успешно. Следовательно, проблема страницы не сводится к сбою
генерации: успешный v21/schema3 способен выдавать непонятный руководителю результат в полном
соответствии со своим контрактом.

## 11. Тестовое покрытие

### 11.1. Выполненные проверки этапа 1

Backend targeted suite:

```text
WeeklyAnalyticsFactsQueryTest
BackendWeeklyAnalyticsFactsSourceTest
WeeklySnapshotDraftBuilderTest
WeeklySnapshotQualityPolicyTest
WeeklyInterpretationV3ResponseValidatorTest
WeeklyInsightQueryServiceTest
WeeklyInsightContentProjectorTest
WeeklyInsightEvidenceProjectorTest
```

Результат: `38 tests`, `0 failures`, `0 errors`, Gradle `BUILD SUCCESSFUL`.

Frontend targeted suite:

```text
weeklyInsight-contract.test.ts
weeklyInsight-null-omission.test.ts
evidence-rendering.test.tsx
placement.test.tsx
presentation.test.ts
```

Результат: `5 files`, `23 tests`, все passed.

### 11.2. Что покрыто

- adjacent Monday–Sunday periods;
- snapshot hash/pseudonymization/basic quality decisions;
- primary candidate requirement и backend normalization;
- privacy-reduced provider boundary;
- employee insufficient/limited headline;
- team tie и relationships;
- public evidence anonymization/formatting;
- READY/DELAYED/BLOCKED и published revision availability;
- public contract invariants;
- evidence rendering, repeated employee narrative suppression и mobile employee preview.

### 11.3. Отсутствующие или недостаточные проверки

- open return/payment issue не должен называться classification issue;
- old out-of-period issue не должен снижать confidence новой недели;
- ERROR latest sync при достаточном data-through;
- ровно 2 sufficient employees не должны объявляться сопоставимыми при min benchmark 3;
- tie не должен использовать LIMITED/INSUFFICIENT employees;
- выбор employee headline среди нескольких candidates по явному priority/magnitude;
- employee outside ranking и previous-only employee scope;
- больше 10 релевантных сотрудников;
- unexpected zero cost и weekly profit confidence;
- точная подпись `COMPLETED_SALES`: projector ожидает `COMPLETED_SALES_COUNT`, fact использует
  `COMPLETED_SALES`, поэтому при цитировании возможна подпись «Показатель»;
- previous period dates в API/UI;
- deterministic report при provider failure;
- полнота действий и фактическая управленческая полезность;
- real browser desktop/mobile layout, overflow, keyboard navigation и accessibility;
- end-to-end API → rendered page golden scenarios обоих production stores.

## 12. Реестр дефектов

Severity:

- `P1` — искажает управленческий смысл или делает основной use case недостоверным;
- `P2` — заметно снижает полноту, объяснимость или устойчивость;
- `P3` — maintainability/неблокирующая UX-проблема.

| ID | Severity | Дефект | Риск | Целевое направление |
| --- | --- | --- | --- | --- |
| AIW-001 | P1 | Monthly `PLAN` участвует в weekly facts/candidates и имеет priority 0 | Главный вывод отвечает на другой вопрос | Полностью удалить plan из v22 weekly contour |
| AIW-002 | P1 | Candidate copy не называет точный показатель и обе недели | Формально верный, но непонятный вывод | KPI comparison block + period-aware wording |
| AIW-003 | P1 | Любой OPEN issue может стать classification limitation | Возвраты/оплаты ошибочно объявляются классификацией | Period/type/metric-aware quality routing |
| AIW-004 | P1 | v21 не формирует содержательный employee analysis/actions | Руководитель получает пустые карточки | Новый deterministic employee contract; AI только поверх разрешённых фактов |
| AIW-005 | P2 | Evidence previous values не имеют дат | Пользователь не понимает базу сравнения | Comparison period в public API и рядом с KPI |
| AIW-006 | P2 | Employee/global limitations дублируются и dedupe-ятся по общему тексту | Два блока «Данные», потеря scope | Одна limitation model с явной привязкой к блоку/сотруднику |
| AIW-007 | P1 | Technical SUCCESS принят за достаточный quality gate | Не гарантируется manager usefulness | Page-level acceptance corpus и minimum usefulness criteria |
| AIW-008 | P1 | Team comparable threshold 2 против benchmark minimum 3 | Недостоверное сравнение команды | Один backend-owned eligibility contract |
| AIW-009 | P1 | Tie path не фильтрует insufficient facts | Возможен персональный вывод на слабой базе | Применять единый eligible set до team claims |
| AIW-010 | P1 | Provider failure блокирует весь READY report | Числа есть, страница бесполезна | Deterministic report всегда READY; AI enhancement отдельно |
| AIW-011 | P2 | Employee headline берёт первый candidate в candidateRef-порядке | Не главный сигнал сотрудника может стать headline | Версионированный employee priority + magnitude tie-break |
| AIW-012 | P2 | Neutral headline не имеет evidence | Непроверяемое утверждение об отсутствии изменений | Явный stable-week evidence/threshold explanation |
| AIW-013 | P2 | UI обещает персональные действия, которые privacy-reduced v21 не может создать | Несоответствие ожиданий | Утвердить backend action templates или убрать обещание |
| AIW-014 | P2 | Список «Сотрудники» скрывает outside-rating scope | Кажется, что анализ охватывает всех | Явный roster contract и подпись |
| AIW-015 | P2 | Hard limit 10 не сопровождается deterministic selection/truncation | Snapshot job может завершиться ошибкой при росте команды | Pagination/selection policy и explicit omitted count |
| AIW-016 | P2 | Latest sync `ERROR` способен блокировать покрытую закрытую неделю | Независимый новый сбой ломает revision | Coverage-first closed-period gate + scoped error |
| AIW-017 | P2 | Старый READY при неудачной revision показывает только общий UPDATE_DELAYED | Причина и актуальность неясны | Expose revision failure reason/data-through safely |
| AIW-018 | P2 | Completed-sales metric label code не совпадает | Evidence может называться «Показатель» | Общий typed metric catalog + contract test |
| AIW-019 | P2 | v21 gate допускает 0 действий и почти 0 secondary insights | PASS не означает полезную страницу | Обязательные product findings и page-level rubric |
| AIW-020 | P2 | Unexpected zero cost считается достоверной себестоимостью | Profit/margin могут быть завышены | Отдельное подтверждённое business rule; задача отложена |
| AIW-021 | P2 | Public presentation contract сохраняет legacy sections/horizons | Пустые блоки и разные горизонты | Новый schema4 без legacy plan/month horizons |
| AIW-022 | P3 | CSS содержит повторные поколения selectors/media queries | Fragile cascade и сложные визуальные регрессии | Собрать один versioned stylesheet/component layer |
| AIW-023 | P3 | Часть operational docs всё ещё пишет, что v21 не production default | Ошибочная точка продолжения | Актуализировать handoff после утверждения нового baseline |

## 13. Матрица «текущая реализация → проблема → целевое решение»

| Текущая реализация | Проблема | Риск | Целевое решение этапа 2 |
| --- | --- | --- | --- |
| Narrative-first page | Числа спрятаны в evidence | Руководитель не может быстро проверить вывод | KPI-first comparison table/cards |
| Primary candidate headline | Один сигнал вытесняет общую картину | Итог недели сводится к случайному priority | Сначала результат в цифрах, затем до 3 факторов |
| Plan priority | Смешение month/week | Неверная интерпретация периода | Plan отсутствует полностью |
| Store-only provider | Нет реального персонального анализа | Пустые employee cards | Backend-owned employee facts/eligibility/action contract |
| Required provider publication | Provider является single point of failure | Generic fallback вместо отчёта | AI optional enrichment with deterministic fallback |
| Global open issue count | Нет type/date/scope | Ложные ограничения | Structured affected-metric limitations |
| Legacy V2 UI adapter | Много nullable пустых slots | Сложная и непредсказуемая иерархия | Direct schema4 → direct UI model |
| Evidence disclosure | Previous values без period label | Непрозрачное сравнение | Current/previous dates in every comparison block |
| Semantic safety gate | Проверяет отсутствие нарушений, не полноту | Технический PASS без пользы | Acceptance gate по управленческим вопросам страницы |

## 14. Gate этапа 1

### 14.1. Критерий завершения

Каждый текущий display block, fallback и пользовательский текст сопоставлен с:

- источником и владельцем;
- периодом;
- sufficiency/materiality policy;
- evidence path;
- empty/error behavior;
- data-quality impact;
- frontend placement;
- существующим и отсутствующим тестовым покрытием.

Критерий этапа 1 выполнен.

### 14.2. Решение для перехода к этапу 2

`READY FOR STAGE 2`, при соблюдении зафиксированных решений:

1. Не исправлять production v21/schema3 точечными semantic правками.
2. Не менять historical content/prompt files.
3. Проектировать новый direct API/content contract без monthly plan и legacy nullable sections.
4. Сначала утвердить точный контракт блоков и acceptance examples, затем писать backend/prompt/UI.
5. Сохранить текущий read path для исторических v21 interpretations и rollout через canary flag.
