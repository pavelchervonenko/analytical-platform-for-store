# Шаблон действующего документа

```yaml
---
doc_schema: 1
doc_type: current
status: draft
owner: <project|backend|frontend|operations|security|integrations|product|ai>
audience:
  - <developer|operator|manager>
last_verified: null
requirement_sources:
  - <customer decision/product requirement path>
implementation_sources:
  - <code/OpenAPI/Flyway/config path>
verification_sources:
  - <automated test/contract check/reconciliation path>
runtime_evidence: []
required_reviewers:
  - <logical reviewer role>
review_triggers:
  - <api-change|metric-change|migration|flag-change|ui-change|ai-contract-change>
supersedes: []
superseded_by: null
---
```

## Название

### Назначение и границы

<Какой вопрос закрывает документ и что намеренно находится вне его области.>

### Термины

<Только термины, без которых контракт можно понять неоднозначно.>

### Действующий контракт

<Реализованное поведение, состояния и границы ответственности.>

Требование без ссылки на реализацию и проверку не может быть опубликовано как `status: current`.

| Утверждение | Requirement source | Реализованное поведение | Implementation source | Verification | Расхождение |
|---|---|---|---|---|---|
| <проверяемое правило> | <path> | <что делает система сейчас> | <path> | <path/result> | <нет или явное отличие> |

### Инварианты

- <Проверяемое правило.>

### Формулы и примеры

<Для метрик: знак, знаменатель, округление, null, период, область магазина/сотрудника и числовой
пример. Раздел можно убрать, если документ не описывает метрику.>

### Ошибки и неполные данные

<Что происходит при missing/partial/stale/invalid данных и что нельзя интерпретировать как ноль.>

### Расхождения и открытые решения

<Отличие требований от реализации. Если расхождений нет — указать, каким evidence это проверено.>

### Проверка

<Тесты, code oracle, contract check и ручная проверка, подтверждающие `last_verified`. Если есть
production-утверждение — добавить ограниченное runtime evidence.>

### Триггеры пересмотра

<Какие изменения требуют обновить документ в том же PR/release.>
