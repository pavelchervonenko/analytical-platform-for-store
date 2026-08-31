---
doc_schema: 1
doc_type: archive
status: archived
owner: ai
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/current/ai/README.md"
original_content_sha256: b267f26b68edd957fb74ed6b67256e4ef1412f8c1225a1d2ffe037a91b57da31
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/current/ai/README.md`.

# «ИИ-разбор» v22/schema4: продуктовый и технический контракт

Дата: 2026-08-26
Статус: **APPROVED — COMPLETED 2026-08-26**
Основание: [этап 1 — аудит](../../history/audits/2026/08/AI_WEEKLY_REDESIGN_STAGE1_AUDIT.md)
Production baseline: `weekly-interpretation-v21`, content schema `3`

## 1. Назначение контракта

Документ определяет новую страницу «ИИ-разбор» как еженедельный управленческий отчёт для
руководителя магазина. По нему backend, AI enrichment и frontend должны реализовываться независимо,
без дополнительного толкования продуктового смысла.

Рабочие версии нового контура:

| Контур | Версия |
| --- | --- |
| Public report contract | `weekly-review-contract-v2` |
| Metrics policy | `weekly-metrics-v4` |
| Snapshot policy | `weekly-snapshot-v7` |
| Quality policy | `weekly-quality-v4` |
| Prompt | `weekly-interpretation-v22` |
| AI content schema | `4` |

Окончательно номера фиксируются вместе с реализацией этапа 3. Production v21/schema3 и его
исторические payload остаются неизменными.

## 2. Область и не-цели

Страница отвечает только на четыре вопроса:

1. Как магазин отработал последнюю завершённую неделю?
2. Что изменилось относительно непосредственно предыдущей завершённой недели?
3. Какие командные и персональные показатели требуют внимания?
4. Какие проверяемые действия выполнить на следующей неделе?

В новый контракт не входят:

- месячный план и темп его выполнения;
- прогноз месяца и цель будущего дня;
- сравнение с прошлым месяцем, годом или «похожей» неделей;
- движение денег и сверка способов оплаты;
- показатели незавершённой текущей недели;
- Telegram fanout;
- кадровые решения или оценка личных качеств сотрудника;
- неподтверждённые причины изменений.

## 3. Общие инварианты

### 3.1. Периоды

- `current` — последняя полностью завершённая локальная неделя, понедельник–воскресенье.
- `previous` — семь календарных дней непосредственно перед `current`.
- Границы рассчитываются в timezone магазина и передаются backend в явном виде.
- В каждом сравнительном блоке доступны обе пары дат; frontend не восстанавливает периоды сам.
- Текущая незавершённая неделя не используется ни в числах, ни в выводах, ни в действиях.
- Исправление источника создаёт новую immutable revision той же недели.
- Если опубликована новая revision, UI показывает дату обновления и нейтральное сообщение
  «Данные за неделю были обновлены».

### 3.2. Владение данными и текстом

Backend владеет:

- числами, формулами, периодами, округлением и знаками;
- состоянием каждого блока и каждой метрики;
- sufficiency, materiality, effect и evidence;
- выбором не более трёх факторов и action specs;
- персональными фактами и персональными формулировками;
- полноценным deterministic fallback.

AI может только:

- сократить и сделать естественнее готовый итог магазина;
- пояснить выбранные backend факторы без добавления причин;
- переформулировать разрешённые store-level actions без изменения цели и критерия проверки.

AI не получает ФИО сотрудников и не создаёт персональные оценки. Недоступность AI не меняет
доступность рассчитанного отчёта.

### 3.3. Состояния

Состояние отчёта и состояние AI разделяются:

```text
reportState = PREPARING | READY | PARTIAL | BLOCKED
aiState     = PREPARING | READY | DELAYED | UNAVAILABLE | DISABLED | NOT_APPLICABLE
blockState  = READY | LIMITED | INSUFFICIENT | NOT_APPLICABLE
metricState = READY | LIMITED | UNAVAILABLE | NOT_APPLICABLE
```

- `READY`: все обязательные для блока данные доступны.
- `PARTIAL`: основной финансовый результат доступен, но один или несколько вторичных блоков
  ограничены.
- `BLOCKED`: нельзя достоверно рассчитать чистую выручку завершённой недели.
- `LIMITED`: значение можно показать, но нельзя использовать для сильного вывода или действия.
- `INSUFFICIENT`: данных недостаточно для сравнения; доступные факты периода всё равно показываются.
- `UNAVAILABLE`: значение не рассчитывается и не заменяется нулём.

Ошибка последнего sync job сама по себе не блокирует закрытую неделю. Влияет только подтверждённый
пробел покрытия обязательного источника, пересекающий соответствующий период и метрику.

### 3.4. Evidence

Каждый вывод, фактор и действие содержит `evidenceRefs`. Evidence неизменно включает:

- код метрики;
- scope магазина или сотрудника;
- текущий и базовый периоды;
- исходные current/previous значения и единицу;
- denominator, если показатель является отношением;
- sufficiency/materiality decision;
- formula/policy version.

Frontend показывает evidence по действию «На основании чего». Технические UUID, внутренние коды и
prompt metadata руководителю не отображаются.

### 3.5. Округление и сравнение

Расчёты и пороги применяются к неокруглённым значениям. Backend хранит и передаёт:

| Тип | API scale | Основной UI | В подробностях |
| --- | --- | --- | --- |
| Деньги | 2 | целые ₽ | 2 знака |
| Процент/доля | 2 | 1 знак | 2 знака |
| Rate per 100 | 2 | 1 знак | 2 знака |
| Количество | 3 | без лишних нулей | до 3 знаков |
| Часы | 2 | 1 знак | 2 знака |

Правила сравнения:

- `absoluteDelta = current - previous`;
- `changePercent = absoluteDelta / abs(previous) * 100`, только если `previous > 0`;
- при `previous = 0` процент равен `null`, `comparisonKind = NO_BASE`;
- при `previous < 0` процент равен `null`, `comparisonKind = NON_POSITIVE_BASE`;
- при недоступном значении процент равен `null`, `comparisonKind = UNAVAILABLE`;
- frontend не выводит бесконечность и не заменяет `null` нулём.

`direction` описывает движение (`UP`, `DOWN`, `FLAT`, `UNKNOWN`), а `effect` — управленческий смысл
(`POSITIVE`, `NEGATIVE`, `NEUTRAL`, `UNKNOWN`). Например, рост суммы возвратов имеет `UP/NEGATIVE`.

## 4. Контракт источников и качества

Готовность определяется по блокам, а не одним глобальным флагом.

| Область | Обязательные данные | Что происходит при проблеме |
| --- | --- | --- |
| Финансовый итог | продажи и возвраты до конца обеих недель | report `BLOCKED`, если current нельзя посчитать; comparison `INSUFFICIENT`, если нет previous |
| Прибыль и маржа | себестоимость строк обеих недель | только profit/margin `UNAVAILABLE` или `LIMITED` |
| Структура продаж | классификация строк обеих недель | только structure/categories `LIMITED` |
| Attach | классификация numerator и denominator | только соответствующая attach-метрика `LIMITED/UNAVAILABLE` |
| Сотрудники: продажи | атрибуция сотрудника и sales sample | только затронутые employee metrics ограничены |
| Сотрудники: эффективность | смены и часы | только efficiency недоступна; продажи сотрудника остаются |
| Заказы | не используются в этой версии страницы | покрытие orders не влияет на отчёт |

Data-quality issue влияет на отчёт только если одновременно выполнены условия:

1. issue открыт на момент snapshot;
2. его бизнес-дата пересекает `current` или `previous`;
3. issue type сопоставлен с конкретной метрикой/блоком;
4. affected records действительно входят в расчёт магазина.

Открытая проблема возврата не может называться проблемой классификации. Глобальный
`openQualityIssueCount` не является основанием ограничения.

### 4.1. Себестоимость

- отсутствующая себестоимость хотя бы одной включённой строки делает profit/margin этого scope
  `UNAVAILABLE`;
- ожидаемая нулевая себестоимость услуги допустима;
- `ZERO_UNEXPECTED` для товара оставляет вычисленное значение видимым со state `LIMITED`, но такое
  значение не используется в factor/action и подписывается «Себестоимость требует проверки»;
- остальные финансовые и количественные метрики не блокируются.

### 4.2. Возвраты и атрибуция сотрудника

- store totals всегда включают все сохранённые возвраты периода;
- возврат уменьшает выручку, количество, себестоимость и прибыль соответствующей категории;
- в employee breakdown возврат относится к сотруднику исходной продажи (`ORIGINAL_SELLER`);
- если исходная продажа или сотрудник не найдены, возврат остаётся в store totals, но не
  распределяется между сотрудниками; employee block получает точное ограничение;
- правило атрибуции и число нераспределённых возвратов доступны в evidence.

## 5. Универсальная модель сравнения

```json
{
  "code": "NET_REVENUE",
  "label": "Чистая выручка",
  "unit": "RUB",
  "current": 1250000.00,
  "previous": 1180000.00,
  "absoluteDelta": 70000.00,
  "changePercent": 5.93,
  "comparisonKind": "PERCENT_AVAILABLE",
  "direction": "UP",
  "effect": "POSITIVE",
  "metricState": "READY",
  "materiality": "MATERIAL",
  "sufficiency": "SUFFICIENT",
  "evidenceRefs": ["STORE.NET_REVENUE"]
}
```

Допустимые `materiality`: `MATERIAL`, `NOT_MATERIAL`, `NOT_EVALUATED`.

Пороговая policy первой версии:

| Тип сигнала | Material, если обе недели sufficient |
| --- | --- |
| Выручка, прибыль, средняя продажа | `abs(changePercent) >= 5%` |
| Маржа, доля категории, доля доп. выручки | `abs(delta percentage points) >= 3 п.п.` |
| Категория | `abs(changePercent) >= 15%` и доля хотя бы одной недели `>= 3%` |
| Attach rate | `abs(delta rate per 100) >= 5` |
| Employee revenue/revenue per hour | `abs(changePercent) >= 10%` |
| Employee share/attach | `abs(delta) >= 3 п.п.` / `>= 5 на 100` |

Пороги версионируются. Изменение числа без новой metrics policy запрещено.

## 6. Блок 1 — период и состояние данных

**Управленческий вопрос:** какие недели сравниваются и насколько отчёту можно доверять?

Обязательные поля:

- current/previous `start`, `end`, locale-ready `label`;
- timezone;
- `dataThroughDate` для обязательных источников;
- `reportState`, `qualitySummary`;
- snapshot `revision`, `calculatedAt`, `sourceDataUpdatedAt`;
- `revisionChanged` и `previousRevisionPublishedAt`, если данные были пересчитаны.

Presentation:

```text
17–23 августа 2026  ·  сравнение с 10–16 августа
Данные продаж и возвратов учтены по 23 августа  ·  Данные готовы
```

При `PARTIAL` показывается одна короткая конкретная подпись и ссылка к ограничениям, например:
«Продажи посчитаны; для 2 сотрудников нет данных о сменах». Технические статусы не показываются.

## 7. Блок 2 — краткий итог недели

**Управленческий вопрос:** что главное произошло за неделю?

Состав:

1. `outcome` — одно предложение о net revenue и, если доступно, gross profit;
2. `positive` — не более одного material positive factor;
3. `risk` — не более одного material negative factor;
4. если material факторов нет, backend сообщает: «Основные показатели без существенных изменений».

Каждая строка содержит evidence. Запрещены:

- месячный план;
- слова «существенно/заметно» без material decision;
- причинные конструкции «из-за», «привело к», если arithmetic contribution не доказана;
- общий текст без числа;
- одинаковая формулировка одновременно в outcome и factors.

Если AI unavailable, backend renderer выводит тот же состав по шаблонам. Сообщение
«Автоматическая интерпретация временно недоступна» не заменяет результат и в пользовательском
контенте не требуется.

## 8. Блок 3 — результаты недели в цифрах

**Управленческий вопрос:** как изменились основные финансовые показатели?

Обязательные карточки:

| Код | Пользовательское название | Формула |
| --- | --- | --- |
| `NET_REVENUE` | Чистая выручка | `salesRevenue - returnRevenue` |
| `GROSS_PROFIT` | Валовая прибыль | `netRevenue - netCostAmount` |
| `MARGIN_PERCENT` | Маржа | `grossProfit / netRevenue * 100`, если `netRevenue > 0` |
| `AVERAGE_SALE` | Средняя продажа | `salesRevenue / completedSaleDocumentCount` |

В раскрытии `NET_REVENUE` обязательно показываются:

- `SALES_REVENUE` — сумма включённых строк документов `SALE`;
- `RETURN_REVENUE` — абсолютная сумма включённых строк документов `RETURN`;
- проверяемое равенство `NET_REVENUE = SALES_REVENUE - RETURN_REVENUE`;
- количество документов продаж и возвратов.

`AVERAGE_SALE` намеренно не использует net revenue: возвраты показаны отдельно и не уменьшают
среднее значение совершённой продажи. Старое название/вычисление «средний чек = net revenue /
sales count» в v22 не используется.

Если нет completed sales, `AVERAGE_SALE = null`, а не `0`.

## 9. Блок 4 — основные изменения недели

**Управленческий вопрос:** какие проверяемые изменения стоит изучить руководителю?

Пользовательское название блока — **«Основные изменения недели»**, а не причинное
«Что повлияло», пока система не выполняет доказанную декомпозицию причины.

Backend выбирает до трёх material candidates по порядку:

1. рост возвратов;
2. отрицательное изменение category/additional/attach;
3. положительное изменение category/additional/attach;
4. store-level revenue/profit — только если смысл не дублирует блок 2/3.

Каждый factor содержит:

- `factorId`, `kind`, `title`;
- metric comparison;
- factual `detail` без причинности;
- `evidenceRefs`;
- optional `contributionAmount`, только если сумма арифметически входит в изменение net revenue.

Если category revenue выросла одновременно с общей выручкой, допустимо написать
«Выручка категории выросла на …». Нельзя писать «Категория обеспечила рост магазина», пока
`contributionAmount` и декомпозиция не рассчитаны.

## 10. Блок 5 — структура продаж и допродажи

**Управленческий вопрос:** из чего сложилась выручка и как изменились дополнительные продажи?

### 10.1. Иерархия без двойного подсчёта

Backend отдаёт готовое дерево; frontend ничего не суммирует:

```text
Чистая выручка
├── Техника
│   ├── Телефоны
│   └── Другая техника
├── Дополнительная выручка
│   ├── Аксессуары
│   ├── Услуги, гарантии и защита
│   └── Прочие дополнительные категории
└── Остальное
```

Инварианты:

- три узла первого уровня взаимно исключаются и в сумме равны net revenue;
- `Телефоны` уже входят в `Технику`;
- `Аксессуары` и `Услуги…` уже входят в `Дополнительную выручку`;
- `Остальное = netRevenue - devices - additionalRevenue`;
- отрицательный residual или пересечение верхних групп является quality error блока;
- каждый узел показывает current revenue, share, previous revenue/share и delta;
- UI подчёркивает subtotal/child relationship отступом, а не отдельными равноправными карточками.

Ни один пользовательский текст не предлагает складывать parent и child.

### 10.2. Attach

Показываются все настроенные attach metrics с current/previous:

- numerator receipt count;
- denominator receipt count;
- rate per 100;
- absolute delta rate per 100;
- metric-specific sufficiency.

Sufficiency для каждой недели:

- denominator `0–2`: `INSUFFICIENT`;
- denominator `3–4`: `LIMITED`;
- denominator `>= 5`: `SUFFICIENT`.

Сравнительный вывод и action разрешены только при `>= 5` в обеих неделях. При нулевом denominator
подпись: «Нет продаж для расчёта». При denominator `1–4`: «Недостаточно продаж: N».

## 11. Блок 6 — команда

**Управленческий вопрос:** что происходит с командой в целом и насколько надёжно её можно сравнивать?

В v22 не используется composite employee score/rank, потому что текущий рейтинг включает plan
context. Это исключает скрытое возвращение месячного плана на недельную страницу.

Team block содержит:

- число active assigned сотрудников с активностью current или previous;
- число сотрудников, участвующих в сравнении;
- число сотрудников с ограниченной базой и причины;
- до двух material командных наблюдений по конкретным метрикам;
- количество сотрудников с sufficient material negative own dynamics;
- ссылку «Перейти к сотрудникам, которым требуется внимание», которая применяет фильтр к блоку 7.

### 11.1. Нормативная граница «Команда» → «Сотрудники»

Блок «Команда» показывает только агрегированную картину:

- roster counts, coverage и причины недостаточной общей базы;
- медианы и распределение по состояниям;
- командные наблюдения, которые относятся минимум к двум сотрудникам;
- количество карточек, требующих внимания, без ФИО и персональных показателей.

В блоке «Команда» запрещены ФИО, персональные значения, персональные выводы, strengths, attention
texts и actions. Кнопка перехода может только прокрутить страницу к блоку «Сотрудники» и включить
фильтр `ATTENTION`; содержание карточек не дублируется.

Блок «Сотрудники» является единственным владельцем:

- ФИО и персональных показателей;
- собственной динамики сотрудника;
- сравнения сотрудника с медианой магазина;
- персональной сильной стороны, зоны внимания, limitations и action.

Медиана может повторяться внутри карточки только как основание персонального сравнения. Текстовое
командное наблюдение в карточке не повторяется.

Peer benchmark:

- рассчитывается отдельно для каждой метрики;
- использует медиану current week;
- включает active assignment + `participatesInRanking=true` + sufficient sample для этой метрики;
- разрешён только при `eligibleCount >= 3`;
- при двух сотрудниках не формируются лидер, ничья или сравнение с магазином;
- сотрудники вне ranking не входят в benchmark, но могут видеть собственную динамику.

Подпись в UI: «Медиана магазина, N сотрудников». Слово «среднее» не используется для медианы.

## 12. Блок 7 — карточки сотрудников

**Управленческий вопрос:** что изменилось у конкретного сотрудника и какое действие уместно?

### 12.1. Кто попадает в список

Карточка создаётся для active employee с active store assignment, если есть хотя бы одна смена или
продажа в current либо previous week. Семантического лимита 10 нет. Frontend первоначально
показывает пять карточек и раскрывает остальные по кнопке; API возвращает весь список, технический
лимит — 100.

Сортировка:

1. sufficient negative own dynamics;
2. limited/insufficient data;
3. sufficient positive dynamics;
4. без material changes;
5. display name.

### 12.2. Поля карточки

- identity: `employeeId`, `displayName`;
- participation: `participatesInBenchmark`;
- current/previous: sales count, net revenue, additional revenue/share;
- current/previous workload: shift count, worked hours;
- revenue per hour, если часы доступны;
- до двух configured attach metrics;
- до двух own-dynamics observations;
- до одного peer comparison;
- `strength`, только при material positive evidence;
- `attention`, только при material negative evidence;
- до одного action;
- metric-scoped limitations.

Sufficiency не является одним глобальным статусом сотрудника:

| Метрика | `INSUFFICIENT` | `LIMITED` | `SUFFICIENT` |
| --- | --- | --- | --- |
| Продажи/структура | 0–2 продажи | 3–5 продаж | `>= 6` продаж |
| Эффективность | 0 смен/часов | 1 смена или `< 12 ч` | `>= 2` смен и `>= 12 ч` |
| Attach | denominator `0–2` | `3–4` | `>= 5` |
| Динамика | хотя бы одна неделя insufficient | хотя бы одна limited | обе sufficient |

Отсутствие смен не скрывает доступную выручку сотрудника; недоступна только эффективность.

Если material наблюдений нет, карточка показывает числа и фразу
«Изменений выше установленных порогов нет». Универсальный текст про «определённое количество
клиентов» запрещён.

### 12.3. Персональные выводы

- не оценивают характер, мотивацию или компетентность;
- не рекомендуют увольнение, штраф или изменение зарплаты;
- явно называют показатель и обе недели;
- не сравнивают с командой при eligible count `< 3`;
- не называют сотрудника «лучшим/худшим» на основании одной метрики;
- формируются backend без передачи персональных данных AI-провайдеру.

## 13. Блок 8 — действия на следующую неделю

**Управленческий вопрос:** что конкретно сделать и как проверить результат?

Store-level действий не более трёх, employee-level — не более одного на карточку. Горизонт только
`NEXT_FULL_WEEK`.

```json
{
  "actionId": "STORE.ATTACH.CASE_TO_PHONE.RESTORE",
  "priority": "HIGH",
  "actionType": "RESTORE_METRIC",
  "scope": "STORE",
  "title": "Разобрать продажи чехлов вместе с командой",
  "metricCode": "CASE_TO_PHONE",
  "target": {"operator": "AT_LEAST", "value": 42.0, "unit": "PER_100"},
  "check": "Сравнить показатель следующей полной недели с 42,0 на 100",
  "evidenceRefs": ["STORE.ATTACH.CASE_TO_PHONE"],
  "horizon": "NEXT_FULL_WEEK"
}
```

Правила:

- действие создаётся только из sufficient material negative candidate;
- target по умолчанию — восстановить previous value, а не произвольное число AI;
- сначала отрицательные store/team факторы, затем employee actions;
- положительное действие «сохранить практику» допускается только одно и только при отсутствии трёх
  отрицательных кандидатов;
- одна метрика не порождает несколько действий;
- data-quality исправления отображаются в ограничениях, а не маскируются под коммерческие actions;
- если кандидатов нет, блок говорит «Действия по существенным отклонениям не требуются».

## 14. Блок 9 — ограничения данных

**Управленческий вопрос:** каким именно выводам нельзя полностью доверять и почему?

```json
{
  "limitationId": "dq-opaque-id",
  "code": "EMPLOYEE_SHIFT_COVERAGE_MISSING",
  "severity": "WARNING",
  "scope": "EMPLOYEE",
  "affectedBlockIds": ["employee:e-opaque"],
  "affectedMetricCodes": ["REVENUE_PER_HOUR"],
  "period": {"start": "2026-08-17", "end": "2026-08-23"},
  "affectedCount": 1,
  "summary": "Для сотрудника не заполнены смены за 2 дня",
  "resolution": "Заполните смены в разделе «План и смены»",
  "evidenceRefs": ["EMP:e-opaque.WORKLOAD.CURRENT"]
}
```

Правила отображения:

- metric/employee limitation показывается inline рядом с затронутой метрикой;
- нижний блок содержит уникальные store-wide ограничения и сводку, без дублирования employee cards;
- limitation всегда называет affected period и scope;
- «Данные ограничены» без конкретной причины запрещено;
- `BLOCKING` применяется только если нельзя рассчитать обязательный финансовый итог;
- closed issue в новую revision не переносится;
- issue вне обеих недель не влияет на отчёт.

## 15. Public API contract

Новый ресурс создаётся параллельно старому:

```http
GET /api/stores/{storeId}/weekly-reviews/current
Cache-Control: private, no-store
```

Старый `GET /api/stores/{storeId}/insights/weekly/current` остаётся read path для v21/schema3 до
завершения canary и чтения исторических interpretations.

Сокращённая top-level schema:

```json
{
  "contractVersion": 2,
  "versions": {
    "metricsPolicy": "weekly-metrics-v4",
    "snapshotPolicy": "weekly-snapshot-v7",
    "qualityPolicy": "weekly-quality-v4"
  },
  "period": {
    "timezone": "Europe/Moscow",
    "current": {"start": "2026-08-17", "end": "2026-08-23"},
    "previous": {"start": "2026-08-10", "end": "2026-08-16"}
  },
  "provenance": {
    "snapshotId": "opaque",
    "revision": 3,
    "calculatedAt": "2026-08-24T04:00:00Z",
    "sourceDataUpdatedAt": "2026-08-24T03:50:00Z"
  },
  "reportState": "PARTIAL",
  "qualitySummary": {"blockingCount": 0, "warningCount": 1},
  "sourceCoverage": [],
  "summary": {},
  "results": [],
  "revenueDecomposition": {},
  "factors": [],
  "salesStructure": {},
  "team": {},
  "employees": [],
  "actions": [],
  "limitations": [],
  "evidence": [],
  "aiEnhancement": {
    "state": "READY",
    "promptVersion": "weekly-interpretation-v22",
    "contentSchemaVersion": 4,
    "publishedAt": "2026-08-24T04:01:00Z"
  }
}
```

API отдаёт direct UI model; legacy nullable `store/teamInsights/dataLimitations` JsonNode adapter в
новом endpoint не используется.

## 16. AI content schema 4

AI возвращает не всю страницу, а только необязательное store-level enrichment:

```json
{
  "schemaVersion": 4,
  "summary": {
    "text": "...",
    "evidenceRefs": ["STORE.NET_REVENUE", "STORE.GROSS_PROFIT"]
  },
  "factorExplanations": [
    {"factorId": "factor-1", "text": "...", "evidenceRefs": ["STORE.CATEGORY.X"]}
  ],
  "actionWordings": [
    {"actionId": "action-1", "title": "...", "check": "..."}
  ]
}
```

Ограничения schema:

- все IDs должны существовать в backend input;
- evidence refs — только из allowlist конкретного объекта;
- AI не может добавить/удалить factor/action, число, target, period или employee;
- текст не может содержать число, отсутствующее в evidence;
- пустой/невалидный/timeout response отбрасывается целиком, deterministic content остаётся;
- semantic validation проверяет полноту обязательных ссылок и запрещённую причинность;
- provider payload не содержит plan, ФИО, employee facts или внутренние UUID.

## 17. Frontend state matrix

| Сценарий | Что видит пользователь | Что запрещено |
| --- | --- | --- |
| Initial load | skeleton фиксированной структуры | старый отчёт под новым периодом |
| `PREPARING` | «Отчёт за 17–23 августа формируется» + время следующей проверки | generic error |
| `READY`, AI ready | полный deterministic report + разрешённые AI формулировки | технический AI status |
| `READY/PARTIAL`, AI unavailable | тот же полный report в backend формулировках | скрыть числа; заменить страницу сообщением об AI |
| `PARTIAL` | готовые блоки + конкретные inline limitations | глобально обесценить все данные |
| `BLOCKED` | периоды, причина, affected source, последнее успешное покрытие, действие | нули вместо отсутствующих данных |
| request error | retry и correlation ID в раскрытии | показать устаревший период без метки |
| no sales | нули там, где они являются фактом; ratios «Нет продаж для расчёта» | `0%` вместо unavailable ratio |
| new revision | полный отчёт + «Данные за неделю были обновлены» | незаметная замена revision |

Mobile сохраняет порядок блоков. Таблицы превращаются в вертикальные comparison rows; current,
previous и delta остаются одновременно видимыми. Evidence drawer доступен с клавиатуры, имеет focus
trap и возвращает focus в исходную кнопку. Цвет никогда не является единственным носителем смысла.

## 18. Wireframe

```text
┌ 17–23 августа · сравнение с 10–16 августа ───── [данные готовы] ┐
│ Данные продаж и возвратов учтены по 23 августа                  │
└─────────────────────────────────────────────────────────────────┘

