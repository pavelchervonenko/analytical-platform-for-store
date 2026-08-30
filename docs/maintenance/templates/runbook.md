# Шаблон runbook

```yaml
---
doc_schema: 1
doc_type: runbook
status: draft
owner: <project|backend|frontend|operations|security|integrations|product|ai>
audience:
  - operator
last_verified: YYYY-MM-DD
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - <static|local|staging|production-read-only|production-drill>
operation_type: <read-only|reversible-write|migration|recovery|destructive>
environments:
  - <local|test|staging|production>
risk_level: <low|medium|high|critical>
source_of_truth:
  - <script/config/runtime path>
verification_evidence:
  - level: static
    scope: <что именно проверено>
    verified_at: YYYY-MM-DD
    evidence: <sanitized evidence path>
required_reviewers:
  - operations
review_triggers:
  - <deployment-change|migration|flag-change|provider-change>
supersedes: []
superseded_by: null
---
```

## Название процедуры

### Цель и область

<Какой результат достигается, для какого окружения и что не разрешает эта процедура.>

### Влияние и требуемая авторизация

- Тип операции и окружение: сверить с `operation_type` и `environments` в metadata.
- Затрагиваемые сервисы/данные: <точный scope>.
- Кто подтверждает запуск: <роль>.
- Ожидаемое пользовательское влияние: <описание>.

### Предусловия

- <Доступ, backup, отсутствие конфликтующей job, версия инструментов.>

### Секреты и безопасный вывод

<Какие значения передаются только через environment/secret store и какие строки вывода можно
сохранить как evidence. Сами секреты здесь не приводятся.>

### Критерии остановки

- <Условие, при котором продолжать нельзя.>

### Preflight

```bash
# Read-only команды, подтверждающие точный target и предусловия.
```

Ожидаемый результат: <проверяемые признаки успеха>.

### Точный target

<Host/environment, release, store/connection, business period, job/document IDs или другой
минимальный набор, исключающий выполнение над неверным объектом.>

### Процедура

1. <Один наблюдаемый шаг.>
2. <Проверка результата перед следующим шагом.>

### Проверка результата

```bash
# Read-only post-check.
```

Бизнес-инварианты: <что должно совпасть до/после>.

### Повторный запуск и конкурентность

<Идемпотентность, допустимость retry, блокировка параллельной операции и действия после обрыва
соединения в неизвестной точке.>

### Rollback или forward-fix

<Что реально обратимо. Для миграции явно указать, что application rollback не откатывает БД, и
описать совместимость/forward-fix/restore.>

### Evidence

<Какие sanitized значения, timestamps, digests и результаты сохраняются; куда помещается
исторический отчет.>

### Репетиция

- Достигнутые независимые уровни: сверить `verification_levels` и все записи
  `verification_evidence`.
- Обязательный набор: все значения `required_verification_levels` должны быть подтверждены.
- Ограничения проверки: <что не было проверено>.

Runbook нельзя перевести в `status: current`, пока достигнутый набор не содержит каждый
обязательный gate из documentation policy. Для production write/migration/recovery/destructive
статической проверки недостаточно.
