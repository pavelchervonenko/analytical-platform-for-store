# Шаблоны документации

Шаблон выбирается по назначению материала, а не по имени существующего файла:

- [`current.md`](current.md) — действующий продуктовый, архитектурный или API-контракт;
- [`runbook.md`](runbook.md) — исполняемая процедура оператора;
- [`decision.md`](decision.md) — архитектурное или продуктовое решение;
- [`evidence.md`](evidence.md) — immutable результат релиза, аудита, canary, incident или сверки;
- [`archive.md`](archive.md) — superseded discovery/design/worklog;
- [`working.md`](working.md) — ограниченный по сроку plan/discovery/worklog до извлечения результата.

Для передачи проекта используются предметные дополнения к `working.md`:

- [`handover-entrypoint.md`](handover-entrypoint.md) — вход, проверенный статус и маршрут чтения;
- [`handover-work-items.md`](handover-work-items.md) — остаток работ и открытые решения;
- [`handover-component.md`](handover-component.md) — модуль, интерфейсы, поля и связи;
- [`handover-infrastructure.md`](handover-infrastructure.md) — окружения, сервисы и эксплуатация;
- [`handover-access.md`](handover-access.md) — безопасный реестр ролей и доступов.

Порядок создания и связи документов описаны в
[`project-handover-blueprint.md`](../project-handover-blueprint.md). Эти дополнения задают разделы
тела документа; lifecycle metadata и правила проверки берутся из `working.md`.

Перед использованием нужно удалить подсказки в угловых скобках, заполнить metadata и проверить
ссылки на источники истины.

Обязательное ядро сохраняется всегда: metadata, назначение/scope, проверяемые утверждения,
verification и связи с заменяемыми материалами. Условные разделы — например формулы, rollback или
runtime-evidence — включаются только когда они относятся к scope документа. Не нужно заполнять
шаблон шумовыми фразами «Не применяется»; отсутствие условного раздела должно быть однозначно из
назначения и metadata.
