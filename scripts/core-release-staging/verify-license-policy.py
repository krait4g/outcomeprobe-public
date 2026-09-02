#!/usr/bin/env python3
"""Normalize release SBOM licenses and enforce the explicit decision policy."""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import hashlib
import json
import pathlib
import re
import shutil
import sys
from dataclasses import dataclass, field
from typing import Any, Iterable
from urllib.parse import unquote

import host_path_policy

UNKNOWN_LICENSES = {"", "NOASSERTION", "NONE", "UNKNOWN"}
ALLOWED_CONTEXTS = {"runtime", "build", "test"}
SPDX_REGISTRY_PATH = pathlib.Path(__file__).with_name(
    "spdx-license-registry-3.28.0.json"
)
SPDX_REGISTRY_VERSION = "3.28.0"
SPDX_REGISTRY_REPOSITORY = "https://github.com/spdx/license-list-data"
SPDX_REGISTRY_TAG = "v3.28.0"
SPDX_REGISTRY_COMMIT = "c4a7237ec8f4654e867546f9f409749300f1bf4c"
SPDX_REGISTRY_SHA256 = "f0c45148b93d643a34e09dc33ff55fed6c3b6205491b0b66c8cdb845fb19851c"
SPDX_REGISTRY_SOURCE_PATH = (
    "scripts/core-release-staging/spdx-license-registry-3.28.0.json"
)
SPDX_REGISTRY_RELEASE_ASSET = "outcomeprobe-spdx-license-registry-3.28.0.json"
SPDX_LICENSES_URL = (
    "https://raw.githubusercontent.com/spdx/license-list-data/"
    f"{SPDX_REGISTRY_COMMIT}/json/licenses.json"
)
SPDX_LICENSES_SHA256 = "f728c534d8bd1044fc515a2ddb2292be99559021d830bfa3281be0bcd36302ee"
SPDX_EXCEPTIONS_URL = (
    "https://raw.githubusercontent.com/spdx/license-list-data/"
    f"{SPDX_REGISTRY_COMMIT}/json/exceptions.json"
)
SPDX_EXCEPTIONS_SHA256 = "bd145bb558f44432fcd6f0d7e956ed0124dff72af7641a7cfc3b1b557dc390a5b"
DENIED_LICENSE = re.compile(
    r"(^|[^A-Za-z0-9])(?:AGPL-(?:1\.0|3\.0)(?:-only|-or-later)?|"
    r"SSPL-1\.0|BUSL-1\.1|Affero|Server Side Public License|"
    r"Business Source License)(?:[^A-Za-z0-9]|$)",
    re.IGNORECASE,
)
SHA256 = re.compile(r"^[0-9a-f]{64}$")
EXACT_IDENTITY = re.compile(r"^pkg:[^*?#\s]+@[^*?#\s]+$")
class PolicyError(RuntimeError):
    """Raised when release license evidence cannot satisfy the policy."""


class SpdxExpressionError(ValueError):
    """Raised when a license value is not an approved SPDX expression."""


@dataclass(frozen=True)
class SpdxRegistry:
    """Pinned non-deprecated identifiers from one official SPDX release."""

    license_ids: frozenset[str]
    deprecated_license_ids: frozenset[str]
    exception_ids: frozenset[str]
    deprecated_exception_ids: frozenset[str]


@dataclass(frozen=True)
class SpdxExpression:
    """Identifiers extracted from one syntactically valid SPDX expression."""

    license_ids: frozenset[str]
    exception_ids: frozenset[str]


@dataclass(frozen=True)
class ResolutionOutcome:
    """Fail-fast categories retained separately for release evidence."""

    blockers: tuple[str, ...]
    denied: tuple[str, ...]
    unapproved: tuple[str, ...]


@dataclass
class LicenseRow:
    subject: str
    context: str
    source_format: str
    component_type: str
    name: str
    version: str | None
    purl: str | None
    identity: str | None
    licenses: list[str]
    missing: bool
    source_ref: str
    synthetic_classification: str | None = None
    resolution: str | None = None
    resolved_licenses: list[str] = field(default_factory=list)
    decision_identity: str | None = None


def fail(message: str) -> None:
    raise PolicyError(message)


