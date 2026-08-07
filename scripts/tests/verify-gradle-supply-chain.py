#!/usr/bin/env python3
"""Fail-closed, offline verification of the repository's Gradle trust roots."""

from __future__ import annotations

import hashlib
import re
import sys
from pathlib import Path
from xml.etree import ElementTree


PROJECT_ROOT = Path(__file__).resolve().parents[2]
WRAPPER_PROPERTIES = PROJECT_ROOT / "gradle/wrapper/gradle-wrapper.properties"
WRAPPER_JAR = PROJECT_ROOT / "gradle/wrapper/gradle-wrapper.jar"
VERIFICATION_METADATA = PROJECT_ROOT / "gradle/verification-metadata.xml"
DEPENDENCY_LOCK = PROJECT_ROOT / "backend/gradle.lockfile"

EXPECTED_DISTRIBUTION_URL = (
    r"https\://services.gradle.org/distributions/gradle-9.0.0-bin.zip"
)
EXPECTED_DISTRIBUTION_SHA256 = (
    "8fad3d78296ca518113f3d29016617c7f9367dc005f932bd9d93bf45ba46072b"
)
EXPECTED_WRAPPER_JAR_SHA256 = (
    "76805e32c009c0cf0dd5d206bddc9fb22ea42e84db904b764f3047de095493f3"
)
VERIFICATION_NAMESPACE = "https://schema.gradle.org/dependency-verification"
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
REQUIRED_COMPONENTS = {
    ("com.networknt", "json-schema-validator", "3.0.2"),
    ("com.puppycrawl.tools", "checkstyle", "13.3.0"),
    ("org.jacoco", "org.jacoco.agent", "0.8.14"),
    ("org.springdoc", "springdoc-openapi-starter-webmvc-ui", "3.0.3"),
    ("org.springframework.boot", "spring-boot-gradle-plugin", "4.1.0"),
    ("org.testcontainers", "testcontainers", "2.0.5"),
    ("tools.jackson.core", "jackson-databind", "3.1.4"),
}


def fail(message: str) -> None:
    print(f"GRADLE SUPPLY-CHAIN CHECK FAILED: {message}", file=sys.stderr)
    raise SystemExit(1)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def load_properties(path: Path) -> dict[str, str]:
    properties: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        if not separator or not key:
            fail(f"invalid wrapper property line: {raw_line!r}")
        if key in properties:
            fail(f"duplicate wrapper property: {key}")
        properties[key] = value
    return properties


def qualified(name: str) -> str:
    return f"{{{VERIFICATION_NAMESPACE}}}{name}"


def require_text(parent: ElementTree.Element, name: str, expected: str) -> None:
    element = parent.find(qualified(name))
    actual = element.text.strip() if element is not None and element.text else None
    if actual != expected:
        fail(f"{name} must be {expected!r}, got {actual!r}")


def main() -> None:
    for required_file in (
        WRAPPER_PROPERTIES,
        WRAPPER_JAR,
        VERIFICATION_METADATA,
        DEPENDENCY_LOCK,
    ):
        if not required_file.is_file():
            fail(f"required file is missing: {required_file.relative_to(PROJECT_ROOT)}")

    wrapper = load_properties(WRAPPER_PROPERTIES)
    if wrapper.get("distributionUrl") != EXPECTED_DISTRIBUTION_URL:
        fail("Gradle distribution URL differs from the reviewed 9.0.0 binary distribution")
    if wrapper.get("distributionSha256Sum") != EXPECTED_DISTRIBUTION_SHA256:
        fail("Gradle distribution SHA-256 differs from the official reviewed checksum")
    if wrapper.get("validateDistributionUrl") != "true":
        fail("Gradle wrapper distribution URL validation must remain enabled")
    if sha256(WRAPPER_JAR) != EXPECTED_WRAPPER_JAR_SHA256:
        fail("gradle-wrapper.jar does not match the official Gradle 9.0.0 wrapper")

    root = ElementTree.parse(VERIFICATION_METADATA).getroot()
    if root.tag != qualified("verification-metadata"):
        fail("unexpected dependency verification metadata namespace")
    configuration = root.find(qualified("configuration"))
    if configuration is None:
        fail("dependency verification configuration is missing")
    require_text(configuration, "verify-metadata", "true")
    if int(wrapper.get("networkTimeout", "0")) < 60_000:
        fail("Gradle wrapper network timeout must be at least 60 seconds")
    require_text(configuration, "verify-signatures", "false")
    for disallowed in ("trusted-artifacts", "trusted-keys", "ignored-keys"):
        if configuration.find(qualified(disallowed)) is not None:
            fail(f"broad verification bypass is forbidden: {disallowed}")

    components = root.find(qualified("components"))
    if components is None:
        fail("dependency verification components are missing")
    identities: set[tuple[str, str, str]] = set()
    artifact_count = 0
    for component in components.findall(qualified("component")):
        identity = (
            component.get("group", ""),
            component.get("name", ""),
            component.get("version", ""),
        )
        if not all(identity):
            fail(f"incomplete component identity: {identity!r}")
        identities.add(identity)
        artifacts = component.findall(qualified("artifact"))
        if not artifacts:
            fail(f"component has no verified artifacts: {identity!r}")
        for artifact in artifacts:
            artifact_count += 1
            checksums = artifact.findall(qualified("sha256"))
            if len(checksums) != 1:
                fail(
                    "every artifact must have exactly one SHA-256: "
                    f"{identity!r}/{artifact.get('name')!r}"
                )
            value = checksums[0].get("value", "")
            if SHA256_PATTERN.fullmatch(value) is None:
                fail(f"invalid SHA-256 for {identity!r}/{artifact.get('name')!r}")
            if any(
                artifact.find(qualified(algorithm)) is not None
                for algorithm in ("md5", "sha1")
            ):
                fail(f"weak checksum present for {identity!r}/{artifact.get('name')!r}")

    missing = REQUIRED_COMPONENTS - identities
    if missing:
        fail(f"critical verified components are missing: {sorted(missing)!r}")
    if artifact_count < 100:
        fail(f"suspiciously small verification set: {artifact_count} artifacts")

    print(
        "Gradle supply-chain integrity passed: "
        f"{len(identities)} components, {artifact_count} artifacts."
    )


if __name__ == "__main__":
    main()
