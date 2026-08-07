# Data Quality API

Статус: реализовано и сверено 2026-07-23. Экран и переходы по `recommendedAction` описаны в
`docs/frontend-actions.md`; frontend не выводит action из текста сообщения.

## Назначение

API дает руководителю единый ответ о пригодности данных доступных ему магазинов. Он объединяет:

- фактическую свежесть продаж и возвратов из `data-status`;
- активную или завершившуюся ошибкой синхронизацию;
- открытые расхождения документов из `data_quality_issues`.

Store-wide сводка не угадывает выбранный месяц. Для явного месяца добавлен отдельный периодный
контроль, который объединяет исходные данные, план, рейтинг и payroll, но сохраняет payroll
readiness как детальную специализированную границу.

## Endpoints и права

- `GET /api/data-quality/summary` — сводка только по активным магазинам, доступным текущему
  пользователю. `ADMIN` видит все активные магазины, `MANAGER` — свои назначения.
- `GET /api/stores/{storeId}/data-quality` — подробность одного магазина. Применяется та же
  store-scoped проверка, что у KPI и payroll; чужой магазин возвращает `403`.

- `GET /api/stores/{storeId}/period-quality/{YYYY-MM}?asOf=YYYY-MM-DD` — общий контроль выбранного
  месяца для источников, плана, рейтинга и payroll. Полный контракт: `docs/period-quality-api.md`.

Все endpoints требуют активную сессию и завершенную смену временного пароля.

## Сводка

```ts
type DataQualityHealthStatus = "OK" | "WARNING" | "ERROR";
type StoreDataFreshnessStatus =
  | "NOT_SYNCED"
  | "CURRENT"
  | "STALE"
  | "SYNCING"
  | "ERROR";

interface StoreDataQualitySummaryView {
  storeId: string;
  storeName: string;
  status: DataQualityHealthStatus;
  freshnessStatus: StoreDataFreshnessStatus;
  dataThroughDate: string | null;
  lagDays: number | null;
  openIssueCount: number;
  errorCount: number;
  warningCount: number;
  infoCount: number;
  checkedAt: string;
}

interface DataQualityOverviewView {
  checkedAt: string;
  storeCount: number;
  okStoreCount: number;
  warningStoreCount: number;
  errorStoreCount: number;
  openIssueCount: number;
  stores: StoreDataQualitySummaryView[];
}
```

`ERROR` имеет приоритет над `WARNING`, `WARNING` — над `OK`. Информационная запись сама по себе
не ухудшает статус `OK`. `openIssueCount` включает как сохраненные открытые расхождения, так и
текущий производный сигнал синхронизации.

## Подробность магазина

```ts
type DataQualitySource = "SYNCHRONIZATION" | "SALES" | "RETURNS" | "DATA";
type DataQualitySeverity = "INFO" | "WARNING" | "ERROR";
type DataQualityRecommendedAction =
  | "NONE"
  | "WAIT_FOR_SYNC"
  | "RUN_SYNC"
  | "REVIEW_SOURCE_DOCUMENT";

interface DataQualityIssueView {
  key: string;
  source: DataQualitySource;
  code: string;
  severity: DataQualitySeverity;
  entityType: string;
  message: string;
  detectedAt: string | null;
  recommendedAction: DataQualityRecommendedAction;
}

interface StoreDataQualityView {
  summary: StoreDataQualitySummaryView;
  dataStatus: StoreDataStatusView;
  issues: DataQualityIssueView[];
}
```

Проблемы сортируются по критичности `ERROR -> WARNING -> INFO`, затем от новых к старым. `key`
нужен frontend только как стабильный ключ строки. Исходный внешний ID документа, JSON metadata,
raw payload и сохраненное внутреннее сообщение наружу не передаются.

## Производные коды синхронизации

| code | severity | recommendedAction |
| --- | --- | --- |
| `DATA_NOT_SYNCED` | `ERROR` | `RUN_SYNC` |
| `SYNC_FAILED` | `ERROR` | `RUN_SYNC` |
| `DATA_STALE` | `WARNING` | `RUN_SYNC` |
| `SYNC_IN_PROGRESS` | `INFO` | `WAIT_FOR_SYNC` |

Сохраненные коды расхождений продаж и возвратов возвращаются без изменения, но сопровождаются
безопасным сообщением. Неизвестный код получает общее сообщение и действие
`REVIEW_SOURCE_DOCUMENT`.

## Связь с frontend

- сводку можно загружать для общего индикатора и списка магазинов;
- подробность запрашивается после выбора магазина;
- `dataStatus.synchronization.active` определяет необходимость polling;
- frontend отображает `code` и `recommendedAction`, но не рассчитывает общий статус повторно;
- `null` у дат покрытия и lag означает отсутствие надежного значения, а не ноль.
