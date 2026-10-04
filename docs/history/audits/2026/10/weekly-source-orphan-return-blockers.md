---
doc_schema: 1
doc_type: evidence
status: historical
owner: backend
audience:
  - developer
  - operations
snapshot_date: 2026-10-04
verdict: PASS
verdict_scope: Nine retained return runs and absent application parents only; not source reconciliation or generation approval
source_of_truth:
  - production-runtime:operator-provided-nine-exact-return-run-read-only-audit
  - docs/runbooks/weekly-review-ai.md
  - docs/current/integrations/livesklad/synchronization.md
required_reviewers:
  - backend
  - operations
---

# Девять targeted return runs: происхождение недельного stability blocker

Источник: предоставленный оператором sanitized вывод read-only аудита девяти точных run IDs.
Мутации БД, синхронизация и provider call в данном аудите отсутствуют. Фактическая версия
production фиксируется только в [project-state](../../../../current/project-state.md).

## Подтверждённое наблюдение

- Все девять runs имеют scope RETURNS, trigger REPROCESS, PARTIAL_SUCCESS; каждый получил
  один документ и зафиксировал один unresolved документ.
- Для каждого run найден retained source version с точной provenance, без сопоставления по
  близости timestamps.
- Восемь возвратов относятся к PRIMARY и один к SECONDARY; активных позиций суммарно шестнадцать.
- Все исходные external IDs присутствуют в сохранённом источнике возврата, но parent SALE
  отсутствует в приложении. Все шестнадцать return items пока не связаны с original items.
- Каждый возврат имеет RETURN_ORIGINAL_DOCUMENT_MISSING. У двух дополнительно
  RETURN_PAYMENT_MISMATCH, у одного RETURN_CASH_TRANSACTION_MISMATCH; аудит не подтверждает
  причину или допустимость этих финансовых расхождений.

## Вывод и границы доказательства

PARTIAL_SUCCESS здесь объясняется недоступными родительскими продажами, а не отдельными девятью
исключениями normalization. Отсутствие parent в приложении не доказывает его отсутствие или
удаление в LiveSklad. Нельзя разрешать orphan attribution, удалять возвраты либо вручную
переводить старые runs в SUCCESS на основании этого вывода.

Следующая проверка: после закрытия периода и штатной загрузки SALES повторно обработать RETURNS,
проверить parent/item links, непрерывный coverage и тот же stability predicate. Возможная причина
порядка поступления — webhook возврата раньше плановой загрузки продажи — согласуется с данными,
но полный source audit продаж ещё не проведён и не считается доказанным этим документом.
