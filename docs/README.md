# Документация Store Analytics

Этот индекс ведёт к действующим контрактам, runbook и immutable evidence. Текущее проверенное
production-состояние хранится только в [current/project-state.md](current/project-state.md).
Датированные release, audit, canary и handoff-файлы не являются текущими инструкциями.

## Навигация

1. [Production state](current/project-state.md) — единственная сводка проверенного runtime.
2. [Действующие контракты](current/README.md) — архитектура, API, LiveSklad, продукт, frontend,
   AI и Telegram.
3. [Runbooks](runbooks/README.md) — процедуры, stop-условия и достигнутые уровни проверки.
4. [Security](security/README.md) — controls, trust boundaries и реестр остаточных рисков.
5. [Decisions](decisions/README.md) — принятые и proposed ADR.
6. [History](history/README.md) — датированные evidence, releases, audits, canaries и handoffs.
7. [Archive](archive/README.md) — superseded материалы, не являющиеся текущими инструкциями.

## Как читать статусы

- `current` — сверено с указанными implementation и verification sources.
- `draft` runbook — не разрешает production-операцию до прохождения перечисленных gates.
- `proposed` ADR — рекомендация, а не реализованное поведение.
- `evidence` — неизменяемый снимок на дату проверки.
- `archive` — только исторический контекст.

Versioned [prompts](prompts/) и [JSON schemas](schemas/) остаются runtime-артефактами на стабильных
путях. Их нельзя переписывать или перемещать как обычный текст.

## Поддержка

Правила lifecycle, ownership, review и удаления находятся в
[documentation-policy.md](maintenance/documentation-policy.md). Полный машинно-проверяемый реестр
— [documentation-inventory.tsv](maintenance/documentation-inventory.tsv). Каждое изменение кода,
API, формулы, UI, конфигурации или процедуры обновляет соответствующий current-документ в том же PR.

Секреты, environment dumps, provider payloads, персональные данные и бизнес-скриншоты в
документацию и evidence не включаются.
