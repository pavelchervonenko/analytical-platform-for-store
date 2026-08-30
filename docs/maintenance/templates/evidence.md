# Шаблон исторического evidence

```yaml
---
doc_schema: 1
doc_type: evidence
status: historical
owner: <project|backend|frontend|operations|security|integrations|product|ai>
audience:
  - developer
  - operator
snapshot_date: YYYY-MM-DD
verdict: <PASS|FAIL|PARTIAL|ACTION_REQUIRED>
verdict_scope: <точно ограниченное утверждение, которое проверено>
source_of_truth:
  - <sanitized output/test report/runtime observation>
required_reviewers:
  - <logical reviewer role>
related_current:
  - <current contract or runbook path>
supersedes: []
---
```

## Тип и название события

### Область и время

<Окружение, release/commit, период данных и точное время наблюдения. Не включать секреты.>

### До события

<Проверенные исходные факты.>

### Выполненное действие или проверка

<Что фактически произошло. Не превращать этот раздел в универсальный runbook.>

### Наблюдаемый результат

<Технические и бизнес-инварианты, digests, schema, health, reconciliation delta.>

### Что подтверждено

- <Утверждение строго внутри `verdict_scope`.>

### Что этим evidence не подтверждено

- <Другие магазины, периоды, полные данные, массовый rollout или иная непроверенная область.>

### Какие действия PASS не разрешает

- <Например: не разрешает включение флага для всех магазинов, migration или удаление rollback без
  отдельного gate.>

### Итог

Сверить с `verdict` и `verdict_scope` в metadata — <краткое основание без расширения scope>.

### Остаточные риски

- <Неустраненный риск или `Нет известных` с основанием.>

### Связи

- Действующий контракт: <path>.
- Runbook: <path>.
- Следующее evidence/errata: <path или `Нет`>.
