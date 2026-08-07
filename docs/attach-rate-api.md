# Attach-rate API

Статус: реализован контракт `attach-rate-v2`, актуализирован 2026-08-07 по уточнениям
заказчика. Расчёт выполняется только по нормализованным данным PostgreSQL и не обращается к
LiveSklad или LLM.

## Бизнес-смысл

Attach-rate показывает долю релевантных чеков продажи, в которых вместе с устройством была
оформлена конкретная допродажа:

```text
attach-rate = чеки с релевантным устройством и допродажей
              / чеки с релевантным устройством * 100
```

Один чек может дать не более одной единицы в числитель и не более одной единицы в знаменатель
каждой метрики. Количество устройств или одинаковых услуг внутри чека на результат не влияет.
Учитываются только документы `SALE`; возвраты не уменьшают и не увеличивают этот показатель.
Процент округляется до целого по `HALF_UP`.

## Endpoint

```text
GET /api/stores/{storeId}/kpi/attach-rates?periodStart=2026-07-01&periodEnd=2026-07-31
```

Даты обязательны и включаются в период. Нужны действующая сессия, завершённая обязательная смена
пароля и доступ к магазину.

Пример ответа:

```json
{
  "storeId": "00000000-0000-0000-0000-000000000000",
  "periodStart": "2026-07-01",
  "periodEnd": "2026-07-31",
  "formulaVersion": "attach-rate-v2",
  "dataQuality": {
    "unmatchedNumeratorItemCount": 0,
    "ambiguousWarrantyItemCount": 0,
    "unknownDeviceConditionItemCount": 0
  },
  "rates": [
    {
      "metricCode": "CASE_APPLE_IPHONE",
      "numeratorCategoryCode": "CASE_APPLE_IPHONE",
      "denominatorCode": "IPHONE",
      "numeratorReceiptCount": 12,
      "denominatorReceiptCount": 20,
      "ratePerHundred": 60,
      "numeratorQuantity": 12,
      "denominatorQuantity": 20
    }
  ]
}
```

`numeratorReceiptCount` и `denominatorReceiptCount` — нормативные поля v2. Поля
`numeratorQuantity`/`denominatorQuantity` временно возвращаются как совместимые aliases с теми же
значениями; новый frontend не должен их использовать. `ratePerHundred` равен `null`, только когда
релевантных чеков нет.

## Метрики

Endpoint возвращает 12 строк в стабильном порядке `metricCode`:

| Metric code | Числитель | Знаменатель |
|---|---|---|
| `ACCESSORY_IPAD_MAC` | чек с аксессуаром iPad/Mac | чек с iPad/Mac |
| `ACCESSORY_PODS_WATCH` | чек с аксессуаром Pods/Watch | чек с Pods/Watch/другим устройством |
| `CASE_APPLE_IPHONE` | чек с чехлом iPhone | чек с любым iPhone |
| `CASE_SAMSUNG` | чек с чехлом Samsung | чек с любым Samsung |
| `CHARGER_CABLE` | чек с зарядкой/кабелем | чек с любым телефоном |
| `FILM_PHONE` | чек с плёнкой | чек с любым телефоном |
| `GLASS_CAMERA_IPHONE` | чек со стеклом/защитой камеры iPhone | чек с любым iPhone |
| `GLASS_CAMERA_SAMSUNG` | чек со стеклом Samsung | чек с любым Samsung |
| `PREMIUM_PROTECTION` | чек с протекцией | чек с новым устройством |
| `SETUP_SERVICE` | чек с настройкой/услугой | чек с любым телефоном |
| `WARRANTY_GENERIC_NEW` | чек с гарантией | чек с новым устройством |
| `WARRANTY_GENERIC_USED` | чек с гарантией | чек с устройством Б/У |

Б/у телефоны входят в общую базу аксессуарных метрик: чехлы, стёкла, зарядки/кабели, плёнки и
настройки. Отдельная база для Б/У применяется только к гарантии.

Подтверждённая классификация CARE:

| Номенклатура | Категория |
|---|---|
| `Check Premium` | протекция (`PREMIUM_PROTECTION`) |
| `ULTIMATE CARE` / `ULTIMATE CARE+` | протекция (`PREMIUM_PROTECTION`) |
| `ELITE CARE` | гарантия (`WARRANTY_GENERIC`) |
| `PRIVILEGE CARE` | гарантия (`WARRANTY_GENERIC`) |

## Условие устройства для гарантии

Гарантия попадает в `WARRANTY_GENERIC_NEW`, если в том же чеке есть новое устройство (`NEW` или
`ASIS`) и нет устройства Б/У. Она попадает в `WARRANTY_GENERIC_USED`, если есть устройство Б/У и
нет нового.

Смешанный чек с новым и Б/У устройством возможен, но гарантию нельзя достоверно связать с одним из
них. Система не делит её 50/50 и не считает в обеих метриках: гарантия исключается из обоих
числителей, а `ambiguousWarrantyItemCount` увеличивается. Знаменатели новых и Б/У устройств при
этом считаются независимо. В будущем точная связь по IMEI/серийному номеру может заменить это
консервативное правило новой версией формулы.

## Контроль качества

- `unmatchedNumeratorItemCount` — строки допродаж без релевантного устройства в том же SALE-чеке;
- `ambiguousWarrantyItemCount` — строки гарантии в смешанном чеке нового и Б/У устройства;
- `unknownDeviceConditionItemCount` — строки устройств, не отнесённые к `NEW`, `ASIS` или `USED`.

Счётчики качества отражают физические строки SALE, а не уникальные чеки. Удалённые документы,
удалённые строки и категория `EXCLUDE` не участвуют.

## Ошибки

- `400 INVALID_ARGUMENT` — период некорректен;
- `401 AUTHENTICATION_REQUIRED` — нет действующей сессии;
- `403 ACCESS_DENIED` — нет доступа к магазину или не завершена смена пароля;
- `404 STORE_NOT_FOUND` — магазин не существует.
