---
doc_schema: 1
doc_type: current
status: current
owner: frontend
audience:
  - developer
  - manager
last_verified: 2026-09-09
requirement_sources:
  - docs/current/product/plans-and-shifts.md
implementation_sources:
  - frontend/src/plan-schedule/PlanSchedulePage.tsx
  - frontend/src/plan-schedule/PlanPanel.tsx
  - frontend/src/plan-schedule/DailyPlanTable.tsx
  - frontend/src/plan-schedule/SchedulePanel.tsx
  - frontend/src/plan-schedule/forms.ts
  - frontend/src/api/queries.ts
verification_sources:
  - frontend/src/plan-schedule/PlanPanel.test.tsx
  - frontend/src/plan-schedule/DailyPlanTable.test.tsx
  - frontend/src/plan-schedule/forms.test.ts
  - frontend/e2e/visual-local.spec.ts
runtime_evidence: []
required_reviewers:
  - frontend
  - product
review_triggers:
  - plan-ui-change
  - shift-ui-change
  - plan-formula-change
supersedes: []
superseded_by: null
---

# «План и смены»

План относится к selected month: progress — `month-01..asOf`, target — monthly. Schedule относится
к календарным дням этого месяца.

| Блок | Scope | Null/empty | Подпись |
|---|---|---|---|
| Направления | Month..asOf | Plan absent → setup | Факт/критерий/месяц |
| Прошлый день | День + cumulative gap | Share nullable без revenue | «Факт за день» |
| Будущий день | Month forecast + remaining | Нет future days | «Цель на день» |
| Смены | Даты месяца | День без смен явный | Дата, сотрудник, часы |

Roster для добавления смен содержит только активных сотрудников с активным назначением и
`participatesInRanking=true`. Ранее сохранённая смена сотрудника, который позже вышел из roster,
остаётся видимой внутри конкретного дня как недоступная для новых смен: пользователь должен удалить
её явно, а интерфейс не теряет существующие данные молча.

Формулы находятся в [product contract](../product/plans-and-shifts.md). Frontend не пересчитывает
achievement по округлённой строке: доля, достигшая месячной цели на текущих данных, получает
backend-статус `ACHIEVED`. «Нужно в день» должно называться остатком месячной цели, а future
target — отличаться от факта. При incomplete classification менеджер видит нейтральное объяснение,
что часть показателей появится после обновления категорий, без технического счётчика и поручения
проверять данные. Пустой roster содержит одно предупреждение со ссылкой на реальное управление
участниками в разделе «Сотрудники».

План, progress, календарь и настройки состава деградируют независимо. Ошибка фонового обновления
не удаляет уже показанные данные; первая ошибка вспомогательного запроса остаётся локальной. Только
отсутствие всех основных данных переводит соответствующий экран в блокирующее состояние.
