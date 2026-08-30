# Шаблон решения (ADR)

```yaml
---
doc_schema: 1
doc_type: decision
status: proposed
owner: <project|backend|frontend|operations|security|integrations|product|ai>
audience:
  - developer
decision_date: null
implementation_status: not-started
decision_sources:
  - <evidence/code/customer-decision path>
implementation_sources: []
verification_sources: []
required_reviewers:
  - <logical reviewer role>
supersedes: []
superseded_by: null
---
```

## ADR-NNNN: Название решения

### Контекст

<Проблема, ограничения и подтвержденные факты. Отделить факт от гипотезы.>

### Решение

<Однозначное принятое правило и область его действия.>

Принятое решение не означает, что система уже ведет себя так же.

### Текущее реализованное поведение

<Что подтверждено кодом/runtime сейчас и чем отличается от решения.>

### Условия вступления решения в силу

<Какие code, migration, test, reconciliation и rollout conditions должны быть выполнены. После
этого обновить `implementation_status`: `not-started`, `partial`, `implemented` или `verified`.>

### Альтернативы

1. <Альтернатива и причина отказа.>

### Последствия

- Положительные: <результат>.
- Отрицательные/риски: <цена решения>.
- Совместимость и миграция: <влияние на данные/API/runtime>.

### Проверка

<Как доказать, что решение реализовано и продолжает соблюдаться; привести code/test/migration или
reconciliation evidence.>

### Связанные документы

- <current contract, runbook и evidence>.
