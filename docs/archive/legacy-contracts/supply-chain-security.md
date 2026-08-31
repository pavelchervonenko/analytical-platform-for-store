---
doc_schema: 1
doc_type: archive
status: archived
owner: security
audience:
  - developer
archived_at: 2026-08-31
superseded_by:
  - "docs/security/supply-chain.md"
original_content_sha256: 1059465d57ad6ba713cd3f014c86e261e4de33a1f78e19c2197712791475fcb6
required_reviewers:
  - information-architecture
---

> Archived legacy material preserved for provenance. Current replacements: `docs/security/supply-chain.md`.

# Gradle supply-chain integrity

Status: backend/repository slice implemented and verified on 2026-07-27. CI image signing,
provenance, SBOM publication and protected deployment remain separate P0-07 delivery gates.

## Trust model

Dependency locking and dependency verification solve different problems:

- `backend/gradle.lockfile` fixes the selected module versions;
- `gradle/verification-metadata.xml` fixes the SHA-256 of every resolved artifact and its metadata;
- `distributionSha256Sum` fixes the downloaded Gradle distribution;
- `scripts/tests/verify-gradle-supply-chain.py` verifies the checked-in wrapper JAR and rejects
  weakened or incomplete trust metadata without using the network.

The current trust roots are Gradle 9.0.0 and the reviewed dependency set for Spring Boot 4.1.0.
The verification file contains one SHA-256 per artifact and no broad `trusted-artifacts`,
`trusted-keys` or `ignored-keys` exceptions. Gradle's default verification mode is strict.
Do not use `--dependency-verification lenient` or `off` in developer, CI or release commands.

Checksums provide integrity against changed bytes. They do not independently prove publisher
identity. The metadata must therefore be generated only on a trusted workstation/runner after
reviewing intended coordinates and official release information. Adding PGP signatures can
strengthen provenance where publishers consistently provide verifiable signatures, but a generated
key allowlist still requires human review.

## Normal verification

Run with JDK 21:

```bash
./gradlew -p backend gradleSupplyChainIntegrityTest
./gradlew -p backend compileJava compileTestJava checkstyleMain checkstyleTest --offline
./gradlew -p backend check
```

The offline command proves that the locked and verified dependency set is already complete. A
temporary negative test with an intentionally wrong SHA-256 must fail with
`Dependency verification failed`; this was revalidated when the baseline was created.

## Controlled update procedure

1. Review the requested coordinates and release notes before changing the build.
2. For a Gradle upgrade, compare both the distribution and wrapper JAR SHA-256 with Gradle's
   official checksum page. Update the wrapper property and the expected values in the verifier
   together.
3. Update dependency declarations and regenerate `backend/gradle.lockfile` intentionally.
4. From a trusted, clean dependency source run:

   ```bash
   ./gradlew --write-verification-metadata sha256 dependencies
   ```

5. Review the metadata diff. Every new coordinate and checksum must correspond to the intended
   upgrade. Never make the build pass by introducing a group-wide trust rule or ignored key.
6. Run the normal and offline verification commands, the full backend suite, OpenAPI compatibility
   gate and the tampering probe before merging.

The generated metadata is deliberately verbose and version-controlled. Its review cost is the
trade-off for a fail-closed build when repository bytes change.

## Remaining CI/release work

The protected CI pipeline must still validate wrapper JARs with Gradle's maintained wrapper
validation action, build from a protected revision, publish SBOM/provenance and promote the exact
accepted image digest. Those controls complement this repository baseline; they must not replace or
disable local strict dependency verification.