def read_json(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        fail(f"invalid JSON {path.name}: {exc}")
    if not isinstance(value, dict):
        fail(f"invalid JSON {path.name}: document root must be an object")
    return value


def read_string_set(document: dict[str, Any], key: str) -> frozenset[str]:
    """Read one unique, non-empty identifier array from the SPDX registry."""
    values = document.get(key)
    if not isinstance(values, list) or any(
        not isinstance(value, str) or not value for value in values
    ):
        fail(f"SPDX registry {key} must be an array of non-empty strings")
    result = frozenset(values)
    if len(result) != len(values):
        fail(f"SPDX registry {key} contains duplicate identifiers")
    return result


def load_spdx_registry(path: pathlib.Path = SPDX_REGISTRY_PATH) -> SpdxRegistry:
    """Load and structurally verify the repository-pinned SPDX identifier list."""
    if sha256_file(path) != SPDX_REGISTRY_SHA256:
        fail("SPDX registry SHA-256 does not match the repository-pinned value")
    document = read_json(path)
    expected_metadata = {
        "schemaVersion": 1,
        "licenseListVersion": SPDX_REGISTRY_VERSION,
        "sourceRepository": SPDX_REGISTRY_REPOSITORY,
        "sourceTag": SPDX_REGISTRY_TAG,
        "sourceCommit": SPDX_REGISTRY_COMMIT,
    }
    for key, expected in expected_metadata.items():
        if document.get(key) != expected:
            fail(f"SPDX registry {key} must be {expected!r}")
    if document.get("sourceFiles") != {
        "licensesSha256": SPDX_LICENSES_SHA256,
        "exceptionsSha256": SPDX_EXCEPTIONS_SHA256,
    }:
        fail("SPDX registry must bind the exact immutable upstream source hashes")
    licenses = read_string_set(document, "licenseIds")
    deprecated_licenses = read_string_set(document, "deprecatedLicenseIds")
    exceptions = read_string_set(document, "exceptionIds")
    deprecated_exceptions = read_string_set(document, "deprecatedExceptionIds")
    if licenses & deprecated_licenses:
        fail("SPDX registry active and deprecated license identifiers overlap")
    if exceptions & deprecated_exceptions:
        fail("SPDX registry active and deprecated exception identifiers overlap")
    return SpdxRegistry(
        license_ids=licenses,
        deprecated_license_ids=deprecated_licenses,
        exception_ids=exceptions,
        deprecated_exception_ids=deprecated_exceptions,
    )


def spdx_registry_policy_contract() -> dict[str, Any]:
    """Return the standalone source and release-asset binding contract."""
    return {
        "version": SPDX_REGISTRY_VERSION,
        "sourceRepository": SPDX_REGISTRY_REPOSITORY,
        "sourceTag": SPDX_REGISTRY_TAG,
        "sourceCommit": SPDX_REGISTRY_COMMIT,
        "registryPath": SPDX_REGISTRY_SOURCE_PATH,
        "releaseAssetName": SPDX_REGISTRY_RELEASE_ASSET,
        "registrySha256": SPDX_REGISTRY_SHA256,
        "upstreamFiles": {
            "licenses": {
                "url": SPDX_LICENSES_URL,
                "sha256": SPDX_LICENSES_SHA256,
            },
            "exceptions": {
                "url": SPDX_EXCEPTIONS_URL,
                "sha256": SPDX_EXCEPTIONS_SHA256,
            },
        },
    }


SPDX_TOKEN = re.compile(
    r"\s*(?:(?P<open>\()|(?P<close>\))|(?P<plus>\+)|"
    r"(?P<operator>AND\b|OR\b|WITH\b)|(?P<identifier>[A-Za-z0-9][A-Za-z0-9.-]*))"
)


class SpdxExpressionParser:
    """Parse SPDX expressions with the precedence and WITH rules from SPDX 2.3."""

    def __init__(self, expression: str, registry: SpdxRegistry) -> None:
        self.expression = expression
        self.registry = registry
        self.tokens = self._tokenize(expression)
        self.position = 0
        self.license_ids: set[str] = set()
        self.exception_ids: set[str] = set()

    @staticmethod
    def _tokenize(expression: str) -> list[str]:
        tokens: list[str] = []
        position = 0
        while position < len(expression):
            match = SPDX_TOKEN.match(expression, position)
            if not match:
                if expression[position:].strip() == "":
                    break
                raise SpdxExpressionError(
                    f"invalid SPDX expression token at offset {position}"
                )
            tokens.append(match.group(0).strip())
            position = match.end()
        if not tokens:
            raise SpdxExpressionError("SPDX expression is empty")
        return tokens

    def current(self) -> str | None:
        return self.tokens[self.position] if self.position < len(self.tokens) else None

    def consume(self, expected: str | None = None) -> str:
        token = self.current()
        if token is None or (expected is not None and token != expected):
            wanted = expected or "token"
            raise SpdxExpressionError(f"expected {wanted!r}, found {token!r}")
        self.position += 1
        return token

    def parse(self) -> SpdxExpression:
        self.parse_or()
        if self.current() is not None:
            raise SpdxExpressionError(f"unexpected token {self.current()!r}")
        return SpdxExpression(
            license_ids=frozenset(self.license_ids),
            exception_ids=frozenset(self.exception_ids),
        )

    def parse_or(self) -> None:
        self.parse_and()
        while self.current() == "OR":
            self.consume("OR")
            self.parse_and()

    def parse_and(self) -> None:
        self.parse_with()
        while self.current() == "AND":
            self.consume("AND")
            self.parse_with()

    def parse_with(self) -> None:
        simple = self.parse_primary()
        if self.current() != "WITH":
            return
        if not simple:
            raise SpdxExpressionError("WITH may only follow a simple license expression")
        self.consume("WITH")
        exception_id = self.consume_identifier("license exception")
        if exception_id in self.registry.deprecated_exception_ids:
            raise SpdxExpressionError(
                f"deprecated SPDX exception identifier is unapproved: {exception_id}"
            )
        if exception_id not in self.registry.exception_ids:
            raise SpdxExpressionError(
                f"unapproved SPDX exception identifier: {exception_id}"
            )
        self.exception_ids.add(exception_id)

    def parse_primary(self) -> bool:
        if self.current() == "(":
            self.consume("(")
            self.parse_or()
            self.consume(")")
            return False
        license_id = self.consume_identifier("license identifier")
        if license_id in self.registry.deprecated_license_ids:
            raise SpdxExpressionError(
                f"deprecated SPDX license identifier is unapproved: {license_id}"
            )
        if license_id not in self.registry.license_ids:
            raise SpdxExpressionError(
                f"unapproved SPDX license identifier: {license_id}"
            )
        self.license_ids.add(license_id)
        if self.current() == "+":
            self.consume("+")
        return True

    def consume_identifier(self, label: str) -> str:
        token = self.current()
        if token is None or token in {"(", ")", "+", "AND", "OR", "WITH"}:
            raise SpdxExpressionError(f"expected {label}, found {token!r}")
        return self.consume()


def parse_spdx_expression(expression: str, registry: SpdxRegistry) -> SpdxExpression:
    """Return identifiers only when the value is valid and non-deprecated SPDX."""
    return SpdxExpressionParser(expression.strip(), registry).parse()


def sha256_file(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: pathlib.Path, value: Any) -> None:
    path.write_text(
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def validate_no_local_absolute_paths(
    subject: str, source_format: str, document: dict[str, Any]
) -> None:
    findings = host_path_policy.find_local_absolute_paths(
        document, f"$.{source_format.lower()}"
    )
    if findings:
        fail(
            f"{subject}/{source_format}: normalized SBOM contains a local absolute "
            "path; "
            + host_path_policy.format_host_path_findings(findings)
        )


def described_spdx_ids(document: dict[str, Any]) -> set[str]:
    described = {str(value) for value in document.get("documentDescribes") or []}
    described.update(
        str(relationship.get("relatedSpdxElement"))
        for relationship in document.get("relationships") or []
        if relationship.get("spdxElementId") == "SPDXRef-DOCUMENT"
        and relationship.get("relationshipType") == "DESCRIBES"
    )
    return described


def has_cdx_sha256(component: dict[str, Any], expected: str) -> bool:
    return any(
        str(entry.get("alg") or "").upper().replace("_", "-") == "SHA-256"
        and str(entry.get("content") or "").lower() == expected
        for entry in component.get("hashes") or []
    )


def has_spdx_sha256(package: dict[str, Any], expected: str) -> bool:
    return any(
        str(entry.get("algorithm") or "").upper().replace("-", "") == "SHA256"
        and str(entry.get("checksumValue") or "").lower() == expected
        for entry in package.get("checksums") or []
    )


def validate_artifact_sbom_binding(
    subject: str,
    artifact: dict[str, Any],
    release_version: str,
    cdx_document: dict[str, Any],
    spdx_document: dict[str, Any],
) -> None:
    component = (cdx_document.get("metadata") or {}).get("component") or {}
    expected_name = artifact["name"]
    expected_sha = artifact["sha256"]
    if (
        component.get("name") != expected_name
        or component.get("version") != release_version
        or not has_cdx_sha256(component, expected_sha)
    ):
        fail(
            f"{subject}/CycloneDX: metadata.component must bind exact artifact "
            "name, release version, and SHA-256"
        )

    described = described_spdx_ids(spdx_document)
    roots = [
        package
        for package in spdx_document.get("packages") or []
        if str(package.get("SPDXID") or "") in described
    ]
    if len(described) != 1 or len(roots) != 1:
        fail(f"{subject}/SPDX: exactly one described artifact package is required")
    root = roots[0]
    if (
        spdx_document.get("name") != expected_name
        or root.get("name") != expected_name
        or root.get("versionInfo") != release_version
        or not has_spdx_sha256(root, expected_sha)
    ):
        fail(
            f"{subject}/SPDX: document and described package must bind exact artifact "
            "name, release version, and SHA-256"
        )


def meaningful_licenses(values: Iterable[Any]) -> list[str]:
    result: set[str] = set()
    for value in values:
        text = str(value or "").strip()
        if text.upper() not in UNKNOWN_LICENSES:
            result.add(text)
    return sorted(result)


def normalize_purl(value: str) -> str:
    base = unquote(value.strip().split("#", 1)[0].split("?", 1)[0])
    match = re.fullmatch(r"pkg:([^/]+)/(.+)", base, re.IGNORECASE)
    if not match or "@" not in match.group(2):
        fail(f"package identity is not a versioned purl: {value!r}")
    return f"pkg:{match.group(1).lower()}/{match.group(2)}"


def purl_from_spdx(package: dict[str, Any]) -> str | None:
    for reference in package.get("externalRefs") or []:
        if str(reference.get("referenceType", "")).lower() == "purl":
            value = str(reference.get("referenceLocator", "")).strip()
            if value:
                return value
    return None


def cdx_licenses(component: dict[str, Any]) -> list[str]:
    values: list[str] = []
    for entry in component.get("licenses") or []:
        if entry.get("expression"):
            values.append(entry["expression"])
            continue
        license_entry = entry.get("license") or {}
        values.append(license_entry.get("id") or license_entry.get("name") or "")
    return meaningful_licenses(values)


def cdx_property(component: dict[str, Any], name: str) -> str | None:
    for prop in component.get("properties") or []:
        if prop.get("name") == name and str(prop.get("value", "")).strip():
            return str(prop["value"]).strip()
    return None


def source_alias(component: dict[str, Any], licenses: list[str]) -> tuple[str, list[str]] | None:
    if not licenses:
        return None
    purl = str(component.get("purl") or "")
    normalized = normalize_purl(purl) if purl else ""
    if not normalized.startswith("pkg:deb/"):
        return None
    source = cdx_property(component, "syft:metadata:source")
    version = str(component.get("version") or "").strip()
    if not source or not version:
        return None
    package_path = normalized[len("pkg:deb/") :].rsplit("@", 1)[0]
    namespace = package_path.rsplit("/", 1)[0]
    return normalize_purl(f"pkg:deb/{namespace}/{source}@{version}"), licenses


def parse_cyclonedx(
    subject: str, context: str, path: pathlib.Path
) -> tuple[list[LicenseRow], dict[str, set[str]]]:
    document = read_json(path)
    if document.get("bomFormat") != "CycloneDX" or not isinstance(document.get("components"), list):
        fail(f"{subject}: malformed CycloneDX document")
    rows: list[LicenseRow] = []
    aliases: dict[str, set[str]] = collections.defaultdict(set)
    for component in document["components"]:
        component_type = str(component.get("type") or "").lower()
        name = str(component.get("name") or "").strip()
        version = str(component.get("version") or "").strip() or None
        purl = str(component.get("purl") or "").strip() or None
        licenses = cdx_licenses(component)
        synthetic = None
        if component_type == "file" and not purl and (
            name.startswith("/") or re.match(r"^[A-Za-z]:[\\/]", name)
            or cdx_property(component, "io.outcomeprobe.synthetic.classification")
            == "cyclonedx-file-component"
        ):
            synthetic = "cyclonedx-file-component"
        elif component_type == "operating-system" and not purl:
            synthetic = "cyclonedx-operating-system-subject"
        identity = normalize_purl(purl) if purl else None
        rows.append(
            LicenseRow(
                subject=subject,
                context=context,
                source_format="CycloneDX",
                component_type=component_type or "unspecified",
                name=name,
                version=version,
                purl=purl,
                identity=identity,
                licenses=licenses,
                missing=not licenses,
                source_ref=str(component.get("bom-ref") or name),
                synthetic_classification=synthetic,
            )
        )
        alias = source_alias(component, licenses)
        if alias:
            aliases[alias[0]].update(alias[1])
    return rows, aliases


def parse_spdx(subject: str, context: str, path: pathlib.Path) -> list[LicenseRow]:
    document = read_json(path)
    if not str(document.get("spdxVersion", "")).startswith("SPDX-") or not isinstance(
        document.get("packages"), list
    ):
        fail(f"{subject}: malformed SPDX document")
    described = described_spdx_ids(document)
    rows: list[LicenseRow] = []
    for package in document["packages"]:
        spdx_id = str(package.get("SPDXID") or "")
        purpose = str(package.get("primaryPackagePurpose") or "").upper()
        purl = purl_from_spdx(package)
        synthetic = None
        if spdx_id in described:
            synthetic = "spdx-described-document-subject"
        licenses = meaningful_licenses(
            [package.get("licenseDeclared"), package.get("licenseConcluded")]
        )
        rows.append(
            LicenseRow(
                subject=subject,
                context=context,
                source_format="SPDX",
                component_type=purpose.lower() or "package",
                name=str(package.get("name") or "").strip(),
                version=str(package.get("versionInfo") or "").strip() or None,
                purl=purl,
                identity=normalize_purl(purl) if purl and not synthetic else None,
                licenses=licenses,
                missing=not licenses,
                source_ref=spdx_id,
                synthetic_classification=synthetic,
            )
        )
    return rows


def validate_evidence(identity: str, evidence: Any, as_of: dt.date) -> None:
    """Require dated HTTPS evidence for every identity-specific manual decision."""
    if not isinstance(evidence, list) or not evidence:
        fail(f"{identity}: decision needs at least one evidence URL")
    for entry in evidence:
        if not isinstance(entry, dict):
            fail(f"{identity}: every evidence item must be an object")
        if not str(entry.get("url") or "").startswith("https://"):
            fail(f"{identity}: every license evidence URL must use HTTPS")
        try:
            retrieved = dt.date.fromisoformat(str(entry.get("retrievedAt")))
        except ValueError:
            fail(f"{identity}: every evidence item needs an ISO retrievedAt date")
        if retrieved > as_of:
            fail(
                f"{identity}: evidence retrievedAt {retrieved.isoformat()} is after "
                f"the policy date {as_of.isoformat()}"
            )


def validate_manifest(
    manifest: dict[str, Any],
    notice_path: pathlib.Path,
    as_of: dt.date,
    registry: SpdxRegistry | None = None,
) -> dict[str, dict[str, Any]]:
    registry = registry or load_spdx_registry()
    if manifest.get("schemaVersion") != 1:
        fail("THIRD-PARTY-LICENSES.json schemaVersion must be 1")
    policy = manifest.get("policy") or {}
    if policy.get("identityNormalization") != "purl-base-without-qualifiers-v1":
        fail("license decision identity normalization policy is missing")
    release_version = str(policy.get("releaseVersion") or "")
    if not re.fullmatch(r"[0-9A-Za-z][0-9A-Za-z.+-]*", release_version):
        fail("license policy releaseVersion is missing or malformed")
    registry_policy = policy.get("spdxLicenseList") or {}
    expected_registry_policy = spdx_registry_policy_contract()
    if registry_policy != expected_registry_policy:
        fail("license policy must bind the exact pinned SPDX license registry")
    expected_synthetic = {
        "cyclonedx-file-component",
        "cyclonedx-operating-system-subject",
        "spdx-described-document-subject",
    }
    synthetic = {
        entry.get("id") for entry in policy.get("syntheticClassifications") or []
    }
    if synthetic != expected_synthetic:
        fail("synthetic classification policy must enumerate the three exact subject kinds")
    notice = policy.get("notices") or {}
    if notice.get("path") != "THIRD-PARTY-NOTICES":
        fail("policy notice path must be THIRD-PARTY-NOTICES")
    expected_notice_sha = str(notice.get("sha256") or "").lower()
    if not SHA256.fullmatch(expected_notice_sha):
        fail("policy notice SHA-256 is missing or malformed")
    if sha256_file(notice_path) != expected_notice_sha:
        fail("THIRD-PARTY-NOTICES SHA-256 does not match the decision manifest")
    notice_text = notice_path.read_text(encoding="utf-8")
    if "THIRD-PARTY-LICENSES.json" not in notice_text or "TODO" in notice_text:
        fail("THIRD-PARTY-NOTICES is incomplete")

    decisions: dict[str, dict[str, Any]] = {}
    for raw in manifest.get("decisions") or []:
        identity_raw = str(raw.get("identity") or "")
        if not EXACT_IDENTITY.fullmatch(identity_raw):
            fail(
                "decision identity must be an exact versioned purl without "
                f"wildcards: {identity_raw!r}"
            )
        identity = normalize_purl(identity_raw)
        if identity in decisions:
            fail(f"duplicate license decision: {identity}")
        decision_type = raw.get("decision")
        contexts = set(raw.get("usageContexts") or [])
        if not contexts or not contexts <= ALLOWED_CONTEXTS:
            fail(f"{identity}: usageContexts must be a non-empty runtime/build/test subset")
        if not re.search(rf"(?m)^- {re.escape(identity)}(?:\s|—)", notice_text):
            fail(f"{identity}: decision is not disclosed in THIRD-PARTY-NOTICES")
        if decision_type == "license":
            expression = str(raw.get("licenseExpression") or "").strip()
            evidence = raw.get("evidence") or []
            if expression.upper() in UNKNOWN_LICENSES:
                fail(f"{identity}: license decision needs an SPDX expression")
            if DENIED_LICENSE.search(expression):
                fail(f"{identity}: denied license in manual decision: {expression}")
            try:
                parse_spdx_expression(expression, registry)
            except SpdxExpressionError as exc:
                fail(f"{identity}: invalid or unapproved SPDX expression: {exc}")
            validate_evidence(identity, evidence, as_of)
        elif decision_type == "exception":
            owner = str(raw.get("owner") or "").strip()
            reason = str(raw.get("reason") or "").strip()
            approved_by = str(raw.get("approvedBy") or "").strip()
            try:
                expires = dt.date.fromisoformat(str(raw.get("expires")))
            except ValueError:
                fail(f"{identity}: exception expiry must be an ISO date")
            if not owner or len(reason) < 20 or not approved_by:
                fail(f"{identity}: exception needs owner, substantive reason, and approvedBy")
            if expires < as_of:
                fail(f"{identity}: license exception expired on {expires.isoformat()}")
            validate_evidence(identity, raw.get("evidence") or [], as_of)
        else:
            fail(f"{identity}: decision must be license or exception")
        decisions[identity] = raw
    return decisions


def resolve_rows(
    rows: list[LicenseRow],
    aliases: dict[str, set[str]],
    decisions: dict[str, dict[str, Any]],
    registry: SpdxRegistry,
) -> ResolutionOutcome:
    declared: dict[str, set[str]] = collections.defaultdict(set)
    for row in rows:
        if row.identity and row.licenses:
            declared[row.identity].update(row.licenses)
    blockers: set[str] = set()
    denied: set[str] = set()
    unapproved: set[str] = set()
    for row in rows:
        if row.synthetic_classification:
            row.resolution = row.synthetic_classification
            continue
        if not row.identity:
            blockers.add(
                f"{row.subject}/{row.source_format}: non-synthetic component has no versioned purl"
            )
            row.resolution = "unresolved-no-identity"
            continue
        candidate_licenses: list[str] = []
        if row.licenses:
            row.resolution = "sbom-declared"
            candidate_licenses = row.licenses
        elif declared.get(row.identity):
            row.resolution = "cross-sbom-identity"
            candidate_licenses = sorted(declared[row.identity])
        elif aliases.get(row.identity):
            row.resolution = "debian-source-package-alias"
            candidate_licenses = sorted(aliases[row.identity])
        else:
            decision = decisions.get(row.identity)
            if not decision:
                row.resolution = "unresolved-package"
                blockers.add(f"{row.subject}: no license decision for {row.identity}")
                continue
            if row.context not in decision["usageContexts"]:
                row.resolution = "decision-context-mismatch"
                blockers.add(
                    f"{row.subject}: decision for {row.identity} does not allow {row.context}"
                )
                continue
            row.decision_identity = row.identity
            if decision["decision"] == "license":
                row.resolution = "decision-manifest-license"
                row.resolved_licenses = [decision["licenseExpression"]]
            else:
                row.resolution = "decision-manifest-exception"
            continue

        denied_values = [
            expression
            for expression in candidate_licenses
            if DENIED_LICENSE.search(expression)
        ]
        if denied_values:
            row.resolution = "denied-license"
            license_text = " OR ".join(sorted(set(denied_values)))
            message = f"{row.subject}: denied license for {row.identity}: {license_text}"
            denied.add(message)
            blockers.add(message)
            continue

        expression_errors: list[str] = []
        for expression in candidate_licenses:
            try:
                parse_spdx_expression(expression, registry)
            except SpdxExpressionError as exc:
                expression_errors.append(f"{expression!r} ({exc})")
        if expression_errors:
            decision = decisions.get(row.identity)
            if (
                decision
                and decision.get("decision") == "exception"
                and row.context in decision["usageContexts"]
            ):
                row.resolution = "decision-manifest-exception"
                row.decision_identity = row.identity
                continue
            row.resolution = "unapproved-license"
            message = (
                f"{row.subject}: unapproved license for {row.identity}: "
                + "; ".join(sorted(set(expression_errors)))
            )
            unapproved.add(message)
            blockers.add(message)
            continue
        row.resolved_licenses = candidate_licenses
    return ResolutionOutcome(
        blockers=tuple(sorted(blockers)),
        denied=tuple(sorted(denied)),
        unapproved=tuple(sorted(unapproved)),
    )


def public_row(row: LicenseRow) -> dict[str, Any]:
    value: dict[str, Any] = {
        "subject": row.subject,
        "usageContext": row.context,
        "sourceFormat": row.source_format,
        "componentType": row.component_type,
        "missingInSource": row.missing,
        "resolution": row.resolution,
        "resolvedLicenses": row.resolved_licenses,
    }
    if row.synthetic_classification:
        value["syntheticClassification"] = row.synthetic_classification
        value["sourceRefSha256"] = hashlib.sha256(
            row.source_ref.encode("utf-8")
        ).hexdigest()
    else:
        value.update(
            {
                "identity": row.identity,
                "name": row.name,
                "version": row.version,
                "decisionIdentity": row.decision_identity,
            }
        )
    return value


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--decision-manifest", type=pathlib.Path, required=True)
    parser.add_argument("--notices", type=pathlib.Path, required=True)
    parser.add_argument("--output-dir", type=pathlib.Path, required=True)
    parser.add_argument(
        "--subject",
        action="append",
        nargs=4,
        metavar=("ID", "CONTEXT", "CYCLONEDX", "SPDX"),
        required=True,
    )
    parser.add_argument(
        "--artifact", action="append", nargs=2, metavar=("ID", "PATH"), default=[]
    )
    parser.add_argument(
        "--as-of",
        type=dt.date.fromisoformat,
        default=dt.datetime.now(dt.UTC).date(),
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output = args.output_dir.resolve()
    output.mkdir(parents=True, exist_ok=True)
    output_files = {
        name: output / name
        for name in (
            "normalized-license-inventory.json",
            "license-policy-summary.json",
            "license-release-bindings.json",
            "THIRD-PARTY-NOTICES",
            "THIRD-PARTY-LICENSES.json",
        )
    }
    if any(path.exists() for path in output_files.values()):
        fail("license evidence output files already exist")

    manifest_path = args.decision_manifest.resolve(strict=True)
    notice_path = args.notices.resolve(strict=True)
    manifest = read_json(manifest_path)
    registry = load_spdx_registry()
    decisions = validate_manifest(manifest, notice_path, args.as_of, registry)
    release_version = str(manifest["policy"]["releaseVersion"])

    artifacts: list[dict[str, Any]] = []
    artifact_by_id: dict[str, dict[str, Any]] = {}
    for artifact_id, path_raw in args.artifact:
        if (
            artifact_id in artifact_by_id
            or not re.fullmatch(r"[a-z0-9][a-z0-9-]*", artifact_id)
        ):
            fail(f"duplicate or invalid artifact id: {artifact_id}")
        path = pathlib.Path(path_raw).resolve(strict=True)
        record = {
            "artifact": artifact_id,
            "name": path.name,
            "size": path.stat().st_size,
            "sha256": sha256_file(path),
        }
        artifacts.append(record)
        artifact_by_id[artifact_id] = record

    rows: list[LicenseRow] = []
    aliases: dict[str, set[str]] = collections.defaultdict(set)
    subject_bindings: list[dict[str, Any]] = []
    seen_subjects: set[str] = set()
    for subject, context, cdx_raw, spdx_raw in args.subject:
        if subject in seen_subjects or not re.fullmatch(r"[a-z0-9][a-z0-9-]*", subject):
            fail(f"duplicate or invalid subject id: {subject}")
        if context not in ALLOWED_CONTEXTS:
            fail(f"{subject}: context must be runtime, build, or test")
        seen_subjects.add(subject)
        cdx_path = pathlib.Path(cdx_raw).resolve(strict=True)
        spdx_path = pathlib.Path(spdx_raw).resolve(strict=True)
        cdx_document = read_json(cdx_path)
        spdx_document = read_json(spdx_path)
        validate_no_local_absolute_paths(subject, "CycloneDX", cdx_document)
        validate_no_local_absolute_paths(subject, "SPDX", spdx_document)
        artifact = artifact_by_id.get(subject)
        if artifact:
            validate_artifact_sbom_binding(
                subject,
                artifact,
                release_version,
                cdx_document,
                spdx_document,
            )
        cdx_rows, cdx_aliases = parse_cyclonedx(subject, context, cdx_path)
        rows.extend(cdx_rows)
        rows.extend(parse_spdx(subject, context, spdx_path))
        for identity, licenses in cdx_aliases.items():
            aliases[identity].update(licenses)
        subject_binding = {
            "subject": subject,
            "usageContext": context,
            "cycloneDx": {"name": cdx_path.name, "sha256": sha256_file(cdx_path)},
            "spdx": {"name": spdx_path.name, "sha256": sha256_file(spdx_path)},
            "localAbsolutePathCount": 0,
        }
        if artifact:
            subject_binding["artifactBinding"] = {
                "name": artifact["name"],
                "version": release_version,
                "sha256": artifact["sha256"],
                "result": "PASS",
            }
        subject_bindings.append(subject_binding)

    outcome = resolve_rows(rows, aliases, decisions, registry)
    blockers = outcome.blockers
    resolution_counts = collections.Counter(row.resolution for row in rows)
    context_counts: dict[str, dict[str, int]] = {}
    for context in sorted(ALLOWED_CONTEXTS):
        selected = [row for row in rows if row.context == context]
        context_counts[context] = {
            "rows": len(selected),
            "missingInSource": sum(row.missing for row in selected),
            "synthetic": sum(bool(row.synthetic_classification) for row in selected),
            "unresolved": sum(str(row.resolution).startswith("unresolved") for row in selected),
        }
    inventory = {
        "schemaVersion": 1,
        "result": "PASS" if not blockers else "FAIL",
        "rows": [public_row(row) for row in rows],
    }
    write_json(output_files["normalized-license-inventory.json"], inventory)
    summary = {
        "schemaVersion": 1,
        "result": "PASS" if not blockers else "FAIL",
        "rowCount": len(rows),
        "missingInSourceCount": sum(row.missing for row in rows),
        "syntheticCount": sum(bool(row.synthetic_classification) for row in rows),
        "actualPackageCount": sum(not row.synthetic_classification for row in rows),
        "actualPackageIdentityCount": len({row.identity for row in rows if row.identity}),
        "unresolvedPackageCount": len(
            {
                row.identity or row.source_ref
                for row in rows
                if str(row.resolution).startswith("unresolved")
            }
        ),
        "blockerCount": len(blockers),
        "blockers": blockers,
        "resolutionCounts": dict(sorted(resolution_counts.items())),
        "usageContexts": context_counts,
        "decisionCount": len(decisions),
        "licenseDecisionCount": sum(
            decision["decision"] == "license" for decision in decisions.values()
        ),
        "exceptionDecisionCount": sum(
            decision["decision"] == "exception" for decision in decisions.values()
        ),
        "usedDecisionCount": len({row.decision_identity for row in rows if row.decision_identity}),
        "usedExceptionDecisionCount": len(
            {
                row.decision_identity
                for row in rows
                if row.decision_identity
                and decisions[row.decision_identity]["decision"] == "exception"
            }
        ),
        "expiredExceptionCount": 0,
        "deniedLicenseCount": len(outcome.denied),
        "deniedLicenseBlockers": list(outcome.denied),
        "unapprovedLicenseCount": len(outcome.unapproved),
        "unapprovedLicenseBlockers": list(outcome.unapproved),
        "spdxLicenseListVersion": SPDX_REGISTRY_VERSION,
        "spdxLicenseRegistry": {
            "sourceRepository": SPDX_REGISTRY_REPOSITORY,
            "sourceTag": SPDX_REGISTRY_TAG,
            "sourceCommit": SPDX_REGISTRY_COMMIT,
            "sourcePath": SPDX_REGISTRY_SOURCE_PATH,
            "releaseAssetName": SPDX_REGISTRY_RELEASE_ASSET,
            "sha256": SPDX_REGISTRY_SHA256,
            "upstreamFiles": spdx_registry_policy_contract()["upstreamFiles"],
        },
    }
    write_json(output_files["license-policy-summary.json"], summary)

    shutil.copyfile(notice_path, output_files["THIRD-PARTY-NOTICES"])
    shutil.copyfile(manifest_path, output_files["THIRD-PARTY-LICENSES.json"])
    bindings = {
        "schemaVersion": 1,
        "result": "PASS" if not blockers else "FAIL",
        "policy": {
            "decisionManifest": {
                "name": "THIRD-PARTY-LICENSES.json",
                "sha256": sha256_file(manifest_path),
            },
            "notices": {
                "name": "THIRD-PARTY-NOTICES",
                "sha256": sha256_file(notice_path),
            },
            "spdxLicenseRegistry": {
                "sourceName": SPDX_REGISTRY_PATH.name,
                "sourcePath": SPDX_REGISTRY_SOURCE_PATH,
                "releaseAssetName": SPDX_REGISTRY_RELEASE_ASSET,
                "version": SPDX_REGISTRY_VERSION,
                "sourceRepository": SPDX_REGISTRY_REPOSITORY,
                "sourceTag": SPDX_REGISTRY_TAG,
                "sourceCommit": SPDX_REGISTRY_COMMIT,
                "sha256": SPDX_REGISTRY_SHA256,
                "upstreamFiles": spdx_registry_policy_contract()["upstreamFiles"],
            },
        },
        "subjects": subject_bindings,
        "artifacts": artifacts,
        "normalizedInventorySha256": sha256_file(
            output_files["normalized-license-inventory.json"]
        ),
        "summarySha256": sha256_file(output_files["license-policy-summary.json"]),
    }
    write_json(output_files["license-release-bindings.json"], bindings)
    if blockers:
        raise PolicyError("license policy gate failed: " + "; ".join(blockers[:20]))
    print(output_files["license-policy-summary.json"])
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (PolicyError, FileNotFoundError) as exc:
        print(f"license policy error: {exc}", file=sys.stderr)
        raise SystemExit(1)