Итог недели
Чистая выручка выросла на 5,9% ...
[положительное изменение]  [риск]

Результаты в цифрах
[Чистая выручка] [Валовая прибыль] [Маржа] [Средняя продажа]
 current / previous / delta
  └ Продажи − Возвраты = Чистая выручка

Основные изменения недели
1. ... [На основании чего]
2. ... [На основании чего]

Структура продаж и допродажи
Чистая выручка
  Техника
    Телефоны
  Дополнительная выручка
    Аксессуары
    Услуги
Attach: current / previous / denominator / delta

Команда
[сколько сотрудников в сравнении] [командные наблюдения] [N требуют внимания →]

Сотрудники
[карточка: числа → собственная динамика → benchmark → действие]
[Показать остальных]

Действия на следующую неделю
1. действие → целевой показатель → как проверить

Ограничения данных
Конкретная проблема → затронутые показатели → что сделать
```

## 19. Acceptance examples

### 19.1. МАГАЗИН, production week 2026-08-17—2026-08-23

- current = `2026-08-17..2026-08-23`, previous = `2026-08-10..2026-08-16`;
- на странице нет plan/forecast/month target ни явно, ни через employee score;
- financial cards доступны независимо от AI provider;
- revenue раскрывается как sales minus returns;
- старый open issue вне двух недель не создаёт classification limitation;
- возврат внутри периода без original sale ограничивает только employee attribution/reconciliation;
- все шесть релевантных employee cards доступны без hard cap 10.

### 19.2. МобиСфера, тот же период

- store results и structure доступны при полном покрытии sales/returns;
- отсутствие смен не скрывает employee sales;
- у revenue-per-hour показывается точное ограничение;
- team benchmark появляется только для метрики с тремя sufficient eligible employees;
- нет общего текста «Данные — ограничены» и нет plan conclusion.

### 19.3. Нулевой denominator

- attach rate и average sale равны `null`, не `0`;
- UI пишет «Нет продаж для расчёта»;
- comparison, factor и action не создаются.

### 19.4. Ровно два eligible сотрудника

- own dynamics доступны;
- team median/leader/comparison отсутствуют;
- UI пишет «Для сравнения с командой нужны данные минимум трёх сотрудников».

### 19.5. Сотрудник вне рейтинга

- карточка с own facts доступна;
- badge «Не участвует в сравнении с командой»;
- сотрудник не входит в median и не получает peer comparison.

### 19.6. Missing и unexpected zero cost

- missing cost скрывает только profit/margin затронутого scope;
- unexpected zero cost показывает ограниченные profit/margin с конкретной причиной;
- net revenue, sales structure by revenue и attach остаются доступны;
- AI не использует ограниченную прибыль как фактор.

### 19.7. Provider failure

- report остаётся `READY/PARTIAL`;
- summary, factors, cards и actions отображаются deterministic renderer;
- manager не видит заглушку вместо отчёта;
- provider incident доступен только в operational/system контуре.

### 19.8. Исправление источника

- создаётся revision `N+1`, revision `N` не изменяется;
- current endpoint отдаёт новую revision;
- UI явно сообщает об обновлении данных;
- исторический v21 payload по-прежнему читается старым projector.

## 20. Удаляемые зависимости и backward compatibility

Из нового weekly contour удаляются:

- `planContexts` из provider input;
- `PLAN_*` facts/candidates/evidence;
- candidate theme `PLAN`;
- section/horizon `PLAN_OUTLOOK`, `MONTH_END`;
- composite employee score/rank, зависящий от plan context;
- required-provider publication как условие полезной страницы;
- global `openQualityIssueCount` как универсальный quality signal;
- legacy `WeeklyInsightContentView` JsonNode presentation model;
- hard semantic limit `MAX_EMPLOYEES = 10`;
- общий employee fallback без конкретного evidence.

Совместимость:

1. v21/schema3 tables и payload не переписываются.
2. Старый endpoint и projector остаются для historical read.
3. Новый endpoint строится из snapshot v7 и optional schema4 enrichment.
4. Frontend умеет переключаться между old/new endpoint через feature flag.
5. Canary сначала включается для одного магазина и внутренних ролей.
6. Rollback выключает flag и возвращает UI на v21, не откатывая миграции.
7. После стабилизации новый endpoint становится default; удаление v21 — отдельная задача.

## 21. Stage 2 gate

Контракт **подтверждён пользователем 2026-08-26** со следующими решениями:

1. Блок называется «Основные изменения недели», без неподтверждённой причинности.
2. Core KPI: чистая выручка, валовая прибыль, маржа и средняя продажа.
3. Средняя продажа считается по gross sales revenue, возвраты раскрываются отдельно.
4. Composite employee rank/score на weekly page не используется из-за plan dependency.
5. Employee sufficiency определяется отдельно для sales, efficiency и attach.
6. Team benchmark — медиана минимум трёх sufficient eligible сотрудников.
7. AI является optional store-level enrichment; employee content принадлежит backend.
8. Новый direct endpoint создаётся параллельно v21 read path.
9. Unexpected zero cost ограничивает profit/margin, но не блокирует остальные блоки.
10. Orders coverage не влияет на weekly report, пока order metrics отсутствуют на странице.
11. «Команда» содержит только агрегаты без ФИО; все персональные показатели и действия
    принадлежат только блоку «Сотрудники».

Gate подтверждён, этап 2 имеет статус `COMPLETED`. Следующая точка — этап 3: новая
backend-модель и contract tests. Production-код в рамках этапа 2 не менялся.
