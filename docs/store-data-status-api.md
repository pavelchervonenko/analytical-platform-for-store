# Статус и актуальность данных магазина

Статус: read-only endpoint реализован и сверен 2026-07-23. Менеджер видит состояние, но запуск
синхронизации остается ADMIN-only действием; см. `docs/frontend-actions.md`.

`GET /api/stores/{storeId}/data-status` возвращает понятный руководителю статус загрузки продаж и
возвратов магазина. Endpoint read-only и не запускает синхронизацию.

## Доступ

- `ADMIN` может читать любой существующий магазин.
- `MANAGER` — только магазин из своих назначений.
- отсутствие права возвращает `403`, неизвестный доступный администратору UUID — `404` с
  `code=STORE_NOT_FOUND`.
- сессия с обязательной сменой временного пароля получает `403`.

Проверка права выполняется до чтения данных, поэтому UUID чужого магазина нельзя использовать для
получения сведений о синхронизации или проблемах качества.

## Контракт

```ts
type StoreDataFreshnessStatus =
  | "NOT_SYNCED"
  | "CURRENT"
  | "STALE"
  | "SYNCING"
  | "ERROR";

type StoreSyncActivityType = "JOB" | "DIRECT_RUN";

interface StoreSyncActivity {
  active: boolean;
  id: string | null;
  type: StoreSyncActivityType | null;
  status: string | null;
  phase: string | null;
  startedAt: string | null;
  nextAttemptAt: string | null;
}

interface StoreDataStatus {
  storeId: string;
  status: StoreDataFreshnessStatus;
  expectedThroughDate: string;
  dataThroughDate: string | null;
  salesDataThroughDate: string | null;
  returnsDataThroughDate: string | null;
  lagDays: number | null;
  lastCompletedSyncAt: string | null;
  synchronization: StoreSyncActivity;
  openQualityIssueCount: number;
  lastError: string | null;
  lastErrorAt: string | null;
  checkedAt: string;
}
```

Даты покрытия имеют формат `YYYY-MM-DD` в timezone магазина. Временные метки — ISO-8601
`Instant`. Объект `synchronization` присутствует всегда; при отсутствии активной загрузки
`active=false`, остальные поля объекта равны `null`.

Пример:

```json
{
  "storeId": "00000000-0000-0000-0000-000000000001",
  "status": "STALE",
  "expectedThroughDate": "2026-07-21",
  "dataThroughDate": "2026-07-19",
  "salesDataThroughDate": "2026-07-21",
  "returnsDataThroughDate": "2026-07-19",
  "lagDays": 2,
  "lastCompletedSyncAt": "2026-07-22T05:30:00Z",
  "synchronization": {
    "active": false,
    "id": null,
    "type": null,
    "status": null,
    "phase": null,
    "startedAt": null,
    "nextAttemptAt": null
  },
  "openQualityIssueCount": 3,
  "lastError": null,
  "lastErrorAt": null,
  "checkedAt": "2026-07-22T08:00:00Z"
}
```

## Правила расчета

1. `expectedThroughDate` — вчерашняя календарная дата в timezone магазина: текущий незавершенный
   день не считается обязательным.
2. Покрытие продаж и возвратов берется из завершенных `SUCCESS` и `PARTIAL_SUCCESS` sync runs.
   Верхняя граница sync-периода исключительная, а в API преобразуется в последнюю полностью
   обработанную календарную дату.
3. `dataThroughDate` — более ранняя из дат продаж и возвратов. Если отсутствует хотя бы одна из
   них, объединенное покрытие неизвестно.
4. `lagDays` — число полных календарных дней от `dataThroughDate` до ожидаемой даты, минимум `0`.
5. `PARTIAL_SUCCESS` подтверждает обработку периода, но открытые проблемы остаются видимы через
   `openQualityIssueCount`.
6. Store-scoped run относится только к своему магазину. Connection-wide run без `storeId`
   относится ко всем магазинам соответствующего подключения.

Приоритет итогового статуса:

1. `SYNCING` — существует активный job (`PENDING`, `RUNNING`, `WAITING_RETRY`) либо прямой
   `RUNNING` run.
2. `ERROR` — активной загрузки нет, а последняя завершенная sync-активность завершилась `FAILED`.
3. `NOT_SYNCED` — нет полного покрытия одновременно по продажам и возвратам.
4. `STALE` — объединенная дата раньше ожидаемой.
5. `CURRENT` — объединенная дата совпадает с ожидаемой или новее.

`lastError` показывает безопасное краткое описание последней ошибки и может сохраняться после
последующего успешного запуска как история. Для основного error-state frontend должен опираться на
`status`, а не только на наличие этого поля.

## Рекомендации frontend

- Обновлять статус после выбора магазина, при возврате вкладки в foreground и пока
  `status=SYNCING`; постоянный частый polling для остальных состояний не нужен.
- Показывать `dataThroughDate` и `lagDays`, а при `NOT_SYNCED` отдельно объяснять, какой поток
  отсутствует по `salesDataThroughDate`/`returnsDataThroughDate`.
- `openQualityIssueCount > 0` — предупреждение о качестве, но не автоматическая ошибка загрузки.
- Не вычислять общий статус на клиенте и не подменять `null` текущей датой или нулем.
- Не показывать руководителю административные кнопки запуска sync: существующие sync endpoints
  доступны только `ADMIN`.

Для общего индикатора по всем доступным магазинам и безопасного списка конкретных проблем следует
использовать `GET /api/data-quality/summary` и `GET /api/stores/{storeId}/data-quality`. Их полный
контракт описан в `docs/data-quality-api.md`; этот endpoint остается источником детальной свежести.
