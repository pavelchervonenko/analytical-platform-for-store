---
doc_schema: 1
doc_type: runbook
status: draft
owner: security
audience:
  - operator
last_verified: 2026-08-31
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - staging
  - production-read-only
operation_type: reversible-write
environments:
  - staging
  - production
risk_level: high
source_of_truth:
  - deploy/compose.production.yml
  - deploy/bin/release-safety.sh
  - backend/src/main/resources/application.yml
verification_evidence:
  - level: static
    scope: current secret file validation and application previous-secret capability
    verified_at: 2026-08-31
    evidence: scripts/tests/deploy-release-safety-test.sh
required_reviewers:
  - security-privacy
  - operations
review_triggers:
  - secret-change
  - provider-change
  - compose-change
  - credential-compromise
supersedes: []
superseded_by: null
---

# Ротация секретов

## Цель и область

Заменить один exact credential с контролируемым overlap/restart и доказать отказ старого. Процедура
не разрешает массовую ротацию нескольких trust boundaries одним шагом.

## Влияние и требуемая авторизация

Security owner подтверждает credential и причину; operations выполняет mount/restart; provider
owner создаёт/revoke value. При compromise используется incident commander.

## Предусловия и точный target

Change record: environment, service/provider, secret logical name/path basename, current key ID
(не value), consumers, overlap support, restart order, validation и revocation owner.

## Критерии остановки

- Нет inventory consumers или exact secret неизвестен.
- Новое значение появилось в shell history/log/env/argv либо file mode/type не прошёл preflight.
- Dual-secret требуется, но previous mount/config отсутствует.
- Нет способа проверить new credential и доказать отказ old credential.

## Процедура

1. Создать новое значение в approved custody и atomic root-owned file `0600`.
2. Если end-to-end previous-secret mount реализован: сначала mount old как previous и new как
   current, restart canary consumer, проверить оба, затем переключить producer.
3. Если previous mount отсутствует: stop. Подготовить Compose/config change и staging rehearsal;
   не имитировать overlap environment secret.
4. После stable window отозвать old у provider, удалить previous mount/file и повторить restart.
5. Проверить, что new работает, old отказан и logs не содержат values.

## Проверка результата

Consumer healthy, authenticated canary успешен с new, отрицательный test старого ожидаемо отказан,
previous path отсутствует, audit/key ID обновлены.

## Повторный запуск и rollback

До provider revocation rollback — вернуть old current. После revocation rollback требует новый
credential; старый не реактивируется без security decision.

## Evidence и известные пробелы

Сохранить credential type/key IDs, timestamps, file validation, canary/negative verdict и revocation
reference. Production Compose не монтирует previous LiveSklad/Telegram secrets, поэтому dual-secret
rotation этих путей пока неоперабельна и runbook остаётся draft.
