---
doc_schema: 1
doc_type: current
status: current
owner: security
audience:
  - developer
  - operator
last_verified: 2026-08-31
requirement_sources:
  - docs/supply-chain-security.md
implementation_sources:
  - .github/workflows/ci.yml
  - .github/workflows/release-images.yml
  - gradle/verification-metadata.xml
  - backend/gradle.lockfile
  - frontend/package-lock.json
  - backend/Dockerfile
  - frontend/Dockerfile
verification_sources:
  - scripts/tests/verify-gradle-supply-chain.py
  - scripts/tests/security-hardening-test.sh
  - .github/workflows/ci.yml
runtime_evidence: []
required_reviewers:
  - security-privacy
  - operations
review_triggers:
  - dependency-change
  - workflow-change
  - base-image-change
  - release-pipeline-change
supersedes: []
superseded_by: null
---

# Supply-chain security

## Назначение и границы

Документ различает реализованную dependency/build integrity и ещё не enforced artifact provenance.
Публикация digest сама по себе не доказывает, что deploy связан с reviewed tag/commit.

## Действующий контракт

- GitHub Actions используются по immutable action SHA; workflow permissions ограничены.
- Gradle wrapper, dependency verification metadata и lockfile проверяются репозиторными gates.
- Frontend устанавливается через `npm ci` по lockfile; build запускает contracts, lint, tests и
  production build.
- Release workflow повторно выполняет backend/frontend checks, строит images и публикует GHCR
  references по digest.
- Production release metadata предполагает `repository@sha256`, но server preflight пока не
  валидирует полную связь image digest, release tag и commit.

## Инварианты

- Mutable tag не является production coordinate.
- Dependency update требует review lock/verification metadata и полного test gate.
- Build secrets не попадают в image layer или artifact.
- Только digest без signature/provenance не подтверждает builder identity.

## Подтверждённые ограничения

- SBOM, vulnerability scan threshold, provenance/attestation и image signature не являются
  обязательными release/deploy gates.
- Server-side signature/provenance verification перед migration отсутствует.
- Docker base images закреплены version tags, а не digests; `--pull` может изменить базовый слой.
- Protected tag/branch и связь release ID с commit находятся вне репозитория и не доказаны.

## Проверка

CI и supply-chain tests проверяют wrapper/checksums/locks и безопасный build path. Полный gate
требует SBOM, scan, signed provenance и server-side verification exact artifact перед deploy.

## Триггеры пересмотра

Изменение workflow/actions, registry, dependency manager, base image, signing/provenance или deploy
preflight требует обновления документа.
