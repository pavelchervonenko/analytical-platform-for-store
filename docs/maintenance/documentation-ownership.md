---
doc_schema: 1
doc_type: current
status: current
owner: project
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/maintenance/documentation-policy.md
implementation_sources:
  - docs/maintenance/templates/current.md
  - docs/maintenance/templates/runbook.md
  - docs/maintenance/templates/decision.md
  - docs/maintenance/templates/evidence.md
verification_sources:
  - docs/maintenance/documentation-inventory.tsv
runtime_evidence: []
required_reviewers:
  - information-architecture
  - operations
review_triggers:
  - ownership-change
  - repository-role-change
supersedes: []
superseded_by: null
---

# Владение и review документации

## Принцип

`owner` — это область ответственности за смысл документа, а не вымышленная GitHub-команда.
До появления нескольких постоянных участников фактическим approver остается владелец проекта,
но тип требуемой проверки определяется таблицей ниже.

Агент может исследовать код, подготовить текст и выполнить техническую проверку. Агент не может без
наблюдаемого evidence самостоятельно подтвердить production-состояние, решение заказчика,
успешность restore drill или применение секретов.

## Логические владельцы

| Owner | Область содержания | Обязательный reviewer для критических изменений |
|---|---|---|
| `project` | индекс, структура, project state, общие правила | независимый information-architecture reviewer |
| `backend` | архитектура backend, API, ошибки, persistence | backend/data reviewer |
| `frontend` | UI-контракты, маршруты, local visual acceptance | frontend/product reviewer |
| `operations` | deploy, migration, rollback, backup/restore, monitoring | независимый operations reviewer |
| `security` | auth, access, secrets, retention, supply chain | независимый security/operations reviewer |
| `integrations` | LiveSklad, sync, webhook, retry, reconciliation ingestion | backend/integration reviewer |
| `product` | KPI, периоды, классификация, payroll, customer methodology | product/formula reviewer и customer confirmation при изменении требований |
| `ai` | weekly-review, legacy LLM, prompts, schemas, evaluation, Telegram | AI contract/semantic reviewer; privacy reviewer при изменении provider payload |

## Роли reviewer-ов

| `required_reviewers` | Логический владелец проверки | Когда нужен |
|---|---|---|
| `information-architecture` | `project` | структура, навигация, lifecycle и project-state |
| `backend-data` | `backend` | backend contract, persistence, migration и data semantics |
| `frontend-product` | `frontend` + `product` | пользовательское поведение и visual contract |
| `operations` | `operations` | production procedure, deploy, recovery, backup и observability |
| `security-privacy` | `security` | auth, secrets, retention, персональные данные и provider payload |
| `integration` | `integrations` + `backend` | LiveSklad, webhook, sync, retry и reconciliation ingestion |
| `product-formula` | `product` | KPI, период, классификация, payroll и customer methodology |
| `ai-semantic` | `ai` | prompt/schema, semantic eval и AI rollout |

Междоменный документ перечисляет все требуемые роли в `required_reviewers`. Проверка privacy для
AI provider payload всегда относится к `security-privacy`, а не считается частью только AI-review.

## Уровни review

### Обычный

Достаточен для исправления формулировки, ссылки или структуры без изменения смысла. Reviewer
проверяет, что канонический источник и status не изменены ошибочно.

### Доменный

Обязателен при изменении API, формулы, классификации, архитектурного контракта или пользовательского
поведения. Reviewer сверяет документ с кодом, тестами и примерами.

### Независимый критический

Обязателен для:

- production runbook и feature flags;
- миграции, rollback и forward-fix;
- backup/restore и disaster recovery;
- auth, secrets, ACL и break-glass;
- финансовой формулы и атрибуции возврата;
- LiveSklad recovery/reconciliation;
- AI provider payload, prompt/schema и production rollout.

Независимый reviewer не должен быть автором основного изменения и возвращает явный `PASS`,
`CHANGES_REQUIRED` или `BLOCKED` с приоритетами замечаний.

## Границы подтверждения

| Утверждение | Кто и чем подтверждает |
|---|---|
| Код соответствует документу | доменный reviewer + автоматические тесты |
| Формула соответствует решению заказчика | product owner + сохраненное customer evidence |
| Production использует релиз/flag | operator по sanitized runtime output после deploy |
| Backup пригоден | operations reviewer по restore drill evidence |
| Recovery завершена | operator по до/после инвариантам и reconciliation evidence |
| AI-контракт безопасен | AI reviewer по schema/eval/privacy gates |
| UI соответствует контракту | frontend reviewer по тестам и локальным visual artifacts |

## CODEOWNERS

`CODEOWNERS` вводится только после появления реальных GitHub users/teams, которые готовы получать и
принимать review. Фиктивные команды не создаются. До этого ownership matrix используется в PR и
release checklist как логическая маршрутизация.
