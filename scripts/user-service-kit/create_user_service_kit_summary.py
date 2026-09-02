#!/usr/bin/env python3
"""Normalize and verify secret-free evidence for the realtime user-service kit."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import pathlib
import re
import sys
import urllib.request
import uuid
import zipfile
import xml.etree.ElementTree as element_tree

MAX_JSON_BYTES = 4 * 1024 * 1024
MAX_JUNIT_BYTES = 4 * 1024 * 1024
MAX_BUNDLE_BYTES = 16 * 1024 * 1024
MAX_ZIP_ENTRIES = 64
SHA256 = re.compile(r"^[0-9a-f]{64}$")
SOURCE_SHA = re.compile(r"^(?:[0-9a-f]{40}|[0-9a-f]{64})$")

SCENARIOS = (
    ("single", "realtime-track-single-event", 90001, 1),
    ("duplicate", "realtime-track-duplicate-3x", 90002, 3),
    ("out-of-order", "realtime-track-out-of-order", 90003, 2),
)

BUSINESS = {
    "single": {
        "directMappingCount": 1,
        "forgedRejectedCount": 1,
        "forgedProcessedCount": 0,
        "validProcessedCount": 1,
    },
    "duplicate": {
        "receivedEventCount": 3,
        "processedEventCount": 1,
        "appliedEventCount": 1,
    },
    "out-of-order": {
        "receivedEventCount": 2,
        "appliedEventCount": 1,
        "latestVersion": 2,
        "latestLatitude": 37.521,
        "latestLongitude": 127.021,
    },
}

ASSET_NAMES = {
    key: (
        pathlib.PurePosixPath("runs") / key / "report.json",
        pathlib.PurePosixPath("runs") / key / "junit.xml",
        pathlib.PurePosixPath("runs") / key / "bundle.zip",
    )
    for key, *_ in SCENARIOS
}

SUMMARY_KEYS = frozenset(
    {
        "schemaVersion",
        "result",
        "source",
        "sourceSha",
        "artifacts",
        "topology",
        "connection",
        "security",
        "scenarios",
        "execution",
        "cleanup",
        "pushPerformed",
    }
)

SCENARIO_KEYS = frozenset(
    {
        "name",
        "version",
        "status",
        "runId",
        "seed",
        "callbackCount",
        "probeAttemptCount",
        "business",
        "reportSha256",
        "junitSha256",
        "bundleSha256",
    }
)


class EvidenceError(RuntimeError):
    """A public, secret-free evidence contract violation."""


def fail(message: str) -> None:
    raise EvidenceError(message)


def bounded_bytes(path: pathlib.Path, maximum: int) -> bytes:
    if path.is_symlink() or not path.is_file():
        fail(f"evidence file is missing or unsafe: {path.name}")
    size = path.stat().st_size
    if size <= 0 or size > maximum:
        fail(f"evidence file has an invalid size: {path.name}")
    return path.read_bytes()


def read_json(path: pathlib.Path) -> tuple[dict[str, object], bytes]:
    raw = bounded_bytes(path, MAX_JSON_BYTES)
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise EvidenceError(f"invalid JSON evidence: {path.name}") from error
    if not isinstance(value, dict):
        fail(f"JSON evidence must be an object: {path.name}")
    return value, raw


def canonical_json(value: dict[str, object]) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":")) + "\n").encode(
        "utf-8"
    )


def read_canonical_json(path: pathlib.Path) -> tuple[dict[str, object], bytes]:
    value, raw = read_json(path)
    if raw != canonical_json(value):
        fail(f"JSON evidence is not canonical: {path.name}")
    return value, raw


def sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def secret_values(required: bool = True) -> list[bytes]:
    names = (
        "OUTCOMEPROBE_CONTROL_API_TOKEN",
        "OUTCOMEPROBE_REALTIME_TARGET_TOKEN",
        "OUTCOMEPROBE_REALTIME_CALLBACK_SECRET",
    )
    values: list[bytes] = []
    for name in names:
        value = os.environ.get(name, "")
        if not value and not required:
            continue
        if len(value) < 32:
            fail(f"required secret sentinel is not configured: {name}")
        values.append(value.encode("utf-8"))
    if len(set(values)) != len(values):
        fail("secret sentinels must be distinct")
    return values


def scan(raw: bytes, sentinels: list[bytes], identity: str) -> None:
    if any(sentinel in raw for sentinel in sentinels):
        fail(f"raw secret found in evidence: {identity}")


def scan_bundle(path: pathlib.Path, raw: bytes, sentinels: list[bytes]) -> None:
    scan(raw, sentinels, path.name)
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if not entries or len(entries) > MAX_ZIP_ENTRIES:
                fail(f"bundle inventory is outside the bounded contract: {path.name}")
            total = 0
            for entry in entries:
                normalized = pathlib.PurePosixPath(entry.filename)
                if normalized.is_absolute() or ".." in normalized.parts:
                    fail(f"bundle contains an unsafe path: {path.name}")
                total += entry.file_size
                if total > MAX_BUNDLE_BYTES:
                    fail(f"bundle expands beyond the bounded contract: {path.name}")
                scan(archive.read(entry), sentinels, path.name)
    except zipfile.BadZipFile as error:
        raise EvidenceError(f"bundle is not a valid ZIP: {path.name}") from error


def validate_junit(raw: bytes, identity: str) -> None:
    if b"<!DOCTYPE" in raw.upper() or b"<!ENTITY" in raw.upper():
        fail(f"JUnit contains a forbidden declaration: {identity}")
    try:
        root = element_tree.fromstring(raw)
    except element_tree.ParseError as error:
        raise EvidenceError(f"JUnit is not valid XML: {identity}") from error
    if root.tag not in {"testsuite", "testsuites"}:
        fail(f"JUnit has an unexpected root: {identity}")
    if root.findall(".//failure") or root.findall(".//error") or root.findall(".//skipped"):
        fail(f"JUnit contains a failed, errored, or skipped test: {identity}")
    suites = [root] if root.tag == "testsuite" else root.findall("./testsuite")
    if not suites:
        fail(f"JUnit contains no test suite: {identity}")
    for suite in suites:
        if (
            suite.get("failures") != "0"
            or suite.get("errors") != "0"
            or suite.get("skipped") != "0"
            or not str(suite.get("tests", "")).isdigit()
            or int(str(suite.get("tests"))) < 1
        ):
            fail(f"JUnit aggregate is not skip-free PASS: {identity}")


def normalized_assets(
    key: str,
    name: str,
    seed: int,
    callback_count: int,
    run_id: str,
    probe_attempts: int,
    raw_bundle_sha256: str,
) -> tuple[bytes, bytes, bytes]:
    report = {
        "schemaVersion": 1,
        "scenario": {"name": name, "version": "1.0.0"},
        "runId": run_id,
        "targetProfile": "realtime-track-compose",
        "seed": seed,
        "status": "PASSED",
        "callbackCount": callback_count,
        "probeAttemptCount": probe_attempts,
        "business": BUSINESS[key],
    }
    report_raw = (json.dumps(report, sort_keys=True, separators=(",", ":")) + "\n").encode()
    junit_raw = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<testsuite name="{name}" tests="1" failures="0" errors="0" skipped="0">'
        f'<testcase classname="io.outcomeprobe.realtime" name="{key}"/>'
        '</testsuite>\n'
    ).encode()
    manifest_raw = (
        json.dumps(
            {
                "schemaVersion": 1,
                "kind": "sanitized-user-service-kit-bundle",
                "runId": run_id,
                "scenario": name,
                "rawBundleSha256": raw_bundle_sha256,
                "rawTimelineIncluded": False,
                "secretFindings": 0,
                "unsafePaths": 0,
            },
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode()
    import io

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_STORED) as archive:
        for entry_name, entry_raw in (
            ("manifest.json", manifest_raw),
            ("report.json", report_raw),
            ("junit.xml", junit_raw),
        ):
            info = zipfile.ZipInfo(entry_name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o100444 << 16
            archive.writestr(info, entry_raw)
    return report_raw, junit_raw, buffer.getvalue()


def fetch_timeline(server_url: str, run_id: str, token: str) -> tuple[list[dict[str, object]], bytes]:
    request = urllib.request.Request(
        server_url.rstrip("/") + f"/api/v1/runs/{run_id}/timeline",
        headers={"Authorization": "Bearer " + token},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read(MAX_JSON_BYTES + 1)
    except Exception as error:
        raise EvidenceError("timeline retrieval failed") from error
    if len(raw) > MAX_JSON_BYTES:
        fail("timeline exceeds the bounded evidence limit")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        raise EvidenceError("timeline is not valid UTF-8 JSON") from error
    if not isinstance(value, list) or not all(isinstance(item, dict) for item in value):
        fail("timeline must be an array of objects")
    return value, raw


def timeline_count(timeline: list[dict[str, object]], event_type: str, event_id: str) -> int:
    return sum(
        1
        for item in timeline
        if item.get("type") == event_type
        and isinstance(item.get("attributes"), dict)
        and item["attributes"].get("eventId") == event_id  # type: ignore[index]
    )


def first_sequence(timeline: list[dict[str, object]], event_type: str, event_id: str) -> int:
    sequences = [
        item.get("sequence")
        for item in timeline
        if item.get("type") == event_type
        and isinstance(item.get("attributes"), dict)
        and item["attributes"].get("eventId") == event_id  # type: ignore[index]
    ]
    if len(sequences) != 1 or not isinstance(sequences[0], int):
        fail(f"timeline identity is not unique: {event_type}/{event_id}")
    return sequences[0]


def assertion(report: dict[str, object], step_id: str, path: str, actual: str) -> None:
    assertions = report.get("assertions")
    if not isinstance(assertions, list):
        fail("report assertions are missing")
    matching = [
        item
        for item in assertions
        if isinstance(item, dict)
        and item.get("stepId") == step_id
        and item.get("jsonPath") == path
        and item.get("passed") is True
        and str(item.get("actual")) == actual
    ]
    if not matching:
        fail(f"required business assertion is missing: {step_id}/{path}")


def validate_business_evidence(
    key: str, report: dict[str, object], timeline: list[dict[str, object]]
) -> None:
    if key == "single":
        if timeline_count(timeline, "CALLBACK_DELIVERY_DEAD", "evt-forged-10001") != 1:
            fail("forged HMAC was not rejected exactly once")
        if timeline_count(timeline, "CALLBACK_DELIVERY_SUCCEEDED", "evt-valid-10001") != 1:
            fail("valid HMAC callback was not delivered exactly once")
        assertion(report, "probe-direct-event", "$.localMappingCount", "1")
        assertion(report, "assert-forged-unchanged", "$.localMappingCount", "0")
        assertion(report, "assert-forged-unchanged", "$.processedEventCount", "0")
        assertion(report, "assert-forged-unchanged", "$.rejectedCallbackCount", "1")
        assertion(report, "assert-valid-callback-state", "$.processedEventCount", "1")
    elif key == "duplicate":
        if timeline_count(timeline, "CALLBACK_DELIVERY_SUCCEEDED", "evt-duplicate-20001") != 3:
            fail("duplicate callback count is not exactly three")
        assertion(report, "assert-duplicate-convergence", "$.receivedEventCount", "3")
        assertion(report, "assert-duplicate-convergence", "$.processedEventCount", "1")
        assertion(report, "assert-duplicate-convergence", "$.appliedEventCount", "1")
    else:
        newer = first_sequence(timeline, "CALLBACK_DELIVERY_SUCCEEDED", "evt-newer-30001")
        older = first_sequence(timeline, "CALLBACK_DELIVERY_SUCCEEDED", "evt-older-30001")
        if newer >= older:
            fail("out-of-order callback delivery sequence is wrong")
        assertion(report, "assert-no-regression", "$.members[0].version", "2")
        assertion(report, "assert-no-regression", "$.members[0].latitude", "37.521")
        assertion(report, "assert-no-regression", "$.members[0].longitude", "127.021")
        assertion(report, "assert-no-regression", "$.receivedEventCount", "2")
        assertion(report, "assert-no-regression", "$.appliedEventCount", "1")


def collect(
    directory: pathlib.Path,
    server_url: str,
    assets_output: pathlib.Path,
    output: pathlib.Path,
) -> None:
    if output.exists() or output.is_symlink() or assets_output.exists() or assets_output.is_symlink():
        fail("run receipt output already exists")
    sentinels = secret_values()
    token = os.environ["OUTCOMEPROBE_CONTROL_API_TOKEN"]
    scenario_records: list[dict[str, object]] = []
    run_ids: set[str] = set()
    assets: dict[pathlib.PurePosixPath, bytes] = {}

    for key, name, seed, callback_count in SCENARIOS:
        report, report_raw = read_json(directory / f"{key}-report.json")
        junit_raw = bounded_bytes(directory / f"{key}-junit.xml", MAX_JUNIT_BYTES)
        validate_junit(junit_raw, f"{key} JUnit")
        bundle_path = directory / f"{key}-bundle.zip"
        bundle_raw = bounded_bytes(bundle_path, MAX_BUNDLE_BYTES)
        scan(report_raw, sentinels, f"{key} report")
        scan(junit_raw, sentinels, f"{key} JUnit")
        scan_bundle(bundle_path, bundle_raw, sentinels)

        run_id = report.get("runId")
        try:
            parsed_id = uuid.UUID(str(run_id))
        except (ValueError, AttributeError) as error:
            raise EvidenceError(f"invalid run ID: {key}") from error
        if str(parsed_id) != str(run_id) or str(run_id) in run_ids:
            fail(f"run ID is non-canonical or duplicated: {key}")
        run_ids.add(str(run_id))

        scenario = report.get("scenario")
        summary = report.get("summary")
        if not isinstance(scenario, dict) or not isinstance(summary, dict):
            fail(f"report identity or summary is missing: {key}")
        if (
            report.get("status") != "PASSED"
            or report.get("targetProfile") != "realtime-track-compose"
            or report.get("seed") != seed
            or scenario.get("name") != name
            or scenario.get("version") != "1.0.0"
            or summary.get("callbackDeliveries") != callback_count
            or not isinstance(summary.get("probeAttempts"), int)
            or int(summary["probeAttempts"]) < 1
        ):
            fail(f"report contract mismatch: {key}")

        timeline, timeline_raw = fetch_timeline(server_url, str(run_id), token)
        scan(timeline_raw, sentinels, f"{key} timeline")
        validate_business_evidence(key, report, timeline)
        normalized_report, normalized_junit, normalized_bundle = normalized_assets(
            key,
            name,
            seed,
            callback_count,
            str(run_id),
            int(summary["probeAttempts"]),
            sha256(bundle_raw),
        )
        report_name, junit_name, bundle_name = ASSET_NAMES[key]
        assets[report_name] = normalized_report
        assets[junit_name] = normalized_junit
        assets[bundle_name] = normalized_bundle
        scenario_records.append(
            {
                "name": name,
                "version": "1.0.0",
                "status": "PASSED",
                "runId": str(run_id),
                "seed": seed,
                "callbackCount": callback_count,
                "probeAttemptCount": int(summary["probeAttempts"]),
                "business": BUSINESS[key],
                "reportSha256": sha256(normalized_report),
                "junitSha256": sha256(normalized_junit),
                "bundleSha256": sha256(normalized_bundle),
            }
        )

    receipt: dict[str, object] = {
        "schemaVersion": 1,
        "result": "PASS",
        "source": {"kind": "working-tree", "shaBinding": "argument"},
        "artifacts": {"providerContract": 1, "targetProfile": 1, "scenario": 3},
        "topology": {
            "targetProfile": "realtime-track-compose",
            "targetService": "realtime-track-target",
            "targetHost": "realtime-track-target",
            "targetPort": 8181,
            "hostPublished": False,
            "network": "app",
        },
        "connection": {"readiness": "PASS", "doctor": "PASS"},
        "security": {
            "targetTokenSecretRef": "realtime-target-token",
            "callbackSecretRef": "realtime-callback-signing-key",
            "validHmac": "PASS",
            "forgedHmacRejected": "PASS",
            "invalidTargetAuthRejected": True,
            "rawSecretFindings": 0,
        },
        "scenarios": scenario_records,
        "execution": {"runCount": 3, "uniqueRunIds": 3},
    }
    assets_output.mkdir(parents=True, exist_ok=False)
    for relative, raw in assets.items():
        target = assets_output / pathlib.Path(*relative.parts[1:])
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
        scan(raw, sentinels, relative.as_posix())
        if relative.name == "bundle.zip":
            scan_bundle(target, raw, sentinels)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(receipt, sort_keys=True, separators=(",", ":")) + "\n", "utf-8")


def validate_published_assets(value: dict[str, object], root: pathlib.Path) -> None:
    expected = {
        pathlib.PurePosixPath("user-service-kit-summary.json"),
        *(path for paths in ASSET_NAMES.values() for path in paths),
    }
    actual: set[pathlib.PurePosixPath] = set()
    for path in root.rglob("*"):
        if path.is_symlink():
            fail("published evidence contains a symlink")
        if path.is_file():
            actual.add(pathlib.PurePosixPath(path.relative_to(root).as_posix()))
    # During finalize the summary itself is created after validation.
    if not (actual == expected or actual == expected - {pathlib.PurePosixPath("user-service-kit-summary.json")}):
        fail("published evidence does not match the exact ten-file allow-list")

    records = value.get("scenarios")
    if not isinstance(records, list):
        fail("summary scenario inventory mismatch")
    sentinels = secret_values(required=False)
    for record, (key, name, seed, callback_count) in zip(records, SCENARIOS):
        if not isinstance(record, dict):
            fail("summary scenario must be an object")
        report_path, junit_path, bundle_path = (
            root / pathlib.Path(*relative.parts) for relative in ASSET_NAMES[key]
        )
        report, report_raw = read_json(report_path)
        junit_raw = bounded_bytes(junit_path, MAX_JUNIT_BYTES)
        bundle_raw = bounded_bytes(bundle_path, MAX_BUNDLE_BYTES)
        scan(report_raw, sentinels, f"{key} report")
        scan(junit_raw, sentinels, f"{key} JUnit")
        scan_bundle(bundle_path, bundle_raw, sentinels)
        validate_junit(junit_raw, f"{key} JUnit")
        if report != {
            "schemaVersion": 1,
            "scenario": {"name": name, "version": "1.0.0"},
            "runId": record.get("runId"),
            "targetProfile": "realtime-track-compose",
            "seed": seed,
            "status": "PASSED",
            "callbackCount": callback_count,
            "probeAttemptCount": record.get("probeAttemptCount"),
            "business": BUSINESS[key],
        }:
            fail(f"sanitized report contract mismatch: {key}")
        try:
            with zipfile.ZipFile(bundle_path) as archive:
                if archive.namelist() != ["manifest.json", "report.json", "junit.xml"]:
                    fail(f"sanitized bundle inventory mismatch: {key}")
                manifest = json.loads(archive.read("manifest.json"))
                if (
                    manifest.get("schemaVersion") != 1
                    or manifest.get("kind") != "sanitized-user-service-kit-bundle"
                    or manifest.get("runId") != record.get("runId")
                    or manifest.get("scenario") != name
                    or manifest.get("rawTimelineIncluded") is not False
                    or manifest.get("secretFindings") != 0
                    or manifest.get("unsafePaths") != 0
                    or not SHA256.fullmatch(str(manifest.get("rawBundleSha256", "")))
                    or archive.read("report.json") != report_raw
                    or archive.read("junit.xml") != junit_raw
                ):
                    fail(f"sanitized bundle contract mismatch: {key}")
                expected_report, expected_junit, expected_bundle = normalized_assets(
                    key,
                    name,
                    seed,
                    callback_count,
                    str(record.get("runId")),
                    int(record.get("probeAttemptCount", 0)),
                    str(manifest.get("rawBundleSha256")),
                )
                if (
                    report_raw != expected_report
                    or junit_raw != expected_junit
                    or bundle_raw != expected_bundle
                ):
                    fail(f"sanitized assets are not canonical: {key}")
        except (zipfile.BadZipFile, json.JSONDecodeError) as error:
            raise EvidenceError(f"sanitized bundle is invalid: {key}") from error
        if (
            record.get("reportSha256") != sha256(report_raw)
            or record.get("junitSha256") != sha256(junit_raw)
            or record.get("bundleSha256") != sha256(bundle_raw)
        ):
            fail(f"published evidence digest mismatch: {key}")


def validate_summary(
    value: dict[str, object], source_sha: str, assets_root: pathlib.Path | None = None
) -> None:
    if not SOURCE_SHA.fullmatch(source_sha):
        fail("source SHA must be exact 40- or 64-character lowercase hex")
    if set(value) != SUMMARY_KEYS:
        fail("summary contains missing or unknown fields")
    if value.get("schemaVersion") != 1 or value.get("result") != "PASS":
        fail("summary result contract mismatch")
    if value.get("sourceSha") != source_sha or value.get("pushPerformed") is not False:
        fail("summary source or push boundary mismatch")
    if value.get("source") != {"kind": "working-tree", "shaBinding": "argument"}:
        fail("summary local source provenance mismatch")
    if value.get("artifacts") != {"providerContract": 1, "targetProfile": 1, "scenario": 3}:
        fail("summary artifact inventory mismatch")
    if value.get("topology") != {
        "targetProfile": "realtime-track-compose",
        "targetService": "realtime-track-target",
        "targetHost": "realtime-track-target",
        "targetPort": 8181,
        "hostPublished": False,
        "network": "app",
    }:
        fail("summary topology mismatch")
    if value.get("connection") != {"readiness": "PASS", "doctor": "PASS"}:
        fail("summary connection checks mismatch")
    if value.get("security") != {
        "targetTokenSecretRef": "realtime-target-token",
        "callbackSecretRef": "realtime-callback-signing-key",
        "validHmac": "PASS",
        "forgedHmacRejected": "PASS",
        "invalidTargetAuthRejected": True,
        "rawSecretFindings": 0,
    }:
        fail("summary security checks mismatch")
    if value.get("execution") != {"runCount": 3, "uniqueRunIds": 3}:
        fail("summary execution inventory mismatch")
    if value.get("cleanup") != {
        "containers": 0,
        "networks": 0,
        "volumes": 0,
        "temporaryCredentialsRemoved": True,
    }:
        fail("summary cleanup mismatch")
    records = value.get("scenarios")
    if not isinstance(records, list) or len(records) != 3:
        fail("summary scenario inventory mismatch")
    expected_names = [item[1] for item in SCENARIOS]
    if [record.get("name") for record in records if isinstance(record, dict)] != expected_names:
        fail("summary scenario order or identity mismatch")
    run_ids: set[str] = set()
    for record, (key, name, seed, callback_count) in zip(records, SCENARIOS):
        if not isinstance(record, dict):
            fail("summary scenario must be an object")
        if set(record) != SCENARIO_KEYS:
            fail("summary scenario contains missing or unknown fields")
        try:
            run_id = str(uuid.UUID(str(record.get("runId"))))
        except (ValueError, AttributeError) as error:
            raise EvidenceError("summary contains an invalid run ID") from error
        run_ids.add(run_id)
        if (
            record.get("name") != name
            or record.get("version") != "1.0.0"
            or record.get("status") != "PASSED"
            or record.get("seed") != seed
            or record.get("callbackCount") != callback_count
            or not isinstance(record.get("probeAttemptCount"), int)
            or int(record["probeAttemptCount"]) < 1
            or record.get("business") != BUSINESS[key]
        ):
            fail(f"summary scenario contract mismatch: {name}")
        for field in ("reportSha256", "junitSha256", "bundleSha256"):
            if not isinstance(record.get(field), str) or not SHA256.fullmatch(str(record[field])):
                fail(f"summary scenario digest is invalid: {name}/{field}")
    if len(run_ids) != 3:
        fail("summary run IDs are not unique")
    if assets_root is not None:
        validate_published_assets(value, assets_root)


def finalize(receipt_path: pathlib.Path, source_sha: str, output: pathlib.Path) -> None:
    receipt, _ = read_canonical_json(receipt_path)
    if output.exists() or output.is_symlink():
        fail("final summary output already exists")
    receipt["sourceSha"] = source_sha
    receipt["cleanup"] = {
        "containers": 0,
        "networks": 0,
        "volumes": 0,
        "temporaryCredentialsRemoved": True,
    }
    receipt["pushPerformed"] = False
    validate_summary(receipt, source_sha, output.parent)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(canonical_json(receipt))


def verify_summary(summary_path: pathlib.Path, source_sha: str) -> None:
    value, _ = read_canonical_json(summary_path)
    validate_summary(value, source_sha, summary_path.parent)


def extract_run_id(report_path: pathlib.Path) -> None:
    report, _ = read_json(report_path)
    try:
        run_id = str(uuid.UUID(str(report.get("runId"))))
    except (ValueError, AttributeError) as error:
        raise EvidenceError("report contains an invalid run ID") from error
    print(run_id)


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser()
    commands = root.add_subparsers(dest="command", required=True)
    collect_parser = commands.add_parser("collect")
    collect_parser.add_argument("--directory", required=True, type=pathlib.Path)
    collect_parser.add_argument("--server-url", required=True)
    collect_parser.add_argument("--assets-output", required=True, type=pathlib.Path)
    collect_parser.add_argument("--output", required=True, type=pathlib.Path)
    finalize_parser = commands.add_parser("finalize")
    finalize_parser.add_argument("--receipt", required=True, type=pathlib.Path)
    finalize_parser.add_argument("--source-sha", required=True)
    finalize_parser.add_argument("--output", required=True, type=pathlib.Path)
    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("--summary", required=True, type=pathlib.Path)
    verify_parser.add_argument("--source-sha", required=True)
    extract_parser = commands.add_parser("extract-run-id")
    extract_parser.add_argument("--report", required=True, type=pathlib.Path)
    return root


def main() -> int:
    arguments = parser().parse_args()
    try:
        if arguments.command == "collect":
            collect(
                arguments.directory,
                arguments.server_url,
                arguments.assets_output,
                arguments.output,
            )
        elif arguments.command == "finalize":
            finalize(arguments.receipt, arguments.source_sha, arguments.output)
        elif arguments.command == "verify":
            verify_summary(arguments.summary, arguments.source_sha)
        else:
            extract_run_id(arguments.report)
    except EvidenceError as error:
        print(f"user-service kit evidence verification failed: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
