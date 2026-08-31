---
doc_schema: 1
doc_type: runbook
status: draft
owner: operations
audience:
  - operator
last_verified: 2026-08-31
last_rehearsed: null
verification_levels:
  - static
required_verification_levels:
  - staging
  - production-read-only
  - production-drill
operation_type: recovery
environments:
  - staging
  - production
risk_level: critical
source_of_truth:
  - deploy/bin/install-host.sh
  - deploy/compose.production.yml
  - deploy/Caddyfile
  - deploy/systemd
verification_evidence:
  - level: static
    scope: installed host paths, modes, Compose topology and disabled-by-default timers
    verified_at: 2026-08-31
    evidence: deploy/bin/install-host.sh
required_reviewers:
  - operations
  - security-privacy
  - backend
review_triggers:
  - host-image-change
  - topology-change
  - network-change
  - disaster-recovery
supersedes: []
superseded_by: null
---

# Пересборка application host

## Цель и область

Создать новый application host и подключить его к существующей подтверждённой БД. Восстановление
БД не выполняется, если она не потеряна/повреждена; тогда используется отдельный DR runbook.

## Влияние и требуемая авторизация

Меняются host/network/DNS, TLS, secret delivery и runtime services. Требуются infrastructure,
operations, security и customer change owner.

## Предусловия

- Чистый supported OS/host с проверенным fingerprint, patch level, Docker/Compose и firewall.
- Exact current release/digests/schema из verified project-state/evidence.
- Customer-held secrets/CA доступны через approved custody; DB connectivity разрешена точечно.
- Старый host изолирован или доказано, что параллельные workers/schedulers не запустятся.

## Критерии остановки

- Host identity/network/DNS или database target неоднозначны.
- Используются mutable images, copied user home credentials или secrets из старого disk без review.
- Live schema не совместима с exact release; backup/restore status неизвестен.
- Старый worker всё ещё способен claim jobs.

## Preflight и точный target

Record: old/new host fingerprints/IPs, DB endpoint/name, domain/DNS, release/digests/schema, firewall
rules, secret inventory/key IDs и cutover/rollback owner. Проверить DB read-only и object/backup
status без запуска application writers.

## Процедура

1. Harden/patch host; установить Docker/Compose и ограниченный operator access.
2. Из reviewed checkout выполнить `install-host.sh`; units/timers остаются disabled.
3. Provision PKI/secrets с root ownership/modes; запустить release preflight и Compose config.
4. Pull exact digests. Если schema уже current, не применять новую migration без отдельного change.
5. Поднять API и проверить internal/public readiness; затем ровно один worker; затем web.
6. Выполнить smoke, release/schema/digest, queue и бизнес-проверки.
7. Переключить DNS/traffic; наблюдать. Включать backup/health timers только после отдельной
   acceptance каждого контура.
8. Изолировать старый host и rotate host-bound credentials по security decision.

## Повторный запуск и конкурентность

Host install идемпотентно копирует artifacts, но application start не должен создавать второй
worker/API session domain. Не повторять migration при неизвестном результате.

## Rollback или forward-fix

До DB/schema changes rollback — вернуть traffic старому изолированному host. После credential/DNS
rotation нужен controlled reverse cutover. DB recovery выполняется отдельно.

## Evidence и известные пробелы

Сохранить host fingerprint/patches, firewall, digests/schema, secret-file validation, health/smoke,
DNS timing и timer acceptance. Нет воспроизводимого infrastructure-as-code, host rebuild drill или
formal RTO evidence; runbook остаётся draft.
